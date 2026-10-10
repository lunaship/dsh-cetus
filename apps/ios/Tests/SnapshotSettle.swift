import QuartzCore
import UIKit

/// 把已挂到窗口上的视图画成截图，但先等渲染稳定。
///
/// 新建的 hosting controller 挂上窗口后立刻 `drawHierarchy`，拿到的是「第一帧」，有两类不确定：
/// - 系统玻璃（导航栏按钮、底部搜索栏）第一帧的底色/文字偶尔差一个色阶，CI 上
///   TrajectorySnapshotTests 4_7、ChatSheetSnapshotTests 5_13、NewTaskSnapshotTests 3_2
///   都因此挂过（main 上也有）。调用方用「同样内容先挂一次窗口拍一张丢掉，再新建一次正式拍」处理，
///   和 WideSnapshotTests 的预热同一思路。
/// - 让出主线程后，输入框/搜索框可能被系统设成第一响应者（出现清除按钮、光标闪烁），
///   于是 3_2 workspace 大字号时有时无清除按钮。静态截图不该有编辑态，每帧前都先 `endEditing`。
///
/// 这里先画一张丢掉，然后每隔一小段让出主线程再画，直到连续两张逐字节相同才返回；
/// 上限之内总会返回最后一张，不会挂住测试。
@MainActor
func settledSnapshot(of view: UIView, size: CGSize, scale: CGFloat = 3, maxFrames: Int = 6) -> UIImage {
    let format = UIGraphicsImageRendererFormat()
    format.scale = scale
    format.opaque = true
    let renderer = UIGraphicsImageRenderer(size: size, format: format)
    let rect = CGRect(origin: .zero, size: size)
    func frame() -> UIImage {
        view.window?.endEditing(true)
        view.layoutIfNeeded()
        CATransaction.flush()
        return renderer.image { _ in
            view.drawHierarchy(in: rect, afterScreenUpdates: true)
        }
    }
    _ = frame()
    var image = frame()
    var previous = pixelBytes(image)
    for _ in 0..<maxFrames {
        RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        let next = frame()
        let bytes = pixelBytes(next)
        image = next
        if let bytes, bytes == previous { break }
        previous = bytes
    }
    return image
}

private func pixelBytes(_ image: UIImage) -> Data? {
    guard let data = image.cgImage?.dataProvider?.data else { return nil }
    return data as Data
}
