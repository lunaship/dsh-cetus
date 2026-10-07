import XCTest

/// Measures the real cetus process. Unit tests render inside the test host and are not cold starts.
final class PerformanceLaunchTests: XCTestCase {
    private let hostName = "性能电脑"
    private let sessionTitle = "性能会话"
    private let firstMessage = "固定消息 1"
    private let lastMessage = "固定消息 3000"

    override func setUpWithError() throws {
        try super.setUpWithError()
        continueAfterFailure = false
    }

    func testColdStartShowsCachedInbox() async throws {
        let fixture = try await preparedFixture()
        let app = XCUIApplication()
        app.launchArguments = ["-performanceFixture", fixture.path]
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)]) {
            launchOnMain(app)
        }
        XCTAssertTrue(app.staticTexts[sessionTitle].waitForExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars[hostName].exists)
        app.terminate()
    }

    func testThreeThousandMessageScroll() async throws {
        let fixture = try await preparedFixture()
        let app = XCUIApplication()
        let ready = onMain { () -> Bool in
            app.launchArguments = ["-performanceFixture", fixture.path]
            app.launch()
            let row = app.staticTexts.matching(identifier: sessionTitle).firstMatch
            guard row.waitForExistence(timeout: 20) else { return false }
            row.tap()
            let stream = app.collectionViews["message-stream"]
            guard stream.waitForExistence(timeout: 10) else { return false }
            stream.swipeUp()
            return app.staticTexts["message-3000"].waitForExistence(timeout: 20)
        }
        XCTAssertTrue(ready)
        measure(metrics: [XCTClockMetric(), XCTOSSignpostMetric.scrollDecelerationMetric]) {
            onMain {
                let stream = app.collectionViews["message-stream"]
                stream.swipeDown()
                stream.swipeUp()
            }
        }
        let stillReachable = onMain {
            app.descendants(matching: .any)["message-1"].exists
                || app.staticTexts["message-3000"].exists
        }
        XCTAssertTrue(stillReachable)
        onMain { app.terminate() }
    }

    func testThreeThousandMessageAppend() throws {
        guard let raw = ProcessInfo.processInfo.environment["PERFORMANCE_CONTROL_URL"],
            let control = URL(string: raw)
        else {
            throw XCTSkip("PERFORMANCE_CONTROL_URL is required")
        }
        let path = try qrPath()
        guard post(control, "/control/restart", [:]) else { return }
        try waitForFreshQR(path)
        let app = XCUIApplication()
        let storage = appendStorageDirectory()
        try? FileManager.default.removeItem(at: storage)
        onMain {
            app.launchArguments = [
                "-e2eQRPayload", path,
                PerformanceFixtureConstants.unsignedStorageArgument, storage.path,
            ]
            app.launchEnvironment = ["E2E_QR_PAYLOAD": path]
            app.launch()
        }
        try continuePastLocalNetworkExplanation(app)
        post(control, "/control/approve-pairing", [:])
        let opened = onMain { () -> Bool in
            let inbox = app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "在线", "Online")
            ).firstMatch
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline, !inbox.exists {
                let replace = app.buttons["替换它"].exists ? app.buttons["替换它"] : app.buttons["Replace it"]
                if replace.exists, replace.isHittable { replace.tap() }
                RunLoop.current.run(until: Date().addingTimeInterval(0.2))
            }
            guard inbox.waitForExistence(timeout: 2) else { return false }
            let showAll = app.buttons.matching(
                NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "显示全部", "Show all")
            ).firstMatch
            if showAll.waitForExistence(timeout: 3) { showAll.tap() }
            let row = app.staticTexts.matching(NSPredicate(format: "label == %@", sessionTitle)).firstMatch
            guard row.waitForExistence(timeout: 20) else { return false }
            row.tap()
            let message = app.staticTexts[lastMessage]
            if !message.waitForExistence(timeout: 5) { app.swipeUp() }
            return message.waitForExistence(timeout: 15)
        }
        guard opened else {
            let hierarchy = onMain { app.debugDescription }
            try? hierarchy.write(
                to: URL(fileURLWithPath: "/tmp/dsh-performance-append-hierarchy.txt"),
                atomically: true, encoding: .utf8)
            XCTFail("performance session row was not exposed")
            return
        }
        measure(metrics: [XCTClockMetric()]) {
            post(control, "/control/stream", ["sessionId": "sess-performance", "text": "追加 3001"])
            _ = onMain { app.staticTexts["追加 3001"].waitForExistence(timeout: 10) }
        }
        onMain { app.terminate() }
    }

    private func waitForFreshQR(_ path: String) throws {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if let data = try? Data(contentsOf: URL(fileURLWithPath: path)),
                let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let expiresAt = object["expiresAt"] as? Double,
                expiresAt > Date().timeIntervalSince1970 * 1000 + 60_000
            {
                return
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTFail("fresh performance QR was not written")
    }

    private func appendStorageDirectory() -> URL {
        let raw = ProcessInfo.processInfo.environment["PERFORMANCE_APPEND_STORAGE"] ?? ""
        let path = raw.isEmpty ? "/tmp/dsh-performance-append-storage" : raw
        return URL(fileURLWithPath: path, isDirectory: true)
    }

    private func qrPath() throws -> String {
        guard let raw = ProcessInfo.processInfo.environment["PERFORMANCE_QR_PATH"], !raw.isEmpty else {
            throw XCTSkip("PERFORMANCE_QR_PATH is required")
        }
        return raw
    }

    private func continuePastLocalNetworkExplanation(_ app: XCUIApplication) throws {
        let explanation = app.buttons["继续"].exists ? app.buttons["继续"] : app.buttons["Continue"]
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: explanation)
        if XCTWaiter.wait(for: [appeared], timeout: 5) == .completed {
            explanation.tap()
        }
    }

    @discardableResult
    private func post(_ control: URL, _ path: String, _ body: [String: Any]) -> Bool {
        let raw = control.absoluteString
        let base = raw.hasSuffix("/") ? String(raw.dropLast()) : raw
        guard let url = URL(string: base + path) else {
            XCTFail("control URL cannot take " + path)
            return false
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        let done = expectation(description: "POST " + path)
        let accepted = AtomicFlag()
        URLSession.shared.dataTask(with: request) { data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            if error == nil, (200..<300).contains(status) { accepted.set() }
            try? "\(path) status=\(status) error=\(error?.localizedDescription ?? "") body=\(text)\n"
                .write(
                    to: URL(fileURLWithPath: "/tmp/dsh-performance-control.log"),
                    atomically: false, encoding: .utf8)
            done.fulfill()
        }.resume()
        let result = XCTWaiter.wait(for: [done], timeout: 30)
        if result != .completed || !accepted.value {
            XCTFail("POST \(path) failed")
            return false
        }
        return true
    }

    private func launchOnMain(_ app: XCUIApplication) {
        onMain { app.launch() }
    }

    private func onMain<T>(_ body: () -> T) -> T {
        if Thread.isMainThread { return body() }
        return DispatchQueue.main.sync(execute: body)
    }

    private func preparedFixture() async throws -> URL {
        let raw = ProcessInfo.processInfo.environment["PERFORMANCE_FIXTURE"] ?? ""
        print("PERFORMANCE_FIXTURE=\(raw)")
        guard !raw.isEmpty else {
            throw XCTSkip("PERFORMANCE_FIXTURE is required")
        }
        let directory = URL(fileURLWithPath: raw, isDirectory: true)
        try await PerformanceFixtureWriter.prepare(at: directory)
        return directory
    }
}

private final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = false

    func set() {
        lock.lock()
        stored = true
        lock.unlock()
    }

    var value: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}
