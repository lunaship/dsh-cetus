# Goal：DeepLinks 重设计 v4

## 粘贴到 /goal 的目标内容

按 `docs/redesign-v4/PLAN.md` 执行 DeepLinks Android 重设计 v4：从阶段 0 到阶段 4，按 R0.1 → R4 的顺序，一个子项一个分支一个 PR。视觉以 `docs/redesign-v4/design-v4.html`（63 页）为准，规则以 `docs/redesign-v4/visual-rules-v4.md` 为准。阶段 0 的 PR 对 main 开、不合并；其余 PR 对 `redesign/v4` 开，CI 全绿后可 squash 合并到 `redesign/v4`；永远不合并到 main。不加任何新功能；删除省钱/均衡/最强三档。遇到 PLAN 第 1 节列出的停止点就汇报并停下。全部完成的标志是 PLAN 第 0 节 7 条完成标准都满足，并提交最终汇报。

## 建议设置

- 最多轮数：60（阶段 0–2 约 10 轮，阶段 3 每模块 4–8 轮，阶段 4 约 4 轮）。
- 权限：工作区内修改。需要在电脑上跑 Gradle、npm、gh。
- 开始前先把本目录放进仓库：`docs/redesign-v4/`（含 `design-v4.html`、`screens/`、`PLAN.md`、`visual-rules-v4.md`、本文件），由 R0.3 的 PR 提交。

## 给执行智能体的第一条消息（可选，和 goal 一起发）

先通读 `docs/redesign-v4/PLAN.md` 和 `visual-rules-v4.md`，再打开 `screens/*-light.png` / `*-dark.png` 看一遍 63 个页面。
然后只做阶段 0（R0.1、R0.2、R0.3；R0.3 里把本交付包提交到 `docs/redesign-v4/`），开对 main 的 PR，汇报 PR 链接后停下，等我合并再继续。
每个 PR 描述里写清楚：覆盖了哪些页面编号、改了哪些文件、门禁结果、截图对照表。
