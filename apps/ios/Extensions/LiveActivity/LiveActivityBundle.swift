import ActivityKit
import SwiftUI
import WidgetKit

/// Content state shared with the app.
///
/// RFC 0002 §5.6 allows only these keys in `content-state`; the title is not
/// one of them. The task title instead rides in the attributes the app sets
/// when it starts the activity locally (user decision 2026-10-10: the lock
/// screen and Dynamic Island show the task title). It is never pushed.
struct CetusActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var state: String
        var step: Int
        var startedAt: Date
        var waitingCount: Int
        var sessionRef: String
    }

    var hostRef: String
    var title: String?
}

struct CetusLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CetusActivityAttributes.self) { context in
            LiveActivityLockView(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) {
                    // Title (one line, truncated) + generic status. Never a command or file name.
                    VStack(alignment: .leading, spacing: 2) {
                        if let title = context.attributes.title {
                            Text(verbatim: title)
                                .font(.headline)
                                .lineLimit(1)
                                .truncationMode(.tail)
                        }
                        Text(verbatim: statusText(context.state.state))
                            .font(context.attributes.title == nil ? .body : .subheadline)
                            .foregroundStyle(context.attributes.title == nil ? .primary : .secondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
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
            // Task title looked up by the app (one line, truncated), then the generic status.
            if let title = context.attributes.title {
                Text(verbatim: title)
                    .font(.headline)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
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
