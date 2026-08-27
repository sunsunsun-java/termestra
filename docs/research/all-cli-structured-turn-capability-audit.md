# Termestra 全部内置 Agent CLI 的结构化 Turn 能力审核

> 状态：外部兼容性审核，不是 ADR，也不定义当前产品行为  
> 审核日期：2026-08-24  
> 范围：指挥官侧“运行中显示过程，成功后自动折叠并只保留正确最终回答”能力；覆盖
> Termestra 当前全部 10 个内置 Command Preset。本文不包含实现。

## 结论先行

**当前 Termestra 中符合要求的内置 CLI 是 0/10。** 原因不是这些 CLI 都没有机器协议，
而是当前 10 个 preset 都启动交互式 TUI，并把原始 PTY 输出交给 xterm；Termestra 尚未接入
任何 provider 的结构化 Turn 接口。

增加专用 adapter 后，审核结果分成两种口径：

1. **核心 final-only 能力**：能可靠识别本次 Turn、成功/失败/中断和最终回答，从而只在成功
   时自动折叠过程。
2. **完整控制能力**：除核心能力外，还要求结构化进度、审批往返、合作取消、会话恢复、
   请求级事件关联和稳定公开协议。

| CLI | 当前 Termestra | 专用 adapter 后的完整评级 | 核心 final-only 判断 |
| --- | --- | --- | --- |
| Codex | Unsupported：裸 TUI/PTY | **Full** | Pass，App Server |
| Claude Code | Unsupported：裸 TUI/PTY | **Partial；升级并用 SDK 后 Full** | Pass，必须以 `ResultMessage.result` 为准 |
| OpenCode | Unsupported：裸 TUI/PTY | **Full，版本门禁** | Pass，Server + SSE + message snapshot |
| Gemini CLI | Unsupported：裸 TUI/PTY | **Partial** | Conditional，final 需从本 prompt chunks 封存 |
| Hermes | Unsupported：裸 TUI/PTY | **Partial** | **Fail/Blocked**，异常可能仍返回 `end_turn` |
| Qwen Code | Unsupported：裸 TUI/PTY | **Full，新版与 capability 门禁** | Pass，优先 `qwen serve` |
| Pi | Unsupported：裸 TUI/PTY | **Partial** | Conditional，必须单飞并等待 `agent_settled` |
| Antigravity CLI | Unsupported：裸 TUI/PTY | **Partial** | Pass（当前 yolo 模式）；审批/单 Turn cancel 不完整 |
| Cursor CLI | Unsupported：裸 TUI/PTY | **Full** | Pass，ACP |
| Grok Build | Unsupported：裸 TUI/PTY | **Full，版本门禁** | Pass，ACP/stdio |

按完整控制契约统计：当前版本组合是 **5 Full、5 Partial**。Claude Code 升级并采用官方
Agent SDK 后可变为 **6 Full、4 Partial**。因此，“全部内置 CLI 已经符合”不成立；若把
全部适配作为发布门槛，至少 Hermes 是当前硬阻塞，Gemini、Pi、Antigravity 还需要明确的
能力降级或补强。

## 1. 审核对象与当前启动事实

内置清单来自
`backend/src/main/java/dev/termestra/platform/persistence/sqlite/ConfigurationSchemaMigrations.java`：

```text
claude  codex  opencode  gemini  hermes
qwen    pi     agy       cursor  grok
```

对应命令分别是 `claude`、`codex`、`opencode`、`gemini`、`hermes`、`qwen`、`pi`、
`agy`、`cursor-agent` 和 `grok`。

当前 `CommandPreset` 只保存 command、args、env、resume template、session ID capture 和 yolo
参数，没有 `adapter_id`、机器协议、版本范围或 capability 描述。
`backend/src/main/java/dev/termestra/configuration/domain/model/CommandPreset.java`

现有 session capture 只从 Claude/Codex/Gemini 文件或 OpenCode SQLite 中识别 provider session
ID，用于恢复；它不会产生 Turn、progress、final 或 completion 事件。
`backend/src/main/java/dev/termestra/execution/adapter/out/session/FilesystemAgentSessionCapture.java`

所以当前能力是：

```text
Provider Session identity ≠ Turn lifecycle ≠ authoritative final answer
```

## 2. 统一审核门槛

### 2.1 核心 final-only 必须全部满足

| 编号 | 门槛 |
| --- | --- |
| F1 | 用户提交能够关联到稳定 Turn/prompt，或 adapter 强制单飞保证无歧义 |
| F2 | 有结构化过程或至少明确 running 状态，不解析人类 TUI 文案 |
| F3 | 最终回答来自权威 result/final item，或由官方定义的完整 prompt 消息块确定性封存 |
| F4 | 能明确区分 completed、failed、interrupted；进程消失不得当作成功 |
| F5 | completed 但 final 缺失时报告协议错误，不折叠为空白 |
| F6 | adapter 做版本或 capability probe，并有真实契约测试 |

只有 F1–F6 全部通过，才能启用自动 final-only。

### 2.2 Full 评级的附加门槛

```text
structured_progress
authoritative_final
explicit_terminal_status
approval_round_trip
durable_deferred_approval
cancel_with_terminal_event
session_resume
request_scoped_events
documented_machine_protocol
```

`inflight_turn_resume` 没有在任何本次审核对象上得到普遍保证。provider 进程在活跃 Turn 中
崩溃时，统一规则应是 `uncertain/interrupted`，禁止自动重放用户输入。

## 3. CLI 逐项审核

### 3.1 Codex：Full

推荐模式：

```bash
codex app-server
```

默认 stdio 是 JSONL。App Server 明确提供：

- `agentMessage.phase=commentary | final_answer`；
- reasoning summary、plan、command、MCP、file change 等结构化 item；
- delta 与权威 `item/completed`；
- `turn/completed.status=completed | failed | interrupted`；
- 审批请求、`turn/interrupt`、`thread/resume`。

[Codex App Server 官方文档](https://learn.chatgpt.com/docs/app-server)

映射：

```text
commentary / reasoning summary → progress
item/completed(final_answer)   → authoritative final
turn/completed(completed)      → completed
turn/completed(failed)         → failed
turn/completed(interrupted)    → interrupted
```

折叠条件必须同时满足成功 Turn 和完整 final item。App Server/WebSocket 的实验性边界、schema
随版本变化和活跃 Turn 崩溃不可续跑，要求固定版本、生成对应 schema，并把崩溃映射为
`uncertain`。第一版应使用默认 stdio，不使用标为 unsupported 的 WebSocket transport。

### 3.2 Claude Code：当前 Partial，升级并采用 SDK 后 Full

裸 CLI 最低可用模式：

```bash
claude -p \
  --input-format stream-json \
  --output-format stream-json \
  --verbose \
  --include-partial-messages
```

推荐使用官方 Agent SDK。SDK区分 Assistant、tool/stream events 和最终 Result；只有
`ResultMessage.subtype=success` 才携带权威 `result`。最后一个 AssistantMessage 可能只是
工具调用前文字，不能直接当 final。

[Claude Agent SDK agent loop](https://code.claude.com/docs/en/agent-sdk/agent-loop)、
[streaming output](https://code.claude.com/docs/en/agent-sdk/streaming-output)

本机 Claude Code 2.1.76 可以获得结构化过程和 Result，但持久化 deferred approval 是
2.1.89 才加入的能力；裸 `stream-json` 也没有证明能完整接管 host approval。上线应升级并
固定 CLI/SDK组合，使用 `canUseTool`，中断后排空 Result，防止旧 Turn 事件串入新 Turn。
[Claude Code 2026 W14 更新](https://code.claude.com/docs/en/whats-new/2026-w14)

### 3.3 OpenCode：Full，但必须固定版本与回读 snapshot

推荐模式：

```bash
OPENCODE_SERVER_PASSWORD=<random-local-secret> \
opencode serve --hostname 127.0.0.1 --port <allocated-port>
```

官方 Server 提供 session、message/prompt_async、SSE events、abort、permission 和持久 session。
消息模型区分 text、reasoning、tool；assistant message 有 completed、finish 和 error。
[OpenCode Server API](https://opencode.ai/docs/server/)、
[官方生成类型](https://github.com/anomalyco/opencode/blob/v1.15.11/packages/sdk/js/src/gen/types.gen.ts)

正确策略：SSE 只做低延迟投影；session idle 或响应结束后，必须回读持久 message snapshot，
检查 assistant completed/finish/error，再固化 final。不能把 `session.idle` 直接当成功。

不推荐 `opencode run --format json` 作为主协议。官方仓库已有丢 final、stdout 未 flush、只输出
step_start 和 resume 后 stdout 为空等问题记录：
[#26855](https://github.com/anomalyco/opencode/issues/26855)、
[#29866](https://github.com/anomalyco/opencode/issues/29866)、
[#31404](https://github.com/anomalyco/opencode/issues/31404)、
[#31482](https://github.com/anomalyco/opencode/issues/31482)。

### 3.4 Gemini CLI：Partial

需要审批的持久指挥官优先：

```bash
gemini --acp
```

无人值守/yolo 可使用：

```bash
gemini -p "<prompt>" --output-format stream-json --resume <session-id>
```

ACP 提供 new/load session、prompt、cancel、permission 和结构化 thought/tool/message update；
`stopReason=end_turn|cancelled` 给出边界。[Gemini ACP mode](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/acp-mode.md)

缺口是 prompt response 没有独立完整 final 字段，adapter 必须从该 prompt 期间的
`agent_message_chunk` 重建并在 `end_turn` 时封存。官方仓库还存在连续 prompt 串回答和
loadSession 未恢复上下文的版本回归记录：
[#24017](https://github.com/google-gemini/gemini-cli/issues/24017)、
[#27913](https://github.com/google-gemini/gemini-cli/issues/27913)。

因此 Gemini 必须固定版本，并通过连续多轮、tool 前后文本、审批、取消、认证失败、进程崩溃、
loadSession 和空 final 的真实契约测试，才能针对核心功能启用自动折叠；完整评级仍为 Partial。

### 3.5 Hermes：Partial，核心功能当前 Blocked

精确身份是 [NousResearch Hermes Agent](https://github.com/NousResearch/hermes-agent)。推荐
机器入口是：

```bash
hermes acp
```

ACP实现具有 thought、message、tool、permission、cancel、load/resume 和 prompt stop reason。
[Hermes ACP Internals](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/developer-guide/acp-internals.md)

硬阻塞有三个：

1. 当前 0.20.0 实现可能把 executor/conversation 异常转换成 `Error: ...` 普通 assistant
   文本，随后仍返回 `PromptResponse(stop_reason="end_turn")`，会让 UI 把失败误判为成功；
2. ACP 仍以 `use_unstable_protocol=True` 运行；
3. 官方问题 [#79196](https://github.com/NousResearch/hermes-agent/issues/79196) 记录 0.20.0
   取消/断连后 running 状态可能永久不能复位。

Hermes 不能按当前版本正式启用自动 final-only。必须升级到修复版本或维护一个经过测试的
兼容 adapter，能把错误识别成 failed，并解决取消卡死问题。

### 3.6 Qwen Code：Full，新版与 capability 门禁

首选：

```bash
QWEN_SERVER_TOKEN=<random-local-secret> \
qwen serve --host 127.0.0.1 --port <allocated-port>
```

`qwen serve` 提供带 `promptId` 的 submit、SSE progress/tool、明确 final 选择规则、
`turn_complete/turn_error`、permission、cancel、load/resume、Last-Event-ID 和 gap/resync。
[Qwen Serve Protocol](https://github.com/QwenLM/qwen-code/blob/main/docs/developers/qwen-serve-protocol.md)

启动时必须读取 `/capabilities`，至少要求 session prompt/events、typed event schema、cancel、
permission vote、load 和 resume。不能只用快速变化的版本号判断。

若需要同时保留原生 TUI，Qwen 官方 Dual Output 与 Termestra 场景高度匹配：

```bash
qwen \
  --json-file <private-runtime-dir>/events.jsonl \
  --input-file <private-runtime-dir>/input.jsonl
```

PTY 保留原生 TUI，JSONL 并行驱动 Chat UI。Dual Output 提供 protocol version、partial/completed
message、result、control request/response 和 session end；但缺少独立外部 cancel，所以正式
adapter 仍优先 `qwen serve`。[Qwen Dual Output](https://github.com/QwenLM/qwen-code/blob/main/docs/users/features/dual-output.md)

### 3.7 Pi：Partial

本机 `pi` 是 `@earendil-works/pi-coding-agent 0.84.1`，不是仅凭命令名可假定的历史
`@mariozechner/pi-coding-agent`。推荐：

```bash
pi --mode rpc --session-id <termestra-session-uuid>
```

RPC提供 text/thinking delta、tool lifecycle、权威 message_end、retry/compaction、abort、session
entries 和 `agent_settled`。[Pi RPC](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc.md)

限制：普通 agent events 通常没有 prompt request ID，所以同一个 RPC session 必须由 adapter
强制最多一个活动 Turn。`agent_end` 后仍可能 retry/compact/continue，必须等
`agent_settled`，再读取最后 assistant message 和 `stopReason`；`settled` 自身不代表成功。

Pi 刻意不内置逐次 permission popup，`--approve` 也不是工具审批协议，因此完整评级只能是
Partial。adapter 还必须探测 package identity、版本、RPC capability 和实际
`agent_settled`，未知同名 Pi 必须降级。

### 3.8 Antigravity CLI：Partial；当前 yolo 模式可完成核心功能

精确身份是 Google Antigravity CLI，命令为 `agy`。推荐：

```bash
agy --input-format stream-json --output-format stream-json
```

双向 NDJSON 提供 step_update、text_delta、tool、checkpoint、user_input、权威
`result.response` 和 `SUCCESS|ERROR|CANCELED|INTERRUPTED|INVALID|WAITING|RUNNING` 状态，
并有 conversation ID 与恢复参数。
[Antigravity headless mode](https://antigravity.google/docs/cli/headless/)

缺口：headless 没有交互式 permission request；工具要么 soft-deny，要么
`--dangerously-skip-permissions`；SIGINT 可得到 INTERRUPTED，但会结束整个进程，没有独立
单 Turn cancel。Termestra 当前 preset 本来使用 yolo 参数，因此核心 final-only 可实现，
完整控制契约仍为 Partial。

### 3.9 Cursor CLI：Full

当前官方主命令是 `agent`，`cursor-agent` 是兼容别名。推荐优先探测：

```bash
agent acp
```

旧安装回退：

```bash
cursor-agent acp
```

官方 ACP 提供 new/load session、prompt、session update、permission、cancel、JSON-RPC error、
plan approval、用户提问和 todo/subagent 通知。
[Cursor ACP](https://prod.cursor.com/docs/cli/acp)

headless stream-json 也有 assistant/tool 事件和明确的 success result，但交互指挥官优先 ACP，
以承载审批。[Cursor output format](https://docs.cursor.com/en/cli/reference/output-format)

Cursor CLI 仍标注 beta；必须锁定最低版本、忽略未知字段并通过 prompt/tool/cancel/load/
permission 契约测试。

### 3.10 Grok Build：Full，版本门禁

精确身份是 xAI/SpaceXAI 官方 [Grok Build](https://github.com/xai-org/grok-build)，不是同名
第三方 grok-cli。推荐：

```bash
grok --no-auto-update agent stdio
```

官方 ACP支持 new/load session、prompt、cancel、assistant/thought/tool update、permission UI 和
持久 session。[Grok Build Headless & Scripting](https://docs.x.ai/build/cli/headless-scripting)

headless 也提供 `text`、`thought`、`end` 事件和 `stopReason/sessionId/requestId`，失败输出 error
并使用非零退出码。ACP 在 0.2.x–1.0.x 变化较快，adapter 应只使用标准 ACP 基础子集，固定
验证过的最低版本，不能用 `_x.ai/*` 私有扩展作为成功判定唯一依据。

## 4. 推荐的兼容性模型

### 4.1 Preset 必须绑定 adapter，而不只是 command

建议内置 preset 增加：

```text
adapter_id
adapter_protocol
supported_version_range
capability_probe
launch_mode
fallback_mode
```

启动流程：

```text
定位 command
  → 精确识别发行物身份
  → 读取版本
  → 执行 capability probe
  → 运行 conformance smoke test / schema check
  → 启动机器协议 adapter
  → 不满足则明确降级原始 Terminal
```

不能仅按可执行文件名匹配。Pi 存在同名不同 fork，Cursor 有新旧命令别名，Grok/Hermes 也有
近名项目。

### 4.2 能力不是一个布尔值

```text
ProviderCapabilities
  structured_progress
  authoritative_final
  explicit_terminal_status
  approval_round_trip
  durable_deferred_approval
  cancel_with_terminal_event
  session_resume
  inflight_turn_resume
  request_scoped_events
```

UI 根据 capability 显示或禁用审批、取消、恢复，不应让 Partial provider 冒充 Full。

### 4.3 自动折叠的统一安全谓词

```text
turn.status == completed
AND final_answer.complete == true
AND final_answer.text is present
AND no unresolved approval/input request
AND provider stream reached its authoritative boundary
```

失败、中断、状态不明、协议丢失或 completed-without-final 都不折叠成 final-only。

## 5. 发布判断

### 当前不能宣称“全部 CLI 已适配”

- 10/10 当前仍是裸 TUI/PTY；
- Hermes 有失败误判硬阻塞；
- Gemini 需要从 chunks 重建 final 并验证已知多轮/恢复风险；
- Pi 缺审批协议和请求级关联；
- Antigravity 缺交互审批与单 Turn cancel；
- Claude 本机版本不足以承诺当前 SDK 的 deferred approval 全能力。

### 可以采用的发布门槛

每个内置 adapter 都必须通过同一 conformance suite：

```text
简单成功 + 非空 final
多次连续 Turn 不串线
tool 前后都有 assistant 文本
审批 allow / deny / pending
模型失败与工具失败
用户取消与进程退出
断流重连和 session resume
completed 但 final 缺失
未知/旧版本能力降级
```

如果产品要求 10 个内置 CLI 首发全部具有同等级 Full 能力，当前结论是 **不可发布**。如果产品
目标只限定“可靠 final-only”，则可以为 9 个 CLI 建立 adapter 和版本门禁，Hermes 需先修复
或升级；Gemini、Pi 仍必须通过专门的单飞/多轮契约测试才可启用。

自定义 command preset 没有已知协议，默认只能进入原始 Terminal 与手动折叠；只有显式绑定
一个已安装 adapter 后，才能获得自动 final-only。
