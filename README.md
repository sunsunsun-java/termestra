<p align="center">
  <img src="./frontend/web/public/logo.png" alt="Termestra 图标" width="112" />
</p>

# Termestra

<p align="center">
  <img src="./frontend/web/public/screenshots/termestra-promo-hero-light.png" alt="Termestra 本地优先 CLI Agent 协作工作空间" width="1120" />
</p>

**把你电脑上已有的 AI CLI 组成一支可见、可持续协作的团队。**

Termestra 为一个 Orchestrator 和多个 CLI Worker 提供共享的本地工作空间、
真实终端、持久化任务状态和可靠派单。每个 Agent 都是可见的本地 CLI 进程，
不会作为隐藏 subagent 消失在后台。

[![npm](https://img.shields.io/npm/v/%40termestra%2Fcli?label=npm)](https://www.npmjs.com/package/@termestra/cli)
[![Node.js 20+](https://img.shields.io/badge/Node.js-20%2B-43853d)](https://nodejs.org/)
![项目状态](https://img.shields.io/badge/status-alpha-f59e0b)
![支持平台](https://img.shields.io/badge/platform-macOS-64748b)

> Termestra 是本地优先应用。服务只监听 `127.0.0.1`，数据保存在你的电脑上，
> 选中的源码目录不会被 Termestra 删除。

## 快速开始

### 环境要求

- Node.js 20 或更高版本，并可使用 npm；
- 至少安装并登录一个受支持的 AI CLI，例如 Codex、Claude Code、Gemini 或
  OpenCode。

npm 包已经包含当前平台所需的 Java 运行时，**无需单独安装 JDK、Maven 或
pnpm**。

### 安装并启动

```bash
npm install -g @termestra/cli
termestra --version
termestra
```

启动后，在浏览器中打开
[http://127.0.0.1:3000](http://127.0.0.1:3000)。Termestra 不会自动打开浏览器。

npm 启动器会自动安装并选择与当前操作系统和 CPU 架构匹配的运行时包。

### 指定端口

```bash
termestra --port 4020
```

然后访问 [http://127.0.0.1:4020](http://127.0.0.1:4020)。

### 更新

```bash
termestra update
```

也可以显式安装最新版：

```bash
npm install -g @termestra/cli@latest
```

### 卸载

```bash
npm uninstall -g @termestra/cli
```

卸载不会删除已有 Workspace，也不会删除你选择的源码目录。运行数据默认保存在
`~/.config/termestra/`，需要清理时请先确认其中的数据不再需要。

## 为什么需要 Termestra

单个 AI 编程 CLI 已经很强，但同时协调多个 CLI 时，问题很快就会出现：

- Agent 分散在不同终端里，彼此缺少共享上下文；
- 很难确认谁正在工作、空闲、已停止，或正在等待输入；
- 任务可能已经记录，却没有真正送进 Worker 的终端；
- 终端重连或进程重启后，很难判断现场发生了什么；
- 开发、审查、测试和调研通常只能依次完成。

Termestra 把协作关系显式化。Orchestrator 面对的是一支持久化的真实团队，通过
精简的 `team` 协议派单，并收到与稳定 Dispatch ID 关联的报告。SQLite 是权威
状态源，因此刷新页面或重启运行时不会丢失团队名单和排队中的工作。

## 适合用来做什么

### 并行开发、审查和测试

通过内置场景创建 Coder、Reviewer 和 Tester，然后只给 Orchestrator 一个最终
目标，不必分别操作多个终端。

```text
实现免密登录。让一个成员负责开发，一个成员审查安全边界，另一个成员补充集成测试，最后汇总全部证据。
```

### 调研与事实核查

让一个 Worker 负责调研，另一个 Worker 检查来源和假设。每份报告都会保留其
真实团队成员和派单关系。

```text
比较这两种部署方案。请调研员收集一手资料，再由核查员挑战每一个重要结论。
```

### 文档流水线

通过“文档流水线”场景创建起草成员和审稿成员，在不创建隐藏 subagent 的前提下
分离写作与核验职责。

```text
为第一次参与项目的贡献者重写入门指南。起草成员负责成文，审稿成员逐条验证所有命令。
```

## 先试用演示模式

首次进入页面时选择 **试用演示**，即可打开带有预录 Orchestrator、Worker 和
Tasks 数据的只读工作空间。演示模式不会启动真实 AI CLI、不会修改真实仓库，
也不会消耗模型额度。

<p align="center">
  <img src="./frontend/web/public/screenshots/1.png" alt="Termestra 首次进入页面，可创建 Workspace 或打开只读演示" width="680" />
</p>
<p align="center"><sub>创建可信的本地 Workspace，或先打开只读演示。</sub></p>

<p align="center">
  <img src="./frontend/web/public/screenshots/2.png" alt="只读演示中的团队状态和 Tasks 面板" width="1120" />
</p>
<p align="center"><sub>任务、派单活动和团队状态集中显示。</sub></p>

<p align="center">
  <img src="./frontend/web/public/screenshots/3.png" alt="只读演示中的 Orchestrator 和 Worker 终端" width="1120" />
</p>
<p align="center"><sub>角色、状态和终端上下文在同一工作空间中保持可见。</sub></p>

## 首次使用

1. 添加 Workspace，并选择一个可信的本地目录。
2. 选择 Orchestrator CLI 预设并启动。
3. Termestra 会在 Workspace 中初始化 `.termestra/tasks.md` 和
   `.termestra/PROTOCOL.md`，用于任务跟踪和团队恢复。
4. 单独添加 Worker、导入角色模板，或选择一键组队场景。
5. 把最终目标告诉 Orchestrator。它可以使用 `team list` 查看团队、使用
   `team send` 派单，并收集 Worker 报告。

## 工作原理

```text
浏览器
  │ HTTP + 有界 WebSocket 数据流
  ▼
Termestra 本地运行时（127.0.0.1）
  │
  ├── Workspace、Team、Tasks、Marketplace、Settings
  ├── SQLite 权威状态与可靠 Dispatch 投递
  └── 本地 PTY 进程与终端生命周期
        │
        ├── Orchestrator CLI
        │     ├── team list
        │     ├── team send
        │     └── team cancel
        └── Worker CLI
              ├── team report
              └── team status
```

每个 Worker 都是界面中可见、可持久化的 Team Member；运行时由真实 PTY 进程
支撑。每个派单先写入 SQLite，再按 Worker 串行交付。团队名单、配置、排队任务
和运行元数据可以跨重启保留；实时进程不会保留，受影响的成员会恢复为
`stopped`。

Termestra 会区分明确失败、可重试失败和结果不确定的终端写入。结果不确定时不会
盲目重试，避免同一任务被执行两次；界面会把问题显式展示给用户处理。

## 主要能力

- **持久化 Workspace 与团队**：配置、角色、排队任务和运行元数据可以跨刷新及
  重启保留。
- **真实终端会话**：通过有界 WebSocket 数据流提供交互式终端，支持 resize、
  重连快照、向上连续浏览当前 Run 的可用滚动历史和慢消费者保护。
- **Orchestrator 输入书签**：在不取代连续滚动的前提下，快速预览并定位当前 Run
  可用历史中的已提交输入，不影响 Worker、Shell 或 TUI 鼠标交互。
- **可靠派单**：消息、Dispatch 和投递记录在同一事务中受理，后台调度器执行
  有限重试，并在重启后恢复待投递任务。
- **可见的投递问题**：明确失败和结果不确定的写入不会静默丢失。
- **一键组队场景**：内置开发·审查·测试、调研与核查、文档流水线。
- **Worker 角色市场**：提供可在加入 Workspace 前审阅的中英文角色模板。
- **Tasks 面板**：同步 `.termestra/tasks.md`，通过 revision 检测避免静默覆盖。
- **会话恢复**：Claude、Codex、Gemini 和 OpenCode 优先恢复原生 session；所有
  Provider 都有持久化恢复摘要兜底。
- **本地演示与双语界面**：无需真实 Provider 即可体验产品，并可切换英文与
  简体中文界面。

Termestra 当前有意不提供隐藏自动创建的 subagent、Workflow/DAG 自动化、定时
任务、Team Memory、远程访问和多用户认证。它也无法从技术上强制 Orchestrator
模型一定派单；委派依赖可见协议和提示契约。

## 支持的 Agent CLI

Termestra 从启动它的 Shell 所继承的 `PATH` 中检测可执行文件。请先单独安装、
登录并确认对应 AI CLI 可以正常运行。

| 预设 | 可执行命令 | 默认启动方式 | 恢复方式 |
| --- | --- | --- | --- |
| Claude Code | `claude` | 绕过权限确认 | 原生 session，失败后回退到恢复摘要 |
| Codex | `codex` | 绕过审批与沙箱 | 原生 session，失败后回退到恢复摘要 |
| OpenCode | `opencode` | Provider 默认模式 | 原生 session，失败后回退到恢复摘要 |
| Gemini | `gemini` | YOLO 模式 | 原生 session，失败后回退到恢复摘要 |
| Hermes | `hermes` | YOLO 模式 | Termestra 恢复摘要 |
| Qwen Code | `qwen` | YOLO 审批模式 | Termestra 恢复摘要 |
| Pi | `pi` | approve 模式 | Termestra 恢复摘要 |
| Antigravity CLI | `agy` | 绕过权限确认 | Termestra 恢复摘要 |
| Cursor CLI | `cursor-agent` | force 模式 | Termestra 恢复摘要 |
| Grok Build | `grok` | 自动批准 | Termestra 恢复摘要 |
| 自定义 | 用户定义 | 用户定义命令 | Termestra 恢复摘要 |

这些是便捷默认配置，不是沙箱。在敏感代码上使用前，请自行核对 CLI 及其启动
参数。

## 支持平台

| 平台 | npm 运行时包 | 目录选择方式 |
| --- | --- | --- |
| macOS Apple Silicon | `@termestra/runtime-darwin-arm64` | 原生选择器、服务器目录浏览或粘贴路径 |
| macOS Intel | `@termestra/runtime-darwin-x64` | 原生选择器、服务器目录浏览或粘贴路径 |

当前源码与后续版本只支持 macOS。历史 Linux/Windows npm runtime 不再更新。

## 安全模型

- HTTP 服务只监听 `127.0.0.1`，并拒绝非 loopback 的 Host/Origin 请求。
- 这是本地应用防护，不是多用户认证，也不能防御同一操作系统用户下的其他进程。
- 内置 Agent 预设可能使用 bypass、YOLO、force 或 approve 参数；受管 CLI 继承
  当前操作系统用户的文件与进程权限。
- 只选择可信 Workspace，并检查所有高级自定义启动命令。不要通过隧道或反向
  代理暴露 Termestra 端口。
- 删除 Workspace 或成员会永久移除对应的 Termestra 元数据，但不会删除所选
  源码目录及其中的文件。

## 数据位置

| 数据 | 默认位置 | 说明 |
| --- | --- | --- |
| 运行时元数据 | `~/.config/termestra/termestra.db` | SQLite 数据库 |
| 任务文档 | `<workspace>/.termestra/tasks.md` | 同步任务计划与进度 |
| Agent 协议 | `<workspace>/.termestra/PROTOCOL.md` | 团队协作与恢复指引 |
| Web 界面 | 嵌入 npm 平台运行时 | 仅由本地服务提供 |

可通过 `TERMESTRA_DATA_DIRECTORY` 或 `TERMESTRA_DATA_DIR` 修改数据目录。

## 常见问题

### Agent 预设显示“未找到”

请在启动 Termestra 的同一个 Shell 中验证对应命令：

```bash
command -v codex
command -v claude
```

只安装桌面应用并不代表对应 CLI 已经加入 `PATH`。

### 默认端口被占用

```bash
termestra --port 4020
```

### 在普通 Shell 中执行 `team` 失败

这是预期行为。`team` 主要供 Termestra 管理的 Agent 会话使用；运行时会注入
`TERMESTRA_PORT`、Workspace/Agent ID 和会话 Token。

### 终端提示 IO connection closed

关闭并重新打开终端以尝试重连。如果反复出现，请确认 Termestra 仍在运行。
Worker 进程与终端查看连接是两个独立生命周期。

### Worker 一直处于 `working`

Worker 必须报告结果，或由 Orchestrator 取消尚未关闭的派单。受管 Worker 使用
`team report "<结果>" --dispatch <id>`；Orchestrator 使用
`team cancel --dispatch <id> "<原因>"`。

## 当前状态

Termestra 的 macOS-only 发行线从 `0.1.2` 开始；当前源码已收缩为 macOS Apple Silicon 与
Intel 两个平台包。历史 Linux/Windows 包保留在 npm 作为既有版本，但不再发布更新。

项目当前仍处于 Alpha 阶段。请在重要 Workspace 中使用版本控制，并在升级前
保留必要的数据备份。

## 项目文档

- [文档导航](docs/README.md)
- [当前架构总览](docs/architecture/overview.md)
- [关键运行流程](docs/architecture/runtime-flows.md)
- [契约与数据所有权](docs/architecture/contracts-and-data.md)
- [已接受架构决策](docs/adr/README.md)
- [npm 发布与运维](docs/release/npm.md)
- [产品路线图](docs/product/roadmap.md)

## 鸣谢与许可

内置 Worker 角色市场快照来源于
[agency-agents](https://github.com/msitarzewski/agency-agents) 和
[agency-agents-zh](https://github.com/jnMetaCode/agency-agents-zh)，来源与许可记录
保留在 `backend/src/main/resources/vendor/marketplace/`。声音素材归属保留在
`frontend/web/public/sounds/LICENSE-KENNEY.txt`。

源自 Hive 的部分仍按照 [Business Source License 1.1](LICENSE.BSL) 分发，依法
需要保留的来源事实与归属记录在 [NOTICE](NOTICE)。这是源码可用许可，不是 OSI
开源许可证。历史许可范围记录在 [LICENSE](LICENSE)，品牌来源与第三方商标说明
见 [TRADEMARK.md](TRADEMARK.md)，第三方内容与资产授权记录见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。许可证边界的事实性审查见
[许可审查](docs/governance/licensing-review.md)。

所有第三方产品名称与商标归各自权利人所有。本文只用于描述 CLI 兼容性，不代表
存在关联或背书。
