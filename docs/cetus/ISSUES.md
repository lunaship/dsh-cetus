# 已知问题登记

> 每条包含：现象、复现、影响、状态、关联 PR / 提交。
> 来源：PR #195 正文「没做的」、HANDOFF.md §7、本仓协作记录。

---

## 1. P0 #197：iOS build 挂（ConversationPage.swift 编译错误）

- **现象**：PR #197（iOS 停止按钮接 `session.cancel`）CI 在 "Build Debug and Release" 失败，`(3 failures)`，exit 65。
- **复现**：推 `feat/ios-stop-button` 分支，CI iOS build 必现。
- **影响**：P0 停止按钮无法合并；`feat/ios-stop-button` 分支处于坏状态（编译不过）。
- **状态**：🔴 阻塞中。已修两处（swift-format 4 处行合并+1 超长注释；`CancelTurnFlowTests` 缺 `@MainActor`），仍剩 3 个 `ConversationPage.swift` 编译错误未定位。GitHub Actions 日志需登录查看（匿名 API 返回 "Must have admin rights"；账号 `astrwisp@gmail.com` 不支持密码登录），**等用户从日志复制 3 条 `error:` 行**。
- **关联**：PR [#197](https://github.com/lunaship/dsh-cetus/pull/197)；分支 `feat/ios-stop-button`。

---

## 2. P1 6.2 #201：iOS build 挂（DiffPage 路由重构）

- **现象**：PR #201（6.2 生产路径接 `DiffPage` 路由）CI iOS build 失败。
- **复现**：推 `feat/ios-diffpage-route` 分支，CI iOS build 必现。
- **影响**：6.2 无法合并；盲修 3 轮（@State 改 @Binding、命名冲突、闭包捕获）均未解决。
- **状态**：🔴 阻塞中。与 #1 同因：看不到 CI 具体错误行。待能看日志后再继续。
- **关联**：PR [#201](https://github.com/lunaship/dsh-cetus/pull/201)；分支 `feat/ios-diffpage-route`。

---

## 3. P1 6.1：三范围（本轮/整个会话/未提交）无数据源

- **现象**：设计稿 6.1 要求分段控件「本轮 / 整个会话 / 未提交」，有真实数据才显示。
- **复现**：N/A（功能未实现）。
- **影响**：6.1 分段控件无法做；iOS 做了也是空控件。
- **状态**：🔴 阻塞，需 DSH Host 侧支持。`docs/MOBILE_SYNC_CONTRACT.md` 明确：DSH Host 的 `workspaceChanges` 只存按轮次的数据（每轮一条 `workspace/changes` 事件），"插件只转发与裁剪，不自己做快照"。无"整个会话"聚合接口，无"未提交"（git）数据源，插件无法凭空提供。
- **关联**：HANDOFF.md §7 第 4 项；PR #195「没做的」第 4 条。

---

## 4. Logo PNG 未随 #192 落地（5 张）

- **现象**：PR #192（鲸鱼线稿）合并了矢量/config 侧，但 5 张 PNG 未推送：iOS AppIcon 三套 + `playstore-icon-512` + `appstore-icon-1024`。
- **复现**：N/A。
- **影响**：应用图标在真机/商店仍为旧占位；iOS/Android 构建验证靠 CI，未在真机确认。
- **状态**：🟡 等用户从 Mac 复制。MCP `push_files` 通道传二进制会损坏（base64 存成文本），不能走 MCP 推送。
- **关联**：PR [#192](https://github.com/lunaship/dsh-cetus/pull/192)。

---

## 5. `chat.stop` / `chat.stopFailed` 中文文案暂用英文 fallback

- **现象**：停止按钮的中文读屏文案未落地，暂用英文。
- **复现**：N/A。
- **影响**：中文用户读屏听到英文；功能不受影响。
- **状态**：🟡 待补。`Localizable.xcstrings` 233KB 超出 MCP 推送单文件 128KB 限制，推不上去。
- **关联**：PR #197（P0 停止按钮）。

---

## 6. 设置页「Tailscale 备用」状态未显示

- **现象**：HANDOFF §7 第 3 项验收原写「● 在线 · 局域网 · Tailscale 备用」，实际只显示「● 在线 · 局域网」。
- **复现**：打开设置页，看电脑行。
- **影响**：用户看不到 Tailscale 备用路线状态；不影响主功能。
- **状态**：🟢 已知限制，接受。Tailscale 备用路线状态首页拿不到（`SettingsRouteKind` 只有 `.local` / `.remote`），按"拿不到就不显示"处理。
- **关联**：PR [#199](https://github.com/lunaship/dsh-cetus/pull/199)。

---

## 7. 4.4 状态行未拆分（信息相同，留到下一轮）

- **现象**：设计稿 4.4 要求状态行拆成「● 等你回答」左 +「问题 1/2」右；现在是一行「等你回答 · 第 1 题，共 2 题」。
- **复现**：N/A。
- **影响**：纯排版差异，信息相同。
- **状态**：⚪ 延期。PR #195 明确留到下一轮。
- **关联**：PR #195「没做的」第 3 条。

---

## 8. 7.1 对话默认（未展开）

- **现象**：PR #195「没做的」提到 7.1 对话默认未做（正文截断，未详述）。
- **复现**：N/A。
- **影响**：待评估。
- **状态**：⚪ 待调研。
- **关联**：PR #195「没做的」第 6 条（正文截断）。

---

## 状态图例

- 🔴 阻塞中 — 需要外部输入（用户/Host/CI 日志）才能继续
- 🟡 等待中 — 已知方案，待执行（用户操作或后续 PR）
- 🟢 已知限制 — 接受现状，不阻塞
- ⚪ 延期/待调研 — 排期未定
