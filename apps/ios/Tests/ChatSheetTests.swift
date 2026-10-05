import DLCore
import DLModels
import Testing

@Suite struct ChatSheetTests {
    @Test func slashQueryKeepsShortListsAndFilters() {
        let entries = paletteEntries(title: { $0 }, detail: { $0 })
        #expect(filterPalette(entries, query: "/").count == 3)
        let triggers = filterPalette(entries, query: "/pause").flatMap(\.items).map(\.command.trigger)
        #expect(triggers == ["/pause"])
    }

    @Test func dangerCommandOpensPermissionInsteadOfSending() {
        #expect(isDangerPermissionCommand("  /Permission   danger-full-access "))
        let command = PaletteCommand(
            trigger: "/permission", title: "Permission", detail: "", action: .complete(dangerFullAccessCommand))
        #expect(resolvedPick(command) == .local(.permission))
        #expect(PermissionPreset.fullAccess.needsConfirmation)
        #expect(!PermissionPreset.readOnly.needsConfirmation)
    }

    @Test func modelSearchStaysHiddenUntilTheListIsLong() {
        let response = SessionModelsResponse(
            current: SessionModelCurrent(provider: "deepseek", model: "step-5"),
            groups: [
                SessionModelGroup(
                    provider: "deepseek", providerName: "DeepSeek",
                    models: [SessionModelOption(id: "step-5", name: "Step 5", reasoningEfforts: ["low", "high"])])
            ])
        let rows = modelRows(response)
        #expect(rows.count == 1)
        #expect(!showsModelSearch(rows.count))
        #expect(showsModelSearch(9))
        #expect(filterModelRows(rows, query: "mini").isEmpty)
        #expect(filterModelRows(rows, query: "step").count == 1)
    }

    @Test func usageAndSubagentsAndSchedules() {
        let figures = usageFigures(
            usage: TokenUsage(uncachedInputTokens: 100, cacheReadTokens: 300, outputTokens: 50),
            stats: SessionStats(turns: 2, steps: 4, llmMs: 10, toolMs: 5, ttftMs: 20, ttftSteps: 2),
            pressure: ContextPressure(projectedTokens: 46, contextWindow: 100),
            breakdown: ContextBreakdown(messageTokens: 25))
        #expect(figures?.totalTokens == 450)
        #expect(figures?.cacheHitRate == 0.75)
        #expect(contextUsedPercent(used: 46, window: 100) == 46)
        #expect(usageFigures(usage: nil, stats: nil, pressure: nil, breakdown: nil) == nil)

        let sessions = [
            SessionSummary(sessionId: "root", title: "root"),
            SessionSummary(
                sessionId: "child", title: "查订阅", updatedAt: 2, running: true, origin: "subagent",
                parentSessionId: "root"),
            SessionSummary(
                sessionId: "grand", title: "补测试", updatedAt: 1, running: false, origin: "subagent",
                parentSessionId: "child"),
        ]
        let tree = flattenSubagents(sessions: sessions, rootID: "root")
        #expect(tree.map(\.id) == ["child", "grand"])
        #expect(tree[1].depth == 1)

        let tasks = [
            ScheduleTask(id: "a", sessionId: "s1", kind: "daily", time: "09:00"),
            ScheduleTask(id: "b", sessionId: "s2", kind: "once"),
        ]
        #expect(visibleSchedules(tasks, scope: .session, sessionID: "s1").map(\.id) == ["a"])
        #expect(scheduleRule(tasks[0]) == .daily(time: "09:00", zone: ""))
    }
}
