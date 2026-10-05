import DLCore
import DLModels
import DLUI
import SwiftUI

struct TrajectoryPage: View {
    var messages: [HistoryMessage]
    @State private var query = ""
    @State private var filter: TrajectoryFilter = .all
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = ConversationCopy(locale: locale)
        let turns = trajectoryTurns(messages, filter: filter, query: query)
        List {
            Section {
                chipRow(copy)
            }
            if turns.isEmpty {
                Section {
                    DLEmptyState(
                        title: copy.text(.trajectoryEmpty),
                        systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                }
            }
            ForEach(turns) { turn in
                Section {
                    ForEach(turn.lines) { line in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(line.title).font(DLFont.headline)
                            if !line.detail.isEmpty {
                                Text(line.detail)
                                    .font(DLFont.footnote)
                                    .foregroundStyle(DLColor.secondaryLabel)
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }
                } header: {
                    HStack {
                        Text(copy.format(.trajectoryTurn, turn.id))
                        Spacer()
                        Text(trajectoryClock(turn.time))
                            .foregroundStyle(DLColor.secondaryLabel)
                    }
                }
            }
        }
        .navigationTitle(copy.text(.trajectoryTitle))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: copy.text(.trajectorySearch))
    }

    private func chipRow(_ copy: ConversationCopy) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(TrajectoryFilter.allCases, id: \.self) { item in
                    Button(title(item, copy)) { filter = item }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .tint(filter == item ? DLColor.accent : DLColor.secondaryLabel)
                        .frame(minHeight: 44)
                }
            }
        }
    }

    private func title(_ filter: TrajectoryFilter, _ copy: ConversationCopy) -> String {
        switch filter {
        case .all: copy.text(.trajectoryAll)
        case .user: copy.text(.trajectoryUser)
        case .assistant: copy.text(.trajectoryAssistant)
        case .tool: copy.text(.trajectoryTool)
        }
    }
}

func trajectoryClock(_ time: Int?) -> String {
    guard let time else { return "" }
    let seconds = time > 10_000_000_000 ? TimeInterval(time) / 1000 : TimeInterval(time)
    return Date(timeIntervalSince1970: seconds).formatted(date: .omitted, time: .shortened)
}
