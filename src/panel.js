/**
 * dsh-cetus 客户端面 · 面板模块（作为 createPanelModule 工厂被主模块组合调用）
 * 「手机连接」：一张连接码（局域网 + 远程）、远程连接（DLP/1）、已配对手机。
 *
 * 版式跟随 DSH 设置页：扁平行 + 0.5px 分隔线，不套卡片；颜色全部取宿主的 --dsw-alias-* 变量
 * （浅色 / 深色随宿主切换），只在宿主缺变量时用括号里的浅色兜底。
 * 强调色只给需要用户动手的东西：批准、启用；状态点用绿 / 黄 / 红表达就绪 / 进行中 / 需处理。
 */
const createPanelModule = (require) => {
  const React = require('react')
  const h = React.createElement

  // ─── 文案与格式 ──────────────────────────────────────────────────────────

  function seenLabel(lastSeenAt) {
    if (!lastSeenAt) return '还没连过'
    const d = Date.now() - lastSeenAt
    if (d < 6e4) return '刚刚在线'
    if (d < 36e5) return `${d / 6e4 | 0} 分钟前在线`
    if (d < 864e5) return `${d / 36e5 | 0} 小时前在线`
    return `${d / 864e5 | 0} 天前在线`
  }

  function formatCode(code) {
    const raw = String(code ?? '').trim()
    if (raw.length === 6) return `${raw.slice(0, 3)} ${raw.slice(3)}`
    return raw || '—'
  }

  function formatCountdown(ms) {
    const s = Math.max(0, Math.ceil(ms / 1000))
    return `${s / 60 | 0}:${String(s % 60).padStart(2, '0')}`
  }

  /** Agent 的 lastError 是错误码（见 src/remote/agent.js describeSocketError）；这里翻成人话。 */
  function remoteErrorText(code) {
    const c = String(code ?? '')
    if (!c) return ''
    if (c === 'ENOTFOUND' || c === 'EAI_AGAIN') return '找不到中继服务器（域名解析失败）'
    if (c === 'ECONNREFUSED') return '中继服务器拒绝连接'
    if (c === 'ECONNRESET' || c === 'EPIPE') return '与中继的连接被中断'
    if (c === 'ETIMEDOUT' || c === 'TIMEOUT') return '连接中继超时'
    if (c === 'ENETUNREACH' || c === 'EHOSTUNREACH') return '网络不通，连不到中继'
    if (c === 'OUTER_PIN_MISMATCH') return '中继证书与填写的指纹不符'
    if (/^(CERT_|ERR_TLS_|UNABLE_TO_|DEPTH_ZERO|SELF_SIGNED)/.test(c)) return '中继证书校验失败'
    const http = /^HTTP_(\d{3})$/.exec(c)
    if (http) return `中继返回 HTTP ${http[1]}，地址可能不对`
    if (c === 'RATE_LIMITED') return '中继限流中，稍后自动重试'
    if (c === 'SERVER_BUSY') return '中继繁忙，稍后自动重试'
    if (c === 'AUTH_FAILED' || /refused registration/.test(c)) return '中继拒绝了这台电脑的注册'
    if (c === 'UNSUPPORTED_VERSION') return '中继协议版本不兼容'
    if (/^[A-Z0-9_]+$/.test(c)) return `连不上中继（${c}）`
    return c
  }

  function remoteSummary(remote) {
    if (!remote || remote.state === 'off') return { tone: 'idle', text: '未开启' }
    if (remote.state === 'ready') return { tone: 'ok', text: '已就绪' }
    if (remote.state === 'error') return { tone: 'danger', text: remoteErrorText(remote.error) || '出错了' }
    // connecting 带错误 = Agent 在按退避重连；说清楚它会自己恢复
    return { tone: 'warn', text: remote.error ? `${remoteErrorText(remote.error)}，正在重试` : '正在连接中继…' }
  }

  // ─── 样式 ────────────────────────────────────────────────────────────────

  const STYLE = `
    .dl-root {
      --dl-text: var(--dsw-alias-label-primary, #0f1115);
      --dl-text-2: var(--dsw-alias-label-secondary, #61666b);
      --dl-text-3: var(--dsw-alias-label-tertiary, #81858c);
      --dl-caption: var(--dsw-alias-label-caption, #adb2b8);
      --dl-line: var(--dsw-alias-border-l2, rgba(0, 0, 0, 0.1));
      --dl-line-strong: var(--dsw-alias-border-l3, rgba(0, 0, 0, 0.12));
      --dl-bg: var(--dsw-alias-bg-layer-1, #ffffff);
      --dl-hover: var(--dsw-alias-interactive-bg-hover, rgba(38, 49, 72, 0.06));
      --dl-active: var(--dsw-alias-interactive-bg-active, rgba(38, 49, 72, 0.1));
      --dl-primary: var(--dsw-alias-button-primary-fill, #0f1115);
      --dl-primary-hover: var(--dsw-alias-button-primary-hover, #43454a);
      --dl-on-primary: var(--dsw-alias-label-primary-foreground, var(--dsw-alias-label-primary-inverted, #ffffff));
      --dl-ok: var(--dsw-alias-state-success-primary, #22c55e);
      --dl-warn: var(--dsw-alias-state-warn-primary, #f59e0b);
      --dl-warn-text: var(--dsw-alias-state-warn-label, #dd8629);
      --dl-warn-soft: var(--dsw-alias-state-warn-tertiary, #fef5e7);
      --dl-danger: var(--dsw-alias-state-error-primary, #ec1313);
      --dl-danger-hover: var(--dsw-alias-interactive-bg-hover-danger, rgba(236, 19, 19, 0.05));
      --dl-link: var(--dsw-alias-link, #4176e6);
      --dl-mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      color: var(--dl-text);
      font-family: var(--dsw-font-family, -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif);
      font-size: 14px; line-height: 22px;
      -webkit-font-smoothing: antialiased;
    }
    .dl-root { min-width: 0; }
    .dl-root *, .dl-root *::before, .dl-root *::after { box-sizing: border-box; }
    .dl-settings { max-width: 480px; }

    /* 头部 */
    .dl-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 4px; }
    .dl-head-title { margin: 0; font-size: 16px; line-height: 24px; font-weight: 600; }
    .dl-head-sub { margin: 2px 0 0; font-size: 12px; line-height: 18px; color: var(--dl-text-3); }
    .dl-pill { flex: none; display: inline-flex; align-items: center; gap: 6px; margin-top: 2px;
      font-size: 12px; line-height: 18px; color: var(--dl-text-2); white-space: nowrap; }

    /* 状态点 */
    .dl-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--dl-caption); }
    .dl-dot[data-tone="ok"] { background: var(--dl-ok); }
    .dl-dot[data-tone="warn"] { background: var(--dl-warn); }
    .dl-dot[data-tone="danger"] { background: var(--dl-danger); }
    .dl-dot[data-tone="warn"][data-live] { animation: dl-pulse 1.4s ease-in-out infinite; }
    @keyframes dl-pulse { 50% { opacity: 0.35 } }

    /* 分组：小标题 + 扁平行 */
    .dl-group { padding-top: 20px; }
    .dl-group-head { display: flex; align-items: center; justify-content: space-between; gap: 12px;
      padding-bottom: 4px; border-bottom: 0.5px solid var(--dl-line); }
    .dl-group-title { margin: 0; font-size: 12px; line-height: 18px; font-weight: 500; color: var(--dl-text-3); }
    .dl-group-count { color: var(--dl-caption); font-variant-numeric: tabular-nums; margin-left: 6px; }
    .dl-row { display: flex; align-items: center; gap: 12px; padding: 14px 0; border-bottom: 0.5px solid var(--dl-line); }
    .dl-row-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    .dl-row-title { font-size: 14px; line-height: 22px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .dl-row-title.is-wrap { white-space: normal; overflow-wrap: anywhere; }
    .dl-row-desc { font-size: 12px; line-height: 18px; color: var(--dl-text-3); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .dl-row-desc .dl-sep { color: var(--dl-caption); }
    .dl-row-desc.is-danger { color: var(--dl-danger); }
    .dl-row-desc.is-warn { color: var(--dl-warn-text); }
    .dl-row-actions { flex: none; display: flex; align-items: center; gap: 6px; }
    .dl-row.is-attention { position: relative; padding-left: 12px; }
    .dl-row.is-attention::before { content: ""; position: absolute; left: 0; top: 14px; bottom: 14px; width: 2px; border-radius: 2px; background: var(--dl-warn); }
    .dl-path { font-family: var(--dl-mono); font-size: 12.5px; }

    /* 按钮 */
    .dl-btn { appearance: none; cursor: pointer; font: inherit; font-size: 13px; line-height: 20px; font-weight: 500;
      border-radius: 999px; padding: 5px 14px; border: 0.5px solid var(--dl-line-strong); background: transparent; color: var(--dl-text);
      transition: background-color 0.12s, border-color 0.12s, color 0.12s; white-space: nowrap; }
    .dl-btn:hover:not(:disabled) { background: var(--dl-hover); }
    .dl-btn:active:not(:disabled) { background: var(--dl-active); }
    .dl-btn:disabled { opacity: 0.45; cursor: default; }
    .dl-btn:focus-visible { outline: 2px solid var(--dl-link); outline-offset: 2px; }
    .dl-btn.is-primary { background: var(--dl-primary); border-color: transparent; color: var(--dl-on-primary); }
    .dl-btn.is-primary:hover:not(:disabled) { background: var(--dl-primary-hover); }
    .dl-btn.is-quiet { border-color: transparent; color: var(--dl-text-2); padding-left: 10px; padding-right: 10px; }
    .dl-btn.is-danger-text { border-color: transparent; color: var(--dl-danger); padding-left: 0; padding-right: 0; }
    .dl-btn.is-danger-text:hover:not(:disabled) { background: transparent; text-decoration: underline; }
    .dl-btn.is-revoke:hover:not(:disabled) { color: var(--dl-danger); background: var(--dl-danger-hover); border-color: transparent; }
    .dl-link { appearance: none; border: 0; background: none; padding: 0; font: inherit; font-size: 12px; line-height: 18px;
      color: var(--dl-link); cursor: pointer; }
    .dl-link:hover { text-decoration: underline; }
    .dl-link.is-danger { color: var(--dl-danger); margin-left: 4px; }
    .dl-foot { margin: 0; padding: 10px 0 0; font-size: 12px; line-height: 18px; color: var(--dl-text-3); }

    /* 开关 */
    .dl-switch { position: relative; flex: none; display: inline-flex; cursor: pointer; }
    .dl-switch input { position: absolute; inset: 0; opacity: 0; margin: 0; cursor: pointer; }
    .dl-switch-track { width: 36px; height: 22px; border-radius: 999px; background: var(--dl-line-strong); transition: background-color 0.18s; position: relative; }
    .dl-switch-track::after { content: ""; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%;
      background: #ffffff; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2); transition: transform 0.18s cubic-bezier(0.2, 0.8, 0.2, 1); }
    .dl-switch input:checked + .dl-switch-track { background: var(--dl-primary); }
    .dl-switch input:checked + .dl-switch-track::after { transform: translateX(14px); background: var(--dl-on-primary); }
    .dl-switch input:disabled + .dl-switch-track { opacity: 0.45; }
    .dl-switch input:focus-visible + .dl-switch-track { outline: 2px solid var(--dl-link); outline-offset: 2px; }

    /* 连接码 */
    .dl-pair { display: flex; flex-direction: column; align-items: flex-start; gap: 12px;
      padding: 16px 0; border-bottom: 0.5px solid var(--dl-line); }
    .dl-qr { appearance: none; cursor: zoom-in; width: 168px; height: 168px; padding: 9px; border-radius: 12px;
      border: 0.5px solid var(--dl-line-strong); background: #ffffff; display: block; transition: transform 0.15s; }
    .dl-status-line { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; line-height: 20px; color: var(--dl-text); }
    .dl-status-line[data-tone="danger"] { color: var(--dl-danger); }
    .dl-more { margin-top: 8px; }
    .dl-more > summary { cursor: pointer; font-size: 13px; line-height: 32px; color: var(--dl-text-2); }
    .dl-qr:hover { transform: scale(1.015); }
    .dl-qr:focus-visible { outline: 2px solid var(--dl-link); outline-offset: 2px; }
    .dl-qr img { display: block; width: 100%; height: 100%; }
    .dl-pair-meta { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .dl-code-row { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
    .dl-code { font-family: var(--dl-mono); font-size: 24px; line-height: 30px; font-weight: 600; letter-spacing: 0.08em; font-variant-numeric: tabular-nums; }
    .dl-copy { appearance: none; border: 0; background: none; padding: 0; cursor: pointer; font: inherit; font-size: 12px; line-height: 18px; color: var(--dl-text-3); }
    .dl-copy:hover { color: var(--dl-text); }
    .dl-copy.is-done { color: var(--dl-ok); }
    .dl-routes { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 12px; line-height: 18px; color: var(--dl-text-2); }
    .dl-route { display: inline-flex; align-items: center; gap: 6px; }
    .dl-route[data-off] { color: var(--dl-caption); }
    .dl-pair-hint { margin: 0; font-size: 12px; line-height: 18px; color: var(--dl-text-3); }
    .dl-expiry { font-variant-numeric: tabular-nums; }
    .dl-zoom { position: fixed; inset: 0; z-index: 99999; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px;
      background: var(--dsw-alias-bg-mask-1, rgba(0, 0, 0, 0.5)); cursor: zoom-out; }
    .dl-zoom img { width: min(72vmin, 360px); height: auto; padding: 16px; border-radius: 16px; background: #ffffff; }
    .dl-zoom span { color: #ffffff; font-size: 12px; opacity: 0.85; }

    /* 远程设置表单 */
    .dl-form { display: flex; flex-direction: column; gap: 10px; padding: 14px 0; border-bottom: 0.5px solid var(--dl-line); }
    .dl-field-label { font-size: 12px; line-height: 18px; color: var(--dl-text-2); }
    .dl-field { width: 100%; font: inherit; font-size: 13px; line-height: 20px; padding: 7px 12px; border-radius: 10px;
      border: 0.5px solid var(--dl-line-strong); background: var(--dl-bg); color: var(--dl-text); }
    .dl-field::placeholder { color: var(--dl-caption); }
    .dl-field:focus { outline: none; border-color: var(--dl-text-3); box-shadow: 0 0 0 3px var(--dl-hover); }
    .dl-form-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .dl-form-error { margin: 0; font-size: 12px; line-height: 18px; color: var(--dl-danger); }
    .dl-note { margin: 0; font-size: 12px; line-height: 18px; color: var(--dl-text-3); }
    .dl-banner { margin: 12px 0 0; padding: 10px 12px; border-radius: 10px; font-size: 12px; line-height: 18px;
      background: var(--dl-warn-soft); color: var(--dl-warn-text); }
    .dl-banner.is-danger { background: var(--dl-danger-hover); color: var(--dl-danger); }

    .dl-empty { padding: 18px 0; font-size: 12px; line-height: 18px; color: var(--dl-text-3); border-bottom: 0.5px solid var(--dl-line); }
    .dl-code-tag { font-family: var(--dl-mono); font-size: 11px; line-height: 16px; color: var(--dl-caption); }
    .dl-status { padding: 12px 0; font-size: 13px; color: var(--dl-text-3); }
    .dl-status.is-error { color: var(--dl-danger); }

    @media (max-width: 420px) {
      .dl-pair { grid-template-columns: 1fr; justify-items: center; text-align: center; }
      .dl-pair-meta { align-items: center; }
      .dl-code-row, .dl-routes { justify-content: center; }
    }
    @media (prefers-reduced-motion: reduce) {
      .dl-dot[data-live] { animation: none; }
      .dl-qr, .dl-switch-track, .dl-switch-track::after { transition: none; }
    }
  `

  // ─── 小组件 ──────────────────────────────────────────────────────────────

  function Dot({ tone, live }) {
    return h('span', { className: 'dl-dot', 'data-tone': tone, 'data-live': live ? '' : undefined, 'aria-hidden': true })
  }

  function Switch({ checked, disabled, onChange, label }) {
    return h('label', { className: 'dl-switch' },
      h('input', { type: 'checkbox', role: 'switch', checked: Boolean(checked), disabled, 'aria-label': label, onChange: (e) => onChange(e.target.checked) }),
      h('span', { className: 'dl-switch-track', 'aria-hidden': true }),
    )
  }

  function Row({ title, titleWrap, desc, descTone, actions, attention, children }) {
    return h('div', { className: 'dl-row' + (attention ? ' is-attention' : '') },
      h('div', { className: 'dl-row-text' },
        title ? h('div', { className: 'dl-row-title' + (titleWrap ? ' is-wrap' : '') }, title) : null,
        desc ? h('div', { className: 'dl-row-desc' + (descTone ? ` is-${descTone}` : '') }, desc) : null,
        children,
      ),
      actions ? h('div', { className: 'dl-row-actions' }, actions) : null,
    )
  }

  function Group({ title, count, action, children }) {
    return h('section', { className: 'dl-group' },
      h('div', { className: 'dl-group-head' },
        h('h3', { className: 'dl-group-title' }, title, count ? h('span', { className: 'dl-group-count' }, count) : null),
        action ?? null,
      ),
      children,
    )
  }

  /** 行描述里用「·」串起来的几段。 */
  function joinParts(parts) {
    const out = []
    parts.filter(Boolean).forEach((part, i) => {
      if (i) out.push(h('span', { className: 'dl-sep', key: `s${i}`, 'aria-hidden': true }, '·'))
      out.push(h(React.Fragment, { key: `p${i}` }, part))
    })
    return out
  }

  function useNow(active) {
    const [now, setNow] = React.useState(Date.now())
    React.useEffect(() => {
      if (!active) return undefined
      const t = setInterval(() => setNow(Date.now()), 1000)
      return () => clearInterval(t)
    }, [active])
    return now
  }

  // ─── 头部 ────────────────────────────────────────────────────────────────

  function Header() {
    return h('div', { className: 'dl-head' },
      h('div', null,
        h('h2', { className: 'dl-head-title' }, '手机连接'),
      ),
    )
  }

  // ─── 待处理 ──────────────────────────────────────────────────────────────

  function AttentionGroup({ devices, workspaceApprovals, actions }) {
    const pending = (devices ?? []).filter((d) => d.status === 'pending')
    const approvals = workspaceApprovals ?? []
    if (!pending.length && !approvals.length) return null
    return h(Group, { title: '需要你处理', count: pending.length + approvals.length },
      pending.map((d) => h(Row, {
        key: d.deviceId,
        attention: true,
        title: `「${d.name}」请求配对`,
        desc: joinParts([
          d.via === 'remote' ? '经远程首次配对，确认是你自己的手机再批准' : (d.pairedFrom ? `来自 ${d.pairedFrom}` : '局域网'),
          d.replacing ? '批准后替换同名旧设备' : null,
        ]),
        actions: [
          h('button', { key: 'ok', type: 'button', className: 'dl-btn is-primary', onClick: () => actions.approve(d.deviceId) }, '批准'),
          h('button', { key: 'no', type: 'button', className: 'dl-btn is-quiet', onClick: () => actions.revoke(d, { silent: true }) }, '拒绝'),
        ],
      })),
      approvals.map((a) => h(Row, {
        key: a.requestId,
        attention: true,
        // 批准前要看清完整路径：换行，不截断
        title: h('span', { className: 'dl-path' }, a.path),
        titleWrap: true,
        desc: `${a.deviceName || '手机'} 想添加这个工作区`,
        actions: [
          h('button', { key: 'ok', type: 'button', className: 'dl-btn is-primary', onClick: () => actions.approveWorkspace(a.requestId) }, '批准'),
          h('button', { key: 'no', type: 'button', className: 'dl-btn is-quiet', onClick: () => actions.rejectWorkspace(a.requestId) }, '拒绝'),
        ],
      })),
    )
  }

  // ─── 连接码 ──────────────────────────────────────────────────────────────

  function connectionLine(remote) {
    const summary = remoteSummary(remote)
    if (!remote || remote.state === 'off') return { tone: 'idle', text: '未开启' }
    if (remote.state === 'connecting' || summary.tone === 'warn') return { tone: 'warn', live: true, text: summary.text || '连接中' }
    if (remote.state === 'ready') return { tone: 'ok', text: '已就绪' }
    return { tone: 'danger', text: summary.text || '失败' }
  }

  function PairGroup({ info, remote, onExpired }) {
    const [zoom, setZoom] = React.useState(false)
    const now = useNow(Boolean(info.expiresAt))
    const left = info.expiresAt ? info.expiresAt - now : null
    const expired = left !== null && left <= 0
    React.useEffect(() => { if (expired) onExpired() }, [expired, onExpired])

    const code = info.pairingCode || ''
    const remoteReady = remote?.state === 'ready'
    // 远程状态变化时换图：同一张配对码，就绪前后编进去的内容不同
    const stamp = `${code}:${info.expiresAt ?? ''}:${remoteReady ? 'r' : 'l'}`
    const src = `/dsh-link/qr.png?v=${encodeURIComponent(stamp)}`
    const line = connectionLine(remote)

    return h(React.Fragment, null,
      h('div', { className: 'dl-pair' },
        h('button', { type: 'button', className: 'dl-qr', onClick: () => setZoom(true), title: '点击放大', 'aria-label': '放大连接码' },
          h('img', { key: stamp, src, alt: '手机连接码' })),
        h('div', { className: 'dl-status-line', 'data-tone': line.tone, role: 'status' },
          h(Dot, { tone: line.tone === 'idle' ? undefined : line.tone, live: line.live }),
          line.text,
        ),
        left !== null ? h('p', { className: 'dl-pair-hint' },
          h('span', { className: 'dl-expiry' }, expired ? '正在换新码…' : `${formatCountdown(left)} 后换新码`),
        ) : null,
      ),
      zoom ? h('div', { className: 'dl-zoom', role: 'button', tabIndex: -1, onClick: () => setZoom(false) },
        h('img', { src, alt: '手机连接码' }),
        h('span', null, '此码可添加新设备，别截图外传 · 点任意处关闭')) : null,
    )
  }

  // ─── 远程连接 ────────────────────────────────────────────────────────────

  function RelayForm({ remote, busy, error, onSubmit, onCancel, submitLabel }) {
    const [endpoint, setEndpoint] = React.useState(remote?.official ? '' : (remote?.endpoint ?? ''))
    const [pin, setPin] = React.useState(remote?.outerPin ?? '')
    const [advanced, setAdvanced] = React.useState(Boolean(remote?.outerPin))
    const submit = (e) => {
      e.preventDefault()
      onSubmit({ endpoint: endpoint.trim(), outerPin: pin.trim() })
    }
    return h('form', { className: 'dl-form', onSubmit: submit },
      h('label', { className: 'dl-field-label', htmlFor: 'dl-endpoint' }, '中继地址'),
      h('input', {
        id: 'dl-endpoint', className: 'dl-field', value: endpoint, autoComplete: 'off', spellCheck: false,
        placeholder: 'wss://relay.example.com/ws（留空用官方中继）', onChange: (e) => setEndpoint(e.target.value),
      }),
      advanced
        ? h(React.Fragment, null,
            h('label', { className: 'dl-field-label', htmlFor: 'dl-pin' }, '中继证书指纹（可选，自签证书时填）'),
            h('input', {
              id: 'dl-pin', className: 'dl-field', value: pin, autoComplete: 'off', spellCheck: false,
              placeholder: '64 位 SHA-256', onChange: (e) => setPin(e.target.value),
            }))
        : h('div', null, h('button', { type: 'button', className: 'dl-link', onClick: () => setAdvanced(true) }, '中继用的是自签证书？')),
      error ? h('p', { className: 'dl-form-error', role: 'alert' }, error) : null,
      h('div', { className: 'dl-form-actions' },
        h('button', { type: 'submit', className: 'dl-btn is-primary', disabled: busy }, busy ? '正在连接…' : submitLabel),
        onCancel ? h('button', { type: 'button', className: 'dl-btn is-quiet', disabled: busy, onClick: onCancel }, '取消') : null,
      ),
    )
  }

  function testResultText(result) {
    if (!result) return ''
    if (result.error) return result.error
    if (result.ok) return `通了：连中继 ${result.outerMs} ms，会合 ${result.rendezvousMs} ms，加密握手 ${result.innerTlsMs} ms`
    const stage = { outer: '连不上中继', rendezvous: '中继没把连接转给电脑', inner: '加密握手失败（证书不符）' }[result.failedStage]
    return `没通：${stage ?? '未知原因'}`
  }

  function RemoteGroup({ info, remote, actions, extra }) {
    const [editing, setEditing] = React.useState(false)
    const [busy, setBusy] = React.useState(false)
    const [error, setError] = React.useState('')
    const [testing, setTesting] = React.useState(false)
    const [test, setTest] = React.useState(null)
    // 启用请求要等首次注册（最多 15 秒）：这段时间先按「连接中」显示，别让开关看起来没反应
    const [enabling, setEnabling] = React.useState(false)
    if (!remote) return null
    const on = remote.enabled || enabling
    const summary = enabling ? { tone: 'warn', text: '正在连接中继…' } : remoteSummary(remote)

    const enable = async (body) => {
      setBusy(true)
      setEnabling(true)
      setError('')
      try {
        const r = await actions.remoteEnable(body)
        if (!r.ok && r.error && r.state !== 'connecting') setError(r.error)
        else setEditing(false)
      } catch (err) {
        setError(String(err?.message ?? err))
      } finally {
        setBusy(false)
        setEnabling(false)
      }
    }
    const toggle = async (next) => {
      setError('')
      setTest(null)
      if (next) return enable({ endpoint: remote.endpoint, outerPin: remote.outerPin })
      setBusy(true)
      try { await actions.remoteDisable() } finally { setBusy(false) }
    }
    const runTest = async () => {
      setTesting(true)
      setTest(null)
      try { setTest(await actions.remoteTest()) } catch (err) { setTest({ error: String(err?.message ?? err) }) } finally { setTesting(false) }
    }
    const reset = async () => {
      if (!window.confirm('重置远程身份？\n\n所有手机的远程凭据立即作废。它们回到局域网后会自动拿到新凭据；在外面的手机要重新扫码。')) return
      setBusy(true)
      try { await actions.remoteReset() } finally { setBusy(false) }
    }

    const failed = on && (remote.state === 'error' || summary.tone === 'danger')
    return h(React.Fragment, null,
      h(Row, {
        title: '外出时也能连',
        desc: failed ? summary.text : null,
        descTone: failed ? 'danger' : null,
        actions: h(Switch, { checked: on, disabled: busy, onChange: toggle, label: '外出时也能连' }),
      }),
      !editing && error ? h('p', { className: 'dl-banner is-danger', role: 'alert' }, error) : null,
      remote.replaced ? h('p', { className: 'dl-banner', role: 'alert' },
        '另一处正以这台电脑的身份连着中继，常见于两个 DSH 配置共用了插件状态目录。给其中一个配置单独设置 stateDir 即可。') : null,
      h('details', { className: 'dl-more' },
        h('summary', null, '更多'),
        h(Row, {
          title: '新手机要在这里批准',
          desc: '开启后，扫码的手机要在这台电脑上点「批准」才能用。经远程首次配对的手机始终需要批准。',
          actions: h(Switch, { checked: info.requireConfirm, onChange: onRequireConfirm, label: '新手机要在这里批准' }),
        }),
        editing ? h(RelayForm, {
          remote, busy, error,
          submitLabel: '更换并连接',
          onSubmit: enable,
          onCancel: () => { setEditing(false); setError('') },
        }) : h(Row, {
          title: '自建中继',
          desc: remote.endpoint || '未设置',
          actions: [
            h('button', { key: 't', type: 'button', className: 'dl-btn is-quiet', disabled: testing || remote.state !== 'ready', onClick: runTest }, testing ? '测试中…' : '测试'),
            h('button', { key: 'e', type: 'button', className: 'dl-btn', disabled: busy, onClick: () => { setEditing(true); setError(''); setTest(null) } }, '更换'),
          ],
        }, test ? h('div', { className: 'dl-row-desc' + (test.ok ? '' : ' is-danger'), role: 'status' },
          h(Dot, { tone: test.ok ? 'ok' : 'danger' }), testResultText(test)) : null),
        h('p', { className: 'dl-foot' },
          h('button', { type: 'button', className: 'dl-link is-danger', disabled: busy, onClick: reset }, '重置远程身份')),
        extra,
      ),
    )
  }

  // ─── 已配对手机 ──────────────────────────────────────────────────────────

  /** 设备能走哪些路；返回分段，由 joinParts 统一加分隔点。 */
  function deviceRoute(device, remote) {
    if (device.via === 'relay') return ['旧版云端配对，已停用']
    if (device.remote && remote?.enabled) return ['局域网', '远程']
    if (remote?.enabled) return ['局域网', '下次连上时自动开通远程']
    return ['局域网']
  }

  function DevicesGroup({ devices, remote, actions }) {
    const paired = (devices ?? []).filter((d) => d.status !== 'pending')
    if (!paired.length) return null
    return h(Group, {
      title: '已配对手机',
      count: paired.length,
      action: paired.length > 1 ? h('button', { type: 'button', className: 'dl-btn is-quiet', onClick: actions.revokeAll }, '全部吊销') : null,
    },
      paired.map((d) => h(Row, {
            key: d.deviceId || d.name,
            title: d.name,
            desc: joinParts([...deviceRoute(d, remote), seenLabel(d.lastSeenAt), d.push?.enabled ? '推送：已开启' : '推送：未开启']),
            descTone: d.via === 'relay' ? 'warn' : null,
            actions: h(React.Fragment, null,
              d.push?.enabled ? h('button', { type: 'button', className: 'dl-btn is-quiet', onClick: () => actions.disablePush(d) }, '关闭推送') : null,
              h('button', { type: 'button', className: 'dl-btn is-quiet is-revoke', onClick: () => actions.revoke(d) }, '吊销'),
            ),
          })),
    )
  }

  function panelLocale() {
    try {
      return /^zh/i.test(navigator.language || '') ? 'zh' : 'en'
    } catch {
      return 'zh'
    }
  }

  const DIAG_COPY = {
    zh: {
      group: '连接诊断',
      button: '一键检查',
      running: '检查中…',
      hint: '连不上时，点一次就能看到卡在哪一步。',
      days: (n) => `剩余 ${n} 天`,
      count: (n) => `${n} 台已配对`,
      pending: (n) => `${n} 台待批准`,
      on: '在',
      off: '不在',
      service: { workspaceChanges: '改动', typertGateway: '网关', sessions: '会话' },
      listen: (d) => `私网 ${d.private} · Tailscale ${d.tailnet} · 其他 ${d.other}`,
      check: {
        'host.rpc': '本机接口',
        'host.services': '本机服务',
        'plugin.version': '插件版本',
        'tls.cert': '连接证书',
        'pairing.devices': '已配对手机',
        'listen.addresses': '监听地址',
        'remote.relay': '远程中继',
        clock: '电脑时间',
      },
      code: {
        HOST_RPC_OK: '本机接口正常',
        HOST_RPC_SLOW: '本机接口偏慢',
        HOST_RPC_TIMEOUT: '本机接口超时',
        HOST_RPC_FAILED: '本机接口失败',
        HOST_RPC_UNAVAILABLE: '本机接口不可用',
        HOST_SERVICES_OK: '需要的服务都在',
        HOST_SERVICES_PARTIAL: '改动服务没挂上，其余正常',
        HOST_SERVICES_MISSING: '会话或网关服务没挂上',
        HOST_SERVICES_UNAVAILABLE: '读不到本机服务',
        PLUGIN_VERSION_OK: '插件版本正常',
        PLUGIN_VERSION_UNKNOWN: '读到协议号，但没有版本字符串',
        PLUGIN_VERSION_INVALID: '插件协议号无效',
        TLS_CERT_OK: '证书有效',
        TLS_CERT_EXPIRING: '证书不到 30 天就要过期',
        TLS_CERT_EXPIRED: '证书已过期，需要重新配对',
        TLS_CERT_MISSING: '还没有连接证书',
        TLS_CERT_UNKNOWN_EXPIRY: '读不到证书到期时间',
        PAIRING_OK: '有已配对的手机',
        PAIRING_NONE: '还没有配对的手机',
        PAIRING_UNAVAILABLE: '读不到配对列表',
        PAIRING_SELF_OK: '这台设备有效',
        PAIRING_SELF_INVALID: '这台设备无效',
        PAIRING_SELF_LEGACY: '这台设备状态异常',
        LISTEN_OK: '有手机能用的局域网或 Tailscale 地址',
        LISTEN_NONE: '没有可用的监听地址',
        LISTEN_NO_LAN: '只有本机地址，手机连不上',
        LISTEN_UNTRUSTED: '只看到公网地址，确认网段可信再用',
        LISTEN_UNAVAILABLE: '读不到监听地址',
        REMOTE_DISABLED: '远程连接已关闭',
        REMOTE_READY: '中继已连上',
        REMOTE_CONNECTING: '正在连接中继',
        REMOTE_REJECTED: '中继拒绝或连不上',
        REMOTE_REPLACED: '中继被另一处同身份连接顶掉',
        REMOTE_DOWN: '远程已开启，但中继没连上',
        CLOCK_OK: '电脑时间正常',
        CLOCK_UNREASONABLE: '电脑时间看起来不对',
        CLOCK_INVALID: '读不到电脑时间',
      },
    },
    en: {
      group: 'Connection check',
      button: 'Run check',
      running: 'Checking…',
      hint: 'When a phone cannot connect, run this once to see which step failed.',
      days: (n) => `${n} days left`,
      count: (n) => `${n} paired`,
      pending: (n) => `${n} waiting`,
      on: 'up',
      off: 'down',
      service: { workspaceChanges: 'changes', typertGateway: 'gateway', sessions: 'sessions' },
      listen: (d) => `private ${d.private} · Tailscale ${d.tailnet} · other ${d.other}`,
      check: {
        'host.rpc': 'Host API',
        'host.services': 'Host services',
        'plugin.version': 'Plugin version',
        'tls.cert': 'Certificate',
        'pairing.devices': 'Paired phones',
        'listen.addresses': 'Listen addresses',
        'remote.relay': 'Remote relay',
        clock: 'Host clock',
      },
      code: {
        HOST_RPC_OK: 'Host API is responding',
        HOST_RPC_SLOW: 'Host API is slow',
        HOST_RPC_TIMEOUT: 'Host API timed out',
        HOST_RPC_FAILED: 'Host API failed',
        HOST_RPC_UNAVAILABLE: 'Host API is unavailable',
        HOST_SERVICES_OK: 'Required services are mounted',
        HOST_SERVICES_PARTIAL: 'Change tracking is missing; the rest is up',
        HOST_SERVICES_MISSING: 'Sessions or the gateway is not mounted',
        HOST_SERVICES_UNAVAILABLE: 'Could not read host services',
        PLUGIN_VERSION_OK: 'Plugin version looks fine',
        PLUGIN_VERSION_UNKNOWN: 'Protocol is present, version string is not',
        PLUGIN_VERSION_INVALID: 'Plugin protocol is invalid',
        TLS_CERT_OK: 'Certificate is valid',
        TLS_CERT_EXPIRING: 'Certificate expires in under 30 days',
        TLS_CERT_EXPIRED: 'Certificate has expired; pair again',
        TLS_CERT_MISSING: 'No connection certificate yet',
        TLS_CERT_UNKNOWN_EXPIRY: 'Could not read the certificate expiry',
        PAIRING_OK: 'At least one phone is paired',
        PAIRING_NONE: 'No phone is paired',
        PAIRING_UNAVAILABLE: 'Could not read the pairing list',
        PAIRING_SELF_OK: 'This device is valid',
        PAIRING_SELF_INVALID: 'This device is not valid',
        PAIRING_SELF_LEGACY: 'This device status is unusual',
        LISTEN_OK: 'A LAN or Tailscale address is available',
        LISTEN_NONE: 'No listen address is available',
        LISTEN_NO_LAN: 'Only loopback is available; phones cannot connect',
        LISTEN_UNTRUSTED: 'Only public addresses were found',
        LISTEN_UNAVAILABLE: 'Could not read listen addresses',
        REMOTE_DISABLED: 'Remote connection is off',
        REMOTE_READY: 'Relay is connected',
        REMOTE_CONNECTING: 'Connecting to the relay',
        REMOTE_REJECTED: 'The relay refused the connection or is unreachable',
        REMOTE_REPLACED: 'Another connection with the same host identity replaced this one',
        REMOTE_DOWN: 'Remote is on, but the relay is not connected',
        CLOCK_OK: 'Host clock looks fine',
        CLOCK_UNREASONABLE: 'Host clock looks wrong',
        CLOCK_INVALID: 'Could not read the host clock',
      },
    },
  }

  function diagTone(status) {
    if (status === 'ok') return 'ok'
    if (status === 'warn') return 'warn'
    if (status === 'fail') return 'danger'
    return undefined
  }

  function diagFacts(check, copy) {
    const detail = check.detail || {}
    const facts = []
    if (typeof detail.ms === 'number') facts.push(`${detail.ms} ms`)
    if (typeof detail.daysRemaining === 'number') facts.push(copy.days(detail.daysRemaining))
    if (typeof detail.fingerprintPrefix === 'string') facts.push(detail.fingerprintPrefix)
    if (typeof detail.version === 'string') facts.push(detail.version)
    if (typeof detail.protocol === 'number') facts.push(`p${detail.protocol}`)
    if (typeof detail.count === 'number') facts.push(copy.count(detail.count))
    if (typeof detail.pending === 'number' && detail.pending > 0) facts.push(copy.pending(detail.pending))
    if (typeof detail.total === 'number') facts.push(copy.listen(detail))
    if (typeof detail.lastCode === 'string') facts.push(detail.lastCode)
    const services = ['workspaceChanges', 'typertGateway', 'sessions'].filter((key) => typeof detail[key] === 'boolean')
    if (services.length) {
      facts.push(services.map((key) => `${copy.service[key]} ${detail[key] ? copy.on : copy.off}`).join(', '))
    }
    return facts
  }

  function DiagnosticsGroup() {
    const copy = DIAG_COPY[panelLocale()] || DIAG_COPY.zh
    const [phase, setPhase] = React.useState('idle')
    const [report, setReport] = React.useState(null)
    const [error, setError] = React.useState('')
    const run = async () => {
      setPhase('running')
      setError('')
      try {
        const res = await fetch('/dsh-link/diagnostics')
        const data = await res.json().catch(() => ({}))
        if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`)
        setReport(data)
        setPhase('done')
      } catch (err) {
        setPhase('error')
        setError(String(err?.message ?? err))
      }
    }
    const checks = Array.isArray(report?.checks) ? report.checks : []
    return h(Group, {
      title: copy.group,
      action: h('button', {
        type: 'button',
        className: 'dl-btn',
        disabled: phase === 'running',
        onClick: run,
      }, phase === 'running' ? copy.running : copy.button),
    },
      phase === 'idle' ? h('div', { className: 'dl-empty' }, copy.hint) : null,
      phase === 'error' ? h('p', { className: 'dl-banner is-danger', role: 'alert' }, error) : null,
      checks.map((item) => h(Row, {
        key: item.id,
        title: copy.check[item.id] || item.id,
        desc: joinParts([
          copy.code[item.code] || item.code,
          ...diagFacts(item, copy),
          h('span', { className: 'dl-code-tag' }, item.code),
        ]),
        descTone: item.status === 'fail' ? 'danger' : item.status === 'warn' ? 'warn' : null,
        actions: h(Dot, { tone: diagTone(item.status) }),
      })),
    )
  }

  function PreviewGroup({ previews, detected, actions }) {
    const [port, setPort] = React.useState('')
    const [label, setLabel] = React.useState('')
    const [error, setError] = React.useState('')
    const [busy, setBusy] = React.useState(false)
    const rows = previews ?? []
    const hints = detected ?? []
    const now = useNow(rows.length > 0)
    const submit = (e) => {
      e.preventDefault()
      setBusy(true)
      setError('')
      actions.approvePreview({ port: Number(port), label: label.trim() })
        .then(() => { setPort(''); setLabel('') })
        .catch((err) => setError(String(err?.message ?? err)))
        .finally(() => setBusy(false))
    }
    return h(Group, { title: '预览端口' },
      h('form', { className: 'dl-form', onSubmit: submit },
        h('label', { className: 'dl-field-label', htmlFor: 'dl-preview-port' }, '端口'),
        h('input', {
          id: 'dl-preview-port', className: 'dl-field', inputMode: 'numeric', value: port, autoComplete: 'off',
          placeholder: '5173', onChange: (e) => setPort(e.target.value),
        }),
        h('label', { className: 'dl-field-label', htmlFor: 'dl-preview-label' }, '名称'),
        h('input', {
          id: 'dl-preview-label', className: 'dl-field', value: label, maxLength: 80, autoComplete: 'off',
          placeholder: '例如 Vite', onChange: (e) => setLabel(e.target.value),
        }),
        error ? h('p', { className: 'dl-form-error', role: 'alert' }, error) : null,
        h('div', { className: 'dl-form-actions' },
          h('button', {
            type: 'submit', className: 'dl-btn is-primary',
            disabled: busy || !port.trim() || !label.trim(),
          }, busy ? '正在添加…' : '添加'),
        ),
        h('p', { className: 'dl-note' }, '只转发到本机 127.0.0.1，默认 2 小时后失效。SSH 和数据库端口不能添加。手机上不能批准。'),
      ),
      hints.map((item) => h(Row, {
        key: `${item.sessionId}:${item.port}`,
        title: `:${item.port}`,
        desc: `会话 ${String(item.sessionId).slice(0, 8)} · 请在这台电脑上批准`,
        actions: h('button', {
          type: 'button', className: 'dl-btn is-primary',
          onClick: () => {
            actions.approvePreview({ port: item.port, label: `开发服务器 :${item.port}` })
              .catch((err) => setError(String(err?.message ?? err)))
          },
        }, '批准'),
      })),
      rows.length === 0
        ? h('div', { className: 'dl-empty' }, '还没有批准的预览端口。')
        : rows.map((item) => h(Row, {
          key: item.previewId,
          title: item.label,
          desc: joinParts([
            `127.0.0.1:${item.port}`,
            `剩余 ${formatCountdown((item.expiresAt ?? now) - now)}`,
          ]),
          actions: h('button', {
            type: 'button', className: 'dl-btn is-revoke',
            onClick: () => actions.revokePreview(item.previewId),
          }, '撤销'),
        })),
    )
  }

  // ─── 数据 ────────────────────────────────────────────────────────────────

  async function postJson(path, body) {
    const res = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body ?? {}) })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`)
    return data
  }

  function usePanelData(active) {
    const [data, setData] = React.useState({ info: null, devices: [], workspaceApprovals: [], remote: null, previews: [], detected: [] })
    const [err, setErr] = React.useState('')
    // pair-info 503(proxy_not_ready) = HTTPS 还没 listen；有限重试后给出明确失败
    const [starting, setStarting] = React.useState(false)
    const startRetries = React.useRef(0)

    const load = React.useCallback(async () => {
      try {
        const resInfo = await fetch('/dsh-link/pair-info')
        if (resInfo.status === 503) {
          const body = await resInfo.json().catch(() => ({}))
          if (body?.error === 'proxy_not_ready') {
            if (body.phase === 'failed') throw new Error('手机连接启动失败，HTTPS 端口没能就绪。请看 DSH 日志或重启 DSH。')
            if (startRetries.current >= 20) throw new Error('手机连接启动超时。请看 DSH 日志或重启 DSH。')
            startRetries.current += 1
            setStarting(true)
            setErr('')
            return
          }
        }
        if (!resInfo.ok) throw new Error(`HTTP ${resInfo.status}`)
        startRetries.current = 0
        const [resDevices, resApprovals, resRemote, resPreviews] = await Promise.all([
          fetch('/dsh-link/devices'), fetch('/dsh-link/workspace-approvals'), fetch('/dsh-link/remote-status'), fetch('/dsh-link/previews'),
        ])
        if (!resDevices.ok || !resApprovals.ok || !resPreviews.ok) throw new Error(`HTTP ${resDevices.status}/${resApprovals.status}`)
        const [info, devices, approvals, remote, previewBody] = await Promise.all([
          resInfo.json(), resDevices.json(), resApprovals.json(), resRemote.ok ? resRemote.json() : null, resPreviews.json(),
        ])
        setData({ info, devices: devices.devices ?? [], workspaceApprovals: approvals.approvals ?? [], remote, previews: previewBody.previews ?? [], detected: previewBody.detected ?? [] })
        setStarting(false)
        setErr('')
      } catch (e) {
        setStarting(false)
        setErr(String(e?.message ?? e))
      }
    }, [])

    const pending = data.devices.filter((d) => d.status === 'pending').length + data.workspaceApprovals.length
    const connecting = data.remote?.state === 'connecting'
    React.useEffect(() => {
      if (!active) return undefined
      load()
      const t = setInterval(load, starting ? 1500 : (pending || connecting ? 2000 : 8000))
      return () => clearInterval(t)
    }, [active, load, pending, connecting, starting])

    const after = (p) => p.finally(load)
    const actions = {
      approve: (deviceId) => after(postJson('/dsh-link/pair-approve', { deviceId }).catch(() => {})),
      revoke: (device, { silent } = {}) => {
        if (!silent && !window.confirm(`吊销「${device.name}」？这台手机要重新扫码才能再连。`)) return undefined
        return after(postJson('/dsh-link/revoke', device.deviceId ? { deviceId: device.deviceId } : { name: device.name }).catch(() => {}))
      },
      disablePush: (device) => after(postJson('/dsh-link/push/disable', { deviceId: device.deviceId }).catch(() => {})),
      revokeAll: () => {
        if (!window.confirm('吊销全部已配对手机？它们都要重新扫码。')) return undefined
        return after(postJson('/dsh-link/revoke-all', {}).catch(() => {}))
      },
      approveWorkspace: (requestId) => after(postJson('/dsh-link/workspace-approve', { requestId }).catch(() => {})),
      rejectWorkspace: (requestId) => after(postJson('/dsh-link/workspace-reject', { requestId }).catch(() => {})),
      setRequireConfirm: (requireConfirm) => after(postJson('/dsh-link/pair-settings', { requireConfirm }).catch(() => {})),
      remoteEnable: (body) => after(postJson('/dsh-link/remote-enable', body)),
      remoteDisable: () => after(postJson('/dsh-link/remote-disable', {})),
      remoteTest: () => postJson('/dsh-link/remote-test', {}),
      remoteReset: () => after(postJson('/dsh-link/remote-reset-identity', { confirm: true })),
      approvePreview: (body) => postJson('/dsh-link/previews', body).finally(load),
      revokePreview: (previewId) => after(postJson('/dsh-link/previews/revoke', { previewId }).catch(() => {})),
    }
    return { ...data, err, starting, load, actions }
  }

  // ─── 组装 ────────────────────────────────────────────────────────────────

  function PanelBody(pair) {
    const { info, devices, workspaceApprovals, remote, previews, detected, err, starting, load, actions } = pair
    if (starting) return h('div', { className: 'dl-status' }, '手机连接正在启动…')
    if (err) return h('div', { className: 'dl-status is-error' }, `加载失败：${err}`)
    if (!info) return h('div', { className: 'dl-status' }, '加载中…')
    return h(React.Fragment, null,
      h(AttentionGroup, { devices, workspaceApprovals, actions }),
      h(PairGroup, { info, remote, onExpired: load }),
      h(RemoteGroup, {
        info, remote, actions,
        extra: h(React.Fragment, null,
          h(DiagnosticsGroup, {}),
          h(PreviewGroup, { previews, detected, actions }),
        ),
      }),
      h(DevicesGroup, { devices, remote, actions }),
    )
  }

  function Panel({ active }) {
    const pair = usePanelData(active)
    return h(React.Fragment, null,
      h(Header, {}),
      h(PanelBody, pair),
    )
  }

  function DshLinkSettingsSection() {
    return h(React.Fragment, null,
      h('style', { 'data-plugin': 'dsh-cetus' }, STYLE),
      h('div', { className: 'dl-settings dl-root' }, h(Panel, { active: true })),
    )
  }

  function apply(ctx) {
    ctx.effect(
      () =>
        ctx.slots.inject(
          'settings.section',
          () =>
            ctx.slots.register(
              {
                name: 'settings.section',
                id: 'dsh-cetus',
                order: 25,
                label: () => '手机连接',
                locale: 'dsh-cetus',
              },
              () => h(DshLinkSettingsSection, {}),
            ),
        ),
      'dsh-cetus: settings.section',
    )
  }

  return { apply, inject: ['slots'] }
}
