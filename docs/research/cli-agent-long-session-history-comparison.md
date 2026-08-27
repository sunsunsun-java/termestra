# 主流 CLI Agent 的长会话历史实现对比

> 调研日期：2026-08-24  
> 范围：Codex CLI、Claude Code、Gemini CLI、OpenCode、Cursor CLI/Agent、Pi。  
> 证据口径：只引用官方文档、官方仓库源码或一方公开协议；没有公开证据的能力明确标为“未知”。

## 结论先行

主流实现没有把“终端可以无限上滚”当作长会话历史的最终方案。它们普遍拆成三层：

1. **显示层**：native terminal scrollback、alternate-screen 内的滚动视图，或虚拟化列表；
2. **会话层**：JSONL、SQLite 或服务端 thread，是恢复、分支和重建 UI 的权威数据；
3. **模型上下文层**：受 token window 约束，通过摘要、压缩和丢弃旧工具输出来控制大小。

因此，xterm 的 10,000 行只应该被解释为“近期终端画面缓存”，不应被解释为“这个 session 只保存 10,000 行”。真正避免旧消息消失的方法是：持久化结构化 transcript，并在用户向上滚动时按稳定游标加载更早的 turn/message。

就公开实现而言：

- **Codex CLI 当前主线**最接近“滚到顶部继续加载旧历史”：初始只水合最近 turns，旧 items 使用 cursor 分页，并在 TUI 中 prepend 到 transcript；不过相应 App Server API 仍标为 experimental。
- **Claude Code**采用“持久 JSONL + 全屏虚拟化 scrollback”，公开承诺长会话只保留可见消息在渲染树中；但未公开磁盘分页算法。
- **OpenCode 当前 dev/V2**的会话权威层是 SQLite，桌面/Web 客户端已经实现 cursor 分页和向上加载；但当前 **CLI TUI 只加载最近 100 条消息且没有 `loadMore`**，因此不能在 CLI 中一路滚到 session 开头。
- **Gemini CLI**会保存完整 session，并能虚拟化 alternate-buffer 的渲染；当前 resume 路径更接近一次加载整个 conversation，而不是旧 turn 游标分页。
- **Pi**保留 append-only JSONL 会话树；压缩不删除旧节点，`/tree` 可回看完整历史，但 UI 会一次物化 session/document，没有发现磁盘历史的 cursor 分页接口。
- **Cursor**公开了 resume、fork、rewind 和上下文摘要，并说明摘要后 Agent 可以通过历史文件查回细节；CLI 渲染器、逐条 transcript 存储格式和滚动分页没有公开到足以验证。

## 横向对比

| 产品 | 当前对话如何显示 | 持久会话与恢复 | 更旧消息如何加载 | 上下文压缩 | 终端 scrollback 耗尽后 |
|---|---|---|---|---|---|
| Codex CLI | inline/native scrollback，另有应用维护的 transcript；alternate-screen 可配置 | 磁盘 rollout/thread；resume、read、fork、archive | `turns/items` cursor 分页；TUI 初始有界水合并 prepend 旧页 | 自动/手动 compaction，替换模型可见历史 | native 行会消失，但持久 thread 中受支持的 items 可重新分页显示 |
| Claude Code | classic 使用 native scrollback；fullscreen 使用 virtualized scrollback | 本地 session JSONL；continue/resume/export；默认有清理期限 | fullscreen 可跳到开头并搜索；磁盘分页细节未公开 | 先清旧 tool output，再 summary；支持 `/compact` | classic 画面受终端限制；session 仍可恢复；fullscreen 不依赖 native 行数 |
| Gemini CLI | 默认 normal buffer；alternate buffer 有应用内滚动和虚拟化渲染 | 本地 conversation 记录；resume/browser；默认有保留期限 | resume 当前读取完整 conversation；未发现 cursor 旧页 API | `/compress` 生成 summary/state snapshot 并保留 recent tail | native 旧行会丢；记录仍在 session 文件，但没有已公开的滚顶磁盘补页合同 |
| OpenCode dev/V2 | CLI TUI 是应用滚动区，但只投影最近 100 条 | SQLite 中的 Session/Message/Part；可继续、导入/导出 session | CLI TUI 无 `loadMore`；桌面/Web 才有 cursor page + prepend | `/compact`/`/summarize` 压缩模型上下文 | 旧消息仍可在持久层，但 resume CLI 仍只显示最近 100 条；需导出或换支持旧页加载的客户端 |
| Cursor CLI/Agent | fullscreen 等参数公开；内部历史渲染机制未公开 | 支持 list/resume/fork/rewind；本地/云 Agent 均有持久状态能力 | CLI 是否 cursor/lazy load 未公开 | 自动摘要、`/summarize`/`/compress`；摘要可引用历史文件 | 预计可 resume，但是否能在原 CLI 里无限回滚到首条，公开资料不足 |
| Pi | regular 使用 main buffer；实验 fullscreen 是应用自有滚动区 | append-only JSONL session tree；resume、branch、`/tree` | 整个 JSONL 读入数组，无 cursor/lazy disk paging | 压缩模型上下文但不删除 JSONL 完整历史 | regular 旧画面受 terminal 限制；可 resume/`/tree` 找回；fullscreen 将成本转移到内存和重绘 |

“终端 scrollback 耗尽后”的结论只描述公开可验证的恢复路径，不等于保证保存无限原始 PTY 字节。多数产品会截断、折叠、外置或压缩大型工具输出。

## 分产品证据

### 1. Codex CLI

**观察到的事实**

- App Server 将会话建模为 thread/turn/item，支持 `thread/resume`、`thread/read`、fork 和 archive；archive 会移动磁盘上的 persisted rollout JSONL。[App Server README](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)
- 同一协议提供 `thread/turns/list` 和 `thread/items/list` 的 cursor/limit/direction 分页，并建议在不想一次读取全部 turns 时使用增量 API；这些接口目前标为 experimental。[App Server README：thread history](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md#example-list-thread-turns-experimental)
- TUI 历史分页模块的源码注释直接说明：加载更旧的 transcript page 时不重写 terminal-native scrollback；取得旧页后会 prepend cells，并保持/补足顶部滚动位置。[`history_pagination.rs`](https://github.com/openai/codex/blob/main/codex-rs/tui/src/app/history_pagination.rs)
- App Server session 的 TUI 水合常量是最近 5 个 turns、每页 100 items，并维护 older-history cursor；这是有界的初始加载，不是把全会话一次放进 UI。[`history.rs`](https://github.com/openai/codex/blob/main/codex-rs/tui/src/app_server_session/history.rs)
- alternate screen 配置允许 `auto`、`always`、`never`；源码明确说明 `never` 的 inline 模式可保留 terminal scrollback。[`types.rs`](https://github.com/openai/codex/blob/main/codex-rs/config/src/types.rs)
- Codex 也有自动/手动 compaction 事件；这是模型上下文管理，不等同于删除持久 thread。[App Server README：compaction](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)

**工程推断**

- Codex 的目标架构是“双层历史”：native scrollback 保留近期自然输出，结构化 transcript/thread 在上滚越界时补回旧内容。终端行数不是 session 的权威容量。

**未知或限制**

- 分页接口仍为 experimental，不能据此承诺所有已发布 CLI 版本都有相同行为。
- persisted item 不等同于逐字保存所有原始 PTY/tool bytes；大输出可能在进入 transcript 前已经被折叠或截断。

### 2. Claude Code

**观察到的事实**

- session 持续保存到 `~/.claude/projects/<project>/<session-id>.jsonl`，支持 `--continue`、`--resume`、`/resume` 和 export；默认 30 天清理，可通过 `cleanupPeriodDays` 修改。[Sessions 文档](https://code.claude.com/docs/en/sessions)
- 当前 fullscreen renderer 使用 alternate screen 和 virtualized scrollback；官方说明只把可见消息放在 render tree 中，因此渲染内存不会随会话长度线性增加。支持滚动、跳到开头和 transcript 搜索，也能把 conversation 写回 native scrollback。[Fullscreen 文档](https://code.claude.com/docs/en/fullscreen)
- classic renderer 仍使用终端 native scrollback，因此其可见容量由用户的 terminal/tmux 配置决定。[Terminal configuration](https://code.claude.com/docs/en/terminal-config)
- 当 context window 接近上限时，Claude Code 会先清理较旧 tool output，再总结 conversation；`/compact` 可手工压缩模型历史。[Context window](https://code.claude.com/docs/en/context-window)

**工程推断**

- fullscreen 的“可滚回开头”由 Claude 自己的 transcript 视图支撑，而不是要求宿主 terminal 提供无限 scrollback。
- JSONL 负责恢复会话；virtualization 负责降低渲染成本；compaction 负责降低模型 token 成本。三者解决的是不同问题。

**未知或限制**

- 官方没有公开 fullscreen 是否按磁盘页懒加载、页大小、内存索引和淘汰策略；只能确认“虚拟化渲染”，不能进一步声称“cursor 分页”。
- 默认 retention 到期后 session 会清理，因此“永久历史”需要产品层的保留策略，而不是只靠 renderer。

### 3. Gemini CLI

**观察到的事实**

- Gemini CLI 自动记录完整 session 到 `~/.gemini/tmp/<project_hash>/chats/`，记录包含 prompts、model responses、tool executions、token usage 和 reasoning summaries；支持 `--resume` 和 session browser，默认保留 30 天。[Session management](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/session-management.md)
- `ui.useAlternateBuffer` 默认关闭，因此默认路径仍主要使用 native terminal buffer；alternate-buffer 模式提供应用内 PageUp/PageDown、跳顶和跳底。[Settings](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/settings.md)、[Keyboard shortcuts](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/keyboard-shortcuts.md)
- UI history manager 把 `HistoryItem[]` 保存在 React state 中并 append 新项；alternate-buffer 的 `MainContent` 对这些历史项做可滚动/虚拟化呈现。[`useHistoryManager.ts`](https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/ui/hooks/useHistoryManager.ts)、[`MainContent.tsx`](https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/ui/components/MainContent.tsx)
- conversation loader 可以流式读记录文件，但未指定 `maxMessages` 时会构造完整 messages 数组；当前 resume 初始化走的是整个 `ConversationRecord`，没有公开 turn cursor。[`chatRecordingService.ts`](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/services/chatRecordingService.ts)
- chat compression 会生成 summary/state snapshot，并保留一段 recent history；默认 compression threshold 为 context window 的 0.5。[`chatCompressionService.ts`](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/context/chatCompressionService.ts)、[Configuration](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/configuration.md)

**工程推断**

- Gemini 的 alternate-buffer “虚拟化”主要降低 DOM/Ink 渲染量，但当前 history 数组和 resume 路径仍更接近 eager load；这与“从磁盘按页加载旧消息”不是一回事。
- normal-buffer 中被 terminal 淘汰的旧行仍可能存在于 session 记录，可通过恢复会话访问；但当前公开实现没有 Codex 式 scroll-top cursor contract。

**未知或限制**

- 未找到单个超长 session 可以在当前 UI 中无限滚到第一条消息的正式保证，也未找到按 turn/item 分页的公开 API。

### 4. OpenCode

以下结论明确针对官方仓库当前 `dev`/V2 代码；它不能自动代表所有稳定发行版。尤其要区分 CLI TUI 与桌面/Web 客户端。

**观察到的事实**

- V2 将 `Session`、`Message`、`Part` 存入 SQLite 表；默认数据库位置为 `~/.local/share/opencode/opencode.db`。[`session.sql.ts`](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/session/session.sql.ts)、[V2 troubleshooting](https://opencode.ai/v2/docs/troubleshooting)
- 当前 CLI TUI 打开 session 时请求最近 100 条消息，随后又执行 `slice(-100)`，并删除更旧消息的 part 前端缓存；该 TUI 路径没有 `loadMore`。[`sync.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/tui/src/context/sync.tsx#L567-L630)
- CLI TUI 使用应用自己的 `ScrollBoxRenderable`，滚动、Page Up/Down、首尾跳转只作用于“已加载的最近 100 条”。[`session/index.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/tui/src/routes/session/index.tsx)
- 与 CLI 不同，桌面/Web 客户端的数据层和时间线支持 cursor、`loadOlder`、prepend，并在插入旧页后恢复滚动锚点。[`server-session.ts`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/context/server-session.ts)、[`message-timeline.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/session/timeline/message-timeline.tsx)、[`session.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/session.tsx)
- TUI 公开 `/sessions` 和 `/compact`；V2 compaction 还公开 durable request/event，使压缩成为 session 状态变化而不是纯屏幕行为。[TUI docs](https://opencode.ai/docs/tui/)、[V2 compaction](https://opencode.ai/v2/docs/compaction)

**工程推断**

- OpenCode 清楚地区分了“SQLite 完整会话”和“当前客户端投影”。终端 scrollback 即便配置成无限，也无法展示 CLI 根本没有加载的第 101 条以前消息。
- 桌面/Web 的 cursor + prepend 实现证明同一权威数据可以支撑按需旧页；CLI TUI 尚未接入这条路径。

**未知或限制**

- 当前 CLI TUI 源码明确没有旧页 `loadMore`。官方没有承诺 100 条投影限制是稳定公共契约，也没有公开其接入旧页分页的路线图。

### 5. Cursor CLI / Agent

**观察到的事实**

- Cursor CLI 公开 thread list/resume，并提供 `/resume`、`/fork`、`/rewind`；命令行也有 fullscreen 运行参数。[CLI usage](https://docs.cursor.com/en/cli/using)、[Slash commands](https://prod.cursor.com/docs/cli/reference/slash-commands)、[CLI parameters](https://docs.cursor.com/en/cli/reference/parameters)
- Cursor 会在 context 填满时自动总结较早消息，并提供 `/summarize`（`/compress` alias）。[Chat summarization](https://docs.cursor.com/en/agent/chat/summarization)
- Cursor 官方说明其 Agent 将 chat history 表示为文件；摘要可引用该历史文件，Agent 在摘要遗漏细节时能够搜索它，而不必把所有旧消息继续塞入模型 context。[Dynamic context discovery](https://cursor.com/blog/dynamic-context-discovery)
- Cursor Agent SDK 公开持久 conversation/run、`Agent.resume`、分页列举 agents/runs 和 message list；这证明 Cursor 平台具备会话恢复能力，但 SDK 不是 CLI renderer 的实现说明。[Cursor Agent SDK](https://prod.cursor.com/docs/sdk/python)

**工程推断**

- Cursor 同样把“模型摘要”和“可查的历史资料”分开；summary 不是历史删除的同义词。

**未知或限制**

- Cursor CLI 是闭源分发。公开资料没有说明 CLI 的逐条 transcript 文件格式、列表 virtualization、磁盘 page size、scroll-top lazy loading，也没有保证在同一 CLI 画面中可连续滚回首条消息。
- 不能用 Cursor IDE chat 的 SQLite 实现细节直接替代 Cursor CLI 的证据。

### 6. Pi

**观察到的事实**

- Pi 把 session 保存为树形 JSONL，默认位于 `~/.pi/agent/sessions/`；支持 `pi -c`、`pi -r`、指定 session、分支和 `/tree`，后者还能用标签作为书签。[Coding Agent README：sessions](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/README.md#sessions)
- compaction 会总结旧消息、保留 recent tail；官方明确说明它是有损的模型上下文变换，但完整历史仍保留在 JSONL，可通过 `/tree` 回看。[Coding Agent README：compaction](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/README.md#compaction)
- `SessionManager` 顺序读取整个 JSONL 并把 entries 放入数组，不是按 cursor/page 加载。[`session-manager.ts`](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/src/core/session-manager.ts#L480-L518)
- 默认 regular renderer 使用 terminal main buffer，由 terminal 拥有 scrollback；实验性 fullscreen 使用 alternate buffer 和应用自有滚动视口，退出时恢复 main buffer。[Pi TUI README](https://github.com/earendil-works/pi/blob/main/packages/tui/README.md#tui-interface-and-renderers)、[`tui-alt-screen.ts`](https://github.com/earendil-works/pi/blob/main/packages/tui/src/tui-alt-screen.ts#L156-L168)

**工程推断**

- regular 模式下，被 terminal 淘汰的旧行不能靠继续滚动找回，但旧消息仍在 JSONL，可 resume 或通过 `/tree` 访问。
- fullscreen 避开 native scrollback 上限，却会一次物化 session/document；它把成本转移到启动、内存和重绘，并没有实现磁盘旧页分页。

**未知或限制**

- 未找到旧 transcript 的 cursor/lazy disk pagination，也未找到 fullscreen 对超长 session 的固定内存容量保证。

## 对 Termestra 的直接设计建议

### 1. 不取消 10,000 行，但改变它的产品语义

保留 xterm `scrollback = 10_000` 作为 **PTY 近期画面缓存** 是合理的：它有明确内存上限，支持普通 shell/TUI，也避免单个长 session 把浏览器拖垮。界面和文档不得把它称为“会话历史上限”。

### 2. 让结构化 transcript 成为唯一历史权威

为 Agent 会话持久化 `Session -> Turn -> Message -> Part`，每个对象有稳定 ID、顺序键和时间戳。聊天文本、工具调用、结果摘要、状态变化与 PTY 字节流分开建模。session detail 首次只返回最新一页：

```text
打开 session
  -> 读取最后 50~100 条结构化消息
  -> 渲染虚拟列表
向上滚到阈值
  -> GET messages?before=<opaque_cursor>&limit=100
  -> prepend，并保持原视觉锚点
到第一页
  -> 显示“已到会话开头”
```

这样即使 xterm 已淘汰旧行，用户仍能从 transcript 页面滚到最早一条持久消息。

### 3. “完整历史”也必须定义边界

不应承诺永久逐字保存无限 raw stdout。建议：

- 对用户/Agent 文本和工具调用元数据做完整结构化持久化；
- 大型 tool/PTY output 单独落 blob/file，message part 只存引用、byte count、hash 和 truncation 状态；
- 设单 artifact、单 session、保留天数和总磁盘配额，并把删除/截断状态显式展示；
- compaction 只产生新的 model-context projection/summary，默认不删除 transcript；真正的数据清理走独立 retention policy。

### 4. 书签绑定 message ID，不绑定行号

书签应记录 `session_id + message_id/turn_id + optional part offset`。xterm 行号会随 reflow、窗口宽度、ANSI 重绘和 scrollback 淘汰而变化，不适合作为可恢复定位。恢复书签时先按 ID 找到所在历史页，再加载并滚到该元素。

### 5. 对不同 CLI 的能力分级

- **结构化协议型**（如 Codex App Server、OpenCode API）：消费原生 thread/message IDs 和游标，能提供完整的历史翻页、书签和恢复。
- **本地 transcript 型**（如 Claude、Gemini、Pi）：在版本和授权允许时读取/跟随其官方持久格式；格式不稳定时要有 adapter version 与降级提示。
- **纯 PTY/闭源型**（或未公开协议的 Cursor CLI）：只能保证近期 raw scrollback。不要从 ANSI 画面猜测可靠 message boundary，也不要宣称能恢复完整结构化历史。

## 最终判断

用户期望的“一个 session 再长，也能一直向上看到最早消息”是合理的，但不能通过把 xterm 的 10,000 改成无限来实现。无限 terminal scrollback 会把重排、DOM/Canvas 状态和内存成本绑在单个前端进程上，而且仍无法可靠表示分支、压缩、工具 artifact 和书签。

更符合市场成熟实现的方案是：

> **有界 terminal scrollback + 持久结构化 transcript + cursor 分页/虚拟化 + 独立的模型 compaction。**

其中，“能无限上滚”的产品承诺应精确定义为：只要消息仍在 retention policy 内，客户端就能按页加载直到 session 的第一条持久消息；它不是“在内存里永久保留无限终端行”，也不是“永久保存无限原始输出字节”。
