import DLUI
import SwiftUI

/// 1.2. Content scrolls independently of the actions at accessibility sizes.
struct PairingWelcomePage: View {
    @Environment(\.locale) private var locale
    var busy: PairingText?
    var scan: () -> Void = {}
    var photo: () -> Void = {}
    var demo: () -> Void = {}
    /// Snapshot path only: the filled system style instead of `.glassProminent`.
    /// A `.glassProminent` button makes the whole hosted page snapshot transparent.
    var staticSnapshot = false

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "terminal")
                    .font(.largeTitle)
                    .imageScale(.large)
                    .foregroundStyle(DLColor.accent)
                    .accessibilityHidden(true)
                Text(copy.text(.welcomeTitle)).font(.largeTitle.bold())
                Text(copy.text(.welcomeBody)).foregroundStyle(DLColor.secondaryLabel)
                step("1.circle", title: .stepOne, detail: .stepOneBody, copy: copy)
                step("2.circle", title: .stepTwo, detail: .stepTwoBody, copy: copy)
                step("3.circle", title: .stepThree, detail: .stepThreeBody, copy: copy)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(24)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if let busy {
                    ProgressView(copy.text(busy))
                }
                scanButton(copy.text(.scan))
                Button(copy.text(.photo), action: photo).frame(minHeight: 44)
                Button(copy.text(.demo), action: demo).frame(minHeight: 44)
                    .foregroundStyle(DLColor.secondaryLabel)
                Text(copy.text(.disclaimer)).font(.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .multilineTextAlignment(.center)
            }
            .disabled(busy != nil)
            .padding(16)
            .background(DLColor.background)
        }
        .background(DLColor.background)
    }

    private func step(_ image: String, title: PairingText, detail: PairingText, copy: PairingCopy) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: image).font(.title3).foregroundStyle(DLColor.accent)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(copy.text(title)).font(.headline)
                Text(copy.text(detail)).foregroundStyle(DLColor.secondaryLabel)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func scanButton(_ title: String) -> some View {
        let button = Button(action: scan) {
            Label(title, systemImage: "qrcode.viewfinder")
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        // Glass shaders sample the background and do not settle to one image. Snapshots use the
        // system filled style instead; production and the pairing matrix stay on glass.
        if staticSnapshot {
            button.buttonStyle(.borderedProminent).tint(DLColor.brandFill)
        } else {
            button.buttonStyle(.glassProminent).tint(DLColor.brandFill)
        }
    }
}

/// The first-use explanation is a route in the pairing flow, not a fake permission prompt.
struct LocalNetworkExplanationPage: View {
    @Environment(\.locale) private var locale
    var proceed: () -> Void = {}
    var back: () -> Void = {}
    /// Snapshot path only: the filled system style instead of `.glassProminent` (see 1.2).
    var staticSnapshot = false

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "network").font(.largeTitle).foregroundStyle(DLColor.accent)
                    .accessibilityHidden(true)
                Text(copy.text(.lanBody)).font(.body)
            }.padding(24)
        }
        .navigationTitle(copy.text(.lanTitle))
        .navigationBarBackButtonHidden()
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                continueButton(copy.text(.continueAction))
                Button(copy.text(.back), action: back).frame(minHeight: 44)
            }.padding(16).background(DLColor.background)
        }
        .background(DLColor.background)
    }

    @ViewBuilder private func continueButton(_ title: String) -> some View {
        let button = Button(title, action: proceed).frame(maxWidth: .infinity, minHeight: 44)
        if staticSnapshot {
            button.buttonStyle(.borderedProminent).tint(DLColor.brandFill)
        } else {
            button.buttonStyle(.glassProminent).tint(DLColor.brandFill)
        }
    }
}

/// 1.5. No implicit navigation back: every exit must cancel and delete the pending local record.
struct PairingPendingPage: View {
    @Environment(\.locale) private var locale
    let computerName: String
    let deviceName: String
    var viaTailscale = false
    var cancelling = false
    var cleanupFailed = false
    var cancel: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                ProgressView().accessibilityLabel(copy.text(.waitTitle))
                Text(copy.text(.waitTitle)).font(.title3.bold()).accessibilityAddTraits(.isHeader)
                Text(copy.format(.waitBody, computerName))
                Text(copy.text(.waitHint)).foregroundStyle(DLColor.secondaryLabel)
                LabeledContent(copy.text(.deviceName), value: deviceName)
                LabeledContent(copy.text(.route), value: copy.text(viaTailscale ? .tailscale : .lan))
                if cleanupFailed {
                    DLBanner(copy.text(.cancelStorageFailure))
                }
            }.padding(24)
        }
        .navigationBarBackButtonHidden()
        .safeAreaInset(edge: .bottom) {
            Button(copy.text(.cancelPairing), action: cancel)
                .frame(maxWidth: .infinity, minHeight: 44)
                .buttonStyle(.glass)
                .disabled(cancelling)
                .padding(16).background(DLColor.background)
        }
        .background(DLColor.background)
    }
}

/// 1.6. Always three actionable suggestions, preserving Android's HTTP hint priority.
struct PairingFailurePage: View {
    @Environment(\.locale) private var locale
    let failure: PairingFailure
    var back: () -> Void = {}
    var settings: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                    .foregroundStyle(DLColor.err).accessibilityHidden(true)
                Text(copy.text(failure.isNetwork ? .networkTitle : .failTitle))
                    .font(.title3.bold()).accessibilityAddTraits(.isHeader)
                Text(copy.reason(failure)).foregroundStyle(DLColor.secondaryLabel)
                Text(copy.text(.suggestions)).font(.headline)
                ForEach(Array(failure.suggestions.enumerated()), id: \.offset) { _, tip in
                    Label(copy.text(tip), systemImage: "checkmark.circle")
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(DLColor.label)
                }
                if failure.offersSettings {
                    Button(copy.text(.settings), action: settings).frame(minHeight: 44)
                }
            }.padding(24)
        }
        .navigationBarBackButtonHidden()
        .safeAreaInset(edge: .bottom) {
            Button(copy.text(.back), action: back)
                .frame(maxWidth: .infinity, minHeight: 44)
                .buttonStyle(.glass).padding(16).background(DLColor.background)
        }
        .background(DLColor.background)
    }
}

/// 1.3 overlay. Snapshots use a fixture camera surface, never a live capture session.
/// 设计稿 1.3：深浅色相同（相机永远是深色）；关闭在左上、手电筒在右上，
/// 中间是标题 + 取景框 + 提示，底部是「从相册选择」。
struct PairingScannerPage<Camera: View>: View {
    @Environment(\.locale) private var locale
    let camera: Camera
    var hint: PairingText?
    var close: () -> Void = {}
    /// nil = 不画「从相册选择」。
    var photo: (() -> Void)? = nil
    /// 设备没有手电筒（模拟器、部分 iPad）时不画按钮。截图显式传 true。
    var torchAvailable = PairingTorch.isAvailable
    @State private var torchOn = false

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ZStack {
            camera.ignoresSafeArea().accessibilityHidden(true)
            VStack(spacing: 0) {
                HStack {
                    Button(action: close) {
                        Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.glass).accessibilityLabel(copy.text(.close))
                    Spacer()
                    if torchAvailable {
                        Button {
                            torchOn.toggle()
                            PairingTorch.set(torchOn)
                        } label: {
                            Image(systemName: torchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.glass)
                        .accessibilityLabel(copy.text(.torch))
                        .accessibilityAddTraits(torchOn ? .isSelected : [])
                    }
                }
                Spacer()
                VStack(spacing: 20) {
                    Text(copy.text(.scanTitle)).font(.headline)
                    PairingViewfinder()
                        .stroke(.white, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                        .frame(width: 232, height: 232)
                        .accessibilityHidden(true)
                    Text(copy.text(hint ?? .scanBody))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                Spacer()
                if let photo {
                    Button(action: photo) {
                        Label(copy.text(.scanPhoto), systemImage: "photo.on.rectangle")
                            .font(.headline)
                            .padding(.horizontal, 8)
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.glass)
                }
            }
            .padding(16)
            // 设计稿 1.3：相机上的控件一律白色，不用强调色。
            .tint(.white)
            .foregroundStyle(.white)
        }
        // 相机界面永远是深色：页面内容按深色取色，呈现层也请求深色。
        .environment(\.colorScheme, .dark)
        .preferredColorScheme(.dark)
        .onDisappear {
            if torchOn { PairingTorch.set(false) }
        }
    }
}

/// 设计稿 1.3：取景框只画四个圆角折角。
struct PairingViewfinder: Shape {
    func path(in rect: CGRect) -> Path {
        let arm = min(rect.width, rect.height) * 0.22
        let radius = min(rect.width, rect.height) * 0.1
        var path = Path()
        // 左上
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + arm))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + radius, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + arm, y: rect.minY))
        // 右上
        path.move(to: CGPoint(x: rect.maxX - arm, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY + radius), control: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + arm))
        // 右下
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - arm))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - radius))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - radius, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - arm, y: rect.maxY))
        // 左下
        path.move(to: CGPoint(x: rect.minX + arm, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + radius, y: rect.maxY))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX, y: rect.maxY - radius), control: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - arm))
        return path
    }
}
