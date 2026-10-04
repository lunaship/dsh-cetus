import SwiftUI
import WebKit

enum LockedContentKind {
    case math
    case mermaid
}

/// 公式和 Mermaid。非持久存储，自定义 scheme 只放行随包资源，导航一律取消。
struct LockedContentView: UIViewRepresentable {
    var source: String
    var kind: LockedContentKind

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.setURLSchemeHandler(context.coordinator.handler, forURLScheme: "deeplinks-asset")
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.bounces = false
        webView.allowsLinkPreview = false
        if let url = Bundle.main.url(forResource: "shell", withExtension: "html", subdirectory: "Math")
            ?? Bundle.main.url(forResource: "shell", withExtension: "html"),
            let html = try? String(contentsOf: url, encoding: .utf8)
        {
            webView.loadHTMLString(html, baseURL: URL(string: "deeplinks-asset://bundle/Math/"))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.source = source
        context.coordinator.kind = kind
        context.coordinator.render(webView)
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let handler = AssetSchemeHandler()
        var source = ""
        var kind = LockedContentKind.math
        private var ready = false

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            ready = true
            render(webView)
        }

        func webView(
            _ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            let scheme = navigationAction.request.url?.scheme
            if navigationAction.navigationType == .other, scheme == "deeplinks-asset" || scheme == "about" {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }

        func render(_ webView: WKWebView) {
            guard ready, let literal = jsonString(source) else { return }
            let script =
                kind == .math
                ? "window.renderMath(\(literal), true)"
                : "window.renderMermaid(\(literal))"
            webView.evaluateJavaScript(script, completionHandler: nil)
        }

        private func jsonString(_ source: String) -> String? {
            guard let data = try? JSONEncoder().encode(source) else { return nil }
            return String(data: data, encoding: .utf8)
        }
    }
}

final class AssetSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url, url.scheme == "deeplinks-asset", url.host == "bundle" else {
            task.didFailWithError(URLError(.badURL))
            return
        }
        let relative = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard relative.hasPrefix("Math/"), !relative.contains(".."), let file = file(relative) else {
            task.didFailWithError(URLError(.noPermissionsToReadFile))
            return
        }
        guard let data = try? Data(contentsOf: file) else {
            task.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let response = URLResponse(
            url: url, mimeType: mime(file), expectedContentLength: data.count, textEncodingName: nil)
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}

    private func file(_ relative: String) -> URL? {
        let root =
            Bundle.main.url(forResource: "Math", withExtension: nil)
            ?? Bundle.main.resourceURL?.appendingPathComponent("Math", isDirectory: true)
        guard let root else { return nil }
        let child = String(relative.dropFirst("Math/".count))
        let file = root.appendingPathComponent(child).standardizedFileURL
        let base = root.standardizedFileURL.path
        let path = file.path
        guard path == base || path.hasPrefix(base + "/") else { return nil }
        return file
    }

    private func mime(_ url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "js": "text/javascript"
        case "css": "text/css"
        case "html": "text/html"
        case "woff2": "font/woff2"
        case "woff": "font/woff"
        case "ttf": "font/ttf"
        default: "application/octet-stream"
        }
    }
}
