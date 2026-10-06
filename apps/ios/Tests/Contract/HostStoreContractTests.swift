import DLModels
import DLSecurity
import Foundation
import Security
import Testing

/// I3.3 合同测试：HostStore 的读写 / 覆盖 / 删除 / 一致性对账 / 敏感字段不入沙盒 JSON，
/// 以及 KeychainStore 构造 query / attributes 的红线属性。
/// 全部用 InMemorySecureStore + 临时目录，**不访问真 Keychain**——CI 的未签名测试宿主
/// 在模拟器上访问钥匙串会报 -34018。
///
/// 「卸载后不可恢复」（PLAN I3.3 单测清单）无法在 CI 上自动验证，这里用
/// 「删掉沙盒 JSON + 保留 SecureStore 条目 → 重新初始化」模拟重装后的对账
/// （见 `keychainResidueWithoutJsonEntryIsCleanedOnInit`）。
/// **需维护者在模拟器 / 真机上手动验证**：
/// 1. 配对一台电脑 → 卸载 App → 重装 → App 应回到未配对状态（凭据缺失 = 未配对）；
/// 2. 设置页无残留主机条目。
struct HostStoreContractTests {
    // MARK: - InMemorySecureStore

    @Test func inMemorySecureStoreRoundTripOverwriteAndDelete() throws {
        let store = InMemorySecureStore()
        #expect(try store.data(forKey: "a") == nil)
        try store.setData(Data("1".utf8), forKey: "a")
        try store.setData(Data("2".utf8), forKey: "b")
        try store.setData(Data("1x".utf8), forKey: "a")
        #expect(try store.data(forKey: "a") == Data("1x".utf8))
        #expect(try store.allKeys() == ["a", "b"])
        try store.removeData(forKey: "a")
        try store.removeData(forKey: "missing")
        #expect(try store.allKeys() == ["b"])
    }

    // MARK: - HostStore 读写 / 覆盖 / 删除

    @Test func savedHostSurvivesNewInstanceWithFullCredentials() async throws {
        let dir = tempDirectory()
        let store = InMemorySecureStore()
        let first = HostStore(fileURL: dir.appendingPathComponent("hosts.json"), secureStore: store)
        let host = sampleHost()
        try await first.save(host: host, token: "tok-1")

        // 新实例：JSON（非敏感）+ SecureStore（指纹与远程密钥）合并回完整 PairedHost。
        let second = HostStore(fileURL: dir.appendingPathComponent("hosts.json"), secureStore: store)
        #expect(await second.get(hostId: host.hostId) == host)
        #expect(await second.all() == [host])
        #expect(await second.token(for: host.hostId) == "tok-1")
        #expect(await second.token(for: "dsh-unknown") == nil)
    }

    @Test func saveOverwritesSameHostId() async throws {
        let dir = tempDirectory()
        let file = dir.appendingPathComponent("hosts.json")
        let store = InMemorySecureStore()
        let hostStore = HostStore(fileURL: file, secureStore: store)
        let host = sampleHost()
        try await hostStore.save(host: host, token: "tok-1")

        var replaced = host
        replaced.name = "改名后的电脑"
        replaced.remote = nil
        try await hostStore.save(host: replaced, token: "tok-2")

        #expect(await hostStore.all() == [replaced])
        #expect(await hostStore.get(hostId: host.hostId) == replaced)
        #expect(await hostStore.token(for: host.hostId) == "tok-2")
        // 覆盖为「无远程」后，远程凭据的钥匙串条目也应清掉，只剩 token 与指纹两条。
        #expect(try store.allKeys().count == 2)
    }

    @Test func saveReplacesSameFingerprintWithoutAddingHost() async throws {
        let (hostStore, store, file) = try await savedSample()
        let original = try #require(await hostStore.all().first)
        var again = original
        again.hostId = "local-new-attempt"
        again.name = "换过局域网地址"
        again.primaryUrl = "https://192.168.10.42:18640"
        again.certFingerprint = "SHA256: " + String(repeating: "A1:B2:C3:D4:", count: 8).dropLast()

        try await hostStore.save(host: again, token: "tok-new")

        let hosts = await hostStore.all()
        #expect(hosts.count == 1)
        #expect(hosts[0].hostId == original.hostId)
        #expect(hosts[0].name == "换过局域网地址")
        #expect(hosts[0].primaryUrl == again.primaryUrl)
        #expect(await hostStore.token(for: original.hostId) == "tok-new")
        #expect(await hostStore.get(hostId: "local-new-attempt") == nil)
        #expect(!jsonText(at: file).contains("local-new-attempt"))
        #expect(try store.allKeys().allSatisfy { !$0.contains("local-new-attempt") })
    }

    @Test func saveReplacesSamePluginHostIdWhenCertificateChanges() async throws {
        let (hostStore, _, _) = try await savedSample(pluginHostId: "dsh-computer")
        let original = try #require(await hostStore.all().first)
        let other = PairedHost(
            hostId: "other-computer",
            name: "另一台",
            primaryUrl: "https://10.0.0.8:18640",
            certFingerprint: String(repeating: "b", count: 64),
            pairedAt: original.pairedAt,
            pluginHostId: "dsh-other"
        )
        try await hostStore.save(host: other, token: "tok-other")

        var rotated = original
        rotated.hostId = "local-after-rotation"
        rotated.certFingerprint = String(repeating: "c", count: 64)
        rotated.pluginHostId = " dsh-computer "
        rotated.name = "换证后的同一台"
        try await hostStore.save(host: rotated, token: "tok-rotated")

        let hosts = await hostStore.all()
        #expect(hosts.count == 2)
        let kept = try #require(hosts.first { $0.pluginHostId == "dsh-computer" })
        #expect(kept.hostId == original.hostId)
        #expect(kept.certFingerprint == String(repeating: "c", count: 64))
        #expect(kept.name == "换证后的同一台")
        #expect(await hostStore.token(for: original.hostId) == "tok-rotated")
        #expect(await hostStore.get(hostId: "other-computer")?.name == "另一台")
        #expect(await hostStore.token(for: "other-computer") == "tok-other")
    }

    @Test func failedSaveKeepsPreviousHostAndToken() async throws {
        let directory = tempDirectory()
        let file = directory.appendingPathComponent("hosts.json")
        let secure = InMemorySecureStore()
        let failing = FailWritesSecureStore(inner: secure, failAfter: 1)
        let hostStore = HostStore(fileURL: file, secureStore: failing)
        let original = sampleHost()
        try await hostStore.save(host: original, token: "tok-old")

        var again = original
        again.hostId = "local-failed-attempt"
        again.name = "不该写进去"
        again.primaryUrl = "https://192.168.10.99:18640"
        do {
            try await hostStore.save(host: again, token: "tok-new")
            Issue.record("第二次保存应当失败")
        } catch {}

        #expect(await hostStore.all() == [original])
        #expect(await hostStore.token(for: original.hostId) == "tok-old")
        #expect(await hostStore.get(hostId: "local-failed-attempt") == nil)
        let reopened = HostStore(fileURL: file, secureStore: secure)
        #expect(await reopened.all() == [original])
        #expect(await reopened.token(for: original.hostId) == "tok-old")
    }

    @Test func deleteRemovesBothSidesAndIsIdempotent() async throws {
        let dir = tempDirectory()
        let file = dir.appendingPathComponent("hosts.json")
        let store = InMemorySecureStore()
        let hostStore = HostStore(fileURL: file, secureStore: store)
        let host = sampleHost()
        try await hostStore.save(host: host, token: "tok-1")

        try await hostStore.delete(hostId: host.hostId)
        #expect(await hostStore.all().isEmpty)
        #expect(await hostStore.get(hostId: host.hostId) == nil)
        #expect(await hostStore.token(for: host.hostId) == nil)
        #expect(try store.allKeys().isEmpty)
        #expect(!jsonText(at: file).contains(host.hostId))

        try await hostStore.delete(hostId: host.hostId)
        #expect(await hostStore.all().isEmpty)
    }

    // MARK: - 敏感字段不入沙盒 JSON

    @Test func jsonFileNeverContainsSensitiveValues() async throws {
        let dir = tempDirectory()
        let file = dir.appendingPathComponent("hosts.json")
        let hostStore = HostStore(fileURL: file, secureStore: InMemorySecureStore())
        let host = sampleHost()
        try await hostStore.save(host: host, token: "tok-secret-123")

        // JSONEncoder 会把 `/` 转义成 `\/`：比对前先还原，避免含斜杠的值被转义藏住。
        let text = jsonText(at: file).replacingOccurrences(of: "\\/", with: "/")
        #expect(!text.contains("tok-secret-123"))
        #expect(!text.contains(host.certFingerprint))
        #expect(!text.contains(host.remote?.relayKey ?? ""))
        #expect(!text.contains(host.remote?.outerCertificatePin ?? ""))
        #expect(text.contains(host.hostId))
        #expect(text.contains(host.primaryUrl))
        #expect(text.contains(host.name))
    }

    // MARK: - 一致性对账（凭据缺失 = 未配对）

    @Test func jsonEntryWithoutKeychainCredentialsIsDropped() async throws {
        let dir = tempDirectory()
        let file = dir.appendingPathComponent("hosts.json")
        let first = HostStore(fileURL: file, secureStore: InMemorySecureStore())
        let host = sampleHost()
        try await first.save(host: host, token: "tok-1")

        // JSON 有条目、钥匙串全新（token 丢失）：整条按未配对处理并清理。
        let second = HostStore(fileURL: file, secureStore: InMemorySecureStore())
        #expect(await second.all().isEmpty)
        #expect(await second.token(for: host.hostId) == nil)
        #expect(!jsonText(at: file).contains(host.hostId))
    }

    @Test func keychainResidueWithoutJsonEntryIsCleanedOnInit() async throws {
        let dir = tempDirectory()
        let file = dir.appendingPathComponent("hosts.json")
        let store = InMemorySecureStore()
        let first = HostStore(fileURL: file, secureStore: store)
        let host = sampleHost()
        try await first.save(host: host, token: "tok-1")
        #expect(try !store.allKeys().isEmpty)

        // 模拟重装：沙盒 JSON 消失、钥匙串残留 → 初始化对账清空残留。
        try FileManager.default.removeItem(at: file)
        let second = HostStore(fileURL: file, secureStore: store)
        #expect(await second.all().isEmpty)
        #expect(try store.allKeys().isEmpty)
        #expect(await second.token(for: host.hostId) == nil)
    }

    @Test func remoteMetaWithoutRelayKeyKeepsPairingButDropsRemote() async throws {
        let dir = tempDirectory()
        let file = dir.appendingPathComponent("hosts.json")
        let store = InMemorySecureStore()
        let first = HostStore(fileURL: file, secureStore: store)
        let host = sampleHost()
        try await first.save(host: host, token: "tok-1")

        // 远程元数据在而会合密钥读不到：只作废远程、保留局域网配对。
        let second = HostStore(fileURL: file, secureStore: DropReadsSecureStore(inner: store, dropping: "relayKey"))
        let merged = try #require(await second.get(hostId: host.hostId))
        #expect(merged.hostId == host.hostId)
        #expect(merged.remote == nil)
        #expect(await second.token(for: host.hostId) == "tok-1")
        #expect(!jsonText(at: file).contains("relay.example"))
    }

    // MARK: - KeychainStore 红线属性（纯函数，不碰钥匙串）

    @Test func keychainQueryAndAttributesCarryRedLineProperties() {
        let service = KeychainStore.defaultService
        let account = "host.dsh-1.token"
        let secret = Data("secret".utf8)
        let genericPassword = kSecClassGenericPassword as String
        let accessible = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String

        #expect(service == "dev.deeplinks.ios", "service 名必须固定")

        let base = KeychainStore.baseQuery(service: service, accessGroup: nil)
        #expect((base[kSecClass as String] as? String) == genericPassword)
        #expect((base[kSecAttrService as String] as? String) == service)
        #expect(base[kSecAttrSynchronizable as String] as? Bool == false, "不进 iCloud 钥匙串")
        #expect(base[kSecAttrAccessGroup as String] == nil)
        #expect(base[kSecUseDataProtectionKeychain as String] as? Bool == true)

        let grouped = KeychainStore.baseQuery(service: service, accessGroup: "group.dev.deeplinks.ios")
        #expect((grouped[kSecAttrAccessGroup as String] as? String) == "group.dev.deeplinks.ios")

        let item = KeychainStore.itemQuery(service: service, accessGroup: nil, account: account)
        #expect((item[kSecAttrAccount as String] as? String) == account)

        let add = KeychainStore.addAttributes(service: service, accessGroup: nil, account: account, data: secret)
        #expect((add[kSecAttrAccessible as String] as? String) == accessible, "红线：AfterFirstUnlockThisDeviceOnly")
        #expect(add[kSecAttrSynchronizable as String] as? Bool == false)
        #expect(add[kSecValueData as String] as? Data == secret)

        let update = KeychainStore.updateAttributes(data: secret)
        #expect((update[kSecAttrAccessible as String] as? String) == accessible, "更新时也要重申红线属性")
        #expect(update[kSecAttrSynchronizable as String] as? Bool == false)
        #expect(update[kSecValueData as String] as? Data == secret)
        #expect(update[kSecAttrAccessGroup as String] == nil, "更新不应改访问组")

        let list = KeychainStore.listQuery(service: service, accessGroup: nil)
        #expect((list[kSecMatchLimit as String] as? String) == (kSecMatchLimitAll as String))
        #expect(list[kSecReturnAttributes as String] as? Bool == true)
        #expect(list[kSecAttrSynchronizable as String] as? Bool == false)
    }

    // MARK: - 读 testdata 的合同测试（pair.json → PairedHost 存取）

    @Test func pairFixtureBuildsPairedHostAndRoundTrips() async throws {
        let url = repoRoot()
            .appendingPathComponent("testdata")
            .appendingPathComponent("mobile-contract")
            .appendingPathComponent("pair.json")
        let pair = try JSONDecoder().decode(PairResponse.self, from: Data(contentsOf: url))
        #expect(pair.ok == true)
        #expect(pair.pending == false)
        let token = try #require(pair.token)
        let primaryUrl = try #require(pair.urls?.first)

        // 配对响应本身不带 hostId / 电脑名 / 指纹：hostId、name、certFingerprint 来自二维码
        // 载荷（I3.8 / I3.4：没有指纹的局域网地址拒绝配对），这里用固定样本补齐后走存取全流程。
        let host = PairedHost(
            hostId: "dsh-fixture-host",
            name: "工作室电脑",
            primaryUrl: primaryUrl,
            tailnetUrl: "https://studio.tail1234.ts.net:18640",
            certFingerprint: "sha256/" + String(repeating: "a1b2c3d4", count: 8),
            remote: DeviceRemoteInfo(
                endpoint: "wss://relay.example/ws",
                routeId: "cm91dGU",
                deviceHandle: "aGFuZGxl",
                relayKey: "a2V5",
                outerCertificatePin: "sha256/ab12"
            ),
            pairedAt: 1_767_225_600_000
        )

        let dir = tempDirectory()
        let file = dir.appendingPathComponent("hosts.json")
        let store = InMemorySecureStore()
        let hostStore = HostStore(fileURL: file, secureStore: store)
        try await hostStore.save(host: host, token: token)

        #expect(await hostStore.get(hostId: host.hostId) == host)
        #expect(await hostStore.token(for: host.hostId) == token)

        let reopened = HostStore(fileURL: file, secureStore: store)
        #expect(await reopened.get(hostId: host.hostId) == host)
        #expect(await reopened.token(for: host.hostId) == token)
        #expect(!jsonText(at: file).contains(token))
    }

    // MARK: - 助手

    private func savedSample(pluginHostId: String? = nil) async throws -> (HostStore, InMemorySecureStore, URL) {
        let directory = tempDirectory()
        let file = directory.appendingPathComponent("hosts.json")
        let store = InMemorySecureStore()
        let hostStore = HostStore(fileURL: file, secureStore: store)
        var host = sampleHost()
        host.pluginHostId = pluginHostId
        try await hostStore.save(host: host, token: "tok-1")
        return (hostStore, store, file)
    }

    private func sampleHost() -> PairedHost {
        PairedHost(
            hostId: "dsh-test-host",
            name: "工作室电脑",
            primaryUrl: "https://192.168.10.17:18640",
            tailnetUrl: "https://studio.tail1234.ts.net:18640",
            certFingerprint: "sha256/" + String(repeating: "a1b2c3d4", count: 8),
            remote: DeviceRemoteInfo(
                endpoint: "wss://relay.example/ws",
                routeId: "cm91dGU",
                deviceHandle: "aGFuZGxl",
                relayKey: "cmVtb3RlLWtleQ",
                outerCertificatePin: "sha256/ab12"
            ),
            pairedAt: 1_767_225_600_000
        )
    }

    private func tempDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("hoststore-tests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func jsonText(at url: URL) -> String {
        guard let data = try? Data(contentsOf: url) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    private func repoRoot() -> URL {
        // …/apps/ios/Tests/Contract/<file>.swift 逐级上溯 5 层到仓库根
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

/// 测试用包装：按子串屏蔽读到的数据（其余操作透传），用于构造「元数据在、密钥缺失」的半态。
private final class DropReadsSecureStore: SecureStore, @unchecked Sendable {
    private let inner: SecureStore
    private let droppedSubstring: String

    init(inner: SecureStore, dropping substring: String) {
        self.inner = inner
        self.droppedSubstring = substring
    }

    func data(forKey key: String) throws -> Data? {
        if key.contains(droppedSubstring) { return nil }
        return try inner.data(forKey: key)
    }

    func setData(_ data: Data, forKey key: String) throws {
        try inner.setData(data, forKey: key)
    }

    func removeData(forKey key: String) throws {
        try inner.removeData(forKey: key)
    }

    func allKeys() throws -> [String] {
        try inner.allKeys()
    }
}

/// 前几次写入成功，之后抛错。用来验证替换同一台电脑失败时，旧 token 和记录都留着。
private final class FailWritesSecureStore: SecureStore, @unchecked Sendable {
    private let inner: SecureStore
    private let lock = NSLock()
    private var remaining: Int

    init(inner: SecureStore, failAfter: Int) {
        self.inner = inner
        self.remaining = failAfter
    }

    func data(forKey key: String) throws -> Data? {
        try inner.data(forKey: key)
    }

    func setData(_ data: Data, forKey key: String) throws {
        let allowed = lock.withLock { () -> Bool in
            if remaining == 0 { return false }
            remaining -= 1
            return true
        }
        guard allowed else { throw CocoaError(.fileWriteUnknown) }
        try inner.setData(data, forKey: key)
    }

    func removeData(forKey key: String) throws {
        try inner.removeData(forKey: key)
    }

    func allKeys() throws -> [String] {
        try inner.allKeys()
    }
}
