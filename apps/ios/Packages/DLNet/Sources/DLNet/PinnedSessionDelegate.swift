import DLSecurity
import Foundation
import Security
import os

/// 主机连接错误（I3.4）。
public enum HostConnectionError: Error, Equatable, Sendable {
    /// 服务器证书与配对时不一致（或链上取不到证书）：连接已取消，需要重新配对。
    /// 红线：只提示「电脑证书变了，需要重新配对」，**不自动删除任何凭据**。
    case certificateChanged
}

/// 按「配对时记录的叶证书 SHA-256 指纹」钉死主机证书的 `URLSessionDelegate`（I3.4）。
///
/// 行为与 Android `PinnedSsl.pinnedTrustManager` 一致：
/// - 只处理 `NSURLAuthenticationMethodServerTrust`，其余交给系统默认处理；
/// - 叶证书 DER 交给 `PinEvaluation` 与期望指纹逐位比较，一致 → `URLCredential(trust:)`；
/// - 不一致、缺指纹或取不到证书 → `.cancelAuthenticationChallenge`，并把
///   `HostConnectionError.certificateChanged` 记进 `lastRecordedError`，供上层把
///   连接取消映射成该错误。
///
/// 红线：证书变更只取消连接、只提示，不自动删除凭据。本委托只应用于已通过
/// `PinEvaluation.requirePin` 的钉扎连接（公网无指纹走系统 PKI 的场景不要用它）。
/// `@unchecked Sendable` 是安全的：除上锁保护的 `lastError` 外没有可变状态。
public final class PinnedSessionDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    /// 已规范化的期望指纹；空串表示没有指纹（缺指纹同样 fail-closed）。
    private let expectedFingerprint: String
    private let lastError = OSAllocatedUnfairLock<HostConnectionError?>(initialState: nil)

    /// - Parameter expectedFingerprint: 配对时记录的叶证书指纹，原始写法即可，内部规范化。
    public init(expectedFingerprint: String?) {
        self.expectedFingerprint = CertificateFingerprint.normalize(expectedFingerprint)
    }

    /// 最近一次记录的错误（线程安全）。请求失败后读取，把取消类错误映射成提示。
    public var lastRecordedError: HostConnectionError? {
        lastError.withLock { $0 }
    }

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
            let trust = challenge.protectionSpace.serverTrust
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
            let leaf = chain.first,
            let leafDER = SecCertificateCopyData(leaf) as Data?
        else {
            // 空链或取不到叶证书：与 Android 一样 fail-closed，不给系统 PKI 兜底。
            recordCertificateChanged()
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        switch PinEvaluation.evaluate(leafDER: leafDER, expected: expectedFingerprint) {
        case .match:
            completionHandler(.useCredential, URLCredential(trust: trust))
        case .mismatch, .missingPin:
            recordCertificateChanged()
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    private func recordCertificateChanged() {
        lastError.withLock { $0 = HostConnectionError.certificateChanged }
    }
}
