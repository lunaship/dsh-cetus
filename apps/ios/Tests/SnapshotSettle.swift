import QuartzCore
import UIKit

/// 把已挂到窗口上的视图画成截图，但先等渲染稳定。
///
/// 新建的 hosting controller 挂上窗口后立刻 `drawHierarchy`，拿到的是「第一帧」。
/// 第一帧里玻璃控件（导航栏按钮、底部搜索栏、浮动侧栏）的阴影/边缘偶尔还是过渡状态：
/// 渲染管线第一次编译、或系统在这一刻做一次场景级更新时，这一帧会和平时差约 0.5% 像素
/// （CI 上 TrajectorySnapshotTests 的 4_7 dark_en default、NewTaskSnapshotTests 的
/// 4_x workspace、ChatSheetSnapshotTests 的 5_13 goal large 都出现过，main 上也有）。
/// 这里先画一张丢掉，让渲染管线和玻璃层建好，然后每隔一小段让出主线程再画，
/// 直到连续两张逐字节相同才返回。上限之内总会返回最后一张，不会挂住测试。
@MainActor
func settledSnapshot(of view: UIView, size: CGSize, scale: CGFloat = 3, maxFrames: Int = 8) -> UIImage {
    let format = UIGraphicsImageRendererFormat()
    format.scale = scale
    format.opaque = true
    let renderer = UIGraphicsImageRenderer(size: size, format: format)
    let rect = CGRect(origin: .zero, size: size)
    func frame() -> UIImage {
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
