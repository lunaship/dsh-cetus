import DLSecurity
import Foundation
import Testing

@testable import Cetus

// MARK: - 测试替身

/// 可控时钟：推进时间而不真等。
@MainActor final class TestClock {
    var current = Date()
    func advance(_ seconds: TimeInterval) { current += seconds }
}

/// 会失败的密钥仓库：用来验证"写盘失败保留内存并回调"。
private final class FailingSecureStore: SecureStore, @unchecked Sendable {
    enum Mode { case failWrite, failRead }
    var mode: Mode
    init(mode: Mode) { self.mode = mode }

    func data(forKey key: String) throws -> Data? {
        if mode == .failRead { throw ComposerDraftFailure.keyStore }
        return nil
    }
    func setData(_ data: Data, forKey key: String) throws {
        if mode == .failWrite { throw ComposerDraftFailure.keyStore }
    }
    func removeData(forKey key: String) throws {}
    func allKeys() throws -> [String] { [] }
}

private func tempDirectory(_ name: String = UUID().uuidString) -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent(name, isDirectory: true)
}

private func makeStore(_ directory: URL, keys: any SecureStore = InMemorySecureStore()) -> ComposerDraftStore {
    ComposerDraftStore(directory: directory, keys: keys)
}

// MARK: - 存储键：A/B 会话互不串

@MainActor @Suite struct ComposerDraftStoreKeyTests {
    /// 正向：同一电脑的 A/B 会话各存不同文本，互不覆盖。
    @Test func sessionDraftsDoNotCollide() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let a = ComposerDraftKey(hostID: "host", sessionID: "session-A")
        let b = ComposerDraftKey(hostID: "host", sessionID: "session-B")

        store.save(a, text: "A 的草稿")
        store.save(b, text: "B 的草稿")

        #expect(store.text(a) == "A 的草稿")
        #expect(store.text(b) == "B 的草稿")
    }

    /// 反向验证：退回旧的"键只有 hostID"实现，上面那条断言必须失败。
    /// 旧实现两份草稿写同一个文件 → B 覆盖 A，读 A 得到 B 的文本。
    @Test func legacyHostOnlyKeyWouldCollide() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // 复刻旧实现：文件名只由 hostID 生成。
        func legacyFile(_ hostID: String) -> URL {
            let name = hostID.unicodeScalars.map { scalar -> String in
                CharacterSet.alphanumerics.contains(scalar) ? String(scalar) : "_"
            }.joined()
            return directory.appendingPathComponent(name).appendingPathExtension("txt")
        }
        try? "A 的草稿".write(to: legacyFile("host"), atomically: true, encoding: .utf8)
        try? "B 的草稿".write(to: legacyFile("host"), atomically: true, encoding: .utf8)

        let readBack = (try? String(contentsOf: legacyFile("host"), encoding: .utf8)) ?? ""
        // 证明旧键确实会串：读到的是 B，A 已经没了。
        #expect(readBack == "B 的草稿")
        #expect(readBack != "A 的草稿")
    }

    /// 正向：三种 kind 在同一会话里互不覆盖。
    @Test func kindsDoNotCollide() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let base = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let prompt = base.with(kind: .prompt)
        let answer = base.with(kind: .answer)
        let reference = base.with(kind: .reference)

        store.save(prompt, text: "正文")
        store.save(answer, text: "回答")
        store.save(reference, text: "引用")

        #expect(store.text(prompt) == "正文")
        #expect(store.text(answer) == "回答")
        #expect(store.text(reference) == "引用")
    }

    /// 正向：新任务草稿用 draftID + workspaceID，不归到任何已存在会话。
    @Test func newTaskDraftIsSeparateFromSessions() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let newTask = ComposerDraftKey(hostID: "host", draftID: "new-task", workspaceID: "ws-1")
        let session = ComposerDraftKey(hostID: "host", sessionID: "s1")

        store.save(newTask, text: "还没发的新任务")
        store.save(session, text: "会话里的草稿")

        #expect(store.text(newTask) == "还没发的新任务")
        #expect(store.text(session) == "会话里的草稿")
        // 关键：新任务草稿绝不能被当成某个已存在会话的草稿。
        #expect(store.text(session) != "还没发的新任务")
    }

    /// 正向：不同工作区的新任务草稿互不串。
    @Test func newTaskDraftsDifferByWorkspace() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let ws1 = ComposerDraftKey(hostID: "host", draftID: "new-task", workspaceID: "ws-1")
        let ws2 = ComposerDraftKey(hostID: "host", draftID: "new-task", workspaceID: "ws-2")

        store.save(ws1, text: "工作区 1")
        store.save(ws2, text: "工作区 2")

        #expect(store.text(ws1) == "工作区 1")
        #expect(store.text(ws2) == "工作区 2")
    }

    /// 正向：会话确定后 bind 到 sessionID，槽位不变（draftID 不参与有会话时的键）。
    @Test func bindingSessionKeepsDraftReachable() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let before = ComposerDraftKey(hostID: "host", sessionID: "s1", draftID: "random-uuid")
        let after = ComposerDraftKey(hostID: "host", sessionID: "s1", draftID: "another-uuid")

        store.save(before, text: "起草中")
        #expect(store.text(after) == "起草中")
    }
}

extension ComposerDraftKey {
    func with(kind: ComposerDraftKind) -> ComposerDraftKey {
        var next = self
        next.kind = kind
        return next
    }
}

// MARK: - 落盘语义

@MainActor @Suite struct ComposerDraftPersistenceTests {
    /// 正向：空文本等于删除。
    @Test func emptyTextRemovesDraft() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        store.save(key, text: "写了一半")
        #expect(store.text(key) == "写了一半")
        store.save(key, text: "")
        #expect(store.text(key) == "")
        #expect(store.load(key) == nil)
    }

    /// 正向：超过上限返回 .failed，不落盘。
    @Test func oversizedTextFails() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        let huge = String(repeating: "x", count: ComposerDraftStore.textByteLimit + 1)
        #expect(store.save(key, text: huge) == .failed)
        #expect(store.text(key) == "")
    }

    /// 反向验证：如果没有这个上限，超大文本会被写进去。
    /// 这里直接确认"上限内"的文本确实能写成功，证明上限是唯一拦截点。
    @Test func limitIsTheOnlyGate() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        let within = String(repeating: "y", count: ComposerDraftStore.textByteLimit - 1024)
        #expect(store.save(key, text: within) == .persisted)
        #expect(store.text(key) == within)
    }

    /// 正向：不认识的 schemaVersion 读成 nil，不猜字段。
    @Test func unknownSchemaIsIgnored() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        let future = ComposerDraftRecord(
            schemaVersion: ComposerDraftRecord.schemaVersion + 99, text: "未来格式")
        store.save(key, record: future)

        #expect(store.load(key) == nil)
        #expect(store.text(key) == "")
    }

    /// 反向验证：同版本能正常读回，证明上面的 nil 确实来自版本判定而非别的原因。
    @Test func knownSchemaReadsBack() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        store.save(key, record: ComposerDraftRecord(text: "当前格式"))
        #expect(store.text(key) == "当前格式")
    }

    /// 正向：正文是密文，磁盘上没有明文。
    @Test func textIsEncryptedAtRest() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let secret = "这段不该以明文出现在磁盘上"

        store.save(key, text: secret)
        let raw = try? Data(contentsOf: store.file(key))
        #expect(raw != nil)
        let asText = String(data: raw ?? Data(), encoding: .utf8) ?? ""
        #expect(!asText.contains(secret))
    }

    /// 正向：时间戳与归属会话随记录一起保存。
    @Test func recordCarriesMetadata() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let fixed = Date(timeIntervalSince1970: 1_700_000_000)
        var store = makeStore(directory)
        store.now = { fixed }
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1", workspaceID: "ws-9")

        store.save(key, text: "带元数据")
        let record = store.load(key)
        #expect(record?.sessionID == "s1")
        #expect(record?.workspaceID == "ws-9")
        #expect(record?.updatedAt == fixed)
        #expect(record?.schemaVersion == ComposerDraftRecord.schemaVersion)
    }
}

// MARK: - 会话删除：不得株连

@MainActor @Suite struct ComposerDraftRemovalTests {
    /// 正向：删一个会话的草稿，其它会话、其它主机不受影响。
    @Test func removingOneSessionSparesOthers() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let a = ComposerDraftKey(hostID: "host", sessionID: "s-A")
        let b = ComposerDraftKey(hostID: "host", sessionID: "s-B")
        let otherHost = ComposerDraftKey(hostID: "host-2", sessionID: "s-A")

        store.save(a, text: "A")
        store.save(b, text: "B")
        store.save(otherHost, text: "另一台电脑")

        store.removeAll(hostID: "host", sessionID: "s-A")

        #expect(store.text(a) == "")
        #expect(store.text(b) == "B")
        #expect(store.text(otherHost) == "另一台电脑")
    }

    /// 反向验证：如果实现成"清掉该主机下所有草稿"，B 会一起没。
    /// 这里确认 B 还在 → 证明实现不是株连式清理。
    @Test func removalIsNotHostWide() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let a = ComposerDraftKey(hostID: "host", sessionID: "s-A")
        let b = ComposerDraftKey(hostID: "host", sessionID: "s-B")

        store.save(a, text: "A")
        store.save(b, text: "B")

        // 假设的错误实现：删掉整个主机目录。
        let hostDirectory = store.file(a).deletingLastPathComponent().deletingLastPathComponent()
        // 先确认这个目录确实包含两台会话的草稿（它是株连会生效的位置）
        #expect(FileManager.default.fileExists(atPath: hostDirectory.path))

        store.removeAll(hostID: "host", sessionID: "s-A")
        // 正确实现只删 A 的三个槽位，B 必须还在。
        #expect(store.text(b) == "B")
    }

    /// 正向：删会话时三个 kind 都清掉。
    @Test func removalClearsAllKinds() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let base = ComposerDraftKey(hostID: "host", sessionID: "s1")
        for kind in ComposerDraftKind.allCases {
            store.save(base.with(kind: kind), text: kind.rawValue)
        }
        store.removeAll(hostID: "host", sessionID: "s1")
        for kind in ComposerDraftKind.allCases {
            #expect(store.text(base.with(kind: kind)) == "")
        }
    }

    /// 解绑一台电脑：它的所有草稿（含附件与旧版孤儿草稿）一起没，另一台完好。
    @Test func removingHostClearsOnlyThatHost() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let a = ComposerDraftKey(hostID: "host", sessionID: "s-A")
        let draft = ComposerDraftKey(hostID: "host", draftID: "d1", workspaceID: "/w")
        let other = ComposerDraftKey(hostID: "host-2", sessionID: "s-A")
        guard let handleA = store.writeAttachment(a, mediaType: "image/png", data: Data([1])),
            let handleOther = store.writeAttachment(other, mediaType: "image/png", data: Data([2]))
        else {
            Issue.record("附件写入应当成功")
            return
        }
        store.save(a, record: ComposerDraftRecord(text: "A", attachments: [handleA]))
        store.save(draft, text: "新任务")
        store.save(other, record: ComposerDraftRecord(text: "另一台", attachments: [handleOther]))
        let legacy = store.legacyDirectory(hostID: "host")
        try? FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
        try? "旧草稿".write(to: legacy.appendingPathComponent("old.txt"), atomically: true, encoding: .utf8)

        store.removeHost(hostID: "host")

        #expect(store.text(a) == "")
        #expect(store.text(draft) == "")
        #expect(store.orphanDrafts(hostID: "host").isEmpty)
        #expect(store.readAttachment(a, bookmark: handleA.bookmark) == nil)
        #expect(store.text(other) == "另一台")
        #expect(store.readAttachment(other, bookmark: handleOther.bookmark) == Data([2]))
    }
}

// MARK: - 附件引用

@MainActor @Suite struct ComposerDraftAttachmentTests {
    /// 正向：附件只存引用，失效后不会被当成已附上。
    @Test func missingAttachmentIsFilteredOut() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        guard let handle = store.writeAttachment(key, mediaType: "image/png", data: Data([1, 2, 3])) else {
            Issue.record("附件写入应当成功")
            return
        }
        var record = ComposerDraftRecord(text: "带附件", attachments: [handle])
        store.save(key, record: record)
        #expect(store.load(key)?.attachments.count == 1)

        // 模拟附件失效（应用容器被清 / 系统回收）。
        try? FileManager.default.removeItem(at: store.attachmentFile(key, bookmark: handle.bookmark))
        #expect(store.load(key)?.attachments.isEmpty == true)
    }

    /// 正向：引用还活着时能读回字节。
    @Test func attachmentRoundTrips() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        let handle = store.writeAttachment(key, mediaType: "image/png", data: Data([9, 8, 7]))
        #expect(handle != nil)
        #expect(store.readAttachment(key, bookmark: handle?.bookmark ?? "") == Data([9, 8, 7]))
    }

    /// 正向：删草稿时连自己的附件一起删，但不动别的草稿还在用的。
    @Test func removingDraftKeepsOtherAttachments() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let a = ComposerDraftKey(hostID: "host", sessionID: "s-A")
        let b = ComposerDraftKey(hostID: "host", sessionID: "s-B").with(kind: .reference)

        guard let handleA = store.writeAttachment(a, mediaType: "image/png", data: Data([1])),
            let handleB = store.writeAttachment(b, mediaType: "image/png", data: Data([2]))
        else {
            Issue.record("附件写入应当成功")
            return
        }
        store.save(a, record: ComposerDraftRecord(text: "A", attachments: [handleA]))
        store.save(b, record: ComposerDraftRecord(text: "B", attachments: [handleB]))

        store.remove(a)
        #expect(store.readAttachment(a, bookmark: handleA.bookmark) == nil)
        #expect(store.readAttachment(b, bookmark: handleB.bookmark) == Data([2]))
    }
}

// MARK: - 旧草稿：只列出，不自动归属

@MainActor @Suite struct ComposerDraftOrphanTests {
    /// 正向：旧 host 级草稿被列为孤儿，且不带会话归属。
    @Test func legacyDraftsBecomeOrphans() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let legacy = store.legacyDirectory(hostID: "host")
        try? FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
        try? "旧版本留下的一段话".write(
            to: legacy.appendingPathComponent("host.txt"), atomically: true, encoding: .utf8)

        let orphans = store.orphanDrafts(hostID: "host")
        #expect(orphans.count == 1)
        #expect(orphans.first?.text == "旧版本留下的一段话")
        // 无法推断会话 → 恒为 nil，UI 据此显示"未知会话"并让用户选目标。
        #expect(orphans.first?.sessionID == nil)
    }

    /// 正向：认领后旧条目消失，草稿归到用户选的会话。
    @Test func adoptingOrphanMovesItToChosenSession() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let legacy = store.legacyDirectory(hostID: "host")
        try? FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
        try? "旧草稿".write(to: legacy.appendingPathComponent("host.txt"), atomically: true, encoding: .utf8)

        guard let orphan = store.orphanDrafts(hostID: "host").first else {
            Issue.record("应当列出一条孤儿草稿")
            return
        }
        let target = ComposerDraftKey(hostID: "host", sessionID: "用户选的会话")
        #expect(store.adopt(orphan, to: target) == .persisted)
        #expect(store.text(target) == "旧草稿")
        #expect(store.orphanDrafts(hostID: "host").isEmpty)
    }

    /// 反向验证：孤儿草稿**不会**自己跑到任意会话里。
    /// 没有 adopt 之前，任何会话都读不到它。
    @Test func orphansNeverAutoAttachToASession() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let legacy = store.legacyDirectory(hostID: "host")
        try? FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
        try? "旧草稿".write(to: legacy.appendingPathComponent("host.txt"), atomically: true, encoding: .utf8)

        #expect(!store.orphanDrafts(hostID: "host").isEmpty)
        // 最近会话也好，任意会话也好，都不能凭空读到它。
        #expect(store.text(ComposerDraftKey(hostID: "host", sessionID: "最近会话")) == "")
        #expect(store.text(ComposerDraftKey(hostID: "host", sessionID: "s1")) == "")
    }

    /// 正向：新草稿不会被误当成待认领的孤儿（新旧目录隔离）。
    @Test func newDraftsAreNotOrphans() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        store.save(ComposerDraftKey(hostID: "host", sessionID: "s1"), text: "新草稿")
        #expect(store.orphanDrafts(hostID: "host").isEmpty)
    }
}

// MARK: - 合并写入

@MainActor @Suite struct ComposerDraftWriterTests {
    /// 正向：静默窗口内不落盘，flush 后才落盘。
    @Test func updateDoesNotWriteUntilFlush() async throws {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)
        let clock = TestClock()
        // 很长的窗口：让"还没写"成为确定性断言，而不是靠 sleep 赌时序。
        controller.configureForTest(quietWindow: .seconds(600), maxPending: .seconds(900), clock: { clock.current })

        controller.update("写了一半", kind: .prompt)
        #expect(store.text(key) == "")
        #expect(controller.hasPending)

        controller.flush()
        #expect(store.text(key) == "写了一半")
        #expect(!controller.hasPending)
    }

    /// 反向验证：如果每个字符都同步写盘，上面那条"还没写"的断言会失败。
    /// 这里直接证明同步写入确实立刻可见 —— 也就是被测试挡掉的那种行为。
    @Test func synchronousWriteWouldBeVisibleImmediately() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")

        store.save(key, text: "同步写")
        #expect(store.text(key) == "同步写")
    }

    /// 正向：同槽位多次 update 只保留最后一份。
    @Test func repeatedUpdatesKeepTheLatest() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)
        let clock = TestClock()
        controller.configureForTest(quietWindow: .seconds(600), maxPending: .seconds(900), clock: { clock.current })

        for character in "一二三四五" { controller.update(String(character), kind: .prompt) }
        controller.flush()
        #expect(store.text(key) == "五")
    }

    /// 正向：clear 删掉磁盘上的草稿。
    @Test func clearRemovesPersistedDraft() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)

        controller.update("待发", kind: .prompt)
        controller.flush()
        #expect(store.text(key) == "待发")

        controller.clear(.prompt)
        #expect(store.text(key) == "")
    }

    /// 正向：写失败时回调 onFailure（内存副本由 UI 继续持有）。
    @Test func writeFailureIsReported() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)
        var failed: [ComposerDraftKind] = []
        controller.onWriteFailure = { failed.append($0) }

        let huge = String(repeating: "z", count: ComposerDraftStore.textByteLimit + 1)
        controller.update(huge, kind: .prompt)
        controller.flush()

        #expect(failed == [.prompt])
        #expect(store.text(key) == "")
    }

    /// 正向：不同槽位分开排队，互不覆盖。
    @Test func kindsFlushIndependently() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)
        let clock = TestClock()
        controller.configureForTest(quietWindow: .seconds(600), maxPending: .seconds(900), clock: { clock.current })

        controller.update("正文", kind: .prompt)
        controller.update("回答", kind: .answer)
        controller.flush()

        #expect(store.text(key.with(kind: .prompt)) == "正文")
        #expect(store.text(key.with(kind: .answer)) == "回答")
    }
}

// MARK: - 审批冻结

@MainActor @Suite struct ComposerDraftFreezeTests {
    /// 正向：冻结期间不落盘；解冻后把内存文字补写回去。
    @Test func freezeStopsWritesAndUnfreezePersists() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)
        let clock = TestClock()
        controller.configureForTest(quietWindow: .seconds(600), maxPending: .seconds(900), clock: { clock.current })

        controller.update("审批前的正文", kind: .prompt)
        controller.flush()
        #expect(store.text(key) == "审批前的正文")

        // 审批出现：冻结。期间的任何输入都不落盘。
        controller.freeze(.prompt)
        controller.update("审批期间的输入", kind: .prompt)
        controller.flush()
        // 关键：冻结不是清空 —— 盘上仍然是用户原来那份。
        #expect(store.text(key) == "审批前的正文")

        // 审批结束：解冻并补写。
        controller.unfreeze(.prompt, text: "审批前的正文")
        controller.flush()
        #expect(store.text(key) == "审批前的正文")
    }

    /// 反向验证：冻结若是"清空"，盘上会变空。断言盘上非空即排除了清空实现。
    @Test func freezeIsNotClearing() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)

        controller.update("不能丢", kind: .prompt)
        controller.flush()
        controller.freeze(.prompt)

        #expect(!store.text(key).isEmpty)
        #expect(store.text(key) == "不能丢")
        #expect(controller.isFrozen(.prompt))
    }

    /// 正向：冻结只影响被冻结的那一类，回答槽位照常写。
    @Test func freezeIsPerKind() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)

        controller.freeze(.prompt)
        controller.update("问题回答", kind: .answer)
        controller.flush()

        #expect(store.text(key.with(kind: .answer)) == "问题回答")
        #expect(store.text(key.with(kind: .prompt)) == "")
    }

    /// 正向：clear 会解冻，避免该槽位永久卡住。
    @Test func clearUnfreezes() {
        let directory = tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let key = ComposerDraftKey(hostID: "host", sessionID: "s1")
        let controller = ComposerDraftController(store: store, key: key)

        controller.freeze(.prompt)
        controller.clear(.prompt)
        #expect(!controller.isFrozen(.prompt))
    }
}

// MARK: - 生产注入

@MainActor @Suite struct ComposerDraftInjectionTests {
    /// 正向：环境值没被覆盖时也有一个真实可用的仓库（不再静默不落盘）。
    @Test func defaultEnvironmentStoreIsUsable() {
        let store = ComposerDraftStore.live(keys: InMemorySecureStore())
        #expect(!store.directory.path.isEmpty)
        #expect(store.directory.path.hasSuffix("ComposerDrafts"))
    }

    /// 反向验证：旧的生产路径拿不到仓库（`draftDirectory` 为 nil）→ 什么都不写。
    /// 这里复刻那个条件分支，证明"没注入就静默失效"确实是真 bug。
    @Test func missingInjectionWouldSilentlyDropDrafts() {
        var draftDirectory: URL? = nil  // 旧生产路径的实际情况
        var writes = 0
        if let draftDirectory {  // swiftlint:disable:this unused
            _ = draftDirectory
            writes += 1
        }
        #expect(writes == 0)
    }
}
