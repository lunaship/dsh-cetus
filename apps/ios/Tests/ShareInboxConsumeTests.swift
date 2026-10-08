import DLCore
import DLModels
import Foundation
import Testing

/// C13：分享扩展、系统入口与前后台恢复。
///
/// 覆盖方案 §17 的四条硬要求：
/// 1. 扩展只写 App Group 最小受保护队列（不联网、不读 token）→ 见 `ShareInboxTests` 既有用例
/// 2. 选择电脑与会话/新任务后**预填、不自动发送**；已有草稿**合并不覆盖**
/// 3. 数量/大小/格式限制明确；取消失败可恢复、**不重复消费队列**
/// 4. 深链与通知进入统一导航协调器；未知/归档/删除目标有合理结果
struct ShareInboxConsumeTests {
    private func makeStore() -> (ShareGroupStore, URL) {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("c13-\(UUID().uuidString)", isDirectory: true)
        return (ShareGroupStore(root: root), root)
    }

    // MARK: - 要求 3：不重复消费队列

    @Test("消费是幂等的：重复消费同一 id 不会二次改变状态")
    func consumeIsIdempotent() throws {
        let (store, root) = makeStore()
        defer { try? FileManager.default.removeItem(at: root) }

        let record = try ShareInbox.imageRecord(
            bytes: Data("png!".utf8), text: "按钮颜色反了", id: "abcd1234",
            now: Date(timeIntervalSince1970: 0)
        ).get()
        try store.writeRecord(record, image: Data("png!".utf8))

        #expect(store.isPending(id: "abcd1234"))

        // 首次消费：真的删掉了东西
        #expect(store.consume(id: "abcd1234") == true)
        #expect(store.readRecord(id: "abcd1234") == nil)
        #expect(store.isPending(id: "abcd1234") == false)

        // 重复消费：必须是安全的 no-op，而不是再看一次/再删一次
        #expect(store.consume(id: "abcd1234") == false)
        #expect(store.consume(id: "abcd1234") == false)
        #expect(store.readRecord(id: "abcd1234") == nil)
    }

    @Test("消费先移除元数据入口：不存在半消费记录（元数据在、图片已被删）")
    func consumeRemovesMetadataBeforeImage() throws {
        let (store, root) = makeStore()
        defer { try? FileManager.default.removeItem(at: root) }

        let record = try ShareInbox.imageRecord(
            bytes: Data("png!".utf8), text: "截图", id: "beef1234",
            now: Date(timeIntervalSince1970: 0)
        ).get()
        try store.writeRecord(record, image: Data("png!".utf8))
        #expect(store.imageData(for: record) == Data("png!".utf8))

        _ = store.consume(id: "beef1234")

        // 元数据与图片都不应再可达
        #expect(store.readRecord(id: "beef1234") == nil)
        // 幂等再消费也不应因为图片已删而抛错/复活记录
        #expect(store.consume(id: "beef1234") == false)
        #expect(store.readRecord(id: "beef1234") == nil)
    }

    @Test("消费会清除 latest.json 快照，已处理内容不长期滞留 App Group")
    func consumeClearsSnapshot() throws {
        let (store, root) = makeStore()
        defer { try? FileManager.default.removeItem(at: root) }

        let record = try ShareInbox.textRecord(
            text: "敏感内容", id: "cafe1234", now: Date(timeIntervalSince1970: 0)
        ).get()
        try store.writeRecord(record)

        let snapshot = root.appendingPathComponent("latest.json")
        #expect(FileManager.default.fileExists(atPath: snapshot.path))

        _ = store.consume(id: "cafe1234")

        // 快照若仍指向已消费记录，会把用户内容长期留在共享容器里
        #expect(FileManager.default.fileExists(atPath: snapshot.path) == false)
    }

    @Test("非法 id 的消费不触碰磁盘")
    func consumeRejectsUnsafeID() throws {
        let (store, root) = makeStore()
        defer { try? FileManager.default.removeItem(at: root) }

        #expect(store.consume(id: "../../etc/passwd") == false)
        #expect(store.consume(id: "short") == false)
        #expect(store.consume(id: "") == false)
    }

    // MARK: - 要求 3：失效附件

    @Test("图片被外部删除后，记录仍在但取不到图：不当成已附上")
    func staleAttachmentYieldsNoImage() throws {
        let (store, root) = makeStore()
        defer { try? FileManager.default.removeItem(at: root) }

        let record = try ShareInbox.imageRecord(
            bytes: Data("png!".utf8), text: "带图", id: "dead1234",
            now: Date(timeIntervalSince1970: 0)
        ).get()
        try store.writeRecord(record, image: Data("png!".utf8))

        // 模拟 sharing 容器被清理 / 复制失败：图片没了，元数据还在
        try FileManager.default.removeItem(at: root.appendingPathComponent("dead1234.img"))

        #expect(store.readRecord(id: "dead1234") != nil)
        #expect(store.imageData(for: record) == nil)

        // 关键：失效附件不能变成"已附上一张空图"
        let prefill = ShareInbox.prefill(record: record, image: nil, target: .session("s1"))
        #expect(prefill.images.isEmpty)
        #expect(prefill.text == "带图")
    }

    // MARK: - 要求 3：security-scoped URL 复制为应用持有文件

    @Test("security-scoped 读取：开启与释放必须配平")
    func securityScopedReadBalancesStartStop() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("c13-scope-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("shot.png")
        try Data("png!".utf8).write(to: file)

        let log = ScopeLog()
        let scope = ShareSecurityScope(
            startAccessing: { url in
                log.record("start", url)
                return true
            },
            stopAccessing: { url in log.record("stop", url) }
        )

        let data = try ShareInbox.readSecurityScopedFile(at: file, scope: scope)
        #expect(data == Data("png!".utf8))
        #expect(log.events == ["start", "stop"], "必须成对且顺序正确")
    }

    @Test("读取失败时仍然释放作用域，不泄漏跨容器访问权")
    func securityScopeReleasedOnFailure() throws {
        let missing = FileManager.default.temporaryDirectory
            .appendingPathComponent("c13-missing-\(UUID().uuidString).png")
        let log = ScopeLog()
        let scope = ShareSecurityScope(
            startAccessing: { url in
                log.record("start", url)
                return true
            },
            stopAccessing: { url in log.record("stop", url) }
        )

        #expect(throws: ShareInboxError.attachmentUnavailable) {
            try ShareInbox.readSecurityScopedFile(at: missing, scope: scope)
        }
        #expect(log.events == ["start", "stop"], "失败路径也要 stop")
    }

    @Test("系统未真正开启作用域时不得调用 stop，避免破坏计数")
    func securityScopeDoesNotStopWhenNotOpened() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("c13-noscope-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("shot.png")
        try Data("png!".utf8).write(to: file)

        let log = ScopeLog()
        let scope = ShareSecurityScope(
            startAccessing: { url in
                log.record("start", url)
                return false  // 开启失败
            },
            stopAccessing: { url in log.record("stop", url) }
        )

        // 开启失败仍尝试读取（有的 URL 本就不需要作用域），但**绝不能** stop
        _ = try? ShareInbox.readSecurityScopedFile(at: file, scope: scope)
        #expect(log.events == ["start"], "未开启成功就不能 stop")
    }

    @Test("超出大小上限的 security-scoped 文件被拒，且作用域已释放")
    func securityScopedReadEnforcesByteLimit() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("c13-big-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("big.png")
        try Data(repeating: 0x1, count: 2048).write(to: file)

        let log = ScopeLog()
        let scope = ShareSecurityScope(
            startAccessing: { url in
                log.record("start", url)
                return true
            },
            stopAccessing: { url in log.record("stop", url) }
        )

        #expect(throws: ShareInboxError.tooLarge) {
            try ShareInbox.readSecurityScopedFile(at: file, scope: scope, byteLimit: 1024)
        }
        #expect(log.events == ["start", "stop"])
    }

    @Test("非文件 URL 不开启作用域")
    func securityScopedReadRejectsNonFileURL() throws {
        let log = ScopeLog()
        let scope = ShareSecurityScope(
            startAccessing: { url in
                log.record("start", url)
                return true
            },
            stopAccessing: { url in log.record("stop", url) }
        )
        let remote = try #require(URL(string: "https://example.com/a.png"))
        #expect(throws: ShareInboxError.unsupportedType) {
            try ShareInbox.readSecurityScopedFile(at: remote, scope: scope)
        }
        #expect(log.events.isEmpty, "非文件 URL 不该动作用域")
    }
}

/// 记录 security-scope 的 start/stop 调用顺序，供配平断言使用。
private final class ScopeLog: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String] = []

    func record(_ event: String, _ url: URL) {
        lock.lock()
        defer { lock.unlock() }
        storage.append(event)
    }

    var events: [String] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    @Test("数量 / 大小 / 格式限制有明确结果")
    func batchLimitsAreEnforced() {
        let ok = Data(repeating: 0x1, count: 1024)
        #expect(ShareInbox.validateImageBatch([ok]) == nil)
        #expect(
            ShareInbox.validateImageBatch(Array(repeating: ok, count: ShareAppGroup.imageCountLimit)) == nil)

        // 超数量
        #expect(
            ShareInbox.validateImageBatch(Array(repeating: ok, count: ShareAppGroup.imageCountLimit + 1))
                == .tooManyImages)
        // 单张超大小
        let tooBig = Data(repeating: 0x1, count: ShareAppGroup.imageByteLimit + 1)
        #expect(ShareInbox.validateImageBatch([tooBig]) == .tooLarge)
        // 空数据
        #expect(ShareInbox.validateImageBatch([Data()]) == .tooLarge)

        // 格式白名单：只做精确匹配
        #expect(ShareInbox.isAllowedImageType("public.png"))
        #expect(ShareInbox.isAllowedImageType("public.jpeg"))
        #expect(ShareInbox.isAllowedImageType("public.heic"))
        #expect(ShareInbox.isAllowedImageType("public.heif"))
        #expect(ShareInbox.isAllowedImageType("com.compuserve.gif"))
        #expect(ShareInbox.isAllowedImageType("public.webp"))
        // 回归守护：曾用前缀匹配"兼容同族"→ 会放行任意后缀，使白名单失效
        #expect(ShareInbox.isAllowedImageType("public.png.something") == false)
        #expect(ShareInbox.isAllowedImageType("public.png.evil") == false)
        #expect(ShareInbox.isAllowedImageType("public.jpeg.exe") == false)
        #expect(ShareInbox.isAllowedImageType("com.adobe.pdf") == false)
        #expect(ShareInbox.isAllowedImageType("public.movie") == false)
        #expect(ShareInbox.isAllowedImageType("") == false)
    }
}

// MARK: - 要求 2：合并不覆盖

struct ShareInboxMergeTests {
    @Test("已有草稿时分享内容**追加**，绝不覆盖用户已写的字")
    func mergeAppendsInsteadOfOverwriting() {
        let merged = ShareInbox.merging(draft: "我在写的东西", shared: "color is inverted")
        #expect(merged.text == "我在写的东西\ncolor is inverted")
        #expect(merged.appended == true)
        // 用户原文必须在最前面，逐字符保留
        #expect(merged.text.hasPrefix("我在写的东西"))
    }

    @Test("草稿为空时直接采用分享内容")
    func mergeIntoEmptyDraft() {
        let merged = ShareInbox.merging(draft: "", shared: "color is inverted")
        #expect(merged.text == "color is inverted")
        #expect(merged.appended == false)
    }

    @Test("分享内容为空时不改动草稿")
    func mergeWithEmptyShareKeepsDraft() {
        let merged = ShareInbox.merging(draft: "保留我", shared: "   ")
        #expect(merged.text == "保留我")
        #expect(merged.appended == false)
    }

    @Test("重复分享同一条内容不会重复追加")
    func mergeIsIdempotentForSameShare() {
        let first = ShareInbox.merging(draft: "底稿", shared: "color is inverted")
        let second = ShareInbox.merging(draft: first.text, shared: "color is inverted")
        #expect(second.text == first.text)
        #expect(second.appended == false)
    }

    @Test("附件合并追加且遵守数量上限")
    func mergeImagesAppendsWithinLimit() {
        let existing = [PromptImage(mediaType: "image/png", data: "AAA")]
        let shared = [PromptImage(mediaType: "image/png", data: "BBB")]
        let merged = ShareInbox.mergingImages(existing: existing, shared: shared)
        #expect(merged.count == 2)
        #expect(merged.first?.data == "AAA")  // 既有在前
        #expect(merged.last?.data == "BBB")

        // 上限截断，不会无限增长
        let many = Array(repeating: PromptImage(mediaType: "image/png", data: "CCC"), count: 10)
        let capped = ShareInbox.mergingImages(existing: existing, shared: many)
        #expect(capped.count == ShareAppGroup.imageCountLimit)
        #expect(capped.first?.data == "AAA")

        // 没有分享图时保持原样
        #expect(ShareInbox.mergingImages(existing: existing, shared: []).count == 1)
    }
}

// MARK: - 要求 2：只预填、不自动发送

struct SharePrefillStaysLocalTests {
    @Test("预填只产出草稿数据，不携带任何发送意图")
    func prefillIsDraftOnly() throws {
        let record = try ShareInbox.textRecord(
            text: "整理一下这次改动", id: "abcd1234", now: Date(timeIntervalSince1970: 0)
        ).get()

        let prefill = ShareInbox.prefill(record: record, image: nil, target: .newTask)
        #expect(prefill.target == .newTask)
        #expect(prefill.text == "整理一下这次改动")
        #expect(prefill.images.isEmpty)

        let toSession = ShareInbox.prefill(record: record, image: nil, target: .session("s1"))
        #expect(toSession.target == .session("s1"))
        #expect(toSession.text == "整理一下这次改动")
    }

    @Test("分享 id 只接受安全字符，路径逃逸被拒")
    func shareIDRejectsPathEscape() {
        #expect(ShareInbox.isSafeID("abcd1234"))
        #expect(ShareInbox.isSafeID("ABCD1234") == false)  // 大写不在白名单
        #expect(ShareInbox.isSafeID("../abcd1234") == false)
        #expect(ShareInbox.isSafeID("abcd1234/../x") == false)
        #expect(ShareInbox.isSafeID("abcd123") == false)  // 太短
        #expect(ShareInbox.openURL(id: "../x") == nil)
        #expect(ShareInbox.id(from: URL(string: "deeplinks://share/../secret")!) == nil)
        // 非本 scheme 一律不认
        #expect(ShareInbox.id(from: URL(string: "https://share/abcd1234")!) == nil)
    }
}

// MARK: - N03.4：deeplinks:// 与 cetus:// 并存

struct ShareInboxSchemeTests {
    @Test("cetus:// 与 deeplinks:// 都能解析出分享 id")
    func bothSchemesParse() throws {
        let viaOld = try #require(URL(string: "deeplinks://share/abcd1234"))
        let viaNew = try #require(URL(string: "cetus://share/abcd1234"))
        #expect(ShareInbox.id(from: viaOld) == "abcd1234")
        #expect(ShareInbox.id(from: viaNew) == "abcd1234")
    }

    @Test("scheme 与 host 大小写不敏感")
    func schemeAndHostAreCaseInsensitive() throws {
        for raw in ["Cetus://share/abcd1234", "CETUS://share/abcd1234", "cetus://SHARE/abcd1234"] {
            let url = try #require(URL(string: raw))
            #expect(ShareInbox.id(from: url) == "abcd1234", "应接受 \(raw)")
        }
    }

    @Test("其余 scheme 一律拒绝")
    func otherSchemesAreRejected() throws {
        for raw in [
            "https://share/abcd1234",
            "http://share/abcd1234",
            "file://share/abcd1234",
            "deeplinks-asset://bundle/abcd1234",
            "cetuss://share/abcd1234",
            "deeplink://share/abcd1234",
        ] {
            let url = try #require(URL(string: raw))
            #expect(ShareInbox.id(from: url) == nil, "不应接受 \(raw)")
        }
    }

    @Test("host 不是 share 的链接不解析")
    func wrongHostIsRejected() throws {
        #expect(ShareInbox.id(from: try #require(URL(string: "cetus://pairing/abcd1234"))) == nil)
        #expect(ShareInbox.id(from: try #require(URL(string: "deeplinks://open/abcd1234"))) == nil)
    }

    @Test("生成端仍固定用 deeplinks（兼容期不改生成端）")
    func generationStaysOnLegacyScheme() throws {
        let url = try #require(ShareInbox.openURL(id: "abcd1234"))
        #expect(url.scheme == "deeplinks")
        #expect(url.absoluteString == "deeplinks://share/abcd1234")
        // 自己生成的链接必然能被自己解析（往返一致）
        #expect(ShareInbox.id(from: url) == "abcd1234")
        #expect(ShareAppGroup.scheme == "deeplinks")
        #expect(ShareAppGroup.acceptedSchemes == ["deeplinks", "cetus"])
    }

    @Test("两个 scheme 都遵守安全 id 校验")
    func bothSchemesEnforceSafeID() throws {
        for scheme in ["deeplinks", "cetus"] {
            #expect(ShareInbox.id(from: try #require(URL(string: "\(scheme)://share/../secret"))) == nil)
            #expect(ShareInbox.id(from: try #require(URL(string: "\(scheme)://share/abcd1234/../x"))) == nil)
            #expect(ShareInbox.id(from: try #require(URL(string: "\(scheme)://share/short"))) == nil)
            #expect(ShareInbox.id(from: try #require(URL(string: "\(scheme)://share/ABCD1234"))) == nil)
        }
    }
}
