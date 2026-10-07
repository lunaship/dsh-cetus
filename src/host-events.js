/**
 * 主机级会话状态 SSE。DSH 没有全局会话事件，只在有订阅者时对 session.list 做差分。
 * 事件只有会话级状态，不带消息正文和工具参数。
 */

const ACTIVE = new Set(["running", "awaitingApproval", "awaitingInput"])
const DEFAULT_BUFFER_LIMIT = 200

export function isActiveHostState(state) {
  return ACTIVE.has(state)
}

export function normalizeHostOrigin(origin) {
  if (origin === "subagent" || origin === "schedule") return origin
  return "user"
}

/** 结束原因映射到通知用的终态。没有原因视为正常完成。 */
export function terminalHostState(stoppedReason) {
  if (stoppedReason === "error") return "failed"
  if (stoppedReason && stoppedReason !== "completed") return "stopped"
  return "completed"
}

export function classifyHostSession(row) {
  const sessionId = typeof row?.sessionId === "string" ? row.sessionId : ""
  const title = typeof row?.title === "string" && row.title.trim() ? row.title.trim() : "未命名会话"
  const origin = normalizeHostOrigin(row?.origin)
  let state
  if (row?.running && row?.pendingApproval) state = "awaitingApproval"
  else if (row?.running && (row?.pendingQuestion || row?.awaitingInput)) state = "awaitingInput"
  else if (row?.running) state = "running"
  else state = terminalHostState(row?.stoppedReason)
  return { sessionId, state, title, origin }
}

/**
 * previous / next 都是 Map<sessionId, classified>。
 * 第一次快照只报仍在进行的会话，避免把历史里已结束的会话刷一遍。
 */
export function diffHostSessions(previous, next, { initial = false } = {}) {
  const events = []
  const seen = new Set()
  for (const [id, row] of next) {
    if (!id) continue
    seen.add(id)
    const prev = previous.get(id)
    if (!prev) {
      if (initial && !isActiveHostState(row.state)) continue
      events.push({ ...row })
      continue
    }
    if (prev.state !== row.state || prev.title !== row.title || prev.origin !== row.origin) {
      events.push({ ...row })
    }
  }
  for (const [id, prev] of previous) {
    if (seen.has(id) || !isActiveHostState(prev.state)) continue
    events.push({ sessionId: id, state: "stopped", title: prev.title, origin: prev.origin })
  }
  return events
}

export function hostEventFrame(event) {
  return `id: ${event.seq}\nevent: session/state\ndata: ${JSON.stringify({
    type: "session/state",
    sessionId: event.sessionId,
    state: event.state,
    title: event.title,
    origin: event.origin,
    seq: event.seq,
  })}\n\n`
}

export function heartbeatFrame() {
  return "event: heartbeat\ndata: {}\n\n"
}

export function resyncFrame(afterSeq) {
  return `event: resync-required\ndata: ${JSON.stringify({ reason: "gap", afterSeq })}\n\n`
}

function eventsAfter(buffer, seq, lastEventId) {
  const id = Number(lastEventId)
  if (!Number.isFinite(id) || id <= 0) return { resync: false, events: [] }
  if (id >= seq) return { resync: false, events: [] }
  if (buffer.length === 0 || buffer[0].seq > id + 1) return { resync: true, events: [] }
  return { resync: false, events: buffer.filter((event) => event.seq > id) }
}

/**
 * @param {{
 *   listSessions: () => Promise<Array<object>>,
 *   terminalReason?: (sessionId: string) => Promise<string|null>,
 *   setIntervalFn?: Function,
 *   clearIntervalFn?: Function,
 *   pollMs?: number,
 *   heartbeatMs?: number,
 * }} options
 */
export function createHostEventHub({
  listSessions,
  terminalReason = null,
  onEvents = null,
  setIntervalFn = setInterval,
  clearIntervalFn = clearInterval,
  pollMs = 5_000,
  heartbeatMs = 25_000,
  bufferLimit = DEFAULT_BUFFER_LIMIT,
} = {}) {
  const subscribers = new Set()
  let previous = new Map()
  let seq = 0
  const buffer = []
  let pollTimer = null
  let heartTimer = null
  let inflight = false

  function remember(event) {
    buffer.push(event)
    if (buffer.length > bufferLimit) buffer.shift()
  }

  function writeOne(conn, frame) {
    try {
      const ok = conn.res.write(frame)
      if (ok === false && (conn.res.writableLength ?? 0) > 4 * 1024 * 1024) drop(conn)
    } catch {
      drop(conn)
    }
  }

  function writeAll(frame) {
    for (const conn of [...subscribers]) writeOne(conn, frame)
  }

  function writeSnapshot(conn) {
    for (const row of previous.values()) {
      if (!row.event || !isActiveHostState(row.state)) continue
      writeOne(conn, hostEventFrame(row.event))
    }
  }

  function drop(conn) {
    subscribers.delete(conn)
    try { conn.res.destroy() } catch {}
    stopTimersIfIdle()
  }

  function startTimers() {
    if (!pollTimer) {
      pollTimer = setIntervalFn(() => { poll().catch(() => {}) }, pollMs)
      pollTimer?.unref?.()
    }
    if (!heartTimer) {
      heartTimer = setIntervalFn(() => heartbeat(), heartbeatMs)
      heartTimer?.unref?.()
    }
  }

  function stopTimersIfIdle() {
    if (subscribers.size > 0) return
    if (pollTimer) clearIntervalFn(pollTimer)
    if (heartTimer) clearIntervalFn(heartTimer)
    pollTimer = null
    heartTimer = null
  }

  function heartbeat() {
    if (subscribers.size === 0) return
    writeAll(heartbeatFrame())
  }

  async function poll() {
    if (subscribers.size === 0 || inflight) return { polled: false }
    inflight = true
    try {
      const rows = await listSessions()
      const next = new Map()
      for (const row of rows ?? []) {
        const classified = classifyHostSession(row)
        if (!classified.sessionId) continue
        next.set(classified.sessionId, classified)
      }
      const initial = previous.size === 0
      const changes = diffHostSessions(previous, next, { initial })
      for (const change of changes) {
        const prev = previous.get(change.sessionId)
        if (change.state === "completed" && prev && isActiveHostState(prev.state) && terminalReason) {
          try {
            change.state = terminalHostState(await terminalReason(change.sessionId))
          } catch {
            // 读不到结束原因就保持 completed
          }
        }
      }
      const stamped = []
      for (const change of changes) {
        seq += 1
        const event = { type: "session/state", ...change, seq }
        remember(event)
        const stored = next.get(change.sessionId) ?? { ...change }
        stored.event = event
        stored.state = change.state
        next.set(change.sessionId, stored)
        stamped.push(event)
      }
      for (const [id, row] of next) {
        if (!row.event && previous.get(id)?.event) row.event = previous.get(id).event
      }
      previous = next
      for (const event of stamped) writeAll(hostEventFrame(event))
      if (stamped.length > 0 && onEvents) {
        try { await onEvents(stamped) } catch {}
      }
      return { polled: true, emitted: stamped.length }
    } finally {
      inflight = false
    }
  }

  function subscribe(res, lastEventId) {
    const conn = { res }
    const first = subscribers.size === 0
    subscribers.add(conn)
    if (first) startTimers()
    const replay = eventsAfter(buffer, seq, lastEventId)
    if (replay.resync) {
      writeOne(conn, resyncFrame(Number(lastEventId) || 0))
      writeSnapshot(conn)
    } else if (replay.events.length > 0) {
      for (const event of replay.events) writeOne(conn, hostEventFrame(event))
    } else {
      writeSnapshot(conn)
    }
    if (first) poll().catch(() => {})
    return () => {
      subscribers.delete(conn)
      stopTimersIfIdle()
    }
  }

  function stop() {
    for (const conn of [...subscribers]) {
      try { conn.res.end() } catch {}
    }
    subscribers.clear()
    stopTimersIfIdle()
  }

  return {
    subscribe,
    poll,
    heartbeat,
    stop,
    get subscriberCount() { return subscribers.size },
    get polling() { return pollTimer != null },
  }
}

export function handleHostEvents(req, res, hub) {
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-store, no-cache, must-revalidate",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  })
  res.flushHeaders?.()
  try { res.socket?.setNoDelay?.(true) } catch {}
  const last = req?.headers?.["last-event-id"]
  const unsubscribe = hub.subscribe(res, last)
  res.on("close", unsubscribe)
}
