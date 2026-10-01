package dev.deeplinks.native

import androidx.compose.runtime.mutableStateOf
import dev.deeplinks.core.L
import dev.deeplinks.core.PrivacySafeDiagnostics
import dev.deeplinks.core.PrivacySafeDiagnostics.Area
import dev.deeplinks.core.PrivacySafeDiagnostics.Op
import dev.deeplinks.core.intervalDays
import dev.deeplinks.core.intervalHours
import dev.deeplinks.core.intervalMinutes
import dev.deeplinks.core.intervalSeconds
import dev.deeplinks.core.listSeparator
import dev.deeplinks.core.scheduleDaily
import dev.deeplinks.core.scheduleEvery
import dev.deeplinks.core.scheduleOnce
import dev.deeplinks.core.scheduleWeekly
import dev.deeplinks.core.weekdayShort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话控制：排队消息、目标、定时任务（插件 capabilities.control）。
 *
 * 状态来源：history 响应的 `queue` / `goal`，SSE stats 帧里的 `inbox` / `goal` 投影，
 * 以及操作成功后的主动刷新。所有写操作都带 CAS 引用（目标 revision / 定时任务 expected），
 * 冲突时插件回 409，这里提示后刷新，不会覆盖网页端的更新。
 * 交互模型参考 Clarklevis1995/dsh-mobile（MIT），见 THIRD_PARTY_NOTICES.md。
 */

/** 一条待发送消息：queued = 下一轮再发；steering = 本轮下一步插入；context = 系统附加上下文（只读）。 */
data class QueuedPrompt(val id: String, val placement: String, val text: String, val images: Int = 0) {
    val editable: Boolean get() = placement == "queued" || placement == "steering"
}

data class SessionGoalRef(val id: String, val revision: Int)

/** 结构化目标；phase：active / paused / blocked / complete。 */
data class SessionGoal(
    val ref: SessionGoalRef,
    val objective: String,
    val phase: String,
    val maxGoalRounds: Int? = null,
    val roundsStarted: Int = 0,
) {
    val manageable: Boolean get() = phase != "complete"
    val active: Boolean get() = phase == "active"
}

/** 定时任务；[raw] 是原始 ScheduleRecord，修改时原样作为 expected 回传。 */
data class ScheduledTask(
    val id: String,
    val sessionId: String?,
    val title: String,
    val prompt: String,
    val kind: String,
    val status: String?,
    val nextRunAt: String?,
    val lastDeliveredAt: String?,
    val raw: JSONObject,
) {
    val active: Boolean get() = status == null || status == "active"
}

internal fun parseQueueItems(arr: JSONArray?): List<QueuedPrompt> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        QueuedPrompt(id, o.optString("placement", "queued"), o.optString("text"), o.optInt("images", 0))
    }
}

/** SSE stats 帧带的是原始 DSH inbox 投影（next-turn / next-step），与插件 queueItemsFromInbox 同规则。 */
internal fun queueFromInbox(inbox: JSONObject?): List<QueuedPrompt> {
    if (inbox == null) return emptyList()
    fun convert(key: String): List<QueuedPrompt> {
        val arr = inbox.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val content = m.optJSONArray("content") ?: return@mapNotNull null
            val source = m.optJSONObject("source")?.optString("kind").orEmpty()
            val placement = if (key == "next-turn") "queued" else if (source == "user") "steering" else "context"
            var images = 0
            val text = buildString {
                for (j in 0 until content.length()) {
                    val b = content.optJSONObject(j) ?: continue
                    when (b.optString("type")) {
                        "text" -> append(b.optString("text"))
                        "image" -> images++
                    }
                }
            }
            QueuedPrompt(id, placement, text, images)
        }
    }
    return convert("next-turn") + convert("next-step")
}

/** 目标投影：`{ goal: { id, revision, objective, phase, maxGoalRounds }, roundsStarted }`，也兼容扁平形状。 */
internal fun parseSessionGoal(obj: JSONObject?): SessionGoal? {
    if (obj == null) return null
    val g = obj.optJSONObject("goal") ?: obj
    val id = g.optString("id").takeIf { it.isNotBlank() } ?: return null
    val revision = g.optInt("revision", -1).takeIf { it > 0 } ?: return null
    val objective = g.optString("objective").trim()
    if (objective.isEmpty()) return null
    val rounds = if (g.has("maxGoalRounds") && !g.isNull("maxGoalRounds")) g.optInt("maxGoalRounds") else null
    return SessionGoal(
        ref = SessionGoalRef(id, revision),
        objective = objective,
        phase = g.optString("phase", g.optString("status", "active")).lowercase(),
        maxGoalRounds = rounds,
        roundsStarted = obj.optInt("roundsStarted", 0),
    )
}

internal fun parseScheduledTasks(arr: JSONArray?): List<ScheduledTask> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ScheduledTask(
            id = id,
            sessionId = o.optString("sessionId").takeIf { it.isNotBlank() },
            title = o.optString("title"),
            prompt = o.optString("prompt"),
            kind = o.optString("kind"),
            status = o.optString("status").takeIf { it.isNotBlank() },
            nextRunAt = o.optString("scheduledAt").takeIf { it.isNotBlank() },
            lastDeliveredAt = o.optJSONObject("lastDelivery")?.optString("deliveredAt")?.takeIf { it.isNotBlank() },
            raw = o,
        )
    }
}

/** 规则一句话：每天 09:00 / 每周一、三 / 每 30 分钟 / cron 表达式 / 一次性。 */
internal fun scheduleRuleLabel(task: ScheduledTask): String {
    val raw = task.raw
    val zone = raw.optString("timeZone").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return when (task.kind) {
        "daily" -> L.scheduleDaily.format(raw.optString("time")) + zone
        "weekly" -> {
            val days = raw.optJSONArray("weekdays")?.let { arr -> (0 until arr.length()).map { arr.optInt(it) } }.orEmpty()
            L.scheduleWeekly.format(days.joinToString(L.listSeparator) { weekdayLabel(it) }, raw.optString("time")) + zone
        }
        "every" -> L.scheduleEvery.format(formatScheduleInterval(raw.optLong("everySeconds")))
        "cron" -> raw.optString("expression") + zone
        "after", "at" -> L.scheduleOnce
        else -> task.kind
    }
}

private fun weekdayLabel(day: Int): String = L.weekdayShort.split(',').getOrNull((day - 1).coerceIn(0, 6)).orEmpty()

internal fun formatScheduleInterval(seconds: Long): String = when {
    seconds <= 0 -> "—"
    seconds % 86_400 == 0L -> L.intervalDays.format(seconds / 86_400)
    seconds % 3_600 == 0L -> L.intervalHours.format(seconds / 3_600)
    seconds % 60 == 0L -> L.intervalMinutes.format(seconds / 60)
    else -> L.intervalSeconds.format(seconds)
}

/**
 * 会话控制状态 + 操作。由 WorkspaceViewModel 持有（跨 Activity 重建），操作在 IO 线程执行，
 * 回调回到主线程。[busy] 是正在进行的操作键（例如 "goal:pause" / "queue:<id>"），UI 用它转圈防重复点。
 */
class SessionControlController(
    private val scope: CoroutineScope,
    private val client: () -> MobileApiClient,
    private val currentSessionId: () -> String?,
    /** 操作失败（多半是 CAS 冲突）后拉一次最新 history，目标 revision 随之更新。 */
    private val refreshAll: () -> Unit = {},
) {
    val supported = mutableStateOf(false)
    val queue = mutableStateOf<List<QueuedPrompt>>(emptyList())
    val goal = mutableStateOf<SessionGoal?>(null)
    val busy = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)
    val schedules = mutableStateOf<List<ScheduledTask>>(emptyList())
    val schedulesLoading = mutableStateOf(false)
    val schedulesError = mutableStateOf<String?>(null)
    private var queueJob: Job? = null

    fun clear() {
        queue.value = emptyList()
        goal.value = null
        busy.value = null
        error.value = null
        schedules.value = emptyList()
        schedulesError.value = null
    }

    /** history 响应：插件已把 inbox 投影成 `queue`，并带上 `goal`。旧插件无这两个字段时不动现值。 */
    fun applyHistory(result: HistoryResult) {
        result.queue?.let { queue.value = it }
        if (result.goalKnown) goal.value = result.goal
    }

    /** SSE stats 帧（完整 projections.values）：有 inbox / goal 投影时同步。 */
    fun applyProjections(values: JSONObject) {
        if (values.has("inbox")) queue.value = queueFromInbox(values.optJSONObject("inbox"))
        if (values.has("goal")) goal.value = parseSessionGoal(values.optJSONObject("goal"))
    }

    fun refreshQueue() {
        val sid = currentSessionId() ?: return
        if (!supported.value) return
        queueJob?.cancel()
        queueJob = scope.launch {
            val items = runCatching { withContext(Dispatchers.IO) { client().getSessionQueue(sid) } }.getOrNull() ?: return@launch
            if (currentSessionId() == sid) queue.value = items
        }
    }

    /** 编辑 = 先移出队列再把文字放回输入框（与网页一致：改完重新发送，不会出现两份）。 */
    fun editQueued(item: QueuedPrompt, onRestore: (String) -> Unit) =
        queueAction(item, "remove") { onRestore(item.text) }

    fun removeQueued(item: QueuedPrompt) = queueAction(item, "remove")

    fun steerQueued(item: QueuedPrompt) = queueAction(item, "steer")

    private fun queueAction(item: QueuedPrompt, action: String, onOk: () -> Unit = {}) {
        val sid = currentSessionId() ?: return
        run("queue:${item.id}", Area.Queue, if (action == "steer") Op.Steer else Op.Remove) {
            client().updateQueueItem(sid, item.id, action)
            withContext(Dispatchers.Main) {
                if (action == "remove") queue.value = queue.value.filterNot { it.id == item.id }
                onOk()
            }
            refreshQueue()
        }
    }

    fun pauseOrResumeGoal() {
        val g = goal.value ?: return
        goalAction(if (g.active) "pause" else "resume")
    }

    fun editGoal(objective: String, maxGoalRounds: Int?, onDone: (Boolean) -> Unit = {}) =
        goalAction("edit", objective, maxGoalRounds, onDone)

    fun clearGoal(onDone: (Boolean) -> Unit = {}) = goalAction("clear", onDone = onDone)

    private fun goalAction(op: String, objective: String? = null, maxGoalRounds: Int? = null, onDone: (Boolean) -> Unit = {}) {
        val sid = currentSessionId() ?: return
        val g = goal.value ?: return
        val diagOp = when (op) { "pause" -> Op.Pause; "resume" -> Op.Resume; "clear" -> Op.Clear; else -> Op.Edit }
        run("goal:$op", Area.Goal, diagOp, onFail = { onDone(false) }) {
            val next = client().goalAction(sid, op, g.ref, objective, maxGoalRounds)
            withContext(Dispatchers.Main) {
                goal.value = when (op) {
                    "clear" -> null
                    "pause" -> g.copy(ref = next ?: g.ref, phase = "paused")
                    "resume" -> g.copy(ref = next ?: g.ref, phase = "active")
                    else -> g.copy(ref = next ?: g.ref, objective = objective ?: g.objective, maxGoalRounds = maxGoalRounds ?: g.maxGoalRounds)
                }
                onDone(true)
            }
        }
    }

    /** [all] = 全部会话的定时任务（schedule/catalog），否则只看当前会话。 */
    fun loadSchedules(all: Boolean) {
        val sid = currentSessionId()
        if (!all && sid == null) return
        schedulesLoading.value = true
        schedulesError.value = null
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    if (all) client().getScheduleCatalog() else client().getSessionSchedules(sid!!)
                }
                schedules.value = items
                PrivacySafeDiagnostics.event(Area.Schedule, Op.Load, ok = true, count = items.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                PrivacySafeDiagnostics.event(Area.Schedule, Op.Load, ok = false)
                schedulesError.value = e.message?.takeIf { it.isNotBlank() } ?: L.unknownError
            } finally {
                schedulesLoading.value = false
            }
        }
    }

    fun updateSchedule(task: ScheduledTask, title: String, prompt: String, all: Boolean, onDone: (Boolean) -> Unit) {
        val sid = task.sessionId ?: currentSessionId() ?: return
        run("schedule:${task.id}", Area.Schedule, Op.Update, onFail = { onDone(false) }) {
            client().updateSchedule(sid, task, title.takeIf { it != task.title }, prompt.takeIf { it != task.prompt })
            withContext(Dispatchers.Main) {
                onDone(true)
                loadSchedules(all)
            }
        }
    }

    fun deleteSchedule(task: ScheduledTask, all: Boolean, onDone: (Boolean) -> Unit = {}) {
        val sid = task.sessionId ?: currentSessionId() ?: return
        run("schedule:${task.id}", Area.Schedule, Op.Delete, onFail = { onDone(false) }) {
            client().deleteSchedule(sid, task.id)
            withContext(Dispatchers.Main) {
                schedules.value = schedules.value.filterNot { it.id == task.id }
                onDone(true)
                loadSchedules(all)
            }
        }
    }

    private fun run(key: String, area: Area, op: Op, onFail: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy.value != null) return
        busy.value = key
        error.value = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                PrivacySafeDiagnostics.event(area, op, ok = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                PrivacySafeDiagnostics.event(area, op, ok = false)
                error.value = e.message?.takeIf { it.isNotBlank() } ?: L.unknownError
                onFail()
                // 多半是 CAS 冲突（网页端刚改过）：拉一次最新状态
                refreshQueue()
                refreshAll()
            } finally {
                busy.value = null
            }
        }
    }
}
