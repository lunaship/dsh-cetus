import SwiftUI
import WidgetKit

struct LiveActivityPlaceholder: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "LiveActivityPlaceholder", provider: Provider()) { _ in
            Text(verbatim: "")
        }
        .configurationDisplayName("DeepLinks")
        .description("")
    }
}

private struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> Entry { Entry(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry(date: .now))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        completion(Timeline(entries: [Entry(date: .now)], policy: .never))
    }
}

private struct Entry: TimelineEntry {
    let date: Date
}

@main
struct LiveActivityBundle: WidgetBundle {
    var body: some Widget {
        LiveActivityPlaceholder()
    }
}
