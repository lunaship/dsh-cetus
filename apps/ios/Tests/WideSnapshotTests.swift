import DLCore
import DLModels
import DLSecurity
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

@MainActor final class WideSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testPortrait() {
        shot("wide_portrait", size: CGSize(width: 768, height: 1024), regular: true, changes: false)
    }

    func testLandscape() {
        shot("wide_landscape", size: CGSize(width: 1024, height: 768), regular: true, changes: true)
    }

    func testSplitTwoThirds() {
        shot("wide_split_two_thirds", size: CGSize(width: 683, height: 768), regular: true, changes: false)
    }

    func testSplitHalf() {
        shot("wide_split_half", size: CGSize(width: 512, height: 768), regular: true, changes: false)
    }

    func testSplitThird() {
        shot("wide_split_third", size: CGSize(width: 341, height: 768), regular: false, changes: false)
    }

    /// 进程里第一次把宽屏窗口挂到场景上时，系统会对整个场景做一次性的几何/特征更新（日志里同一时刻
    /// 涌出几百条前面测试留下的 hosting controller 的 appearance 回调，这一张要多花 20 多秒）。
    /// 这次更新和第一张截图的绘制是交错的，所以总是第一个渲染的变体落到另一种状态。
    /// 先完整渲染一张丢掉，等这次一次性更新结束，之后每张截图都从同一个稳定状态开始。
    private static var sceneWarmedUp = false
    private static var warmUpImage: UIImage?

    private func shot(_ scene: String, size: CGSize, regular: Bool, changes: Bool) {
        if !Self.sceneWarmedUp {
            Self.sceneWarmedUp = true
            let warm = wideImage(
                makePage(size: size, regular: regular, changes: changes, reduceTransparency: false),
                size: size, regular: regular, label: "\(scene)/warm-up")
            wideLog("WIDEDIAG \(scene)/warm-up button=\(wideButtonColor(warm)) \(wideFormat(warm))")
            Self.warmUpImage = warm
        }
        render(scene, size: size, regular: regular, changes: changes, named: "default")
        render(
            scene, size: size, regular: regular, changes: changes, named: "reduce-transparency",
            reduceTransparency: true)
        render(
            scene, size: size, regular: regular, changes: changes, named: "increase-contrast",
            increaseContrast: true)
    }

    private func render(
        _ scene: String, size: CGSize, regular: Bool, changes: Bool, named: String,
        reduceTransparency: Bool = false, increaseContrast: Bool = false
    ) {
        let page = makePage(
            size: size, regular: regular, changes: changes, reduceTransparency: reduceTransparency)
        let image = wideImage(
            page, size: size, regular: regular, increaseContrast: increaseContrast,
            label: "\(scene)/\(named)")
        wideLog("WIDEDIAG \(scene)/\(named) button=\(wideButtonColor(image)) \(wideFormat(image))")
        if let warm = Self.warmUpImage {
            Self.warmUpImage = nil
            // 用和断言同样的比较（预热图先过一遍 PNG，当作“基线”），看预热图是不是落在另一种状态。
            let diffing = Diffing<UIImage>.image(precision: 0.995, perceptualPrecision: 0.99)
            let reference = diffing.fromData(diffing.toData(warm))
            let verdict = diffing.diffV2(reference, image)?.0 ?? "identical-within-tolerance"
            wideLog("WIDEDIAG \(scene) warm-up-vs-\(named): \(verdict) | \(widePixelDiff(warm, image))")
        }
        // 分栏玻璃层每次有大量像素差 1–2 个色阶，字节精度会低于 0.995。
        // 感知精度 0.99 放过这种色差；像素精度仍要求 0.995，缺一列内容会失败。
        assertSnapshot(
            of: image,
            as: .image(precision: 0.995, perceptualPrecision: 0.99),
            named: named,
            testName: "Snapshot_\(scene)_light_zh"
        )
    }

    private func makePage(
        size: CGSize, regular: Bool, changes: Bool, reduceTransparency: Bool
    ) -> some View {
        let model = inbox()
        if regular { model.selectedSessionID = "approve" }
        return InboxPage(
            model: model,
            staticSnapshot: true,
            wideSnapshotConversation: regular ? conversation() : nil,
            wideSnapshotChanges: changes
        )
        .environment(\.locale, Locale(identifier: "zh-Hans"))
        .environment(\.colorScheme, .light)
        .environment(\.dynamicTypeSize, DynamicTypeSize.large)
        .environment(\.horizontalSizeClass, regular ? .regular : .compact)
        .environment(\._accessibilityReduceTransparency, reduceTransparency)
        .tint(DLColor.accent)
        .transaction { $0.disablesAnimations = true }
    }

    private func wideImage<V: View>(
        _ view: V, size: CGSize, regular: Bool, increaseContrast: Bool = false, label: String
    ) -> UIImage {
        let host = UIHostingController(rootView: view)
        host.view.backgroundColor = .systemBackground
        host.overrideUserInterfaceStyle = .light
        host.traitOverrides.userInterfaceStyle = .light
        host.traitOverrides.preferredContentSizeCategory = .large
        if increaseContrast {
            host.traitOverrides.accessibilityContrast = .high
        }
        host.traitOverrides.userInterfaceIdiom = .pad
        host.traitOverrides.horizontalSizeClass = regular ? .regular : .compact
        host.safeAreaRegions = []
        host.view.frame = CGRect(origin: .zero, size: size)

        let scene =
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first {
                $0.activationState == .foregroundActive
            } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else {
            fatalError("wide snapshots need a window scene")
        }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = WideSnapshotWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.overrideUserInterfaceStyle = .light
        window.traitOverrides.userInterfaceStyle = .light
        window.traitOverrides.accessibilityContrast = increaseContrast ? .high : .unspecified
        window.traitOverrides.preferredContentSizeCategory = .large
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.frame = CGRect(origin: .zero, size: size)
        wideDiag("\(label) shown", scene: scene, window: window, host: host)

        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        // Split columns get their frames on a later turn. Measuring the message
        // stream before that stretches it to the whole window and covers the sidebar.
        for _ in 0..<4 {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
            host.view.layoutIfNeeded()
            CATransaction.flush()
        }
        if let stream = wideStream(in: host) ?? wideStream(in: host.view) {
            let bounds = stream.view.bounds.size
            let canvas = bounds.width > 1 && bounds.height > 1 ? bounds : size
            stream.layoutForStaticSnapshot(canvas: canvas)
        }
        CATransaction.flush()

        let format = UIGraphicsImageRendererFormat()
        format.scale = window.screen.scale > 0 ? window.screen.scale : 3
        format.opaque = true
        wideDiag("\(label) before-draw", scene: scene, window: window, host: host)
        let image = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
        wideDiag("\(label) after-draw", scene: scene, window: window, host: host)
        window.isHidden = true
        window.rootViewController = nil
        window.windowScene = nil
        previousKey?.makeKey()
        return image
    }

    private func wideStream(in controller: UIViewController) -> MessageStreamController? {
        if let stream = controller as? MessageStreamController { return stream }
        for child in controller.children {
            if let stream = wideStream(in: child) { return stream }
        }
        return nil
    }

    private func wideStream(in view: UIView) -> MessageStreamController? {
        if let collection = view as? UICollectionView {
            var responder: UIResponder? = collection
            while let current = responder {
                if let stream = current as? MessageStreamController { return stream }
                responder = current.next
            }
        }
        for subview in view.subviews {
            if let stream = wideStream(in: subview) { return stream }
        }
        return nil
    }
}

/// 诊断：截图时的场景/窗口状态，和品牌色按钮在图里的实际颜色。
@MainActor private func wideDiag(_ label: String, scene: UIWindowScene, window: UIWindow, host: UIViewController) {
    let traits = window.traitCollection
    let current = UITraitCollection.current
    var red: CGFloat = 0
    var green: CGFloat = 0
    var blue: CGFloat = 0
    var alpha: CGFloat = 0
    DLUIKitColor.brandFill.resolvedColor(with: traits).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
    let brand = String(format: "%.3f,%.3f,%.3f", Double(red), Double(green), Double(blue))
    let visible = scene.windows.filter { !$0.isHidden }.count
    wideLog(
        "WIDEDIAG \(label) t=\(String(format: "%.3f", Date().timeIntervalSince1970))"
            + " scene=\(scene.activationState.rawValue) key=\(window.isKeyWindow)"
            + " style=\(traits.userInterfaceStyle.rawValue) active=\(traits.activeAppearance.rawValue)"
            + " gamut=\(traits.displayGamut.rawValue) contrast=\(traits.accessibilityContrast.rawValue)"
            + " curStyle=\(current.userInterfaceStyle.rawValue) curActive=\(current.activeAppearance.rawValue)"
            + " curGamut=\(current.displayGamut.rawValue) tint=\(host.view.tintAdjustmentMode.rawValue)"
            + " windows=\(scene.windows.count) visible=\(visible) brand=\(brand)"
            + " orient=\(scene.effectiveGeometry.interfaceOrientation.rawValue)")
}

private func wideFormat(_ image: UIImage) -> String {
    guard let cgImage = image.cgImage else { return "format=none" }
    let space = cgImage.colorSpace?.name.map { $0 as String } ?? "nil"
    return
        "format=\(cgImage.bitsPerComponent)bpc/\(cgImage.bitsPerPixel)bpp \(space) \(cgImage.width)x\(cgImage.height)"
}

/// 诊断：两张图转成 sRGB 8 位逐像素比，报告不同像素数、范围和最常见的一对颜色。
private func widePixelDiff(_ first: UIImage, _ second: UIImage) -> String {
    guard let a = first.cgImage, let b = second.cgImage, a.width == b.width, a.height == b.height,
        let space = CGColorSpace(name: CGColorSpace.sRGB)
    else { return "pixel-diff-unavailable" }
    let width = a.width
    let height = a.height
    func bitmap(_ image: CGImage) -> CGContext? {
        guard
            let context = CGContext(
                data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context
    }
    guard let left = bitmap(a), let right = bitmap(b), let leftData = left.data, let rightData = right.data
    else { return "pixel-diff-no-context" }
    let leftBytes = leftData.assumingMemoryBound(to: UInt8.self)
    let rightBytes = rightData.assumingMemoryBound(to: UInt8.self)
    let rowBytes = left.bytesPerRow
    var differing = 0
    var pairs: [String: Int] = [:]
    var minX = Int.max
    var minY = Int.max
    var maxX = -1
    var maxY = -1
    var y = 0
    while y < height {
        var x = 0
        while x < width {
            let index = y * rowBytes + x * 4
            let dr = abs(Int(leftBytes[index]) - Int(rightBytes[index]))
            let dg = abs(Int(leftBytes[index + 1]) - Int(rightBytes[index + 1]))
            let db = abs(Int(leftBytes[index + 2]) - Int(rightBytes[index + 2]))
            if max(dr, max(dg, db)) > 2 {
                differing += 1
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                let key =
                    String(format: "#%02X%02X%02X", leftBytes[index], leftBytes[index + 1], leftBytes[index + 2])
                    + "->"
                    + String(format: "#%02X%02X%02X", rightBytes[index], rightBytes[index + 1], rightBytes[index + 2])
                pairs[key, default: 0] += 1
            }
            x += 2
        }
        y += 2
    }
    let top = pairs.sorted { $0.value > $1.value }.prefix(4).map { "\($0.key)x\($0.value)" }
    return "differing(sampled 1/4)=\(differing) box=(\(minX),\(minY))-(\(maxX),\(maxY)) \(top.joined(separator: " "))"
}

private func wideLog(_ message: String) {
    NSLog("%@", message as NSString)
}

/// 诊断：右下四分之一里偏蓝的饱和像素（品牌色按钮），按 sRGB 8 位统计最多的三种颜色和范围。
private func wideButtonColor(_ image: UIImage) -> String {
    guard let cgImage = image.cgImage else { return "no-cgimage" }
    let width = cgImage.width / 2
    let height = cgImage.height / 4
    let crop = CGRect(x: cgImage.width - width, y: cgImage.height - height, width: width, height: height)
    guard let cropped = cgImage.cropping(to: crop),
        let space = CGColorSpace(name: CGColorSpace.sRGB)
    else { return "no-crop" }
    guard
        let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { return "no-context" }
    context.draw(cropped, in: CGRect(x: 0, y: 0, width: width, height: height))
    guard let data = context.data else { return "no-data" }
    let bytes = data.assumingMemoryBound(to: UInt8.self)
    let rowBytes = context.bytesPerRow
    var counts: [Int: Int] = [:]
    var minX = Int.max
    var minY = Int.max
    var maxX = -1
    var maxY = -1
    var y = 0
    while y < height {
        var x = 0
        while x < width {
            let index = y * rowBytes + x * 4
            let red = Int(bytes[index])
            let green = Int(bytes[index + 1])
            let blue = Int(bytes[index + 2])
            if blue - red > 60 {
                counts[(red << 16) | (green << 8) | blue, default: 0] += 1
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
            x += 2
        }
        y += 2
    }
    let top = counts.sorted { $0.value > $1.value }.prefix(3).map {
        String(format: "#%06lX", $0.key) + "x\($0.value)"
    }
    return "\(top.joined(separator: " ")) box=(\(minX),\(minY))-(\(maxX),\(maxY)) of \(width)x\(height)"
}

private final class WideSnapshotWindow: UIWindow {
    override var safeAreaInsets: UIEdgeInsets { .zero }
}

@MainActor private func inbox() -> InboxModel {
    let suite = "wide-snap-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let now = 1_780_000_000
    let model = InboxModel(
        hostID: "host-a", service: WideInboxService(), cache: InboxMemoryCache(),
        preferences: InboxPreferences(defaults: defaults), autostart: false,
        now: Date(timeIntervalSince1970: TimeInterval(now)), calendar: calendar)
    model.computerName = "工作室"
    model.link = .online(.local)
    model.workspaces = [WorkspaceInfo(path: "/work/app", sessionIds: ["approve", "run"])]
    model.sessions = [
        SessionSummary(
            sessionId: "approve", title: "等你审批发布", updatedAt: now - 120, running: true, cwd: "/work/app",
            awaitingInput: true),
        SessionSummary(
            sessionId: "run", title: "跑测试", updatedAt: now - 1_200, running: true, cwd: "/work/app"),
    ]
    return model
}

@MainActor private func conversation() -> ConversationModel {
    let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(
        "deeplinks-wide-snapshots", isDirectory: true)
    return ConversationModel(
        hostID: "host-a",
        sessionID: "approve",
        seed: ConversationSeed(title: "等你审批发布", workspace: "/work/app", running: true, step: 2),
        service: WideConversationService(),
        box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
        prepared: PreparedTranscript(
            messages: [
                HistoryMessage(id: "user", role: "user", kind: .user, text: "把发布说明写短一点"),
                HistoryMessage(
                    id: "assistant", role: "assistant", kind: .role("assistant"), text: "我先看一下现有说明。"),
            ],
            running: true),
        autostart: false)
}

private struct WideInboxService: InboxServing {}

private struct WideConversationService: ConversationServing {}
