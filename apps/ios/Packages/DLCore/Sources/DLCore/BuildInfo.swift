import Foundation

/// `BuildInfo.from` 只依赖一次 Info.plist 查表，解耦成协议便于单测注入假值。
public protocol BundleValueLookup {
    func object(forInfoDictionaryKey key: String) -> Any?
}

extension Bundle: BundleValueLookup {}

/// 内部构建元数据（方案 §4 C00）。
///
/// 解决的真实问题：iOS 安装信息恒为 `1.0 (1)`，拿到一张截图无法定位到具体提交。
/// 值来自 `Info.plist` 的自定义键，而 `Info.plist` 引用 `BuildMetadata.xcconfig`
/// （由 `scripts/build-metadata.mjs` 生成）。
///
/// 只在「关于」页展示，**不进日常首页**。营销版本（`CFBundleShortVersionString`）
/// 与构建号（`CFBundleVersion`）保持原样，由维护者按发布阶段处理。
public struct BuildInfo: Sendable, Equatable {
    /// 短提交 SHA；工作树脏时带 `-dirty`。取不到时为 `unknown`。
    public let commit: String
    /// 构建日期（UTC，`yyyy-MM-dd`）。取不到时为 `unknown`。
    public let date: String
    /// `Debug` / `Release`。
    public let configuration: String
    /// 手机同步合同版本（`docs/MOBILE_SYNC_CONTRACT.md`）。
    public let contractVersion: String
    /// 营销版本，例如 `1.0`。
    public let marketingVersion: String
    /// 构建号，例如 `1`。
    public let buildNumber: String

    public init(
        commit: String, date: String, configuration: String, contractVersion: String,
        marketingVersion: String, buildNumber: String
    ) {
        self.commit = commit
        self.date = date
        self.configuration = configuration
        self.contractVersion = contractVersion
        self.marketingVersion = marketingVersion
        self.buildNumber = buildNumber
    }

    /// 从 bundle 读取。缺键（例如旧包或未跑生成脚本）回退到 `unknown`，不崩。
    public static func from(bundle: any BundleValueLookup = Bundle.main) -> BuildInfo {
        func string(_ key: String) -> String {
            guard let value = bundle.object(forInfoDictionaryKey: key) as? String,
                !value.isEmpty, !value.hasPrefix("$(")
            else { return "unknown" }
            return value
        }
        return BuildInfo(
            commit: string("DLBuildCommit"),
            date: string("DLBuildDate"),
            configuration: string("DLBuildConfiguration"),
            contractVersion: string("DLBuildContractVersion"),
            marketingVersion: string("CFBundleShortVersionString"),
            buildNumber: string("CFBundleVersion")
        )
    }

    /// 「关于」页展示的营销版本 + 构建号，例如 `1.0 (1)`。
    public var versionLine: String { "\(marketingVersion) (\(buildNumber))" }

    /// 诊断页展示的一行：提交 / 日期 / 配置 / 合同版本。
    public var diagnosticLine: String {
        "\(commit) · \(date) · \(configuration) · contract v\(contractVersion)"
    }
}
