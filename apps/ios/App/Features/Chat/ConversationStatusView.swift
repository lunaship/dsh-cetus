import DLCore
import DLModels
import DLUI
import SwiftUI

/// 4.5 / 4.8 content surface below the navigation bar; all operations belong to later modules.
struct ConversationStatusView: View {
    let state: ConversationStatusState
    let copy: ConversationCopy
    @Binding var expanded: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let kind = state.kind {
            DLStatusSlot(
                title: statusTitle(kind), systemImage: symbol(kind),
                isExpanded: kind == .goal && expanded,
                toggleLabel: copy.text(expanded ? .statusCollapse : .statusExpand),
                onToggle: toggleAction(kind)
            ) {
                details
            }

        }
    }

    @ViewBuilder private var details: some View {
        if state.plan.isEmpty {
            phaseLabel
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    phaseLabel
                    if let progress = state.progress {
                        ProgressView(value: progress) {
                            Text(copy.format(.statusPlan, state.completedCount, state.plan.count))
                        }
                        .tint(DLColor.accent)
                    }
                    ForEach(Array(state.plan.enumerated()), id: \.offset) { entry in
                        planItem(entry.element)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 280)
        }
    }

    @ViewBuilder private var phaseLabel: some View {
        if let phase = state.goal?.phase {
            Text(copy.goalPhase(phase)).font(DLFont.footnote).foregroundStyle(DLColor.secondaryLabel)
        }
    }

    private func planItem(_ item: TodoItem) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(
                systemName: conversationPlanKind(item.status) == .completed
                    ? "checkmark.circle"
                    : conversationPlanKind(item.status) == .active ? "arrow.triangle.2.circlepath" : "circle"
            )
            .accessibilityHidden(true)
            Text(item.content ?? "").font(conversationPlanKind(item.status) == .active ? DLFont.headline : DLFont.body)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(conversationPlanKind(item.status) == .completed ? DLColor.secondaryLabel : DLColor.label)
        .accessibilityElement(children: .combine)
        .accessibilityValue(
            copy.text(
                conversationPlanKind(item.status) == .completed
                    ? .statusDone
                    : conversationPlanKind(item.status) == .active ? .statusActive : .statusNotDone))
    }

    private func toggleAction(_ kind: ConversationStatusKind) -> (() -> Void)? {
        guard kind == .goal else { return nil }
        return { toggle() }
    }

    private func toggle() {
        withAnimation(reduceMotion ? nil : .spring) { expanded.toggle() }
    }

    static func statusTitle(
        _ kind: ConversationStatusKind, state: ConversationStatusState, copy: ConversationCopy, expanded: Bool
    ) -> String {
        ConversationStatusView(state: state, copy: copy, expanded: .constant(expanded)).statusTitle(kind)
    }

    func statusTitle(_ kind: ConversationStatusKind) -> String {
        switch kind {
        case .disconnected:
            return copy.text(
                state.connection == .connecting
                    ? .statusConnecting
                    : state.connection == .failed ? .statusFailed : .statusReconnecting)
        case .pending:
            return state.pendingCount > 0 ? copy.format(.statusPending, state.pendingCount) : copy.text(.awaiting)
        case .goal:
            if expanded, let objective = state.goal?.objective, !objective.isEmpty { return objective }
            var parts: [String] = []
            if state.goal != nil { parts.append(copy.text(.statusGoal)) }
            if let round = state.goal?.roundsStarted, round > 0 {
                if let maximum = state.goal?.maxGoalRounds, maximum > 0 {
                    parts.append(copy.format(.statusRounds, round, maximum))
                } else {
                    parts.append(copy.format(.statusRound, round))
                }
            }
            if !state.plan.isEmpty { parts.append(copy.format(.statusPlan, state.completedCount, state.plan.count)) }
            return parts.joined(separator: " · ")
        case .preview:
            return copy.format(.statusPreview, state.previewPorts.map(String.init).joined(separator: " · "))
        }
    }

    private func symbol(_ kind: ConversationStatusKind) -> String {
        switch kind {
        case .disconnected: "wifi.exclamationmark"
        case .pending: "clock"
        case .goal: "scope"
        case .preview: "globe"
        }
    }
}
