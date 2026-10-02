package dev.deeplinks.core

val DshStrings.notifChannelTasksDesc: String get() = translation("notifChannelTasksDesc")
val DshStrings.notifChannelMonitor: String get() = translation("notifChannelMonitor")
val DshStrings.notifChannelMonitorDesc: String get() = translation("notifChannelMonitorDesc")
val DshStrings.notifMonitorTitle: String get() = translation("notifMonitorTitle")
val DshStrings.notifMonitorBody: String get() = translation("notifMonitorBody")
val DshStrings.taskProgressRunning: String get() = translation("taskProgressRunning")
val DshStrings.taskProgressAwaitingApproval: String get() = translation("taskProgressAwaitingApproval")
val DshStrings.taskProgressAwaitingInput: String get() = translation("taskProgressAwaitingInput")
val DshStrings.taskProgressCompleted: String get() = translation("taskProgressCompleted")
val DshStrings.taskProgressStep: String get() = translation("taskProgressStep")
val DshStrings.taskProgressElapsed: String get() = translation("taskProgressElapsed")
val DshStrings.taskProgressPublic: String get() = translation("taskProgressPublic")
val DshStrings.taskElsewhereTitle: String get() = translation("taskElsewhereTitle")
val DshStrings.taskElsewhereBody: String get() = translation("taskElsewhereBody")
val DshStrings.backgroundTakeoverHint: String get() = translation("backgroundTakeoverHint")
val DshStrings.backgroundTakeoverDoc: String get() = translation("backgroundTakeoverDoc")

/** 设置「通知」里的后台接管开关（默认关闭，见 WorkspacePrefs.backgroundTakeover）。 */
val DshStrings.backgroundTakeover: String get() = translation("backgroundTakeover")
