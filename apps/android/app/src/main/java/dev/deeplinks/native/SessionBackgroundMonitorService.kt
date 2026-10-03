package dev.deeplinks.native

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.deeplinks.core.ActiveSinceTracker
import dev.deeplinks.core.ConnectivitySignals
import dev.deeplinks.core.decideCompletionNotice
import dev.deeplinks.core.DshNotifier
import dev.deeplinks.core.notifySessionCompletion
import dev.deeplinks.core.ApprovalActionReceiver
import dev.deeplinks.core.Host
import dev.deeplinks.core.HOST_MONITOR_IDLE_MS
import dev.deeplinks.core.HostLink
import dev.deeplinks.core.NetworkChangeAction
import dev.deeplinks.core.hostMonitorShouldReconnect
import dev.deeplinks.core.isHostSessionActive
import dev.deeplinks.core.stepHostMonitor
import dev.deeplinks.core.TASK_PROGRESS_MIN_INTERVAL_MS
import dev.deeplinks.core.TaskProgressEvent
import dev.deeplinks.core.TaskProgressState
import dev.deeplinks.core.reduceTaskProgress
import dev.deeplinks.core.shouldPostTaskProgress
import dev.deeplinks.core.taskProgressEventChangesNotification
import dev.deeplinks.core.todoProgressCounts
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.parseStoppedReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 离开 App 或锁屏时，让当前打开、仍在运行的会话保持 SSE 订阅，从而继续收到审批与完成提醒。
 *
 * 只在设置「离开 App 后继续接管审批」打开时工作（[WorkspacePrefs.backgroundTakeover]，默认关闭）：
 * 插件只要看到手机在订阅，就会把该会话的审批交给手机，电脑网页上不再出现；这是用户需要明确选择的行为。
 */
class SessionBackgroundMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: Host? = null
    private var sessionId: String? = null
    private var sessionTitle: String = ""
    private var background = false
    private var stream: SessionStreamClient? = null
    private var hostEvents: HostEventClient? = null
    private var streamJob: Job? = null
    private var networkJob: Job? = null
    private val sessionStates = mutableMapOf<String, String>()
    private var idleSince = emptyMap<String, Long>()
    private var idleStop: Job? = null
    private var terminalCheck: Job? = null
    private var generation = 0L
    private var progress = TaskProgressState(title = "")
    private var lastProgressPostAt = 0L
    private var progressFlush: Job? = null
    private var foregroundPosted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (intent.getStringExtra(EXTRA_SESSION_ID) == sessionId) stopMonitoring()
            return START_NOT_STICKY
        }
        val isRestore = intent == null
        // 开关已关：进程被回收后系统按 START_STICKY 重建时不再恢复订阅（恢复走 startService，无需 startForeground）
        if (isRestore && !WorkspacePrefs(this).backgroundTakeover) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        val nextSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
            ?: if (isRestore) storedSessionId() else null
        val nextHost = HostStore.current(this)
        if (nextSessionId.isNullOrBlank() || nextHost == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val expectedSlot = intent?.getStringExtra(EXTRA_HOST_SLOT)
        if (expectedSlot != null && expectedSlot != nextHost.slotKey) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ANSWER) {
            if (sessionId != null && sessionId != nextSessionId) {
                DshNotifier.cancelApproval(this, nextHost, nextSessionId)
                DshNotifier.openSession(this, nextHost, nextSessionId)
                return START_NOT_STICKY
            }
            if (sessionId == null) configure(nextHost, nextSessionId, storedTitle().orEmpty(), storedSequence(), true)
            else startForegroundNow()
            val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID).orEmpty()
            if (approvalId.isNotBlank()) submitApproval(nextHost, nextSessionId, approvalId, intent.getBooleanExtra(EXTRA_APPROVE, false))
            return START_STICKY
        }
        configure(
            nextHost,
            nextSessionId,
            intent?.getStringExtra(EXTRA_TITLE) ?: storedTitle().orEmpty(),
            intent?.getLongExtra(EXTRA_AFTER_SEQ, storedSequence()) ?: storedSequence(),
            intent?.getBooleanExtra(EXTRA_BACKGROUND, false) ?: storedBackground(),
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopReader()
        terminalCheck?.cancel()
        serviceScope.cancel()
        if (instance === this) instance = null
        if (activeSessionId == sessionId) activeSessionId = null
        super.onDestroy()
    }

    private fun startForegroundNow() {
        val currentHost = host ?: return
        val sid = sessionId ?: return
        DshNotifier.ensureChannel(this)
        progress = progress.copy(title = sessionTitle)
        val notification = DshNotifier.taskMonitorNotification(
            this,
            currentHost,
            sid,
            progress.snapshot(System.currentTimeMillis()),
        )
        lastProgressPostAt = android.os.SystemClock.elapsedRealtime()
        foregroundPosted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                DshNotifier.TASK_MONITOR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(DshNotifier.TASK_MONITOR_NOTIFICATION_ID, notification)
        }
    }

    private fun configure(nextHost: Host, sid: String, title: String, afterSeq: Long, inBackground: Boolean) {
        if (sessionId != sid || host?.slotKey != nextHost.slotKey) {
            stopReader()
            terminalCheck?.cancel()
            progressFlush?.cancel()
            if (host != null && sessionId != null) DshNotifier.cancelTaskMonitor(this, host!!, sessionId!!)
            progress = TaskProgressState(title = title)
            lastProgressPostAt = 0L
            foregroundPosted = false
        }
        host = nextHost
        sessionId = sid
        sessionTitle = title
        progress = progress.copy(title = title)
        background = inBackground
        persist(afterSeq)
        startForegroundNow()
        if (background) startReader(afterSeq) else stopReader()
    }

    private fun startReader(afterSeq: Long) {
        val currentHost = host ?: return
        val sid = sessionId ?: return
        background = true
        // 后台不再订阅单会话 SSE，改听这台电脑的主机事件。会话页自己的流在离开时已经停掉。
        stream?.stop()
        stream = null
        val client = HostEventClient(currentHost, serviceScope)
        hostEvents = client
        streamJob?.cancel()
        networkJob?.cancel()
        generation++
        val readerGeneration = generation
        streamJob = serviceScope.launch {
            client.start()
            for (item in client.items) {
                if (!isActive || readerGeneration != generation) break
                when (item) {
                    is HostEventClient.Item.State -> onHostState(currentHost, sid, item)
                    HostEventClient.Item.Resync -> {
                        sessionStates.clear()
                        activeSince.clear()
                    }
                    HostEventClient.Item.Disconnected -> {
                        if (client.lastFailure == StreamFailure.AUTH) stopMonitoring()
                    }
                }
            }
        }
        networkJob = serviceScope.launch {
            ConnectivitySignals.probeNow.collect {
                if (!isActive || readerGeneration != generation || !background) return@collect
                if (hostMonitorShouldReconnect(NetworkChangeAction.ResetPool)) {
                    client.reconnect()
                }
            }
        }
        if (afterSeq > 0) client.noteEventId(afterSeq)
    }

    private val activeSince = ActiveSinceTracker()

    private fun onHostState(host: Host, takeoverId: String, item: HostEventClient.Item.State) {
        val now = System.currentTimeMillis()
        activeSince.observe(item.sessionId, item.state, now)
        sessionStates[item.sessionId] = item.state
        val active = sessionStates.values.any(::isHostSessionActive)
        val decision = stepHostMonitor(
            nowMs = now,
            links = listOf(HostLink(host.slotKey, active)),
            idleSince = idleSince,
        )
        idleSince = decision.idleSince
        if (host.slotKey !in decision.connect) {
            stopMonitoring()
            return
        }
        scheduleIdleStop(host.slotKey)
        if (!isHostSessionActive(item.state)) {
            postCompletion(host, item, activeSince.finish(item.sessionId, now))
        }
        if (item.sessionId != takeoverId) {
            if (item.state == "awaitingApproval") {
                DshNotifier.notifyHandleOnComputer(this, host, item.sessionId, item.title.ifBlank { sessionTitle })
            }
            return
        }
        if (item.title.isNotBlank()) {
            sessionTitle = item.title
            progress = progress.copy(title = item.title)
        }
        when (item.state) {
            "awaitingApproval" -> {
                onProgressEvent(TaskProgressEvent.ApprovalAsked)
                reconcilePendingApproval(host, item.sessionId)
            }
            "awaitingInput" -> onProgressEvent(TaskProgressEvent.QuestionAsked)
            "running" -> onProgressEvent(TaskProgressEvent.ApprovalDecided)
            "completed", "failed", "stopped" -> onProgressEvent(TaskProgressEvent.TurnEnded)
        }
    }

    /** 完成通知走纯函数。回复首行只在开关打开时另读会话列表，不放进主机事件。 */
    private fun postCompletion(host: Host, item: HostEventClient.Item.State, durationMs: Long?) {
        val prefs = WorkspacePrefs(this)
        val preliminary = decideCompletionNotice(
            origin = item.origin,
            state = item.state,
            observedDurationMs = durationMs,
            longTaskMinutes = prefs.longTaskMinutes,
            notifyOnDone = prefs.notifyOnDone,
            showReplyFirstLine = false,
            replyFirstLine = null,
        )
        if (!preliminary.notify) return
        val showLine = prefs.notifyReplyFirstLine
        serviceScope.launch(Dispatchers.IO) {
            val line = if (showLine) {
                runCatching {
                    MobileApiClient(host).getSessions().sessions
                        .firstOrNull { it.sessionId == item.sessionId }
                        ?.lastResult
                        ?.text
                }.getOrNull()
            } else {
                null
            }
            val notice = decideCompletionNotice(
                origin = item.origin,
                state = item.state,
                observedDurationMs = durationMs,
                longTaskMinutes = prefs.longTaskMinutes,
                notifyOnDone = prefs.notifyOnDone,
                showReplyFirstLine = showLine,
                replyFirstLine = line,
            )
            if (!notice.notify) return@launch
            withContext(Dispatchers.Main) {
                notifySessionCompletion(this@SessionBackgroundMonitorService, host, item.sessionId, item.title, notice)
            }
        }
    }

    private fun scheduleIdleStop(hostKey: String) {
        val since = idleSince[hostKey] ?: run {
            idleStop?.cancel()
            return
        }
        idleStop?.cancel()
        val wait = (since + HOST_MONITOR_IDLE_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        idleStop = serviceScope.launch {
            delay(wait)
            val still = stepHostMonitor(
                nowMs = System.currentTimeMillis(),
                links = listOf(HostLink(hostKey, sessionStates.values.any(::isHostSessionActive))),
                idleSince = idleSince,
            )
            idleSince = still.idleSince
            if (hostKey !in still.connect) stopMonitoring()
        }
    }

    private fun handleMessage(host: Host, sid: String, client: SessionStreamClient, item: SessionStreamClient.Item.Message) {
        recordSequence(client, sid, item.seq)
        when (item.type) {
            "approval/asked" -> {
                postApproval(host, sid, item.data.optString("id"), item.data.optString("toolName", L.toolFallbackName))
                onProgressEvent(TaskProgressEvent.ApprovalAsked)
            }
            "approval/decided" -> {
                DshNotifier.cancelApproval(this, host, sid)
                onProgressEvent(TaskProgressEvent.ApprovalDecided)
            }
            "turn/start" -> {
                terminalCheck?.cancel()
                val at = item.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                onProgressEvent(TaskProgressEvent.TurnStarted(at))
            }
            "turn/end" -> {
                onProgressEvent(TaskProgressEvent.TurnEnded)
                scheduleTerminalCheck(host, sid, item.data)
            }
            "todo/write" -> onProgressEvent(todoEvent(item.data))
            "tool/call" -> {
                val step = item.data.optLong("step", -1L).takeIf { it >= 0L }
                onProgressEvent(TaskProgressEvent.ToolStep(step))
            }
            else -> onProgressEvent(TaskProgressEvent.Ignored(item.type))
        }
    }

    private fun todoEvent(data: JSONObject): TaskProgressEvent {
        val todos = data.optJSONArray("todos") ?: return TaskProgressEvent.Todos(0, 0)
        val statuses = (0 until todos.length()).map { index ->
            todos.optJSONObject(index)?.optString("status", "pending") ?: "pending"
        }
        val (done, total) = todoProgressCounts(statuses)
        return TaskProgressEvent.Todos(done, total)
    }

    private fun onProgressEvent(event: TaskProgressEvent, force: Boolean = false) {
        val next = reduceTaskProgress(progress, event)
        val changed = next != progress
        progress = next
        if (!force && (!changed || !taskProgressEventChangesNotification(event))) return
        postTaskProgress(force)
    }

    /** 2 秒内只刷新一次；流式片段不会进到这里。错过的更新在窗口结束后补一次。 */
    private fun postTaskProgress(force: Boolean) {
        val currentHost = host ?: return
        val sid = sessionId ?: return
        if (!foregroundPosted) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!shouldPostTaskProgress(lastProgressPostAt, now, force)) {
            if (progressFlush?.isActive == true) return
            val wait = (TASK_PROGRESS_MIN_INTERVAL_MS - (now - lastProgressPostAt)).coerceAtLeast(0L)
            progressFlush = serviceScope.launch {
                delay(wait)
                postTaskProgress(force = true)
            }
            return
        }
        progressFlush?.cancel()
        lastProgressPostAt = now
        DshNotifier.updateTaskMonitor(this, currentHost, sid, progress.snapshot(System.currentTimeMillis()))
    }

    private fun seedTaskProgress(host: Host, sid: String) {
        serviceScope.launch(Dispatchers.IO) {
            val history = runCatching { MobileApiClient(host).getSessionHistory(sid, maxMessages = 80) }.getOrNull()
            val todo = history?.messages?.lastOrNull { it.role == "todo" }
            val seeded = if (todo == null) {
                null
            } else {
                val (done, total) = todoProgressCounts(todo.todos.map { it.status })
                TaskProgressEvent.Todos(done, total)
            }
            val step = history?.stats?.steps?.takeIf { it > 0L }
            withContext(Dispatchers.Main) {
                if (sessionId != sid) return@withContext
                if (seeded != null) onProgressEvent(seeded)
                if (step != null && progress.step == null) onProgressEvent(TaskProgressEvent.ToolStep(step))
            }
        }
    }

    private fun postApproval(host: Host, sid: String, approvalId: String?, toolName: String) {
        if (approvalId.isNullOrBlank()) {
            DshNotifier.notifyApproval(this, host, sid, toolName, sessionTitle = sessionTitle)
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            val actionable = runCatching {
                isPendingApprovalActionable(MobileApiClient(host).getSessionRequests(sid), approvalId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (sessionId == sid && background) {
                    DshNotifier.notifyApproval(this@SessionBackgroundMonitorService, host, sid, toolName, approvalId.takeIf { actionable }, sessionTitle)
                }
            }
        }
    }

    private fun submitApproval(host: Host, sid: String, approvalId: String, approve: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val client = MobileApiClient(host)
            var accepted = false
            for (attempt in 1..3) {
                accepted = runCatching {
                    client.answerApproval(sid, approvalId, if (approve) ApprovalActionReceiver.OUTCOME_ALLOW else ApprovalActionReceiver.OUTCOME_REJECT)
                }.onFailure { error ->
                    Log.w(TAG, "approval failed attempt=$attempt session=${sid.take(8)}: ${error.javaClass.simpleName}")
                }.getOrDefault(false)
                if (accepted) break
                if (attempt < 3) delay(700)
            }
            withContext(Dispatchers.Main) {
                if (accepted) DshNotifier.markApprovalAnswered(this@SessionBackgroundMonitorService, host, sid, approve)
                else {
                    DshNotifier.cancelApproval(this@SessionBackgroundMonitorService, host, sid)
                    DshNotifier.openSession(this@SessionBackgroundMonitorService, host, sid)
                }
            }
        }
    }

    private fun reconcilePendingApproval(host: Host, sid: String) {
        serviceScope.launch(Dispatchers.IO) {
            val pending = runCatching { MobileApiClient(host).getSessionRequests(sid).approvals.firstOrNull { it.status == REQUEST_PENDING } }.getOrNull()
            val waiting = runCatching {
                MobileApiClient(host).getSessions().sessions.firstOrNull { it.sessionId == sid }?.awaitingInput == true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (sessionId != sid || !background) return@withContext
                when {
                    pending != null -> DshNotifier.notifyApproval(this@SessionBackgroundMonitorService, host, sid, pending.toolName ?: L.toolFallbackName, pending.id, sessionTitle)
                    waiting -> DshNotifier.notifyApproval(this@SessionBackgroundMonitorService, host, sid, L.toolFallbackName, sessionTitle = sessionTitle)
                }
            }
        }
    }

    private fun scheduleTerminalCheck(host: Host, sid: String, data: JSONObject) {
        terminalCheck?.cancel()
        val scheduledGeneration = generation
        val eventReason = data.optJSONObject("reason")?.optString("kind")
        terminalCheck = serviceScope.launch {
            delay(2_000)
            val ended = runCatching {
                withContext(Dispatchers.IO) {
                    MobileApiClient(host).getSessions().sessions.firstOrNull { it.sessionId == sid }
                }
            }.getOrNull()
            if (scheduledGeneration != generation || !background || ended?.let(::isSessionMonitorFinished) != true) return@launch
            DshNotifier.cancelApproval(this@SessionBackgroundMonitorService, host, sid)
            val stopped = parseStoppedReason(ended.stoppedReason ?: eventReason)
            if (stopped == null) {
                DshNotifier.notifyTaskDone(this@SessionBackgroundMonitorService, host, sid, ended.title, ended.lastResult?.text)
            } else {
                DshNotifier.notifyTaskFailed(this@SessionBackgroundMonitorService, host, sid, ended.title, stopped)
            }
            stopMonitoring()
        }
    }

    private fun resync(host: Host, sid: String, client: SessionStreamClient, readerGeneration: Long) {
        client.pauseForResync()
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val history = MobileApiClient(host).getSessionHistory(sid, maxMessages = 500)
                historySeedSeq(history.maxSeq, history.messages.mapNotNull { it.seq })
            }.onSuccess { seed ->
                withContext(Dispatchers.Main) {
                    if (readerGeneration == generation && stream === client) {
                        recordSequence(client, sid, seed)
                        client.noteSeedMaxSeq(seed)
                        reconcilePendingApproval(host, sid)
                    }
                }
            }.onFailure { Log.w(TAG, "background stream resync failed session=${sid.take(8)}", it) }
        }
    }

    private fun recordSequence(client: SessionStreamClient, sid: String, seq: Long) {
        client.noteCommittedSeq(seq)
        SessionMonitorCursors.update(sid, client.lastSeq)
        persist(client.lastSeq)
    }

    private fun stopReader() {
        generation++
        streamJob?.cancel()
        streamJob = null
        networkJob?.cancel()
        networkJob = null
        idleStop?.cancel()
        idleStop = null
        stream?.stop()
        stream = null
        hostEvents?.stop()
        hostEvents = null
    }

    private fun beginBackground(sid: String, afterSeq: Long) {
        if (sessionId != sid) return
        background = true
        val cursor = maxOf(afterSeq, SessionMonitorCursors.get(sid))
        persist(cursor)
        if (streamJob?.isActive != true) startReader(cursor)
    }

    private fun returnToForeground(sid: String): Long {
        if (sessionId != sid) return SessionMonitorCursors.get(sid)
        background = false
        terminalCheck?.cancel()
        val latest = maxOf(stream?.lastSeq ?: 0L, SessionMonitorCursors.get(sid))
        stopReader()
        persist(latest)
        return latest
    }

    private fun finishForSession(sid: String) {
        if (sessionId == sid) stopMonitoring()
    }

    private fun stopMonitoring() {
        val currentHost = host
        val sid = sessionId
        stopReader()
        terminalCheck?.cancel()
        progressFlush?.cancel()
        if (currentHost != null && sid != null) DshNotifier.cancelTaskMonitor(this, currentHost, sid)
        prefs().edit().clear().apply()
        background = false
        sessionId = null
        sessionTitle = ""
        host = null
        activeSessionId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persist(seq: Long) {
        prefs().edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_TITLE, sessionTitle)
            .putBoolean(KEY_BACKGROUND, background)
            .putLong(KEY_AFTER_SEQ, seq)
            .apply()
        sessionId?.let { activeSessionId = it }
    }

    private fun storedSessionId(): String? = prefs().getString(KEY_SESSION_ID, null)
    private fun storedTitle(): String? = prefs().getString(KEY_TITLE, null)
    private fun storedBackground(): Boolean = prefs().getBoolean(KEY_BACKGROUND, false)
    private fun storedSequence(): Long = prefs().getLong(KEY_AFTER_SEQ, 0L)
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SessionMonitor"
        private const val PREFS = "session_background_monitor"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_TITLE = "title"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_AFTER_SEQ = "afterSeq"
        private const val ACTION_START = "dev.deeplinks.action.START_SESSION_MONITOR"
        private const val ACTION_STOP = "dev.deeplinks.action.STOP_SESSION_MONITOR"
        private const val ACTION_ANSWER = "dev.deeplinks.action.ANSWER_APPROVAL"
        private const val EXTRA_SESSION_ID = "monitorSessionId"
        private const val EXTRA_TITLE = "monitorSessionTitle"
        private const val EXTRA_AFTER_SEQ = "monitorAfterSeq"
        private const val EXTRA_BACKGROUND = "monitorBackground"
        private const val EXTRA_HOST_SLOT = "monitorHostSlot"
        private const val EXTRA_APPROVAL_ID = "monitorApprovalId"
        private const val EXTRA_APPROVE = "monitorApprove"
        @Volatile private var instance: SessionBackgroundMonitorService? = null
        @Volatile private var activeSessionId: String? = null

        fun answerApproval(context: Context, host: Host, sid: String, approvalId: String, approve: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, SessionBackgroundMonitorService::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_SESSION_ID, sid)
                putExtra(EXTRA_HOST_SLOT, host.slotKey)
                putExtra(EXTRA_APPROVAL_ID, approvalId)
                putExtra(EXTRA_APPROVE, approve)
            })
        }

        fun start(context: Context, host: Host, sid: String, title: String, afterSeq: Long) {
            val service = instance
            if (service != null) {
                service.configure(host, sid, title, afterSeq, inBackground = false)
                return
            }
            activeSessionId = sid
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionBackgroundMonitorService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sid)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_AFTER_SEQ, afterSeq)
                    putExtra(EXTRA_HOST_SLOT, host.slotKey)
                },
            )
        }

        fun background(context: Context, host: Host, sid: String, title: String, afterSeq: Long) {
            val service = instance
            if (service != null) {
                if (service.sessionId == sid) service.beginBackground(sid, afterSeq)
                return
            }
            if (activeSessionId != sid) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionBackgroundMonitorService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sid)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_AFTER_SEQ, afterSeq)
                    putExtra(EXTRA_HOST_SLOT, host.slotKey)
                    putExtra(EXTRA_BACKGROUND, true)
                },
            )
        }

        /** 设置里关掉开关时调用：停止订阅并收回可操作的审批通知，审批回到电脑网页。 */
        fun stopAll(context: Context) {
            val service = instance
            if (service != null) {
                val h = service.host
                val sid = service.sessionId
                if (h != null && sid != null) DshNotifier.cancelApproval(context, h, sid)
                service.stopMonitoring()
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            }
            activeSessionId = null
        }

        fun foreground(sid: String): Long = instance?.returnToForeground(sid) ?: SessionMonitorCursors.get(sid)

        fun finish(context: Context, sid: String) {
            val service = instance
            if (service != null) {
                service.finishForSession(sid)
            } else if (activeSessionId == sid) {
                context.startService(Intent(context, SessionBackgroundMonitorService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SESSION_ID, sid)
                })
            }
        }
    }
}

private object SessionMonitorCursors {
    private val cursors = ConcurrentHashMap<String, AtomicLong>()
    fun get(sessionId: String): Long = cursors[sessionId]?.get() ?: 0L
    fun update(sessionId: String, seq: Long) {
        if (seq > 0) cursors.getOrPut(sessionId) { AtomicLong() }.updateAndGet { maxOf(it, seq) }
    }
}

internal fun isSessionMonitorFinished(session: MobileSession): Boolean = !session.running && !session.awaitingInput

internal fun isPendingApprovalActionable(snapshot: SessionRequestSnapshot, approvalId: String): Boolean =
    snapshot.approvals.any { it.id == approvalId && it.status == REQUEST_PENDING }
