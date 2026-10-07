import DLCore
import Foundation
import Testing

/// 方案 §4 C00：构建元数据必须能从产物里读回来，且缺键时不崩。
@Suite struct BuildInfoTests {
    @Test func readsKeysFromBundle() {
        let bundle = StubBundle(values: [
            "DLBuildCommit": "abc12345",
            "DLBuildDate": "2026-10-08",
            "DLBuildConfiguration": "Debug",
            "DLBuildContractVersion": "1",
            "CFBundleShortVersionString": "1.0",
            "CFBundleVersion": "1",
        ])
        let info = BuildInfo.from(bundle: bundle)
        #expect(info.commit == "abc12345")
        #expect(info.date == "2026-10-08")
        #expect(info.configuration == "Debug")
        #expect(info.contractVersion == "1")
        #expect(info.versionLine == "1.0 (1)")
        #expect(info.diagnosticLine == "abc12345 · 2026-10-08 · Debug · contract v1")
    }

    /// 未跑生成脚本 / 旧包：值仍是未展开的 `$(DLBuildCommit)`，必须回退 unknown 而不是显示字面量。
    @Test func unexpandedPlaceholderFallsBackToUnknown() {
        let bundle = StubBundle(values: [
            "DLBuildCommit": "$(DLBuildCommit)",
            "DLBuildDate": "",
            "DLBuildConfiguration": "Release",
            "DLBuildContractVersion": "1",
            "CFBundleShortVersionString": "1.0",
            "CFBundleVersion": "1",
        ])
        let info = BuildInfo.from(bundle: bundle)
        #expect(info.commit == "unknown")
        #expect(info.date == "unknown")
        #expect(info.configuration == "Release")
    }

    @Test func missingKeysFallBackToUnknown() {
        let info = BuildInfo.from(bundle: StubBundle(values: [:]))
        #expect(info.commit == "unknown")
        #expect(info.date == "unknown")
        #expect(info.configuration == "unknown")
        #expect(info.contractVersion == "unknown")
        #expect(info.versionLine == "unknown (unknown)")
    }
}

private struct StubBundle: BundleValueLookup {
    let values: [String: String]

    func object(forInfoDictionaryKey key: String) -> Any? { values[key] }
}
