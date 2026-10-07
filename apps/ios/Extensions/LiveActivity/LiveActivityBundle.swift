import ActivityKit
import SwiftUI
import WidgetKit

struct CetusActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var phase: String
        var step: Int
        var startedAt: Date
        var waitingCount: Int
        var sessionRef: String
        var title: String
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
                    Text(verbatim: "Need approval. Handle it in the app.")
                }
            } compactLeading: {
                Image(systemName: "link")
            } compactTrailing: {
                Text(verbatim: statusText(context.state.phase))
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
            Text(verbatim: context.state.title)
            Text(verbatim: "\(statusText(context.state.phase)) · \(max(1, context.state.step))")
            Text(timerInterval: context.state.startedAt...Date.distantFuture, countsDown: false)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }
}

private func statusText(_ phase: String) -> String {
    switch phase {
    case "approval", "question", "running", "ended":
        return phase
    default:
        return "running"
    }
}

@main
struct LiveActivityBundle: WidgetBundle {
    var body: some Widget {
        CetusLiveActivity()
    }
}
