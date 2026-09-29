# 复验速查：四项未验项怎么补（等你回桌边时用）

前提：手机能插 USB 或与电脑同一 Wi-Fi。全程**不动你的数据**（隔离实例用临时 `stateDir`）、
**不花额度**（只发读/建会话类调用；要造审批才用到一次极小任务）。

工作树：`/Volumes/Space/Dev/dsh-links-redesign`（分支 `redesign/inbox`）。Android 侧命令都在
`apps/android/` 下，且需要 `export ANDROID_HOME="$HOME/Library/Android/sdk"`。

---

## 0. 判断落在哪个基线（先做这个）

```bash
export PATH="$HOME/.nvm/versions/node/v24.21.0/bin:$PATH"; dsh --version
```

- `0.1.5-rc.3`（CLI 自带）→ **04/06 在本机仍验不了**，需要「把手机配到你的桌面 host」（下面 §3）。
- 若哪天 CLI 也升到 `0.1.7-alpha.1` → 直接走 §1，全程可隔离。

---

## 1. 起隔离实例（不碰你的 host 与 state）

```bash
cat > /tmp/isolated.yml <<'EOF'
- id: dsh-links
  config:
    stateDir: /tmp/link-scratch
    port: 19441
EOF
export PATH="$HOME/.nvm/versions/node/v24.21.0/bin:$PATH"
DSH_HOME=~/.dsh dsh --profile redesign-smoke --patch /tmp/isolated.yml \
  --host 127.0.0.1 --port 19400 --no-open        # 后台跑，日志里会给 web token
```

- 面板端点（取配对码/指纹）：`curl -s -H "Origin: http://127.0.0.1:19400" http://127.0.0.1:19400/dsh-link/pair-info`
- 注意：`/dsh-link/pair-info` 在 **web 端口**上，不在插件端口；必须带同源 `Origin`。

## 2. 手机/模拟器配对（手动配对的两个坑）

1. 坐标**必须**用 `adb shell uiautomator dump` 取，别按截图比例估（我估错过 300px）；
2. 表单用 **TAB（`input keyevent 61`）**逐格跳，别连点——软键盘弹出会把表单顶上去，后续点击全落空；
3. 地址填宿主可达的地址（模拟器用 `10.0.2.2:19441`，真机用局域网 IP）；
4. TOFU 会弹证书核对，指纹应与 `pair-info` 的 `certFingerprint` **逐字一致**（不一致就别继续）。

## 3. 04 / 14 看改动（真机）

需要宿主宣告 `capabilities.files.changes`：

```bash
TOKEN=<设备 token>; B=https://<host>:<port>/dsh-link/mobile
curl -sk -H "x-dsh-link-token: $TOKEN" "$B/bootstrap" | grep -o '"changes":[a-z]*'
```

- 有 `changes:true` → 在 App 里打开一个**工作区有未提交改动**的会话 → 从流里的改动卡进入看改动，
  核对：文件条 `1/N`、只显新行号、未改动段折叠、长行**悬挂缩进**、底部提问条。
- 没有 → App **正确地隐藏**入口（这是按能力位降级，不是 bug）。rc.3 的 web profile 就没有。

*快速造现场*（隔离实例上）：

```bash
mkdir -p /tmp/link-demo && cd /tmp/link-demo && git init -q && echo demo > README.md
git -c user.email=d@l -c user.name=d add -A && git -c user.email=d@l -c user.name=d commit -qm baseline
# 注册工作区（手机侧提交 → 面板批准）：POST $B/workspaces {"path":"/tmp/link-demo"}
#   → GET  http://127.0.0.1:19400/dsh-link/workspace-approvals 拿 requestId
#   → POST http://127.0.0.1:19400/dsh-link/workspace-approve {"requestId":...,"approve":true}
# 建会话 POST $B/sessions {"cwd":"/private/tmp/link-demo"}，再 POST $B/sessions/<id>/prompt {"text":"改一个已跟踪文件"}
```

## 4. 06 通知的锁屏批准/拒绝（真机）

**先确认「手机接管」是否可达**（这是关键前提）：

```bash
# 在插件钩子入口临时插一行写文件（查完即删），重启实例后发一条必然要审批的任务（写工作区外）：
#   用 bash 在 /usr/local/xxx 写一行
# 若探针文件始终为空 → 接管不可达 → 06 的动作路径无从验，只能记「实现齐全、未验」
```

可达时的步骤：App 打开该会话（订阅）→ 触发审批 → **先切后台再触发**（通知才有机会出现）→
下拉通知栏核对两个动作（批准需解锁 / 拒绝不需）→ 点「允许一次」→ 服务端 `awaitingInput` 消失且
目标文件真的落地。

`awaitingInput` 自检（不经 App）：`curl -sk -H "x-dsh-link-token: $TOKEN" $B/sessions | grep awaitingInput`
——等审批时应为 `true`（走历史 `approval/asked` 无配对 `approval/decided` 推导）。

## 5. 11 平板整页

平板或大窗口折叠形态跑一遍：左栏常驻 390、正文与输入区封顶 760 居中、宽屏隐藏返回键、
新任务居中弹层。截图墙已有宽屏版（`1024dp`）。

---

## 收尾（每次都做）

```bash
pkill -f "profile redesign-smoke"; rm -rf /tmp/link-scratch /tmp/link-demo /tmp/isolated.yml
"$ANDROID_HOME/platform-tools/adb" emu kill      # 模拟器
```

演示会话若要清掉：在 App 里 ⋯ →「删除会话」（服务端即归档，可在设置 → 已归档会话恢复/删除）。
