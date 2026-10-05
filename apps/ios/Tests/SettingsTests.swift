import DLModels
import Testing

@testable import DeepLinks

@Suite struct SettingsTests {
    @Test func clipboardKeepsCodesAndNumbers() {
        let checks = [
            DiagnosticCheck(
                id: "clock",
                status: .warn,
                code: "CLOCK_SKEW",
                detail: [
                    "note": .text("ahead"),
                    "skewSeconds": .number(3),
                ]
            )
        ]
        #expect(diagnosticsClipboard(checks) == "clock warn CLOCK_SKEW note=ahead,skewSeconds=3.0")
    }
}
