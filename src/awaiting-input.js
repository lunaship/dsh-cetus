/**
 * 「等待确认」计数：审批与澄清问题只要还没结束，这个会话就在等人——不论请求由手机接管
 * 还是交给电脑端网页。DSH 的 session.list 不带这个状态，插件在两个 waterfall 钩子外层记账，
 * 会话摘要据此下发 `awaitingInput`，手机首页把它放进「等待确认」分区。
 */
export function createAwaitingInput() {
  const counts = new Map()

  function release(sessionId) {
    const next = (counts.get(sessionId) ?? 1) - 1
    if (next <= 0) counts.delete(sessionId)
    else counts.set(sessionId, next)
  }

  return {
    /** 计数包住整个钩子处理：同步抛错也会立刻释放，结果原样透传。 */
    track(sessionId, run) {
      if (!sessionId) return run()
      counts.set(sessionId, (counts.get(sessionId) ?? 0) + 1)
      let result
      try {
        result = run()
      } catch (err) {
        release(sessionId)
        throw err
      }
      return Promise.resolve(result).finally(() => release(sessionId))
    },
    has(sessionId) {
      return (counts.get(sessionId) ?? 0) > 0
    },
  }
}
