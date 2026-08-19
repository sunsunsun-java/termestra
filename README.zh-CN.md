<p align="center">
  <img src="./frontend/web/public/logo.png" alt="Termestra 图标" width="112" />
</p>

# Termestra

<p align="center">
  <img src="./frontend/web/public/screenshots/termestra-promo-hero-light.png" alt="Termestra 本地优先 CLI Agent 协作工作空间，由一个 Orchestrator 协调多个可见 Worker" width="1120" />
</p>

**把你电脑里已经安装的 AI CLI 组成一支可见、可持续的团队。**

Termestra 为一个 Orchestrator 和多个 CLI Worker 提供共享的本地工作空间、真实终端、持久化任务状态和 SQLite-first 可靠派单。它采用 Java 运行时和显式三态团队模型，不创建隐藏 subagent，让所有工作都能被看见，而不是散落在互不相干的终端窗口里。

[![Java 21+](https://img.shields.io/badge/Java-21%2B-4b73a3)](https://adoptium.net/)
[![Maven 3.9+](https://img.shields.io/badge/Maven-3.9%2B-c71a36)](https://maven.apache.org/)
[![Node 22.22+ 源码构建](https://img.shields.io/badge/Node.js-22.22%2B%20source-43853d)](https://nodejs.org/)
![项目状态](https://img.shields.io/badge/status-alpha-f59e0b)
![构建目标](https://img.shields.io/badge/targets-macOS%20%7C%20Linux%20%7C%20Windows-64748b)

[English](README.md) · [简体中文](README.zh-CN.md)

> Termestra 是本地优先应用。服务只监听 `127.0.0.1`，数据库保存在你的电脑上，每个正在运行的 Agent 都是真实的本地 CLI 进程。

## 为什么需要 Termestra

单个 AI 编程 CLI 已经很强，但同时协调多个 CLI 时，问题很快就会出现：

- 每个 Agent 分散在不同终端里，彼此缺少共享上下文；
- 很难确认谁正在工作、空闲、已停止，或正在等待输入；
- 任务记录可能已经创建，却没有真正送进 Worker 的终端；
- 终端重连和进程重启后，很难判断现场到底发生了什么；
- 审查、测试和调研往往要等主任务结束后才开始。

Termestra 把这些协作关系显式化。Orchestrator 面对的是一支持久化的真实团队，通过精简的 `team` 协议派单，并收到与稳定 Dispatch ID 关联的报告。SQLite 是权威状态源，因此刷新页面或重启后端不会丢失团队名单和排队中的工作。

## 适合用来做什么

**并行开发、审查和测试**

通过内置场景创建 Coder、Reviewer 和 Tester，然后只给 Orchestrator 一个最终目标，不必分别操控三个终端。

```text
实现免密登录。让一个成员负责开发，一个成员审查安全边界，另一个成员补集成测试，最后汇总全部证据。
```

**调研与事实核查**

让一个 Worker 负责调研，另一个 Worker 检查来源和假设。每份报告都会保留其真实团队成员和派单关系。

```text
比较这两种部署方案。请调研员收集一手资料，再由核查员挑战每一个重要结论。
```

**文档流水线**

通过“文档流水线”场景创建起草成员和审稿成员，在不创建隐藏 subagent 的前提下分离写作与核验职责。

```text
为第一次参与项目的贡献者重写入门指南。起草成员负责成文，审稿成员逐条验证仓库中的命令。
```

## 先试用演示模式

在首次进入页面时选择 **试用演示**，即可打开一个带有预录 Orchestrator、Worker 和 Tasks 数据的只读工作空间。演示模式不会启动真实 AI CLI、不会修改真实代码仓库，也不会消耗模型额度。

在创建真实 Workspace 前，你可以先体验工作空间切换、可拖拽面板、Worker 状态、终端布局和任务进度。

## 界面预览

下面四张截图均来自内置的匿名演示：首次进入、预录任务视图、团队名单和打开目标选择器。它们不展示真实 Workspace、用户账户或正在运行的外部 CLI。

<p align="center">
  <img src="./frontend/web/public/screenshots/1.png" alt="Termestra 首次进入页面，可创建 Workspace 或打开只读演示" width="680" />
</p>
<p align="center"><sub>创建一个可信的本地 Workspace，或直接体验无需安装 Agent CLI 的只读演示。</sub></p>

<p align="center">
  <img src="./frontend/web/public/screenshots/2.png" alt="只读演示中，预录的 Orchestrator 和 Worker 状态与打开的 Tasks 面板并列显示" width="1120" />
</p>
<p align="center"><sub>无需启动外部 CLI，即可体验预录派单活动和任务进度。</sub></p>

<p align="center">
  <img src="./frontend/web/public/screenshots/3.png" alt="只读演示中，Orchestrator 的预录输出与一名运行中的开发成员和一名空闲审查成员并列显示" width="1120" />
</p>
<p align="center"><sub>角色、状态和预录终端上下文会在同一个本地工作空间中保持可见。</sub></p>

<p align="center">
  <img src="./frontend/web/public/screenshots/4.png" alt="只读演示的打开目标菜单，兼容工具以中性矢量图形显示" width="1120" />
</p>
<p align="center"><sub>兼容工具以名称标识，界面统一使用 Termestra 的中性矢量图形。</sub></p>

## 快速开始

Termestra 目前仍是 Alpha 源码版本（`0.1.0-SNAPSHOT`）。现阶段最可复现的方式是从源码构建。

环境要求：

- JDK 21 或更高版本
- Maven 3.9 或更高版本
- 当前锁定的源码依赖需要 Node.js 22.22.2 或更高版本
- Corepack 与 pnpm 10.29.1
- 至少安装并登录一个受支持的 Agent CLI

```bash
corepack enable
mvn clean verify
mvn -pl backend spring-boot:run
```

打开 [http://127.0.0.1:3000](http://127.0.0.1:3000)。Termestra 不会自动打开浏览器。

**npm 运行时包发布**

项目已经实现发行包结构、隔离式全局安装验证和五平台发布工作流。公开 npm 渠道要等首个受保护 Tag 完成跨平台构建与已发布包验证后才会宣布可用。正式发布后，安装与更新命令为：

```bash
npm install -g @termestra/cli
termestra
termestra update
```

npm 启动器会选择匹配的可选平台运行时包，其中包含 jlink Java 运行时。因此发行包用户只需要 Node.js 20+，无需另装 JDK。Linux 发行包要求 glibc。指定其他端口：

```bash
termestra --port 4020
```

**首次使用流程**

1. 添加 Workspace，并选择一个可信的本地目录。
2. 选择 Orchestrator CLI 预设并启动。
3. Termestra 会在 Workspace 中初始化 `.termestra/tasks.md` 和 `.termestra/PROTOCOL.md`，用于任务跟踪和恢复指引。
4. 单独添加 Worker、导入角色模板，或选择一个一键组队场景。
5. 把目标告诉 Orchestrator。它可以使用 `team list` 查看真实团队，用 `team send` 派单并收集 Worker 报告。

浏览器界面也可以安装成 PWA。安装 PWA 不会把 Termestra 变成云服务，本地 Java 运行时仍需保持运行。

## 工作原理

```text
浏览器 / 已安装 PWA
        │ HTTP + 有界 WebSocket 数据流
        ▼
Spring WebFlux 本地运行时（127.0.0.1）
        │
        ├── Workspace、Team、Tasks、Marketplace、Settings
        ├── 可靠 Dispatch 投递与重试调度器
        ├── SQLite 权威状态
        └── pty4j 进程与终端生命周期
                    │
                    ├── Orchestrator CLI
                    │       ├── team list
                    │       ├── team send
                    │       └── team cancel
                    └── Worker CLI
                            ├── team report
                            └── team status
```

计划、终端活动和实时团队状态分别通过 Tasks 面板、真实终端和团队名单保持可见，同时不把界面扩展成隐藏的工作流引擎。

Worker 不是进程内模型调用。每个成员都是 UI 中可见、可持久化的 Team Member；成员运行时由真实 PTY 进程支撑。每个派单先写入 SQLite，再按 Worker 串行 FIFO 交付；派单生命周期与 Worker 对外公开的 `idle`、`working`、`stopped` 三态彼此分离。团队名单、配置、排队任务和 run 元数据可以跨后端重启保留；实时进程不会保留，受影响成员会恢复为 `stopped`。

Termestra 会区分投递失败、可重试失败和终端写入结果不确定。对于“不确定是否已经写入”的任务不会盲目自动重试，避免同一任务执行两次；UI 会把这类问题显式展示给用户处理。

## Agent 预设

Termestra 从**后端进程继承的 PATH** 中检测可执行文件。只安装桌面应用并不会让对应 CLI 变为可用。

| 预设 | 可执行命令 | 默认启动方式 | 当前版本恢复方式 |
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

这些只是便捷默认配置，不是沙箱。在敏感代码上使用前，请自行核对每个 CLI 及其启动参数。

## Termestra 提供什么

- **持久化 Workspace 与团队**：名称、路径、Orchestrator 配置、Worker 角色、排队任务和 run 元数据可以跨刷新及重启保留；实时 PTY 和实时状态不会保留。
- **真实终端会话**：通过有界 WebSocket 数据流提供可交互的 xterm 视图，支持终端 resize、重连快照和按 viewer 隔离的流控。
- **可靠派单**：消息、Dispatch 和投递记录在同一事务中受理；后台调度器执行有限重试，并在后端重启后恢复待投递任务。
- **可见的投递问题**：明确失败和结果不确定的 PTY 写入会显示在 UI 中，等待用户有意识地重试，不会静默丢失。
- **一键组队场景**：开发·审查·测试、调研与核查、文档流水线会创建真实持久化成员，并把团队名单和目标告知 Orchestrator。
- **Worker 角色市场**：内置中英文角色模板，加入 Workspace 前可以先审阅。
- **Tasks 面板**：监听并同步 `.termestra/tasks.md`，通过 revision 检测让浏览器与本地文件同时编辑时出现明确冲突。
- **重启恢复**：Claude、Codex、Gemini 和 OpenCode 在可用时优先恢复原生 session；所有 Provider 都有持久化恢复摘要兜底。
- **Workspace 工具**：支持原生目录选择、浏览服务器文件系统、粘贴绝对路径，以及用受支持的编辑器、终端和文件管理器打开目录。
- **本地演示、PWA 与双语 UI**：无需真实 Provider 即可体验产品，可安装成类桌面 PWA，并在英文与简体中文之间切换。
- **事务性元数据删除**：删除 Workspace 或成员时，会在一个 SQLite 事务中删除 Termestra 自有的数据库关系图；数据库失败会整体回滚。所选源码目录及其中的文件永远不会被删除。

Termestra 当前有意**不提供**隐藏自动创建的 subagent、Workflow/DAG 自动化、定时任务、Team Memory、远程访问和多用户认证。它也无法从技术上强制 Orchestrator 模型一定派单而不能自己执行；委派依赖可见协议和系统提示契约。

## 平台构建目标

发行流水线会在原生 CI 上构建、打包并全局安装以下目标。在首次公开 Tag 发布和所有系统的生产切换验证完成前，它们仍属于预发布目标，不能称为稳定 Tier 1 支持。

| 平台 | 打包目标 | 文件夹选择 |
| --- | --- | --- |
| macOS arm64 / x64 | `@termestra/runtime-darwin-*` | 原生 `osascript`、服务器目录浏览或粘贴路径 |
| Linux arm64 / x64（glibc） | `@termestra/runtime-linux-*` | 存在 `zenity` 时使用原生选择器，否则使用目录浏览或粘贴路径 |
| Windows x64 | `@termestra/runtime-win32-x64` | PowerShell 文件夹对话框、服务器目录浏览或粘贴路径 |

Linux 没有安装 `zenity` 时，请使用“浏览服务器文件系统”或粘贴绝对目录路径。

## 安全模型

- HTTP 服务只监听 `127.0.0.1`，并拒绝非 loopback 的 Host/Origin。UI 请求与 Agent 请求分别使用进程级和会话级 Token。
- 这是本地应用防护，不是多用户认证，也不能防御以同一操作系统用户身份运行的其他进程。
- 内置 Agent 预设会主动使用 bypass、YOLO、force 或 approve 参数。受管 CLI 继承当前操作系统用户的文件和进程权限。
- 只选择可信 Workspace，并检查所有高级自定义启动命令。不要通过隧道或反向代理暴露 Termestra 端口。
- 演示模式不会启动真实 Agent，是了解界面时最安全的方式。
- Workspace 与成员删除会永久移除对应的 Termestra 元数据、团队、消息和 run 历史，但**不会**删除所选源码目录及其中的文件。

## 数据与 Workspace 文件

| 数据 | 位置 | 说明 |
| --- | --- | --- |
| 运行时元数据 | `~/.config/termestra/termestra.db` | 当前所有平台都使用这个默认路径 |
| 任务文档 | `<workspace>/.termestra/tasks.md` | 同步任务计划与进度 |
| Agent 协议指南 | `<workspace>/.termestra/PROTOCOL.md` | 自动创建或刷新，用于恢复与团队协作 |
| 打包后的 Web UI | 嵌入 Java 应用 | 仅由本地运行时提供 |

可通过 `TERMESTRA_DATA_DIRECTORY` 或 `TERMESTRA_DATA_DIR` 覆盖数据目录。

SQLite 保存 Workspace 与成员配置、run/session 元数据、消息、Dispatch/投递记录、设置和应用状态。列表接口不会携带每个 Agent 的完整终端历史，数据库也不会保存无界的完整终端转录。PTY scrollback 是有界内存投影，后端重启后会丢失。

## 常见问题

**Agent 预设显示“未找到”**

Termestra 检查 Java 后端继承到的 PATH。请在启动 Termestra 的同一个 Shell 中先验证命令：

```bash
command -v codex
command -v claude
```

Windows 使用 `where codex`。只有 Codex 桌面端并不够，还需要安装并登录对应 CLI。

**在普通 Shell 里执行 `team` 失败**

这是预期行为。`team` 主要供 Termestra 管理的 Agent 会话使用；运行时会向这些会话注入 `TERMESTRA_PORT`、Workspace/Agent ID 和会话 Token。

**默认端口已被占用**

```bash
termestra --port 4020
```

源码启动时，在 Maven 命令前使用 `TERMESTRA_PORT=4020`。

**Agent 启动时等待粘贴输入超时**

确认 CLI 已经进入可交互提示符，完成首次登录或初始化，并与所选输入 Profile 匹配。等提示符就绪后重试；如果 Provider 版本行为不同，请使用经过审阅的自定义启动命令。

**终端显示 IO connection closed**

关闭并重新打开终端即可尝试重连。如果反复出现，请确认后端仍在运行并检查后端日志；Worker 进程和终端 viewer 连接是两个独立生命周期。

**Worker 一直处于 `working`**

Worker 必须报告结果，或由 Orchestrator 取消未关闭的派单。受管 Worker 会话使用 `team report "<result>" --dispatch <id>`（或 `team report --stdin --dispatch <id>`）；Orchestrator 使用 `team cancel --dispatch <id> "<reason>"`。

**Tasks 出现冲突**

这表示浏览器保留本地修改期间，Workspace 文件被外部独立改动。请核对两个版本，再明确选择重新载入远端内容或保存新 revision。Termestra 不会静默覆盖任意一方。

## 开发

Maven Reactor 包含 `frontend`、`backend` 和 `distribution` 三个模块。完整验证会安装锁定的前端依赖、检查和测试 TypeScript、构建 React UI、运行 Java 单元测试与跨真实边界的集成测试、检查架构规则、构建 Spring Boot 应用，并组装当前宿主平台的发行包。

```bash
corepack enable
mvn clean verify
```

发行模块会调用 POSIX `sh`。Windows 上运行完整 Maven Reactor 时，请使用 Git Bash 或其他提供 `sh` 的环境。

开发前端时，分别启动后端与 Vite：

```bash
# 终端 1 — 仓库根目录
TERMESTRA_PORT=4010 mvn -pl backend spring-boot:run
```

PowerShell 等价命令：

```powershell
$env:TERMESTRA_PORT = "4010"
mvn -pl backend spring-boot:run
```

```bash
# 终端 2 — 仓库根目录
cd frontend
corepack enable
pnpm install --frozen-lockfile
pnpm exec vite --config web/vite.config.ts
```

Vite 默认监听 `127.0.0.1:5180` 并代理到 `4010`。可使用 `TERMESTRA_WEB_PORT` 和 `TERMESTRA_RUNTIME_PORT` 覆盖这两个默认值。

生成的发行输入位于：

- `backend/target/termestra-backend-0.1.0-SNAPSHOT.jar`
- `distribution/target/npm-cli`
- `distribution/target/npm/runtime-<platform>-<arch>`
- `distribution/target/runtime-current`

## 架构

Termestra 是前后端同仓、按业务能力分包的模块化单体，而不是由十几个 Maven 微服务组成的系统。

```text
termestra/
├── frontend/       React、TypeScript、Vite、xterm
├── backend/        Java 21、Spring Boot/WebFlux、SQLite JDBC、pty4j
├── distribution/   jlink 镜像与各平台 npm 包
├── docs/           当前架构、决策、设计、调研与状态
└── scripts/        仓库工具
```

后端在 `workspace`、`team`、`execution`、`terminal`、`tasks` 等业务能力内部采用 DDD、六边形端口/适配器和轻量 CQRS。SQLite 是权威状态源：持久化状态提交成功后才更新内存投影。Spring 组装与技术适配器保持在领域代码之外，ArchUnit 会验证主要依赖规则。

建议按以下顺序阅读文档：

- [文档导航](docs/README.md)
- [当前架构总览](docs/architecture/overview.md)
- [关键运行流程](docs/architecture/runtime-flows.md)
- [契约与数据所有权](docs/architecture/contracts-and-data.md)
- [已接受架构决策](docs/adr/README.md)
- [可靠派单详细设计](docs/design/reliable-dispatch.md)
- [npm 运行时包发布](docs/release/npm.md)
- [路线图](docs/product/roadmap.md)

## 当前状态

Termestra 目前是 `0.1.0-SNAPSHOT` Alpha 软件。核心本地 Workspace、终端、团队、Tasks、一键组队、恢复和可靠投递路径已有自动化测试覆盖。平台打包链已经会通过隔离安装验证最终 npm tarball，但公开资源发布门槛和首个五平台 Tag 发布仍需验证。

Termestra 自身的公共契约、自动化测试和已接受架构决策是当前实现的唯一事实来源。

## 鸣谢与许可

内置 Worker 角色市场快照来源于 [agency-agents](https://github.com/msitarzewski/agency-agents) 和 [agency-agents-zh](https://github.com/jnMetaCode/agency-agents-zh)，来源与许可记录保留在 `backend/src/main/resources/vendor/marketplace/`。声音素材归属保留在 `frontend/web/public/sounds/LICENSE-KENNEY.txt`。

源自 Hive 的部分仍按照 [Business Source License 1.1](LICENSE.BSL) 分发，依法需要保留的来源事实与归属记录在 [NOTICE](NOTICE)。这是源码可用许可，不是 OSI 开源许可证；历史许可范围记录在 [LICENSE](LICENSE) 中，请勿假设当前组合项目采用 MIT 或 Apache 许可。品牌来源与第三方商标说明见 [TRADEMARK.md](TRADEMARK.md)。随包第三方内容与尚待确认的资产授权列在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，许可证边界的事实性审查见 [许可审查](docs/governance/licensing-review.md)。

所有第三方产品名称与商标归各自权利人所有。本文只用于描述 CLI 兼容性，不代表存在关联或背书。
