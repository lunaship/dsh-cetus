import CryptoKit
import Foundation

/// RFC 8032 Ed25519, deterministic. The dom2 prefix is empty.
///
/// CryptoKit signing is randomized on Apple platforms. This uses SHA-512 and the RFC scalar
/// arithmetic so one seed and transcript match Node and the shared vectors.
enum DlpEd25519 {
    static func sign(seed: Data, message: Data) throws -> Data {
        let seedBytes = [UInt8](seed)
        guard seedBytes.count == 32 else { throw DlpCrypto.Failure.invalidLength("host key seed") }
        var hashed = sha512(seedBytes)
        hashed[0] &= 248
        hashed[31] &= 127
        hashed[31] |= 64
        let scalar = little(Array(hashed[..<32]))
        let prefix = Array(hashed[32...])
        let publicBytes = base.multiply(scalar).bytes
        let nonce = reduce(sha512(prefix + [UInt8](message)))
        let commitment = base.multiply(nonce).bytes
        let challenge = reduce(sha512(commitment + publicBytes + Array(message)))
        let proof = addMod(nonce, mulMod(challenge, scalar))
        return Data(commitment + little32(proof))
    }

    private static func sha512(_ bytes: [UInt8]) -> [UInt8] {
        Array(SHA512.hash(data: bytes))
    }

    private static let fieldPrime = BigUInt(
        "57896044618658097711785492504343953926634992332820282019728792003956564819949")
    private static let groupOrder = BigUInt(
        "7237005577332262213973186563042994240857116359379907606001950938285454250989")
    private static let curveD = BigUInt("37095705934669439343138083508754565189542113879843219016388785533085940283555")
    private static let base = Point(
        x: BigUInt("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        y: BigUInt("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
        z: BigUInt(1),
        t: BigUInt("15112221349535400772501151409588531511454012693041857206046113283949847762202")
            * BigUInt("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    )

    private struct Point {
        var x: BigUInt
        var y: BigUInt
        var z: BigUInt
        var t: BigUInt

        static let identity = Point(x: BigUInt(0), y: BigUInt(1), z: BigUInt(1), t: BigUInt(0))

        func add(_ other: Point) -> Point {
            let a = field(x * other.x)
            let b = field(y * other.y)
            let c = field(t * curveD * other.t)
            let d = field(z * other.z)
            let e = field(field((x + y) * (other.x + other.y)) + fieldPrime + fieldPrime - a - b)
            let f = field(d + fieldPrime - c)
            let g = field(d + c)
            let h = field(b + a)
            return Point(x: field(e * f), y: field(g * h), z: field(f * g), t: field(e * h))
        }

        func double() -> Point { add(self) }

        func multiply(_ scalar: BigUInt) -> Point {
            var result = Point.identity
            var addend = self
            var bits = scalar
            while bits > BigUInt(0) {
                if bits & 1 == 1 { result = result.add(addend) }
                addend = addend.double()
                bits = bits >> 1
            }
            return result
        }

        var bytes: [UInt8] {
            let inverse = pow(z, fieldPrime - BigUInt(2))
            let affineX = field(x * inverse)
            let affineY = field(y * inverse)
            var encoded = little32(affineY)
            if affineX & 1 == 1 { encoded[31] |= 0x80 }
            return encoded
        }
    }

    private static func field(_ value: BigUInt) -> BigUInt { value % fieldPrime }
    private static func reduce(_ bytes: [UInt8]) -> BigUInt { little(bytes) % groupOrder }
    private static func addMod(_ lhs: BigUInt, _ rhs: BigUInt) -> BigUInt { (lhs + rhs) % groupOrder }
    private static func mulMod(_ lhs: BigUInt, _ rhs: BigUInt) -> BigUInt { (lhs * rhs) % groupOrder }

    private static func little(_ bytes: [UInt8]) -> BigUInt {
        var value = BigUInt(0)
        for byte in bytes.reversed() { value = (value << 8) + BigUInt(UInt64(byte)) }
        return value
    }

    private static func little32(_ value: BigUInt) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 32)
        var rest = value
        for index in 0..<32 {
            out[index] = UInt8(rest & 0xff)
            rest = rest >> 8
        }
        return out
    }

    private static func pow(_ base: BigUInt, _ exponent: BigUInt) -> BigUInt {
        var result = BigUInt(1)
        var square = base % fieldPrime
        var bits = exponent
        while bits > BigUInt(0) {
            if bits & 1 == 1 { result = field(result * square) }
            square = field(square * square)
            bits = bits >> 1
        }
        return result
    }

    private struct BigUInt: Comparable {
        private var limbs: [UInt64]
        init(_ value: UInt64) { limbs = value == 0 ? [] : [value] }
        init(_ decimal: String) {
            var result = BigUInt(0)
            for scalar in decimal.unicodeScalars { result = result * BigUInt(10) + BigUInt(UInt64(scalar.value - 48)) }
            self = result
        }
        private init(limbs: [UInt64]) {
            var copy = limbs
            while copy.last == 0 { copy.removeLast() }
            self.limbs = copy
        }
        static func < (lhs: BigUInt, rhs: BigUInt) -> Bool {
            if lhs.limbs.count != rhs.limbs.count { return lhs.limbs.count < rhs.limbs.count }
            let last = lhs.limbs.count - 1
            for index in stride(from: last, through: 0, by: -1) where lhs.limbs[index] != rhs.limbs[index] {
                return lhs.limbs[index] < rhs.limbs[index]
            }
            return false
        }
        static func == (lhs: BigUInt, rhs: BigUInt) -> Bool { lhs.limbs == rhs.limbs }
        static func + (lhs: BigUInt, rhs: BigUInt) -> BigUInt {
            var out: [UInt64] = []
            var carry = UInt64(0)
            for index in 0..<max(lhs.limbs.count, rhs.limbs.count) {
                let first = lhs.limb(index).addingReportingOverflow(rhs.limb(index))
                let second = first.partialValue.addingReportingOverflow(carry)
                out.append(second.partialValue)
                carry = (first.overflow || second.overflow) ? 1 : 0
            }
            if carry > 0 { out.append(carry) }
            return BigUInt(limbs: out)
        }
        static func - (lhs: BigUInt, rhs: BigUInt) -> BigUInt {
            var out: [UInt64] = []
            var borrow = UInt64(0)
            for index in 0..<lhs.limbs.count {
                let first = lhs.limbs[index].subtractingReportingOverflow(rhs.limb(index))
                let second = first.partialValue.subtractingReportingOverflow(borrow)
                out.append(second.partialValue)
                borrow = (first.overflow || second.overflow) ? 1 : 0
            }
            return BigUInt(limbs: out)
        }
        static func * (lhs: BigUInt, rhs: BigUInt) -> BigUInt {
            if lhs.limbs.isEmpty || rhs.limbs.isEmpty { return BigUInt(0) }
            var out = [UInt64](repeating: 0, count: lhs.limbs.count + rhs.limbs.count)
            for left in lhs.limbs.indices {
                var carry = UInt64(0)
                for right in rhs.limbs.indices {
                    let product = mul(lhs.limbs[left], rhs.limbs[right])
                    let low = out[left + right].addingReportingOverflow(product.low)
                    let high = product.high &+ (low.overflow ? 1 : 0) &+ carry
                    out[left + right] = low.partialValue
                    let carried = out[left + right + 1].addingReportingOverflow(high)
                    out[left + right + 1] = carried.partialValue
                    carry = carried.overflow ? 1 : 0
                }
            }
            return BigUInt(limbs: out)
        }
        static func % (lhs: BigUInt, rhs: BigUInt) -> BigUInt { lhs.divided(by: rhs).remainder }
        static func << (lhs: BigUInt, rhs: Int) -> BigUInt {
            let words = rhs / 64
            let bits = rhs % 64
            var out = [UInt64](repeating: 0, count: words) + lhs.limbs
            if bits > 0 {
                var carry = UInt64(0)
                for index in out.indices {
                    let next = bits == 0 ? 0 : out[index] >> (64 - bits)
                    out[index] = (out[index] << bits) | carry
                    carry = next
                }
                if carry > 0 { out.append(carry) }
            }
            return BigUInt(limbs: out)
        }
        static func >> (lhs: BigUInt, rhs: Int) -> BigUInt {
            let words = rhs / 64
            let bits = rhs % 64
            guard words < lhs.limbs.count else { return BigUInt(0) }
            var out = Array(lhs.limbs[words...])
            if bits > 0 {
                var carry = UInt64(0)
                for index in stride(from: out.count - 1, through: 0, by: -1) {
                    let next = out[index] << (64 - bits)
                    out[index] = (out[index] >> bits) | carry
                    carry = next
                }
            }
            return BigUInt(limbs: out)
        }
        static func & (lhs: BigUInt, rhs: UInt64) -> UInt64 { (lhs.limbs.first ?? 0) & rhs }
        private func limb(_ index: Int) -> UInt64 { index < limbs.count ? limbs[index] : 0 }
        private func divided(by divisor: BigUInt) -> (quotient: BigUInt, remainder: BigUInt) {
            if self < divisor { return (BigUInt(0), self) }
            var quotient = BigUInt(0)
            var remainder = BigUInt(0)
            for shift in stride(from: limbs.count * 64 - 1, through: 0, by: -1) {
                remainder = remainder << 1
                let word = shift / 64
                if word < limbs.count, (limbs[word] >> (shift % 64)) & 1 == 1 { remainder = remainder + BigUInt(1) }
                if remainder >= divisor {
                    remainder = remainder - divisor
                    quotient = quotient + (BigUInt(1) << shift)
                }
            }
            return (quotient, remainder)
        }
        private static func mul(_ lhs: UInt64, _ rhs: UInt64) -> (high: UInt64, low: UInt64) {
            let leftHigh = lhs >> 32
            let leftLow = lhs & 0xffff_ffff
            let rightHigh = rhs >> 32
            let rightLow = rhs & 0xffff_ffff
            let highProduct = leftHigh * rightHigh
            let mixedLeft = leftHigh * rightLow
            let mixedRight = leftLow * rightHigh
            let lowProduct = leftLow * rightLow
            let middle = (lowProduct >> 32) + (mixedLeft & 0xffff_ffff) + (mixedRight & 0xffff_ffff)
            return (
                highProduct + (mixedLeft >> 32) + (mixedRight >> 32) + (middle >> 32),
                (lowProduct & 0xffff_ffff) | (middle << 32)
            )
        }
    }
}
