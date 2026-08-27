# Codex 式回答展示调研：过程可见、完成后收起、最终答复突出

> 状态：研究记录，不是 ADR，也不定义 Termestra 当前行为  
> 调研日期：2026-08-24  
> 范围：Codex 官方协议与当前 Codex Desktop 体验；Termestra 当前 PTY/xterm、Run、
> Dispatch 与 WebSocket 实现。本文不包含功能实现。

## 结论先行

用户想要的效果可以概括为：**工作中显示有用的进度或思考摘要；工作成功完成后，
默认收起过程区，只把最终答复作为主内容；过程仍可按需展开。** 这里不应把 UI 文案称为
或实现为“完整内部思维链”。Codex 官方协议明确区分可读 reasoning summary、
`commentary`、`final_answer`、工作 item 和回合终态；这才是可靠实现该效果的语义边界。
[Codex App Server：item 类型与 delta](https://learn.chatgpt.com/docs/app-server#item-types)

对 Termestra 的判断是：

| 问题 | 结论 |
| --- | --- |
| 当前 raw PTY/xterm 能否做出相似视觉效果？ | **只能做易碎原型。** 可以在浏览器上加遮罩、折叠区或清屏，但无法可靠知道哪段是过程、哪段是最终答复，也不知道一个用户回合何时真正完成。 |
| 当前架构能否可靠实现该产品语义？ | **不能。** 当前协议只有原始终端文本、restore、exit 和进程级 Run 状态；Run 是长寿命 CLI 进程，不是一次问答 Turn。`frontend/web/src/terminal/terminal-client.ts:1-17,61-83,179-208,217-278`；`backend/src/main/java/dev/termestra/execution/domain/model/RunStatus.java:3-9` |
| Codex 场景的可靠路径是什么？ | **接入结构化 Codex App Server（或 SDK），不要解析 TUI 文本。** 官方把 App Server 定义为富客户端深度集成接口，并提供对话历史、审批和流式 agent events。[Codex App Server](https://learn.chatgpt.com/docs/app-server) |
| 完成后是否应删除过程？ | **不删除，默认收起。** 成功时隐藏主视图中的过程文案，但保留有界、可展开的进度摘要用于解释、故障排查与重连；失败、取消、状态不明时默认不自动隐藏。 |

推荐的完成判定不是“终端看起来停了”或“出现提示符”，而是同时满足：

```text
turn.status == completed
AND 已收到 item/completed 的 final_answer
AND 没有未解决的审批/用户输入请求
```

只有满足这个谓词，UI 才把过程区从展开态切为默认收起态。若 `completed` 但没有完整
`final_answer`，应显示协议不完整错误，不能把空白页面伪装成成功。

## 1. 证据分类与限制

本文严格区分三类结论：

1. **官方明确事实**：只引用 OpenAI 官方文档或 API 参考。
2. **直接观察**：2026-08-24 当前 Codex Desktop 任务中的可见行为；它是版本快照，
   不是长期兼容承诺。
3. **工程推断与建议**：由官方协议和 Termestra 当前源码推出，明确标记为建议，
   不是已实现事实。

没有找到 OpenAI 官方文档承诺“Codex Desktop 永远在完成后按某种固定动画隐藏全部过程
文案”。因此本文不复制某个像素级界面，也不把当前桌面行为当成公共 API。可依赖的是
App Server 和 Responses API 的结构化事件与状态。

## 2. Codex 的实际展示效果

### 2.1 当前桌面体验的直接观察

在本次 Codex Desktop 任务中，工作时的进度更新与最终答复是两个展示层级：

- 执行期间会显示简短的工作说明、当前动作和阶段性结论；这些内容帮助用户确认系统仍在
  工作，但不是逐 token 暴露完整内部推理。
- 最终答复是独立、完整、可单独阅读的交付内容。
- 回合完成后，工作过程不再与最终答复争夺主视觉；过程默认折叠/弱化，最终答复保留为
  主内容。

这只是 **Codex Desktop 2026-08-24 观察记录**。实现时应以官方结构化协议为准，而非
依赖当前客户端 DOM、动画或具体文案。

### 2.2 官方协议明确了“过程”和“最终答复”不是同一种消息

Codex App Server 的 `agentMessage` item 可带 `phase`，官方给出的 wire 值正是
`commentary` 与 `final_answer`；reasoning 则是另一种 item，其中 `summary` 是流式可读
摘要，`content` 是原始 reasoning block。`item/reasoning/summaryTextDelta` 专门流式传输
可读摘要。[Codex App Server：item types 与 deltas](https://learn.chatgpt.com/docs/app-server#item-types)

官方还规定：

- `item/started` 表示一个工作单元开始；`item/completed` 给出最终 item，且应把它视为
  authoritative state。[Codex App Server：item lifecycle](https://learn.chatgpt.com/docs/app-server#item-lifecycle)
- `turn/completed` 的终态是 `completed`、`interrupted` 或 `failed`；失败携带结构化错误。
  [Codex App Server：turn events](https://learn.chatgpt.com/docs/app-server#events)
- `turn/interrupt` 请求成功后，Turn 最终以 `interrupted` 结束；请求返回 `{}` 本身不等于
  UI 可以提前宣称取消完成。[Codex App Server：Interrupt a turn](https://learn.chatgpt.com/docs/app-server#interrupt-a-turn)

这三个边界足以实现“工作时展示过程，成功后只突出最终答复”，无需从终端字符猜测。

### 2.3 `codex exec` 也验证了同一产品原则

OpenAI 官方非交互模式说明：`codex exec` 运行时把进度流到 `stderr`，只把最终 agent
message 打到 `stdout`；启用 `--json` 后会输出 `thread.started`、`turn.started`、
`turn.completed`、`turn.failed`、`item.*` 和 `error`，item 类型包括 agent message、
reasoning、命令、文件变更、MCP、web search 和 plan update。
[Codex Non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode#make-output-machine-readable)

这不是 Desktop UI 的布局规范，但它是一条很强的一手证据：**进度流和最终产物应是
可分离的数据通道，而不是一段事后靠正则切割的文本。**

### 2.4 Responses API 的底层边界

Responses API 提供 `queued`、`in_progress`、`completed`、`failed`、`cancelled`、
`incomplete` 等响应状态，并分别发出 reasoning summary delta、最终 output item、
失败和 incomplete 事件。[Responses API reference](https://developers.openai.com/api/reference/cli/resources/beta/subresources/responses)

因此“思考摘要结束”和“整个用户回合成功”不是同一事件。UI 不应在最后一条 summary
停止变化时就隐藏过程；必须等待权威回合终态和最终答复 item。

## 3. Termestra 当前实现到底有什么

### 3.1 Terminal 只拥有 Run 的浏览协议

当前领域语言把 Terminal 定义为 Run 的浏览器视图：restore、resize、input/control、
viewer flow control 和 bounded screen projection；Restore Snapshot 明确不是完整 transcript。
`backend/src/main/java/dev/termestra/terminal/CONTEXT.md:1-29`

前端 `TerminalClient` 的服务端 control message 只有 `error`、`exit` 和 `restore`；IO channel
收到的任意字符串在 restore 前暂存，之后直接交给 `onOutput`。
`frontend/web/src/terminal/terminal-client.ts:1-17,61-83,179-208,217-278`

`useTerminalRun` 将 restore 与 live chunk 原样写入 xterm，状态也只有
`connecting/running/stopped`。它不知道 message、turn、commentary、final answer 或 reasoning
item。`frontend/web/src/terminal/useTerminalRun.ts:44-50,265-299`

后端同样只把解码文本写入 headless mirror，再按 sequence 发给 viewer；sequence 解决的是
snapshot-to-live 不丢不重，不提供回答语义。
`backend/src/main/java/dev/termestra/terminal/adapter/in/http/TerminalWebSocketHandler.java:74-120,187-218,319-435`

### 3.2 Agent Execution 的状态是进程状态，不是回答状态

Run 只有 `starting/running/exited/error`，active 表示 PTY 进程仍受管理。
`backend/src/main/java/dev/termestra/execution/domain/model/RunStatus.java:3-9`

输出被解码后进入 1,000,000-byte bounded buffer 和 output hub；订阅者接收的是纯文本。
`backend/src/main/java/dev/termestra/execution/application/service/AgentExecutionService.java:20-35,207-253,434-456,508-520`；
`backend/src/main/java/dev/termestra/execution/application/service/RunOutputHub.java:11-54`

这意味着一个 Codex/Claude/OpenCode TUI 可以在同一 `running` Run 内经历很多次用户问答。
用 Run 退出作为回答完成信号会漏掉正常的长寿命交互；用提示符、颜色或 ANSI 布局解析又会
被 CLI 版本、终端尺寸、alternate screen、locale 和不同 provider 破坏。

### 3.3 用户输入只证明“写入请求被接受”

`POST /api/workspaces/{workspaceId}/user-input` 返回 `202 Accepted`；它把文本送到活动
Orchestrator PTY，但没有创建或返回稳定 `turn_id`。
`backend/src/main/java/dev/termestra/execution/adapter/in/http/AgentExecutionController.java:22-28`；
`backend/src/main/java/dev/termestra/execution/application/service/AgentExecutionService.java:436-453`

所以浏览器无法把后续 PTY 文本可靠关联到“刚才这一个问题”，也无法在刷新后重建这个问答
的权威状态。

### 3.4 Team Dispatch/Report 有结构，但不是 Orchestrator 对用户的最终答复

Team 的 Dispatch 确实有 `queued/submitted/reported/cancelled`，Report 会保存 result、
artifacts 和时间。`backend/src/main/java/dev/termestra/team/domain/model/DispatchStatus.java:3-22`；
`backend/src/main/java/dev/termestra/team/domain/model/Dispatch.java:76-110,118-130`

但这是 Orchestrator 向 Worker 派工、Worker 汇报的业务状态。Worker 的正式回复通过
Termestra 系统消息进入 Orchestrator stdin，而 `last_pty_line` 明确只是 UI hint、不是回复。
`frontend/src/shared/types.ts:23-33`。当前通知甚至通过 pending 数下降或
`working -> idle` 推断“reported”，不携带最终报告正文。
`frontend/web/src/notifications/WorkspaceNotifications.tsx:43-56,105-114`

因此不能把 Worker Report 直接冒充用户问题的最终答案；最终用户答复仍需要独立 Turn
聚合和 Orchestrator 的 `final_answer`。

### 3.5 当前 UI 明确是纯 PTY

Worker detail 的源码注释直接称其为 “pure PTY view”，运行时只挂载 terminal slot；
Orchestrator running pane 同样只挂载 `orch-pty-*` 容器。
`frontend/web/src/worker/WorkerModal.tsx:24-29,125-163`；
`frontend/web/src/worker/OrchestratorPane.tsx:226-253`

这进一步说明该需求不是在现有组件上加一个 `display:none` 就完成，而是缺少新的结构化
读模型。

## 4. 可行性分级

| 方案 | 可靠性 | 结论 |
| --- | --- | --- |
| 解析 raw PTY 文本、ANSI、提示符或特定标题 | 低 | Reject。无法跨 provider/版本/语言稳定，重连时 mirror 也不是完整 transcript。 |
| 在 xterm 中清屏，完成后只重写最后几行 | 低 | Reject。会破坏用户终端历史、选择/复制与 restore 语义，也没有可靠完成信号。 |
| 仅用 Run `running/exited` 切换 | 低 | Reject。Run 是进程生命周期，不是 Turn。 |
| 仅用 Worker `working/idle` 或 Dispatch `reported` | 中低 | 只能展示派工进度；不能确定 Orchestrator 已形成用户最终答复。 |
| Codex `exec --json` | 中 | 适合一次性、非交互任务；官方明确提供完整事件和最终消息分离，但不自然替代当前长期交互 TUI。 |
| Codex SDK | 高 | 适合服务端控制本地 Codex thread，直接返回 `finalResponse`；官方文档将其定位为应用/内部工具集成。[Codex SDK](https://learn.chatgpt.com/docs/codex-sdk) |
| Codex App Server | **最高** | 官方富客户端深度集成接口，含 history、approvals、structured streamed events、phase 和 turn lifecycle；最接近 Codex Desktop 所需语义。[Codex App Server](https://learn.chatgpt.com/docs/app-server) |

推荐 Codex 首个可靠实现走 **本地 App Server 的 stdio JSONL**。官方文档说明 stdio 是默认
transport；App Server 的 WebSocket transport 是 experimental/unsupported，不应为了省一层
本地 adapter 直接把它暴露给浏览器。
[Codex App Server：Protocol](https://learn.chatgpt.com/docs/app-server#protocol)

Claude/OpenCode 等 provider 若没有等价的稳定结构化协议，应保留现有 Terminal 模式，
明确不承诺自动提取最终答复；不要用 Codex 正则反向兼容它们。

## 5. 推荐的可靠状态模型

### 5.1 分开三个维度

不要再造一个含糊的 `thinking` boolean。至少分开：

```text
TurnStatus       = submitting | in_progress | completed | failed | interrupted | unknown
StreamStatus     = connecting | live | recovering | stale
PresentationMode = progress_expanded | progress_collapsed
```

- `TurnStatus` 是持久化业务事实。`submitting` 是本地已接受、provider 尚未回执的短暂状态；
  `unknown` 表示断线后无法确认是否已创建/完成，禁止自动重投。
- `StreamStatus` 只是连接健康度。浏览器或 App Server transport 断开不能把 Turn 改成
  `failed` 或 `interrupted`。
- `PresentationMode` 是 UI 派生状态，不应反写 provider lifecycle。

官方 App Server 的 provider 映射是：`inProgress -> in_progress`，`completed -> completed`，
`failed -> failed`，`interrupted -> interrupted`。
[Codex App Server：turn/completed](https://learn.chatgpt.com/docs/app-server#events)

### 5.2 Turn 内的展示段

建议把每个 provider item 归一化为有稳定 ID 的有界段：

```text
ProgressSegment
  kind = commentary | reasoning_summary | plan | tool_activity
  provider_item_id
  text/status
  completed

FinalAnswer
  provider_item_id
  markdown
  completed
```

映射规则：

- `agentMessage.phase=commentary` -> `ProgressSegment(commentary)`；
- reasoning `summary`/summary delta -> `ProgressSegment(reasoning_summary)`；
- plan/tool item -> 对应进度段；
- `agentMessage.phase=final_answer` -> `FinalAnswer`；
- delta 只更新暂存内容，`item/completed` 用同一 item ID 覆盖为权威最终内容。

默认不保存或展示 raw reasoning `content`；产品需要的是用户可理解的工作摘要，不是完整内部
推理。若 provider 不支持 summary，则显示工具/阶段状态，不能伪造“正在思考某件事”。

### 5.3 自动折叠规则

```text
in_progress:
  展开 progress；final_answer 若已开始可并行流式显示，但不隐藏 progress

completed + complete final_answer:
  默认收起 progress；突出 final_answer；提供“查看过程”按钮

failed:
  显示错误；保留最后进度并默认展开，便于理解失败位置

interrupted:
  显示“已中断”；保留已产生内容，不把部分文本标成最终答复

unknown/recovering:
  显示“正在恢复/状态待确认”；禁止自动重试；不自动折叠
```

用户手动展开/收起应覆盖默认值，但新 Turn 开始时回到 `progress_expanded`。

## 6. 失败、取消和重连边界

### 6.1 失败

App Server 先发 `error`，再以 `turn/completed(status=failed)` 收尾；结构化错误可能包含
`codexErrorInfo` 和 HTTP 状态。[Codex App Server：Errors](https://learn.chatgpt.com/docs/app-server#errors)

建议先持久化错误与终态，再发布 UI 事件。若只见 error 而未见终态，Turn 留在
`recovering/unknown`，不要抢先宣称失败已最终确认。

### 6.2 取消

取消分两步：`interrupt_requested` 可以是内部瞬态，但只有收到权威
`turn/completed(status=interrupted)` 才对用户显示“已中断”。`turn/interrupt` 回执丢失时，
结果不确定；应重读 thread/turn 状态，不自动再次创建 Turn。
[Codex App Server：Interrupt a turn](https://learn.chatgpt.com/docs/app-server#interrupt-a-turn)

当前 Terminal 的 `stop` 是杀整个 Run/PTY，不应拿来实现“停止本次回答”；它会终止长寿命
agent 进程。`backend/src/main/java/dev/termestra/terminal/adapter/in/http/TerminalWebSocketHandler.java:159-181`；
`backend/src/main/java/dev/termestra/execution/application/service/AgentExecutionService.java:367-388`

### 6.3 浏览器重连

浏览器断开只结束 viewer，不等同于停止 Run；当前架构文档已明确这一点。
`docs/architecture/frontend.md:73-80`。新的回答流也应遵守同一原则：断开只清理 viewer，
后台 Turn 继续。

建议由 Termestra 在 SQLite 中保存 Turn、权威 item snapshot 和每 Turn 单调 `event_seq`；
浏览器重连先取 bounded snapshot/revision，再接收 `after_seq` 的 live events。这个
snapshot-to-live handoff 可复用 Terminal 的设计原则，但不能复用 raw terminal payload。
`docs/architecture/contracts-and-data.md:38-48,145-156`

### 6.4 App Server/后端重连

官方提供 `thread/read(includeTurns=true)` 读取存储历史、`thread/resume` 恢复 thread，
也说明 `thread/read` 本身不会订阅事件。
[Codex App Server：Read and resume threads](https://learn.chatgpt.com/docs/app-server#read-a-stored-thread-without-resuming)

官方文档没有保证“transport 或 app-server 进程在活动 Turn 中断后，所有未交付 delta 都能按
游标无损重放”。因此 Termestra 不能假设 provider stream exactly-once。推荐流程是：

1. 新连接 `initialize`；
2. `thread/read`/持久 item 与本地 SQLite 按 provider item ID 对账；
3. `thread/resume` 后重新订阅；
4. `item/completed` 覆盖暂存 delta；
5. 无法确认的在途 Turn 标为 `unknown`，由用户显式重试。

这里“按 item ID 对账”和本地 `event_seq` 是 Termestra 的工程建议，不是 OpenAI 协议承诺。

### 6.5 提交结果不确定

App Server 文档没有给 `turn/start` 幂等键。若请求可能已到达 provider、但回执在断线中丢失，
自动新建 Turn 可能重复执行工具或修改文件。应保存本地 submission 记录，先读取 thread 最近
turn 对账；仍无法证明时显示 `unknown` 并要求用户决定是否重试。这与 Termestra 现有“副作用
可能触达时不自动重试”的原则一致。`docs/architecture/contracts-and-data.md:50-57`

## 7. 建议的 Termestra 架构落点

### 7.1 不让 Terminal 解析回答

Terminal 继续只拥有 Run viewer、restore、raw input、flow control 和 screen projection。
它不应新增 provider 名称判断、ANSI parser 或 `final answer` 正则。这样可以保留 xterm 作为
高级/兼容视图，同时让新的回答 UI 使用结构化数据。

### 7.2 建议新增独立的 Conversation 所有者

这是工程建议，不是当前事实：引入一个明确拥有 `Thread`、`Turn`、ProgressSegment、
FinalAnswer、取消与重连状态的 **Conversation context**。理由是它有独立的持久状态机、公共
HTTP/WebSocket 契约和生命周期，既不是 Team Dispatch，也不是 PTY Run。

建议边界：

| 责任 | 所有者 |
| --- | --- |
| Thread/Turn、结构化 item、最终答复、UI stream | Conversation |
| Codex App Server stdio JSON-RPC 适配 | Conversation-owned outbound adapter，复用 platform process mechanism |
| CLI/PTTY Run、原始终端、进程终止 | Agent Execution + Terminal，保持现状 |
| Worker Dispatch/Report | Team，保持现状；可作为 Conversation 聚合回答的输入，但不取代 Turn |
| Browser answer projection | Conversation inbound HTTP/WebSocket adapter；summary/detail/stream 分离 |

实现前需要领域建模或 ADR 确认该 owner；本文不构成已接受架构决定。若产品只做一次 Codex
试验，也至少应先定义 `ProviderConversationGateway` 这一真实 volatility seam，并让不支持
结构化协议的 provider 显式 fallback 到 Terminal，而不是假装能力等价。

### 7.3 写入与发布顺序

建议在同一 SQLite 事务内先保存 item/final answer 与 Turn 状态，再递增本地 `event_seq`；
commit 后才唤醒 browser stream。完成事务应保证 `completed` 与完整 FinalAnswer 一起可见，
避免 UI 先折叠、刷新后却找不到答案。

所有集合都需要容量：每 Turn 的 progress item 数、summary 字节、final answer 字节、保留 Turn
数、viewer 数、未确认 delta 和慢消费者窗口。当前仓库已有相同的有界读模型要求。
`docs/architecture/frontend.md:30-48,51-69`；`docs/architecture/contracts-and-data.md:145-156`

## 8. 推荐交互规格

工作中：

```text
用户问题

⌄ 正在处理
  · 检查当前实现…
  · 运行边界测试…
  · 整理结论…

[停止]
```

成功后：

```text
用户问题

› 查看过程（3 步）

最终答复正文……
```

失败或中断：

```text
用户问题

⌄ 处理过程（保留展开）
  · 已完成……
  · 在……失败/被中断

错误或中断说明
[重试]  // 新 Turn，必须明确由用户触发
```

文案建议使用“处理过程”“工作进度”或“思考摘要”，不使用“完整思考过程”。最终答复必须在
折叠过程后仍能独立成立，不能引用只有展开过程才看得到的关键信息。

## 9. 最小验收边界（供后续设计，不是本次实现）

1. commentary/reasoning summary 在 `in_progress` 可见；收到完整 final answer 和 completed 后
   自动收起。
2. final answer 先到、turn completed 后到时，不提前隐藏 progress。
3. completed 无 final answer 时显示协议错误。
4. failed/interrupted/unknown 不把部分 agent message 当最终答复。
5. interrupt 回执与权威 interrupted 终态分开测试。
6. 浏览器断开不取消 Turn；重连 snapshot 与 live 不丢不重。
7. app-server 断线、重复 delta、乱序 delta、重复 item completed 可幂等收敛。
8. `turn/start` 结果不确定时不自动重投。
9. 慢消费者关闭后释放 viewer；后台 Turn 与其他 viewer 不受影响。
10. provider 不支持结构化事件时明确进入 Terminal fallback，不启用自动最终答复提取。

## 10. 最终建议

**采用 Codex 的数据模型，不复制 Codex 的表面动画。** 第一阶段若只做调研后的技术验证，
应验证 App Server stdio adapter 能否稳定取得 `commentary`、reasoning summary、
`final_answer`、`item/completed` 和 `turn/completed`；不要先写 PTY parser。

产品决策上，成功路径默认折叠但不删除过程；失败、取消、恢复中保持过程可见。工程上，先
建立独立 Turn 权威状态和 snapshot-to-live 流，再做 UI 动画。只有这条顺序能让刷新、断线、
取消和 provider 升级之后仍保持同一个产品语义。

## 一手来源索引

- [Codex App Server 官方文档](https://learn.chatgpt.com/docs/app-server)
- [Codex Non-interactive mode 官方文档](https://learn.chatgpt.com/docs/non-interactive-mode)
- [Codex SDK 官方文档](https://learn.chatgpt.com/docs/codex-sdk)
- [Responses API 官方参考](https://developers.openai.com/api/reference/cli/resources/beta/subresources/responses)
- Termestra 当前架构：`CONTEXT-MAP.md:1-42`；`docs/architecture/frontend.md:30-80`；
  `docs/architecture/contracts-and-data.md:38-57,145-156`
- Termestra Terminal/Execution 源码：
  `frontend/web/src/terminal/terminal-client.ts:1-328`；
  `frontend/web/src/terminal/useTerminalRun.ts:44-319`；
  `backend/src/main/java/dev/termestra/terminal/adapter/in/http/TerminalWebSocketHandler.java:27-435`；
  `backend/src/main/java/dev/termestra/execution/application/service/AgentExecutionService.java:20-35,207-253,367-456,508-520`
- Termestra Team/UI 源码：
  `backend/src/main/java/dev/termestra/team/domain/model/Dispatch.java:76-130`；
  `frontend/src/shared/types.ts:23-33`；
  `frontend/web/src/notifications/WorkspaceNotifications.tsx:43-56`；
  `frontend/web/src/worker/WorkerModal.tsx:24-29,125-163`
