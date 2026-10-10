import XCTest

/// UI coverage for the live pairing path. Snapshot unit tests stay offline because they set
/// XCTestConfigurationFilePath and do not pass -e2eQRPayload.
///
/// Session id is sess-demo-login (SESSION_LOGIN in scripts/export-contract-fixtures.mjs).
/// Inbox rows combine title, meta, and preview into one element and do not expose sessionId.
/// This test cannot add a control-host route, so it uses that fixture id.
final class E2ELaunchTests: XCTestCase {
    private let sessionID = "sess-demo-login"
    private let sessionTitle = "修复登录超时"
    private let streamMarker = "e2e-stream-marker"
    private let phoneText = "e2e-from-phone"
    private let waitLimit: TimeInterval = 30

    func testPairAndInbox() throws {
        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        XCTAssertTrue(try waitForInbox(ctx.app).exists)
    }

    func testStreamAndSend() throws {
        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        try openFirstSession(ctx.app)
        post(ctx.control, "/control/stream", ["sessionId": sessionID, "text": streamMarker])
        XCTAssertTrue(try waitUntilExists(ctx.app.staticTexts[streamMarker]).exists)
        try typeAndSend(ctx.app, phoneText)
        XCTAssertTrue(try waitUntilExists(labeled(ctx.app.staticTexts, phoneText)).exists)
        post(ctx.control, "/control/prompt-seen", ["sessionId": sessionID, "text": phoneText])
        XCTAssertTrue(labeled(ctx.app.staticTexts, phoneText).exists)
    }

    /// 4.1「停止」按钮：运行中主按钮变为停止态，点按后 turn/end（interrupted）
    /// 让按钮回到发送态，会话显示已停止。
    func testStopTurn() throws {
        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        try openFirstSession(ctx.app)
        // text-delta 把 running 置 true，主按钮变为停止态。
        post(ctx.control, "/control/stream", ["sessionId": sessionID, "text": "e2e-stop-marker"])
        let stop = try waitUntilHittable(button(ctx.app, "停止", "Stop"))
        stop.tap()
        // cancel 走真实插件 → 假 gateway 返回 ok；再下发 turn/end 结束这一轮。
        post(ctx.control, "/control/turn-end", ["sessionId": sessionID, "kind": "interrupted"])
        let send = try waitUntilHittable(button(ctx.app, "发送", "Send"))
        XCTAssertTrue(send.exists)
        let stopGone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: button(ctx.app, "停止", "Stop"))
        XCTAssertEqual(XCTWaiter.wait(for: [stopGone], timeout: waitLimit), .completed)
    }

    func testApproval() throws {        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        try openFirstSession(ctx.app)
        post(ctx.control, "/control/approval", ["sessionId": sessionID])
        let allow = try waitUntilHittable(button(ctx.app, "允许一次", "Allow once"))
        allow.tap()
        let waiting = eitherText(ctx.app, "等你批准", "Waiting for approval")
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: waiting)
        wait(for: [gone], timeout: waitLimit)
    }

    func testQuestion() throws {
        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        try openFirstSession(ctx.app)
        // One question at a time. Status is "等你回答" / "Waiting for an answer".
        // The draft is submitted with "发送" / "Send". There is no per-option control.
        post(
            ctx.control, "/control/question",
            [
                "sessionId": sessionID,
                "questions": [
                    ["id": "q0", "question": "e2e-question-one"],
                    ["id": "q1", "question": "e2e-question-two"],
                ],
            ])
        _ = try waitUntilExists(ctx.app.staticTexts["e2e-question-one"])
        try typeAndSend(ctx.app, "e2e-answer-one")
        _ = try waitUntilExists(ctx.app.staticTexts["e2e-question-two"])
        try typeAndSend(ctx.app, "e2e-answer-two")
        XCTAssertTrue(try waitUntilExists(composerField(ctx.app)).exists)
    }

    func testReconnect() throws {
        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        try openFirstSession(ctx.app)
        post(ctx.control, "/control/stream", ["sessionId": sessionID, "text": streamMarker])
        _ = try waitUntilExists(ctx.app.staticTexts[streamMarker])
        post(ctx.control, "/control/restart", ["sessionId": sessionID])
        let lost = eitherText(
            ctx.app, "连接断开 · 正在重连…", "Connection lost · Reconnecting…")
        _ = try waitUntilExists(lost)
        let recovered = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: lost)
        XCTAssertEqual(XCTWaiter.wait(for: [recovered], timeout: waitLimit), .completed)
        XCTAssertEqual(ctx.app.staticTexts.matching(identifier: streamMarker).count, 1)
    }

    func testRevoke() throws {
        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        _ = try waitForInbox(ctx.app)
        post(ctx.control, "/control/revoke", [:])
        let welcome = try waitUntilExists(
            eitherText(ctx.app, "把电脑上的 DSH 放进口袋", "Put DSH in your pocket"))
        XCTAssertTrue(welcome.exists)
    }

    func testSearchInputAndKeyboard() throws {
        let ctx = try launchContext()
        try continuePastLocalNetworkExplanation(ctx.app)
        post(ctx.control, "/control/approve-pairing", [:])
        let inbox = try waitForInbox(ctx.app)
        let before = inbox.frame

        let search = try waitUntilHittable(searchField(ctx.app))
        search.tap()
        search.typeText("登录\n")
        let match = try waitUntilExists(eitherText(ctx.app, "标题匹配", "Title matches"))
        XCTAssertTrue(match.exists)
        XCTAssertTrue(ctx.app.staticTexts[sessionTitle].exists)
        XCTAssertFalse(ctx.app.staticTexts["整理周报"].exists)

        ctx.app.staticTexts[sessionTitle].firstMatch.tap()
        _ = try waitUntilExists(ctx.app.navigationBars[sessionTitle])
        let field = try waitUntilExists(composerField(ctx.app))
        let resting = field.frame
        field.tap()
        let keyboard = ctx.app.keyboards.firstMatch
        let shown = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: keyboard)
        XCTAssertEqual(XCTWaiter.wait(for: [shown], timeout: waitLimit), .completed)
        let lifted = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "frame.maxY < %f", resting.maxY - 20), object: field)
        XCTAssertEqual(XCTWaiter.wait(for: [lifted], timeout: waitLimit), .completed)
        field.typeText("e2e-keyboard")
        XCTAssertTrue(field.value as? String == "e2e-keyboard")
        try waitUntilHittable(button(ctx.app, "发送", "Send")).tap()
        XCTAssertTrue(try waitUntilExists(labeled(ctx.app.staticTexts, "e2e-keyboard")).exists)
        XCTAssertNotEqual(before, .zero)
    }

    private struct Context {
        let app: XCUIApplication
        let control: URL
    }

    private func launchContext() throws -> Context {
        let env = ProcessInfo.processInfo.environment
        guard let qrPath = env["E2E_QR_PATH"], !qrPath.isEmpty,
            let control = env["E2E_CONTROL_URL"], !control.isEmpty
        else {
            throw XCTSkip("E2E_QR_PATH and E2E_CONTROL_URL are required")
        }
        guard let controlURL = URL(string: control) else {
            XCTFail("E2E_CONTROL_URL is not a URL")
            throw XCTSkip("E2E_CONTROL_URL is not a URL")
        }
        let app = XCUIApplication()
        app.launchArguments = ["-e2eQRPayload", qrPath]
        app.launchEnvironment = ["E2E_QR_PAYLOAD": qrPath]
        app.launch()
        return Context(app: app, control: controlURL)
    }

    /// First LAN address shows "继续" / "Continue" before the same receive() path submits.
    /// Loopback and already-explained installs skip this page; either outcome is valid.
    private func continuePastLocalNetworkExplanation(_ app: XCUIApplication) throws {
        let explanation = button(app, "继续", "Continue")
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: explanation)
        if XCTWaiter.wait(for: [appeared], timeout: 5) == .completed {
            explanation.tap()
        }
    }

    private func post(_ control: URL, _ path: String, _ body: [String: Any]) {
        let raw = control.absoluteString
        let base = raw.hasSuffix("/") ? String(raw.dropLast()) : raw
        guard let url = URL(string: base + path) else {
            XCTFail("control URL cannot take " + path)
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        let done = expectation(description: "POST " + path)
        URLSession.shared.dataTask(with: request) { _, _, _ in done.fulfill() }.resume()
        wait(for: [done], timeout: waitLimit)
    }

    /// Inbox is the cetus title plus a session row. Rows do not publish sessionId.
    private func waitForInbox(_ app: XCUIApplication) throws -> XCUIElement {
        let row = app.staticTexts[sessionTitle]
        _ = try waitUntilExists(row)
        return row
    }

    private func openFirstSession(_ app: XCUIApplication) throws {
        try waitForInbox(app).tap()
        _ = try waitUntilExists(app.navigationBars[sessionTitle])
    }

    private func searchField(_ app: XCUIApplication) -> XCUIElement {
        let query = "label == %@ OR label == %@ OR placeholderValue == %@ OR placeholderValue == %@"
        return app.searchFields.matching(
            NSPredicate(format: query, "搜索", "Search", "搜索", "Search")
        ).firstMatch
    }

    /// Composer is a UITextView with no placeholder or accessibility identifier.
    private func composerField(_ app: XCUIApplication) -> XCUIElement {
        app.textViews.firstMatch
    }

    private func typeAndSend(_ app: XCUIApplication, _ value: String) throws {
        let field = try waitUntilExists(composerField(app))
        field.tap()
        field.typeText(value)
        try waitUntilHittable(button(app, "发送", "Send")).tap()
    }

    private func button(_ app: XCUIApplication, _ chinese: String, _ english: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", chinese, english)).firstMatch
    }

    private func eitherText(_ app: XCUIApplication, _ chinese: String, _ english: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label == %@ OR label == %@", chinese, english)).firstMatch
    }

    private func labeled(_ query: XCUIElementQuery, _ label: String) -> XCUIElement {
        query[label]
    }

    private func waitUntilExists(_ element: XCUIElement) throws -> XCUIElement {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: waitLimit), .completed)
        return element
    }

    private func waitUntilHittable(_ element: XCUIElement) throws -> XCUIElement {
        _ = try waitUntilExists(element)
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: waitLimit), .completed)
        return element
    }
}
