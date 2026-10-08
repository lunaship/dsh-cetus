import ActivityKit
import SwiftUI
import WidgetKit

/// Content state shared with the app.
///
/// RFC 0002 §5.6 allows only these keys. A task title is deliberately absent:
/// the lock screen shows generic copy, and the concrete title is shown inside
/// the app only after tapping through.
struct CetusActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var state: String
        var step: Int
        var startedAt: Date
        var waitingCount: Int
        var sessionRef: String
    }

    var hostRef: String
}

struct CetusLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CetusActivityAttributes.self) { context in
            LiveActivityLockView(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) {
                    // Generic on purpose: never a command, file name, or title.
                    Text(verbatim: statusText(context.state.state))
                }
            } compactLeading: {
                Image(systemName: "link")
            } compactTrailing: {
                Text(verbatim: shortStatusText(context.state.state))
            } minimal: {
                Image(systemName: "link")
            }
        }
    }
}

private struct LiveActivityLockView: View {
    var context: ActivityViewContext<CetusActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: statusText(context.state.state))
            Text(verbatim: "步骤 \(max(1, context.state.step))")
            // `Text(timerInterval:)` counts up forever and makes a dead task look
            // live. Show the last update instead, and mark it stale once the
            // staleDate passes (plan §16.4.3).
            if context.isStale {
                Text(verbatim: "已离线 · 最后更新 \(lastUpdatedText)")
            } else {
                Text(verbatim: "最后更新 \(lastUpdatedText)")
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }

    private var lastUpdatedText: String {
        context.state.startedAt.formatted(date: .omitted, time: .shortened)
    }
}

/// Generic lock-screen copy. Unknown phases stay generic rather than echoing
/// whatever the host sent.
private func statusText(_ phase: String) -> String {
    switch phase {
    case "approval", "question": return "有一项任务需要处理"
    case "ended": return "任务已结束"
    default: return "任务进行中"
    }
}

/// The compact Dynamic Island slot only fits a couple of characters.
private func shortStatusText(_ phase: String) -> String {
    switch phase {
    case "approval", "question": return "需处理"
    case "ended": return "已结束"
    default: return "进行中"
    }
}

@main
struct LiveActivityBundle: WidgetBundle {
    var body: some Widget {
        CetusLiveActivity()
    }
}
