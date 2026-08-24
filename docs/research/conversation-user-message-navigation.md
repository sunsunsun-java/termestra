# 对话区用户消息定位刻度调研

> 状态：研究记录，不是 ADR，也不定义 Termestra 当前行为
>
> 调研日期：2026-08-24
>
> 范围：当前生产 Orchestrator PTY/xterm 视图、用户输入链路、重连投影、xterm.js
> 6.0.0 能力，以及 Codex 专用结构化消息路径。本文不包含功能实现。

## 结论先行

截图中的交互在视觉和浏览器技术上**可以实现**：在对话区左侧叠加一条窄 rail，默认显示
若干短刻度；hover/focus 时展开刻度和消息预览；点击后滚动到目标并短暂高亮。

但当前 Termestra 的生产“对话区”不是 React 消息列表，而是通用 CLI/TUI 的 raw PTY
字节流交给 xterm 渲染。因此可行性必须分两档：

| 目标 | 判断 | 保证范围 |
| --- | --- | --- |
| 当前浏览器、当前 Run 内做出类似视觉和跳转 | **可做，作为 best-effort MVP** | 只保证 xterm normal buffer 中尚未被淘汰的本地行锚点；不承诺它一定是 CLI 语义上的一条用户消息 |
| 精确识别每条用户消息，且跨刷新、重连、Workspace 切换、Run 重启和不同 CLI 仍能定位 | **当前契约不能直接做到** | 需要稳定 user-message/turn ID、消息历史与 terminal row 的关联，或者改为结构化原生消息 UI |

推荐产品决策是：

1. 如果目标是快速验证交互价值，明确命名为“本次终端会话的输入书签”，只支持当前
   Run 的 normal buffer；不要宣传成完整对话历史。
2. 如果目标就是截图所表达的“用户历史消息导航”，应先建立结构化用户消息契约。Codex
   可以走 App Server 的 `Thread` / `Turn` / `userMessage`；其他 CLI 若无等价协议，保留
   Terminal 模式或只提供 best-effort 书签。
3. 不建议从 ANSI 文本、提示符、颜色或 Enter 字节反向解析“用户消息”。这种方案会在
   alternate screen、重绘、多行编辑、粘贴、IME、不同 provider/版本下产生静默错位。

## 1. 当前真实界面：没有消息列表

### 1.1 展示 owner

Orchestrator 运行时的 React 组件只提供一个 PTY slot：
`<div id="orch-pty-${runId}">`，没有 `messages` state、消息数组或消息 DOM 节点。
[`OrchestratorPane.tsx` L226-L253](../../frontend/web/src/worker/OrchestratorPane.tsx)

Workspace 层负责把这个 slot 放进左右 pane；右侧还有一个现有的 resize separator，和本需求
的消息导航 rail 不是一回事。
[`WorkspaceDetail.tsx` L247-L287](../../frontend/web/src/WorkspaceDetail.tsx)

真正渲染内容的是 `TerminalPtyView -> useTerminalRun`。hook 动态创建 xterm，连接双
WebSocket，并把 restore/live 文本直接 `write` 到终端。
[`TerminalView.tsx` L198-L235](../../frontend/web/src/terminal/TerminalView.tsx)；
[`useTerminalRun.ts` L111-L155、L265-L299](../../frontend/web/src/terminal/useTerminalRun.ts)

因此当前不存在常见聊天 UI 的：

- `Message[]` 或 `Turn[]`；
- 每条消息的 React `key` / DOM `ref`；
- 消息级分页或虚拟列表；
- 可直接调用 `element.scrollIntoView()` 的用户消息节点。

仓库确有一个 `useWorkerHighlight`，会找到 Worker 卡片、调用 `scrollIntoView()` 并高亮
1 秒，但它只证明现有 UI 有可复用的交互模式，不提供 terminal message anchor。
[`useWorkerHighlight.ts` L3-L31](../../frontend/web/src/useWorkerHighlight.ts)

### 1.2 当前滚动与加载模型

当前依赖锁定为 `@xterm/xterm@6.0.0`，前端 xterm 配置了 `scrollback: 10_000`。滚动由
xterm 自己生成的 viewport/buffer 管理，不是 React virtualization。
[`package.json` L14-L29](../../frontend/package.json)；
[`useTerminalRun.ts` L127-L148](../../frontend/web/src/terminal/useTerminalRun.ts)

`WorkspaceTerminalPanels` 以 `run_id` 保持 `TerminalView`，并通过 parking/re-parenting 在
pane 或 tab 变化时移动宿主节点，避免每次显示切换都重建 xterm。只要同一个组件树和 Run
还在，本地 marker 有机会继续存活。
[`WorkspaceTerminalPanels.tsx` L25-L50](../../frontend/web/src/WorkspaceTerminalPanels.tsx)；
[`TerminalView.tsx` L128-L195](../../frontend/web/src/terminal/TerminalView.tsx)

这不等于 durable history：

- Agent Execution 的 live Run output 是一个 1,000,000 UTF-8 bytes 的有界尾部缓冲，不是消息
  transcript。[`AgentExecutionService.java` L20-L32、L508-L514](../../backend/src/main/java/dev/termestra/execution/application/service/AgentExecutionService.java)
- Terminal mirror 默认只保留 1,000 行，最多 100,000 个 cell，restore transport 上限
  900 KiB。[`HeadlessTerminalMirror.java` L8-L31、L46-L56、L67-L109](../../backend/src/main/java/dev/termestra/terminal/application/service/HeadlessTerminalMirror.java)
- Terminal 领域语言明确把 Restore Snapshot 定义为有界 screen image，禁止把它当作
  full terminal history 或 transcript。[`terminal/CONTEXT.md` L22-L29](../../backend/src/main/java/dev/termestra/terminal/CONTEXT.md)
- 客户端只接受 `restore`、`error`、`exit` 三种 server control message；restore 中没有
  message/turn/anchor metadata。[`terminal-client.ts` L1-L17、L217-L279](../../frontend/web/src/terminal/terminal-client.ts)

## 2. xterm 已有能力足以做“本地行书签”

xterm 6.0.0 已提供实现 MVP 所需的低层原语：

- `registerMarker(cursorYOffset)` 在 normal buffer 的某一行创建 marker；marker 有本地唯一
  `id`，行增删/scrollback 修剪时会跟踪位置，被淘汰后 `line = -1` 并 dispose。
  [xterm 官方 `IMarker` / `registerMarker` 文档](https://xtermjs.org/docs/api/terminal/classes/terminal/#registermarker)
- `scrollToLine(line)` 跳到 0-based buffer line。
  [xterm 官方 `scrollToLine` 文档](https://xtermjs.org/docs/api/terminal/classes/terminal/#scrolltoline)
- `registerDecoration({ marker, ... })` 能给 marker 对应行加临时 DOM/cell 装饰，用于点击后的
  高亮；该 API 标为 experimental，当前项目已经开启 `allowProposedApi: true`。
  [xterm 官方 `registerDecoration` 文档](https://xtermjs.org/docs/api/terminal/classes/terminal/#registerdecoration)；
  [`useTerminalRun.ts` L127-L140](../../frontend/web/src/terminal/useTerminalRun.ts)
- `onScroll`、buffer `viewportY/baseY/length` 能驱动 rail 的可见位置更新。
  [xterm 官方 Terminal API](https://xtermjs.org/docs/api/terminal/classes/terminal/)

xterm 的内建 overview ruler 是右侧滚动条区域，不符合截图的左侧样式。左 rail 应由 Termestra
在 `TerminalPtyView` 内叠加 DOM；刻度位置可由当前有效 marker 的 `line / (buffer.length-1)`
归一化，点击调用 `scrollToLine(marker.line)`。这只是建议公式，实际还应避免刻度重叠并为
首尾行设置安全边距。

关键限制也写在 xterm 官方接口里：marker 属于 normal buffer；alternate buffer 活跃时
marker 集合不可用，`registerDecoration` 也可能返回 `undefined`。Termestra 已有专门的
alternate-screen wheel fallback，并真实检查 `terminal.buffer.active.type === 'alternate'`，
所以这不是可以忽略的理论边角。
[`wheelFallback.ts` L22-L43、L89-L121](../../frontend/web/src/terminal/wheelFallback.ts)；
[xterm 官方 `registerDecoration` 文档](https://xtermjs.org/docs/api/terminal/classes/terminal/#registerdecoration)

## 3. 当前为什么不能知道“这是一条用户消息”

### 3.1 生产输入路径只有 raw bytes

用户在 xterm 内输入时，`onData` / `onBinary` 直接把 chunk 交给 WebSocket client；这里没有
构造 message，也没有稳定 message ID。
[`useTerminalRun.ts` L286-L295](../../frontend/web/src/terminal/useTerminalRun.ts)

client 只创建一个 viewer 级 `clientId = crypto.randomUUID()`，然后连接同一个 Run 的
`/io` 与 `/control`；这个 ID 用于配对 viewer，不是用户消息 ID。
[`terminal-client.ts` L61-L83](../../frontend/web/src/terminal/terminal-client.ts)

后端 `/ws/terminal/{runId}/io` 收到一帧后只执行 `terminal.input(runId, bytes)`。它无法知道
某个 `\r` 是“发送一条问题”、终端快捷键、TUI 内确认、粘贴的一部分还是应用鼠标/方向键协议。
[`TerminalWebSocketHandler.java` L74-L104](../../backend/src/main/java/dev/termestra/terminal/adapter/in/http/TerminalWebSocketHandler.java)

直接在 `onData` 中看到 Enter 就注册 marker，最多只能标记“当时 cursor 附近的一行”。CLI
可能随后清屏、重绘 prompt、把多行编辑折叠为一行、切换 buffer，或者根本不回显输入。marker
仍然存在并不代表语义目标仍然正确，这种静默错位比“不显示刻度”更误导。

### 3.2 仓库里的 `user-input` HTTP 路径尚不能补齐映射

仓库另有 `POST /api/workspaces/{workspaceId}/user-input`。它会先向 `messages` 插入
`user_input`，SQLite `sequence` 是稳定的自增值，然后向 Orchestrator 自动投递。
[`AgentExecutionController.java` L26-L27](../../backend/src/main/java/dev/termestra/execution/adapter/in/http/AgentExecutionController.java)；
[`AgentExecutionService.java` L436-L453](../../backend/src/main/java/dev/termestra/execution/application/service/AgentExecutionService.java)；
[`JdbcAgentRecoveryContextProvider.java` L51-L89](../../backend/src/main/java/dev/termestra/execution/adapter/out/persistence/JdbcAgentRecoveryContextProvider.java)

但它当前仍不满足导航需求：

- 生产 frontend 没有通过该 endpoint 发送 xterm 内的日常输入；
- endpoint 只返回 `{ok: true}`；insert 返回的 `sequence` 只在投递失败时供内部条件回滚，
  不返回给客户端；
- 没有 UI user-message list/read endpoint；
- `messages` row 没有 `run_id` 或 terminal row，`sequence` 无法映射到 xterm buffer；
- 自动 PTY 输入保留 `uncertain` 失败语义，不能在未知触达时伪造一个已发送消息锚点；
- 架构文档把 `messages` 表归 Team，主要用于 send/report/status 审计；不能让新功能继续
  默默扩大跨 context 普通写入。

内部 sequence 回滚见
[`PersistedMessageDelivery.java` L9-L26](../../backend/src/main/java/dev/termestra/execution/application/service/PersistedMessageDelivery.java)；
当前 `messages` schema 见
[`DispatchSchemaMigrations.java` L177-L200](../../backend/src/main/java/dev/termestra/platform/persistence/sqlite/DispatchSchemaMigrations.java)。

SQLite 所有权与有界读模型要求见
[`contracts-and-data.md` L120-L155](../architecture/contracts-and-data.md)。跨 context 关系要求
Terminal 只能通过 gateway 观察/控制 Run，Agent Execution 对 Team 只读恢复上下文，见
[`CONTEXT-MAP.md` L41-L55](../../CONTEXT-MAP.md)。实现 durable user-message 前必须先明确 owner，
不应由前端直接查询 `messages` 表。

## 4. 重连、修剪和 Run 生命周期边界

### 4.1 本地 marker 会在哪里失效

一个 session-local marker 至少会在以下情况失效或变得不可信：

1. scrollback 修剪掉目标行；xterm 会 dispose marker，rail 必须同步删除刻度；
2. macOS `Command+K` 当前会调用 `nextTerminal.clear()`，旧行/decoration 不能再当作可定位历史；
   [`useTerminalRun.ts` L213-L234](../../frontend/web/src/terminal/useTerminalRun.ts)
3. CLI 进入 alternate buffer，normal-buffer marker 不能定位当前 TUI 内部的滚动内容；
4. 页面刷新、Workspace 组件树重建或新的 viewer 连接；server restore 是 ANSI screen snapshot，
   不带 marker metadata；
5. Orchestrator restart 生成新的 `run_id`；旧 Run 的 buffer position 不能复用于新 Run；
6. resize/reflow、wrapped input 与应用级光标重绘可能改变“用户文本首行”，必须使用真实 xterm
   集成测试验证，而不能只测比例函数。

### 4.2 不能通过文本重新寻找

刷新后拿持久化的用户文本，在 restore snapshot 中全文搜索也不可靠：

- 同一句话可能出现多次，assistant 还可能引用它；
- ANSI/宽字符/自动换行会改变行边界；
- restore 最多保留有界 screen/history，不是完整 transcript；
- alternate screen 与 provider 自己的滚动模型可能根本不把历史放进 xterm primary scrollback；
- 恶意或普通输出都可能伪造相同文本。

因此“文本匹配后猜一行”不应进入产品级方案。

## 5. 方案比较

| 方案 | 改动 | 优点 | 主要限制 | 建议 |
| --- | --- | --- | --- | --- |
| A. 当前 Run 的 xterm 本地书签 | 前端在提交动作附近 `registerMarker`，加左 rail，点击 `scrollToLine` | 改动集中、能快速验证交互 | 不是语义消息；normal buffer only；刷新/重连丢失；注册时机易错 | **可做受限实验** |
| B. Termestra composer + 稳定 input ID + terminal anchor | 用户输入走 owning application endpoint；返回 ID；Terminal stream/snapshot 带有界 anchor metadata | 可统一提交、预览、失败状态；可逐步支持重连 | PTY/TUI 未必回显；仍需 provider/OSC 确认点；跨 context 契约和持久化要设计 | **通用 PTY 的中期路径** |
| C. provider 专用结构化 conversation UI | 使用 provider 的 Thread/Turn/item 协议；React 渲染用户消息列表，DOM/virtualizer 定位 | 用户消息有稳定 ID；历史、分页、重连语义明确；最符合截图 | 需要 provider adapter；不能假定所有 CLI 都有等价协议 | **Codex 的长期推荐** |
| D. 解析 ANSI、prompt、颜色或可见文本 | 从 raw PTY 猜消息边界 | 表面无需新协议 | 跨版本/provider/语言脆弱，重绘和 alternate screen 下静默错位 | **Reject** |

### 5.0 粗略工作量

下表是基于当前代码边界的单人量级，不是排期承诺；不含产品设计反复、上游协议变化和发布
等待：

| 范围 | 量级 | 粗略投入 | 最大未知量 |
| --- | --- | --- | --- |
| A. session-local normal-buffer 交互实验 | M | 约 3-5 工程日 | 如何从 raw `onData` 组合出不误导的输入 preview；真实 xterm 测试环境 |
| B. durable input/anchor 契约 | L | 约 2-4 工程周 | owner/表设计、PTY 回显确认点、snapshot-to-live anchor 原子交接、`uncertain` 恢复 |
| C. Codex 首个结构化 conversation slice | XL | 约 3-6 工程周 | App Server 生命周期/版本兼容、持久投影、审批/重连/流、Terminal 与原生 UI 的产品切换 |

每增加一个没有相同结构化协议的 provider，不能按“复制 Codex adapter”估算；必须独立确认其
session/turn/item 与恢复契约。

### 5.1 方案 A 的最小设计边界

如果先做实验，建议接口把限制写进名字，例如 `TerminalInputBookmark`，而不是 `Message`：

```text
TerminalInputBookmark
  local_id          // 仅当前 viewer 内唯一
  run_id
  marker            // xterm IMarker
  preview           // 有界、去控制字符后的用户输入摘要
  created_at
```

实现 owner 应留在 terminal 前端模块：`useTerminalRun` 或一个新 deep hook 同时持有 xterm、marker
和 scroll 操作，`TerminalPtyView` 只渲染 rail。只在 Orchestrator slot 开启，不能让 shell/Worker
terminal 自动出现“用户消息”语义。

必须设置容量，例如每个 Run 最多 128 个 bookmark；超限丢弃最旧项并 dispose；marker
`onDispose`、Run unmount、client failure、restart 都做清理。rail 更新应合并到 animation frame，
不能对每个 output chunk 产生无界 React state 更新。

交互还应满足：

- 默认窄刻度，rail hover **或键盘 focus-within** 后展开；
- 每个刻度是可聚焦 `button`，有消息摘要和序号的 `aria-label`，不能仅靠 hover；
- hover 只预览，不改变 scroll；click/Enter/Space 才跳转；
- 点击后用 decoration 或 overlay 短暂高亮，失败/已 dispose 时静默移除刻度，不跳到错误行；
- 用户正在选中文本或 TUI 开启 mouse tracking 时，rail overlay 不能把整个左侧内容区的 pointer
  事件吞掉。

方案 A 上线文案应明确：“定位只在本次终端会话、当前可用滚动历史内有效。”

### 5.2 方案 B 需要新增的可靠契约

若要保留通用 PTY，又希望跨重连，应至少增加：

1. 明确 user input owner；接收命令后返回稳定 `input_id`、`run_id` 和 `submission_status`；
2. 一个有界、分页的 UI projection，只返回用户输入摘要，不把 1 MB terminal output 塞进列表；
3. Terminal snapshot-to-live 中的 anchor event，携带 `input_id` 与可重建的 buffer anchor，并与
   output cursor 原子交接；
4. `uncertain`、failed、accepted/committed、anchor-unavailable 的明确区分；
5. Run restart 后不把旧 anchor 指到新 Run；读取旧消息时可以显示“该终端历史已不可定位”。

“在 PTY 输入前后插入自定义 OSC marker”只有在受控 provider/adapter 能保证 CLI 不吞掉或重排
它时才可考虑。不能接受任意 PTY 输出伪造一个 anchor 控制序列；parser handler 必须限定来源或
携带不可猜的本地关联 token，并有严格长度/数量上限。

### 5.3 方案 C：Codex 已有原生结构化消息 ID

Codex App Server 是这里的直接对照：官方 `thread/read(includeTurns=true)` 能返回持久 turn
历史，也有分页的 `thread/turns/list` / `thread/items/list`；`turn/start` 返回稳定 Turn ID，
`ThreadItem.userMessage` 明确含 `{id, content}`。这些字段正是可靠消息导航所需的语义，不需要
从 TUI 像素反推。
[OpenAI 官方 App Server：读取 thread/turn history](https://learn.chatgpt.com/docs/app-server#read-a-stored-thread-without-resuming)；
[OpenAI 官方 App Server：Turn 与 ThreadItem](https://learn.chatgpt.com/docs/app-server#items)

本机 `codex-cli 0.147.0` 也可用下列只读生成命令复核相同 schema：

```bash
codex app-server generate-json-schema --experimental --out <temporary-directory>
```

生成的 `ThreadReadParams.includeTurns`、`Turn.id` 与 `UserMessageThreadItem.id` 证明 Codex
专用 adapter 可以建立原生消息列表。这是 **Codex 路径**，不能把它的能力外推给 Claude、
OpenCode 或任意 shell CLI。更完整的过程/final answer 分离评估见同目录的
[Codex 式回答展示调研](codex-answer-presentation.md)。

App Server 当前仍需按实验性上游能力管理版本。官方默认 transport 是本地 stdio JSONL；
WebSocket transport 明确为 experimental/unsupported。因此 Termestra 若做该 slice，应由后端
adapter 管理本地 stdio 子进程并向浏览器暴露自己的有界协议，不能让浏览器直连 App Server
WebSocket。
[OpenAI 官方 App Server：Protocol](https://learn.chatgpt.com/docs/app-server#protocol)

## 6. 推荐落地顺序

### 阶段 0：先定产品承诺

在开发前选择且写进验收标准：

- **实验承诺**：“当前 Run、normal buffer、当前浏览器生命周期内的输入书签”；或
- **产品承诺**：“所有内置 provider 的用户历史消息，重连后仍准确定位”。

两者不是同一个工作量。前者是前端中等改动；后者是 Terminal + Agent Execution，且很可能
包含 provider-specific conversation adapter 的架构改动。

### 阶段 1：若先验证 UI，只做明确受限的书签

- 不改 backend，不复用 Team `messages` 伪装 transcript；
- 限定 Orchestrator normal buffer；alternate buffer 不显示不可用刻度；
- marker 数量、preview 长度、更新频率全部有界；
- 收集用户是否真的使用 rail、是否需要预览、是否接受 session-local 限制。

### 阶段 2：若验证通过，优先做 Codex 结构化 UI

- 以 provider `userMessage.id` 作为 React key/定位 ID；
- 用有界、分页 turn projection，列表加载旧消息时保留 viewport anchor，避免页面跳动；
- 点击 rail 后对 DOM message 调用 `scrollIntoView` 或 virtualizer 的 `scrollToIndex`，再做 1 秒
  highlight；
- Terminal 作为 raw/advanced view 保留，不与结构化消息的 scroll position 混为一个事实来源。

### 阶段 3：决定其他 provider 的降级策略

只有 provider 提供稳定结构化 session/turn/item 协议时才承诺同等级能力。否则显示
“本次终端输入书签”，或完全不显示 rail；不要用正则补齐表面一致性。

## 7. 必须覆盖的测试与验收

### 7.1 方案 A 前端/xterm 边界

1. 多个 marker 的位置、点击 `scrollToLine`、hover/focus 展开和键盘可访问性；
2. marker dispose、scrollback trim、`clear()` 后 rail 清理；
3. wrapped CJK、多行粘贴、IME、Shift+Enter 与普通 Enter 不误增无界 bookmark；
4. resize/reflow 后定位仍指向接受范围内的行，或主动移除无法证明的 anchor；
5. alternate buffer 不建立虚假 marker，切回 normal 后状态正确；
6. TerminalView parking/re-parenting 不丢本地书签，Run/Workspace unmount 则全部释放；
7. 最多 N 个 bookmark、preview 最多 N 字符、每帧最多一次位置状态更新；
8. 选择/复制、mouse tracking、现有 wheel fallback 和 pane resize 不被 rail 截获。

现有测试只覆盖 terminal client 的 restore-before-live、大小边界和失败，以及 alternate-screen
wheel resolver；没有 marker/decoration/scroll rail 测试。
[`terminal-client.test.mjs` L91-L109、L252-L335](../../frontend/tests/terminal-client.test.mjs)；
[`terminal-wheel-fallback.test.mjs` L1-L18](../../frontend/tests/terminal-wheel-fallback.test.mjs)

### 7.2 产品级契约边界

1. HTTP/stream 精确字段、`snake_case`、稳定 `input_id` 与有界分页；
2. authoritative write 在 anchor/event 发布前提交；提交失败不出现幽灵刻度；
3. snapshot-to-live 对 input/anchor 不丢不重；重连时 message ID 与 anchor 对账；
4. input delivery `uncertain` 时不自动重投，也不标记为确定已发送；
5. Run restart、历史截断和 anchor unavailable 的 UI 状态；
6. 慢消费者与超量历史按既有 Terminal/stream 策略关闭或分页，不形成无界内存；
7. Codex `thread/read` 恢复后，同一 `userMessage.id` 只渲染一次且定位稳定。

## 8. 最终建议

**功能值得做，但先把承诺拆清。** 左侧 rail、hover 预览、点击滚动和短暂高亮没有浏览器
技术障碍；xterm 6 已具备 marker、scroll 和 decoration 原语。真正的难点是当前 Termestra
没有“用户消息”这一结构化读模型，也没有 message ID 到 terminal row 的稳定映射。

因此建议：用 session-local normal-buffer 书签做低成本交互实验；若反馈证明有价值，Codex
直接转向 App Server 的结构化 `userMessage`/Turn UI。不要把 raw PTY 文本解析方案演化成
长期公共契约。
