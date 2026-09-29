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
import dev.deeplinks.core.DshNotifier
import dev.deeplinks.core.ApprovalActionReceiver
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.core.notifMonitorBody
import dev.deeplinks.core.notifMonitorTitle
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

/** Keeps the selected, running DSH conversation connected while the user switches apps or locks the phone. */
class SessionBackgroundMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: Host? = null
    private var sessionId: String? = null
    private var sessionTitle: String = ""
    private var background = false
    private var stream: SessionStreamClient? = null
    private var streamJob: Job? = null
    private var terminalCheck: Job? = null
    private var generation = 0L

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
        val notification = DshNotifier.taskMonitorNotification(this, currentHost, sid, sessionTitle)
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
            if (host != null && sessionId != null) DshNotifier.cancelTaskMonitor(this, host!!, sessionId!!)
        }
        host = nextHost
        sessionId = sid
        sessionTitle = title
        background = inBackground
        persist(afterSeq)
        startForegroundNow()
        if (background) startReader(afterSeq) else stopReader()
    }

    private fun startReader(afterSeq: Long) {
        val currentHost = host ?: return
        val sid = sessionId ?: return
        background = true
        val startingAt = maxOf(afterSeq, SessionMonitorCursors.get(sid))
        val client = SessionStreamClient(currentHost, sid, serviceScope)
        client.applySnapshotCursor(startingAt)
        stream = client
        streamJob?.cancel()
        generation++
        val readerGeneration = generation
        streamJob = serviceScope.launch {
            client.start()
            for (item in client.items) {
                if (!isActive || readerGeneration != generation) break
                when (item) {
                    is SessionStreamClient.Item.Ready -> {
                        recordSequence(client, sid, item.resumeSeq)
                        reconcilePendingApproval(currentHost, sid)
                    }
                    is SessionStreamClient.Item.Message -> handleMessage(currentHost, sid, client, item)
                    is SessionStreamClient.Item.ResyncRequired -> resync(currentHost, sid, client, readerGeneration)
                    SessionStreamClient.Item.Disconnected -> {
                        if (client.lastFailure == StreamFailure.AUTH) stopMonitoring()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handleMessage(host: Host, sid: String, client: SessionStreamClient, item: SessionStreamClient.Item.Message) {
        recordSequence(client, sid, item.seq)
        when (item.type) {
            "approval/asked" -> postApproval(host, sid, item.data.optString("id"), item.data.optString("toolName", L.toolFallbackName))
            "approval/decided" -> DshNotifier.cancelApproval(this, host, sid)
            "turn/start" -> terminalCheck?.cancel()
            "turn/end" -> scheduleTerminalCheck(host, sid, item.data)
        }
    }

    private fun postApproval(host: Host, sid: String, approvalId: String?, toolName: String) {
        if (approvalId.isNullOrBlank()) {
            DshNotifier.notifyApproval(this, host, sid, toolName)
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            val actionable = runCatching {
                isPendingApprovalActionable(MobileApiClient(host).getSessionRequests(sid), approvalId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (sessionId == sid && background) {
                    DshNotifier.notifyApproval(this@SessionBackgroundMonitorService, host, sid, toolName, approvalId.takeIf { actionable })
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
                    pending != null -> DshNotifier.notifyApproval(this@SessionBackgroundMonitorService, host, sid, pending.toolName ?: L.toolFallbackName, pending.id)
                    waiting -> DshNotifier.notifyApproval(this@SessionBackgroundMonitorService, host, sid, L.toolFallbackName)
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
        stream?.stop()
        stream = null
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
