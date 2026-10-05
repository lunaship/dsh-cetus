import Foundation

public enum PaletteGroup: String, CaseIterable, Sendable, Equatable {
    case agent
    case session
    case app
}

public enum PaletteLocal: String, Sendable, Equatable {
    case newSession
    case search
    case model
    case permission
    case chat
    case trace
    case settings
}

public enum PaletteAction: Equatable, Sendable {
    case insert(String)
    case complete(String)
    case local(PaletteLocal)
}

public struct PaletteSpec: Equatable, Sendable {
    public var trigger: String
    public var titleKey: String
    public var detailKey: String
    public var group: PaletteGroup
    public var action: PaletteAction

    public init(
        trigger: String, titleKey: String, detailKey: String, group: PaletteGroup, action: PaletteAction
    ) {
        self.trigger = trigger
        self.titleKey = titleKey
        self.detailKey = detailKey
        self.group = group
        self.action = action
    }
}

public struct PaletteCommand: Equatable, Sendable, Identifiable {
    public var trigger: String
    public var title: String
    public var detail: String
    public var action: PaletteAction
    public var id: String { trigger }

    public init(trigger: String, title: String, detail: String, action: PaletteAction) {
        self.trigger = trigger
        self.title = title
        self.detail = detail
        self.action = action
    }
}

public struct PaletteEntry: Equatable, Sendable {
    public var command: PaletteCommand
    public var group: PaletteGroup

    public init(command: PaletteCommand, group: PaletteGroup) {
        self.command = command
        self.group = group
    }
}

public struct PaletteSection: Equatable, Sendable {
    public var group: PaletteGroup
    public var items: [PaletteEntry]

    public init(group: PaletteGroup, items: [PaletteEntry]) {
        self.group = group
        self.items = items
    }
}

public enum SlashPick: Equatable, Sendable {
    case insert(String)
    case submit(String)
    case local(PaletteLocal)
}

/// 最高权限不能从指令面板直接提交，必须走权限弹层的二次确认。
public let dangerFullAccessCommand = "/permission danger-full-access"

public let paletteSpecs: [PaletteSpec] = [
    PaletteSpec(
        trigger: "/plan", titleKey: "palettePlan", detailKey: "palettePlanDetail", group: .agent,
        action: .insert("/plan")),
    PaletteSpec(
        trigger: "/goal", titleKey: "paletteGoal", detailKey: "paletteGoalDetail", group: .agent,
        action: .insert("/goal")),
    PaletteSpec(
        trigger: "/subagent", titleKey: "paletteSubagent", detailKey: "paletteSubagentDetail", group: .agent,
        action: .insert("/subagent")),
    PaletteSpec(
        trigger: "/skills", titleKey: "paletteSkills", detailKey: "paletteSkillsDetail", group: .agent,
        action: .complete("/skills")),
    PaletteSpec(
        trigger: "/pause", titleKey: "palettePause", detailKey: "palettePauseDetail", group: .session,
        action: .complete("/pause")),
    PaletteSpec(
        trigger: "/resume", titleKey: "paletteResume", detailKey: "paletteResumeDetail", group: .session,
        action: .complete("/resume")),
    PaletteSpec(
        trigger: "/clear", titleKey: "paletteClear", detailKey: "paletteClearDetail", group: .session,
        action: .complete("/clear")),
    PaletteSpec(
        trigger: "/feedback", titleKey: "paletteFeedback", detailKey: "paletteFeedbackDetail", group: .session,
        action: .insert("/feedback")),
    PaletteSpec(
        trigger: "/new-session", titleKey: "paletteNew", detailKey: "paletteNewDetail", group: .app,
        action: .local(.newSession)),
    PaletteSpec(
        trigger: "/search", titleKey: "paletteSearch", detailKey: "paletteSearchDetail", group: .app,
        action: .local(.search)),
    PaletteSpec(
        trigger: "/model", titleKey: "paletteModel", detailKey: "paletteModelDetail", group: .app,
        action: .local(.model)),
    PaletteSpec(
        trigger: "/permission", titleKey: "palettePermission", detailKey: "palettePermissionDetail", group: .app,
        action: .local(.permission)),
    PaletteSpec(
        trigger: "/chat", titleKey: "paletteChat", detailKey: "paletteChatDetail", group: .app,
        action: .local(.chat)),
    PaletteSpec(
        trigger: "/trace", titleKey: "paletteTrace", detailKey: "paletteTraceDetail", group: .app,
        action: .local(.trace)),
    PaletteSpec(
        trigger: "/settings", titleKey: "paletteSettings", detailKey: "paletteSettingsDetail", group: .app,
        action: .local(.settings)),
]

public func paletteEntries(title: (String) -> String, detail: (String) -> String) -> [PaletteEntry] {
    paletteSpecs.map { spec in
        PaletteEntry(
            command: PaletteCommand(
                trigger: spec.trigger, title: title(spec.titleKey), detail: detail(spec.detailKey),
                action: spec.action),
            group: spec.group)
    }
}

/// 空查询或只有 `/` 时返回全表。更长的查询去掉前导 `/` 后，按命令或标题做不区分大小写的子串匹配。
public func filterPalette(_ source: [PaletteEntry], query: String) -> [PaletteSection] {
    let needle = query.count <= 1 ? "" : String(query.dropFirst())
    var buckets: [PaletteGroup: [PaletteEntry]] = [:]
    for entry in source {
        let matches: Bool
        if query.count <= 1 {
            matches = true
        } else {
            let command = entry.command
            let bare = command.trigger.dropFirst()
            matches =
                command.trigger.range(of: needle, options: .caseInsensitive) != nil
                || command.title.range(of: needle, options: .caseInsensitive) != nil
                || needle.range(of: "\(bare) ", options: .caseInsensitive)?.lowerBound == needle.startIndex
        }
        if matches {
            buckets[entry.group, default: []].append(entry)
        }
    }
    return PaletteGroup.allCases.compactMap { group in
        guard let items = buckets[group], !items.isEmpty else { return nil }
        return PaletteSection(group: group, items: items)
    }
}

public func normalizeSlashCommand(_ text: String) -> String {
    text.split(whereSeparator: \.isWhitespace).map { $0.lowercased() }.joined(separator: " ")
}

public func isDangerPermissionCommand(_ text: String) -> Bool {
    normalizeSlashCommand(text) == dangerFullAccessCommand
}

public func resolvedPick(_ command: PaletteCommand) -> SlashPick {
    if case .complete(let text) = command.action, isDangerPermissionCommand(text) {
        return .local(.permission)
    }
    switch command.action {
    case .insert(let text):
        return .insert(text.hasSuffix(" ") ? text : text + " ")
    case .complete(let text):
        return .submit(text)
    case .local(let kind):
        return .local(kind)
    }
}
