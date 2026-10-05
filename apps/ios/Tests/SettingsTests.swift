import DLModels
import Testing

@testable import DeepLinks

@Suite struct SettingsTests {
    @Test func clipboardKeepsCodesAndNumbers() {
        let checks = [
            DiagnosticCheck(
                id: "clock", status: .warn, code: "CLOCK_SKEW",
                detail: ["skewSeconds": .number(3), "note": .text("ahead")]),
        ]
        #expect(diagnosticsClipboard(checks) == "clock warn CLOCK_SKEW note=ahead,skewSeconds=3.0")
    }
}
