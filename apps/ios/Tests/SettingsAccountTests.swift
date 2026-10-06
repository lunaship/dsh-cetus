import DLModels
import Testing

@testable import DeepLinks

@Suite struct SettingsAccountTests {
    @Test func revokePrefersDeviceID() {
        let body = selfRevokeBody(deviceID: " phone-1 ", pairedPhoneName: "iPhone")
        #expect(body == .device("phone-1"))
        #expect(body?.encoded == RevokeRequestBody(deviceId: "phone-1", name: nil))
    }

    @Test func revokeFallsBackToPairedPhoneName() {
        let body = selfRevokeBody(deviceID: " ", pairedPhoneName: " iPhone ")
        #expect(body == .name("iPhone"))
        #expect(selfRevokeBody(deviceID: nil, pairedPhoneName: " ") == nil)
    }

    @Test func selfDeviceIDRequiresUniqueName() {
        let rows = [
            DeviceRow(deviceId: "a", name: "iPhone", status: .active),
            DeviceRow(deviceId: "b", name: " iPhone ", status: .active),
        ]
        #expect(selfDeviceID(in: rows, pairedPhoneName: "iPhone") == nil)
        #expect(selfDeviceID(in: [rows[0]], pairedPhoneName: "iPhone") == "a")
    }

    @Test func credentialsStayUntilRevokeSucceeds() {
        #expect(shouldDeleteCredentials(after: .revoked))
        #expect(shouldDeleteCredentials(after: .alreadyUnauthorized))
        #expect(!shouldDeleteCredentials(after: .kept(.transport)))
        #expect(!shouldDeleteCredentials(after: .kept(.forbidden)))
        #expect(!shouldDeleteCredentials(after: .kept(.storage)))
    }

    @Test func renameStaysLocal() {
        let renamed = renameComputerLocally(alias: nil, originalName: "Mac", draft: " Studio ")
        #expect(renamed == ComputerRename(displayName: "Studio", storedAlias: "Studio"))
        let restored = renameComputerLocally(alias: "Studio", originalName: "Mac", draft: "Mac")
        #expect(restored?.storedAlias == nil)
        #expect(renameComputerLocally(alias: nil, originalName: "Mac", draft: " ") == nil)
        #expect(renameComputerLocally(alias: nil, originalName: "Mac", draft: String(repeating: "a", count: 65)) == nil)
    }

    @Test func diagnosticsKeepStableCodes() {
        let rows = diagnosticRows(
            [
                DiagnosticCheck(id: "host.rpc", status: .ok, code: "HOST_RPC_OK", detail: ["ms": .number(12)]),
                DiagnosticCheck(id: "", status: .fail, code: "HOST_RPC_FAILED"),
            ],
            locale: Locale(identifier: "en")
        )
        #expect(rows.count == 1)
        #expect(rows[0].code == "HOST_RPC_OK")
        #expect(rows[0].subtitle == "Responded in 12 ms")
    }

    @Test func crashExportRedactsSecretsAndDropsEmptyReports() {
        let raw = """
            crash /Users/me/Library/token.txt Bearer abcdefghijklmnopqrstuvwxyz123456
            token=secret-value "message":"private reply"
            """
        let shared = crashShareText(raw)
        #expect(shared?.contains("/Users/me") == false)
        #expect(shared?.contains("abcdefghijklmnopqrstuvwxyz123456") == false)
        #expect(shared?.contains("secret-value") == false)
        #expect(shared?.contains("private reply") == false)
        #expect(shared?.contains("<path>") == true)
        #expect(shared?.contains("<redacted>") == true)
        #expect(crashShareText("   ") == nil)
        #expect(crashShareText(nil) == nil)
    }
}
