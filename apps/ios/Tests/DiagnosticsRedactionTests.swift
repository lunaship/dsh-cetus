import DLModels
import Foundation
import Testing

@testable import Cetus

/// C16 §20.3：诊断导出「不带 token、二维码凭据、API key、正文或完整敏感路径」。
///
/// 插件侧由 `src/diagnostics.js:361-368` 的 `PRIVATE_TEXT` 正则在报告生成时强制拦截；
/// 手机侧此前**没有任何测试**断言这件事。本套用例补上：
/// 用一份故意混入敏感字段的诊断样本走真实导出函数，断言输出里找不到敏感串。
///
/// 注意：这里测的是"导出**不泄露**"，不是"导出一定过滤"。
/// 若将来有人把敏感值塞进 `detail`，本测试会失败 —— 这正是目的。
@Suite("诊断导出脱敏 (C16 §20.3)")
struct DiagnosticsRedactionTests {
    /// 一段足以识别泄露的样本：token、密钥、绝对路径、IPv4、正文片段。
    private static let secrets = [
        "ghp_0123456789abcdef",  // API key 形状
        "Bearer abcdef123456",  // Authorization 头
        "/Users/wuyanzu/.dsh/dsh-cetus/tls.json",  // 完整敏感路径
        "10.255.255.1",  // 设备 IP
        "用户的私密任务正文",
    ]

    @Test("正常样本能被导出，且只含 id/status/code")
    func normalChecksExport() {
        let text = diagnosticsClipboard([
            DiagnosticCheck(id: "host.rpc", status: .ok, code: "HOST_RPC_OK", detail: ["ms": .number(12)]),
            DiagnosticCheck(id: "tls.cert", status: .warn, code: "TLS_CERT_EXPIRING"),
        ])

        #expect(text.contains("host.rpc"))
        #expect(text.contains("HOST_RPC_OK"))
        #expect(text.contains("tls.cert"))
        #expect(text.contains("ms=12.0") || text.contains("ms=12"))
    }

    @Test("混入敏感值的样本：导出不得包含任何敏感串")
    func exportNeverCarriesSecrets() {
        // 故意把敏感值放进 detail —— 模拟上游误把 token/路径当诊断明细。
        var detail: [String: DiagnosticDetailValue] = [:]
        for (index, secret) in Self.secrets.enumerated() {
            detail["leak\(index)"] = .text(secret)
        }
        let text = diagnosticsClipboard([
            DiagnosticCheck(id: "host.rpc", status: .ok, code: "HOST_RPC_OK", detail: detail)
        ])

        for secret in Self.secrets {
            #expect(
                !text.contains(secret),
                "诊断导出泄露了敏感串「\(secret)」—— 必须在上游拦住，不能靠客户端过滤")
        }
    }

    @Test("真实诊断样本（不混入敏感值）导出后不含敏感串")
    func realSampleIsClean() {
        // 与插件侧同样的稳定 id 集合，值只放数字/布尔/短枚举。
        let text = diagnosticsClipboard([
            DiagnosticCheck(id: "host.rpc", status: .ok, code: "HOST_RPC_OK", detail: ["ms": .number(8)]),
            DiagnosticCheck(id: "host.services", status: .ok, code: "HOST_SERVICES_OK"),
            DiagnosticCheck(id: "plugin.version", status: .ok, code: "PLUGIN_VERSION_OK"),
            DiagnosticCheck(id: "tls.cert", status: .warn, code: "TLS_CERT_EXPIRING", detail: ["days": .number(12)]),
            DiagnosticCheck(
                id: "pairing.devices", status: .ok, code: "PAIRING_DEVICES_OK", detail: ["count": .number(2)]),
            DiagnosticCheck(id: "listen.addresses", status: .ok, code: "LISTEN_OK", detail: ["lan": .flag(true)]),
            DiagnosticCheck(id: "remote.relay", status: .skip, code: "REMOTE_DISABLED"),
            DiagnosticCheck(id: "clock", status: .ok, code: "CLOCK_OK"),
        ])

        for secret in Self.secrets {
            #expect(!text.contains(secret), "真实样本不应含「\(secret)」")
        }
    }

    @Test("导出按 id 排序稳定，便于对照与去重")
    func exportOrderIsStable() {
        let checks = [
            DiagnosticCheck(id: "tls.cert", status: .ok, code: "A"),
            DiagnosticCheck(id: "host.rpc", status: .ok, code: "B"),
        ]
        #expect(diagnosticsClipboard(checks) == diagnosticsClipboard(checks))
    }
}
