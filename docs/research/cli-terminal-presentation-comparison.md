# Codex CLI、Claude Code CLI 与 Pi CLI 的终端展示机制调研

> 状态：外部工具研究，不是 ADR，也不定义 Termestra 当前行为  
> 调研日期：2026-08-24  
> 范围：交互式终端中的进度、推理摘要、工具调用、最终答复、折叠与重绘；以及可供
> Termestra 集成的结构化接口。本文不包含功能实现。

## 结论先行

三款 CLI 都没有在默认交互模式中严格实现“工作中展示全部过程，成功后删除所有过程文案，
只留下最终答复”。它们最值得借鉴的共同点不是 ANSI 清屏，而是：

1. provider 输出先进入结构化事件或消息模型；
2. 运行中过程、已完成历史和最终结果是不同的 UI 投影；
3. delta 只负责实时体验，权威 completed/result/message_end 负责校正；
4. 只有明确的成功终态才允许切换到最终答复视图；
5. 已经写进原生 terminal scrollback 的文本不能可靠按 Turn 撤回。

对用户目标的接近程度：

| 工具 | 交互模式完成后的实际效果 | 最接近目标的能力 | 严格 final-only |
| --- | --- | --- | --- |
| Claude Code | 默认折叠 thinking 和工具详情，保留摘要与最终答复 | fullscreen `/focus`：最后 prompt、单行工具摘要、最终答复 | 否，仍保留 prompt 和工具摘要 |
| Pi | thinking 和工具可手动折叠，完成后仍留在组件树 | RPC 自定义 UI；`pi -p` 仅输出最终文本 | 交互 TUI 否；自定义 UI 可实现 |
| Codex CLI | commentary、工具和完成后的 reasoning summary 留在 scrollback，final 追加在末尾 | App Server 的 `commentary` / `final_answer` / Turn 终态 | CLI TUI 否；自定义 UI 可实现 |

若 Termestra 要严格达到目标，推荐建立独立 Conversation/Turn UI，通过各 CLI 的结构化接口
驱动；原始 xterm 保留为高级终端或诊断视图。不要解析人类可读 TUI 文案，也不要尝试从
PTY scrollback 中事后删除字符。

## 1. 调研范围、版本与证据边界

本文把用户所说的 “code cli” 按 **Codex CLI** 理解。

- Codex：官方 `openai/codex` 仓库提交
  [`76d98a7`](https://github.com/openai/codex/commit/76d98a771e6cd44a79a3ab895a9f7c49d27d6deb)，
  本机 `codex-cli 0.147.0`。
- Claude Code：官方文档与官方发行物；本机版本 `2.1.76`，调研日 npm 最新版本为
  `2.1.241`，因此 fullscreen 的新行为不应假定旧版本全部具备。
- Pi：本机 `0.84.1` 是 `@earendil-works/pi-coding-agent`，不是旧包名
  `@mariozechner/pi-coding-agent`。原 `badlogic/pi-mono` 当前重定向到
  `earendil-works/pi`；本文以本机同源后继版本为主，并用历史 v0.73.0 核验继承行为。

Claude Code 的完整应用源码未公开。官方 changelog 能证明 Ink 输出路径，Ink 官方仓库也将
Claude Code 列为使用者，但无法据此断言当前 fullscreen renderer 的全部内部算法。因此
本文只把官方公开行为和可确认的渲染栈写作事实，不采用非官方反编译仓库作为依据。

## 2. Codex CLI

### 2.1 用户实际看到什么

Codex CLI 的主聊天不是“完成后只留最终答复”的界面：

- reasoning 流式阶段主要用短标题更新底部 shimmer/status；
- reasoning item 完成后，可读 summary 以弱化、斜体样式进入历史；
- commentary 与 final answer 使用同一 assistant Markdown 流式管线；
- commentary 完成后恢复“仍在工作”的状态，但 commentary 本身通常已经提交到历史；
- `turn/completed` 停止 spinner、清理临时活动状态并确保 final 已完成渲染，不会删除本 Turn
  已提交的 commentary、工具或 reasoning summary。

源码证据见
[`chatwidget/streaming.rs`](https://github.com/openai/codex/blob/76d98a771e6cd44a79a3ab895a9f7c49d27d6deb/codex-rs/tui/src/chatwidget/streaming.rs#L232-L365)、
[`turn_runtime.rs`](https://github.com/openai/codex/blob/76d98a771e6cd44a79a3ab895a9f7c49d27d6deb/codex-rs/tui/src/chatwidget/turn_runtime.rs#L104-L224)。

### 2.2 渲染是“可变活动区 + 不可逆 scrollback”

Codex TUI 使用 Rust、Ratatui 和 Crossterm。`ChatWidget` 将协议事件归约为 UI 状态，
`HistoryCell` 表示用户、assistant、reasoning、命令、MCP、patch 等展示单元，
`StreamController` 处理 Markdown delta 与完成校正。

主聊天采用 inline viewport：当前 active cell 和 bottom pane 会重绘；完成的 cell 则通过 ANSI
scroll-region 操作写入 terminal 自身的 scrollback。已提交内容不再作为普通 Ratatui viewport
反复全量绘制。[主聊天渲染](https://github.com/openai/codex/blob/76d98a771e6cd44a79a3ab895a9f7c49d27d6deb/codex-rs/tui/src/chatwidget/rendering.rs#L20-L90)、
[history 插入](https://github.com/openai/codex/blob/76d98a771e6cd44a79a3ab895a9f7c49d27d6deb/codex-rs/tui/src/app/history_ui.rs#L23-L65)

这正是它不尝试“按 Turn 删除过程”的原因：过程一旦进入原生 scrollback，应用无法稳定地
只撤回其中几行。Codex 的 alternate screen 主要用于 transcript、diff、审批详情等 overlay；
`--no-alt-screen` 只禁用这些 alternate-screen overlay，不会把主聊天改成可任意删除的虚拟列表。

`/clear` 或 `Ctrl+L` 可以清可见屏幕、scrollback 和内存 transcript，但这是清除整个会话
展示，不是完成后只删除某一 Turn 的过程。

### 2.3 结构化语义比 CLI 视觉更值得复用

Codex App Server 明确区分：

- `AgentMessage.phase = commentary | final_answer`；
- `Reasoning.summary` 与 raw `Reasoning.content`；
- `item/started`、delta、权威 `item/completed`；
- `turn/completed` 的 `completed | interrupted | failed` 终态。

见 [Codex App Server 官方文档](https://learn.chatgpt.com/docs/app-server) 和
[协议类型源码](https://github.com/openai/codex/blob/76d98a771e6cd44a79a3ab895a9f7c49d27d6deb/codex-rs/app-server-protocol/src/protocol/v2/item.rs#L227-L345)。

Codex 流式 Markdown 会保留未结束 tail、平滑提交完整行、积压时 catch-up，并在
`item/completed` 到达时以完整文本校正 delta。这个“临时流 + 权威完成态”的模式适合复用，
终端 scrollback 策略不适合复用。

非交互 `codex exec` 则把进度写入 `stderr`，只把最终 agent message 写入 `stdout`；
`--json` 提供 JSONL 事件。但当前 exec 的公开 `AgentMessageItem` 不保留 phase，若产品必须
可靠区分 commentary 和 final answer，应优先使用 App Server v2。
[Codex 非交互模式](https://learn.chatgpt.com/docs/non-interactive-mode)

## 3. Claude Code CLI

### 3.1 三种 transcript 投影

Claude Code 当前官方界面提供 `default`、`verbose` 和 `focus` 三种 `viewMode`：

| 模式 | 可见内容 |
| --- | --- |
| default | thinking 折叠；工具调用折叠或聚合；assistant 正文完整显示 |
| verbose | 详细工具执行；可用的 thinking summary 以灰色斜体显示 |
| focus | 最后一次 prompt、单行工具摘要与 edit diffstat、最终答复 |

`Ctrl+O` 切换详细 transcript；`/focus` 只适用于 fullscreen renderer，并可跨 session 保持。
它是三个工具中最接近用户目标的现成效果，但仍不是严格 final-only。
[Claude Code fullscreen](https://code.claude.com/docs/en/fullscreen)、
[interactive mode](https://code.claude.com/docs/en/interactive-mode)

Extended thinking 默认折叠，显示时是 reasoning summary，而不是对完整内部思维链的承诺；
summary 还可能被 redacted 或省略。产品文案应称“工作进度”或“推理摘要”。
[Extended thinking](https://code.claude.com/docs/en/model-config#extended-thinking)

### 3.2 Classic 与 fullscreen 的关键差别

Claude Code 有两类终端表面：

- classic/main-screen：内容进入终端原生 scrollback，可用终端搜索和 tmux copy mode，但旧文本
  无法可靠撤回；
- fullscreen：使用 alternate screen，输入框固定在底部，只把可见消息保留在 render tree，
  应用自己管理虚拟滚动、搜索、auto-follow、鼠标与折叠。

所以 `/focus` 不是执行清屏，而是把同一权威 transcript 切换成另一种投影。`Ctrl+L`/`Cmd+K`
只重绘；`/clear` 才开始新 conversation。官方还允许把完整 transcript 临时写回原生 scrollback
或交给编辑器查看。[Fullscreen rendering](https://code.claude.com/docs/en/fullscreen)

### 3.3 正式完成信号是 `result`

Agent SDK 的 assistant message 内容是 `thinking`、`redacted_thinking`、`text`、`tool_use`
等块的数组。一次 agent loop 可以产生多条 assistant message，所以看到 `text` 或某条
`end_turn` 都不应直接视为整个任务完成。

程序化宿主应等待 `SDKResultMessage`：

- `subtype=success` 带最终 `result`；
- 错误 subtype 区分 max turns、执行错误、预算和结构化输出重试耗尽等；
- `terminal_reason` 进一步区分 completed、aborted、tool deferred、hook stopped、model error 等。

[Agent SDK 类型](https://code.claude.com/docs/en/agent-sdk/typescript)、
[agent loop 完成语义](https://code.claude.com/docs/en/agent-sdk/agent-loop#handle-the-result)

`claude -p --output-format stream-json --verbose --include-partial-messages` 会输出 NDJSON 增量，
最后的 `type=result` 才提供 final、cost 和 session metadata。SIGTERM 等无 result 的退出必须
标成 interrupted/uncertain，不能把过程清空成成功。
[Headless/stream-json](https://code.claude.com/docs/en/headless#stream-responses)

## 4. Pi CLI

### 4.1 交互 TUI 的默认行为

Pi 的 assistant message 是 `text`、`thinking`、`toolCall` 有序内容块，不是 provider 原始字符。
交互 UI 将普通文本渲染为 Markdown，thinking 用弱色斜体，工具调用用独立组件。

- `Ctrl+T` 折叠/展开 thinking；折叠后仍显示静态 `Thinking...`；
- `Ctrl+O` 全局展开/折叠工具输出；
- `message_end` 后组件仍留在 chat 容器，不会自动隐藏 thinking；
- `agent_end` 只清运行状态和异常残留的 streaming component。

[AssistantMessageComponent](https://github.com/earendil-works/pi/blob/53fa77ccd8a279eb87e92294ef3687b03ff80112/packages/coding-agent/src/modes/interactive/components/assistant-message.ts#L89-L169)、
[交互事件处理](https://github.com/earendil-works/pi/blob/53fa77ccd8a279eb87e92294ef3687b03ff80112/packages/coding-agent/src/modes/interactive/interactive-mode.ts#L3121-L3284)

因此 Pi 内置交互模式是“可手动折叠”，不是“完成后自动 final-only”。`pi -p` 会等待完成，
取最后一条 assistant message，只输出其中的 text blocks；它能得到最终文本，但运行中没有
可视过程。[print mode](https://github.com/earendil-works/pi/blob/53fa77ccd8a279eb87e92294ef3687b03ff80112/packages/coding-agent/src/modes/print-mode.ts#L131-L157)

### 4.2 组件树 + 行数组差分重绘

Pi 的 TUI 先让整个组件树 `render(width)` 得到 `string[]`，再和上次屏幕比较，只输出变化行
所需的 ANSI 控制序列：

```text
结构化事件更新组件状态
  → component.render(width)
  → 新旧 line/screen diff
  → ANSI synchronized output
```

`requestRender()` 会合并重复请求并以最小 16ms 间隔限频；regular/main-screen renderer
比较新旧行，fullscreen renderer 比较固定 viewport 的 `previousScreen`。输出包在 DEC
synchronized output 序列中以减少撕裂。

[TUI 调度](https://github.com/earendil-works/pi/blob/53fa77ccd8a279eb87e92294ef3687b03ff80112/packages/tui/src/tui.ts#L772-L823)、
[main-screen diff](https://github.com/earendil-works/pi/blob/53fa77ccd8a279eb87e92294ef3687b03ff80112/packages/tui/src/tui-main-screen.ts#L180-L320)、
[fullscreen diff](https://github.com/earendil-works/pi/blob/53fa77ccd8a279eb87e92294ef3687b03ff80112/packages/tui/src/tui-alt-screen.ts#L1252-L1319)

技术上，只要应用层在完成时从组件树移除过程组件，renderer 就能清掉旧行；Pi 只是没有选择
这一产品行为。对 Web UI，可复用“结构化状态 + animation-frame 合并”思路，无需复制 ANSI
renderer。

### 4.3 `agent_settled` 才是当前版本的稳定态

当前 0.84.1 事件序列大致是：

```text
agent_start
  turn_start
    message_start → message_update* → message_end
    tool_execution_start/update*/end
  turn_end
  …可能 retry、compaction、queued continuation…
agent_end
agent_settled
```

`message_update` 是 delta，客户端用 `contentIndex` 累计；`message_end.message` 是权威消息。
`agent_end` 后仍可能自动 retry/compact/continue，`agent_settled` 才表示不会再自动继续。
[Pi RPC events](https://github.com/earendil-works/pi/blob/53fa77ccd8a279eb87e92294ef3687b03ff80112/packages/coding-agent/docs/rpc.md#L832-L956)

历史 v0.73.0 尚无 `agent_settled`，所以集成必须按实际版本做 capability detection。settled 也
只代表稳定，不代表成功；还需检查最后 assistant message 的 `stopReason`。

Pi 的 `--mode rpc` 以 stdin/stdout JSONL 双向交互，最适合外部 UI；`--mode json` 适合一次性
日志或管道。RPC command response 的 request `id` 不会自动附到所有后续 agent events，
Termestra 仍需补自己的稳定 Turn correlation。

## 5. 横向比较：效果背后的实现

| 维度 | Codex CLI | Claude Code CLI | Pi CLI |
| --- | --- | --- | --- |
| UI 模型 | active cell + history cells + bottom pane | 权威 transcript 的 default/verbose/focus 投影 | message/tool 组件树 |
| 主聊天表面 | inline viewport + 原生 scrollback | classic scrollback 或 fullscreen alternate screen | regular 或 fullscreen 差分 renderer |
| 实时更新 | Markdown tail、稳定行 FIFO、完成校正 | structured block/delta；fullscreen 虚拟投影 | contentIndex delta 更新组件，约 60fps 合并 |
| thinking 展示 | 流式时短状态；完成后 summary history cell | 默认折叠，verbose 显示 summary | Ctrl+T；隐藏后保留 `Thinking...` |
| 工具展示 | 独立 history/active cells | 默认折叠/聚合，verbose 或点击展开 | 独立 ToolExecutionComponent，Ctrl+O |
| 最终完成信号 | `turn/completed` + final item | `SDKResultMessage` | `agent_settled` + stopReason |
| 完成后自动删除过程 | 否 | 否；focus 仅弱化为摘要 | 否 |
| 最佳集成接口 | App Server v2 | Agent SDK 或 `stream-json` | RPC |

三者共同说明：终端渲染技术只决定“能否重画”，结构化协议才决定“应该删什么、何时能删”。

## 6. Termestra 的推荐实现

### 6.1 不在 raw PTY 上做最终回答提取

若所有 provider 字节仍直接写进 xterm：

- 无法稳定识别 commentary、thinking、tool 和 final；
- 无法把一次用户输入与长寿命 CLI 进程中的某一 Turn 可靠关联；
- 无法跨版本、locale、terminal width、alternate screen 和不同 provider 维护正则；
- 已写入 scrollback 的过程无法按 Turn 撤回。

因此应把 Conversation/Turn 视图与原始 Terminal 视图分开。

### 6.2 统一事件模型

建议 provider adapter 归一化成：

```text
TurnEvent
  turn_started
  progress_summary_delta
  commentary_delta
  tool_started | tool_updated | tool_completed
  final_answer_delta
  item_completed
  turn_completed(status = completed | failed | interrupted | uncertain)
```

状态至少分开：

```text
TurnStatus       = running | completed | failed | interrupted | uncertain
PresentationMode = progress_visible | final_only
```

delta 只更新临时投影；Codex `item/completed`、Claude 完整 message/result、Pi `message_end`
覆盖临时累计内容。每个 Turn 和 item 都必须有稳定 ID，不能只靠事件时序。

### 6.3 严格 final-only 切换规则

```text
running:
  显示有界进度摘要、工具状态和流式答复草稿

completed + 完整 final:
  从主投影移除全部过程文案
  只保留 final answer

failed / interrupted / uncertain:
  不伪装成成功
  保留精简诊断、错误或中断状态

completed + 无 final:
  显示协议不完整错误
  不展示空白成功页
```

如果产品要求字面意义的“隐藏所有过程文案”，成功态甚至应比 Claude `/focus` 更收紧：不保留
prompt，也不保留单行工具摘要。过程事件可作为有界后台诊断记录保留；UI 隐藏不等于数据
删除，若连持久化也禁止，需要另立数据保留与脱敏策略。

### 6.4 Provider 映射

| Provider | 过程来源 | 最终来源 | 收尾条件 |
| --- | --- | --- | --- |
| Codex | reasoning summary、commentary、tool/plan items | 最后一个 `phase=final_answer`，旧协议才兼容 phase 缺失 | `turn/completed.status=completed` 且 final item 完整 |
| Claude | thinking summary、tool_use/result、stream events | `SDKResultMessage.result` | `subtype=success`，并关联当前 human turn |
| Pi 0.84.1 | thinking/text/tool delta 与工具事件 | settled 后最后 assistant text | `agent_settled` 且 stopReason 成功 |

原始 xterm 应继续存在，但定位为“终端/诊断视图”，不能作为回答 UI 的权威读模型。

## 7. 最终判断

- **想先模仿现有效果**：Claude Code `/focus` 是最接近的视觉参考。
- **想理解终端如何高效重绘**：Pi 的组件树、16ms 合并和行数组 diff 最清晰。
- **想可靠实现 Codex 式语义**：Codex App Server 的 phase、item lifecycle 和 Turn status 最完整。
- **想在 Termestra 达到严格 final-only**：需要结构化 provider adapter + 独立 Turn UI；单靠 PTY、
  ANSI 或清 scrollback 不可可靠完成。

关于 Codex Desktop 自身以及 Termestra 当前协议缺口，另见
[`codex-answer-presentation.md`](codex-answer-presentation.md)。
