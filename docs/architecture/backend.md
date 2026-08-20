# 后端架构

后端是一个 Spring Boot 进程内的包级模块化单体。它把框架和设备依赖留在
adapter，将业务规则与编排暴露为小而明确的 application interface。

## 包结构与职责

有真实业务规则的上下文遵循：

```text
dev.termestra.<context>/
├── CONTEXT.md          只记录领域词汇
├── domain/             实体、值对象、纯策略、状态转换
├── application/
│   ├── port/in/        调用方使用的 use-case interface 与 command/view
│   ├── port/out/       应用层需要的持久化或运行时 interface
│   └── service/        事务外编排、策略组合、恢复流程
└── adapter/
    ├── in/             HTTP、WebSocket 等 driving adapter
    └── out/            SQLite、PTY、filesystem、classpath 等 driven adapter
```

Auth、Marketplace 等简单上下文可以使用较小结构；是否分层由复杂度决定，不以
目录完整为目标。

## 关键深模块和 seam

| interface / seam | 隐藏的实现复杂度 | 当前 adapter |
| --- | --- | --- |
| `WorkspaceRepository` | 规范路径幂等注册、容量、软删除/恢复 | `JdbcWorkspaceRepository` |
| `TeamLedger` | Message/Dispatch/Delivery 原子事务、查询投影、claim 和状态保护 | `JdbcTeamLedger` |
| `AgentExecutionUseCase` / `AgentMessagingUseCase` | Run 容量、PTY 生命周期、输入串行、恢复与持久状态 | `AgentExecutionService` |
| `PseudoTerminalLauncher` | 平台 PTY 启动、进程组/Job Object 终止 | `Pty4jProcessLauncher` |
| `TerminalRuntimeGateway` | Terminal 与 Run 所有权隔离 | `RuntimeWiring` 中的 Execution adapter |
| `TasksDocumentStore` | 真实目录/文件校验、大小限制、原子替换 | `NioTasksDocumentStore` |
| `ConfigurationRepository` | 内建项刷新、自定义项容量和 legacy 行防护 | `JdbcConfigurationRepository` |

这些 seam 的测试表面就是调用方表面。新增 interface 前先确认存在真实的可变
实现或需要隔离的外部副作用；纯转发层不能增加架构深度。

## 组合根

`dev.termestra.bootstrap.config.RuntimeWiring` 是唯一组合根，负责：

- 创建 SQLite 数据库并在其他 Bean 之前迁移到 schema v29；
- 把各上下文的 application interface 连接到 adapter；
- 在组合层实现小型跨上下文 adapter，例如 Terminal 到 Agent Execution；
- 启动/关闭 Dispatch delivery runtime、Agent execution 和 Tasks watcher；
- 根据配置、操作系统和环境变量构造 Orchestrator 启动计划。

业务代码不得通过 Spring 容器查找依赖；依赖由构造器显式传入。

## 持久化模型

`SqliteDatabase` 为读写操作提供统一错误上下文和事务入口；
`SqliteSchemaMigrator` 依版本顺序组合 Core、Configuration 与 Dispatch migration。
Repository 仍属于各上下文，`platform.persistence.sqlite` 只拥有迁移和数据库技术
机制。表的所有权见 [契约与数据](contracts-and-data.md#sqlite-所有权)。

Workspace 和 Worker hard delete 是刻意保留的例外：发起删除的 persistence
adapter 在一个 SQLite 事务内清除跨上下文 lifecycle graph，避免“成员已删但 Run
仍在”或“Workspace 已删但 Delivery 仍可 claim”的部分状态。普通创建和更新仍由
各上下文 repository 完成；这个例外只适用于销毁流程，见
[ADR-0006](../adr/0006-context-ownership-and-lifecycle-deletion.md)。

主要写入顺序是：

1. 在 SQLite 事务内写入权威状态；
2. 事务成功返回；
3. 失效有界投影或唤醒运行时；
4. 执行无法纳入数据库事务的 PTY、进程或浏览器副作用。

可靠派单把第三步之后的 PTY 副作用变成 Team 自有的持久工作项，而不是在 HTTP
请求线程里同步完成。

## 运行时协调

`RuntimeOperationCoordinator` 维护引用计数的精确键锁：

- Workspace 普通操作取得公平、可重入的共享锁；
- Workspace 创建元数据和删除取得排他锁；
- Agent 操作还取得 `(workspace_id, agent_id)` 的公平锁；
- 一次调用共用一个默认两秒的锁获取期限；
- 共享锁升级为排他锁立即失败；
- owner 和 waiter 全部释放后，registry 项被清理。

锁只限制进入临界区的等待，不是执行超时或分布式锁。调用方若在取得锁前已经
claim 了 durable work，必须在 `RuntimeOperationBusyException` 时明确 defer 或
释放 claim。

Agent Execution 另外使用每 Run 的公平 `ptyInputLock` 串行浏览器输入与自动输入；
停止流程先终止进程树，再完成持久终态，避免死锁在正在写 PTY 的调用上。原生终止
由有界的守护平台线程监管；调用方到期只得到明确失败，Run、credential 和容量仍由
同一终止尝试保留，直到确认进程树已经停止。

pty4j 的输出流读取可能阻塞在原生调用，因此每个受全局 Run 容量约束的 PTY 使用一个
守护平台线程读取输出，退出等待也使用守护平台线程。Workspace 删除和服务关闭的批量
生命周期清理由有界平台线程池编排，并与最多八个并发原生终止尝试对齐；这些路径不依赖
全局虚拟线程 carrier。macOS 进程组若在退出回收窗口内对信号返回 `EPERM`，adapter 会在
固定期限内继续等待 `ESRCH` 消失确认；持续不可访问仍失败关闭，不会提前释放 Run 所有权。
自动输入 mailbox 和 provider session capture 等不进入阻塞原生
边界的任务仍可使用虚拟线程。

Team Scenario 在持久化完整 roster 后，按 catalog 顺序逐个启动成员；一个成员的 PTY
启动返回前不启动下一个。某个启动失败仍会继续尝试余下成员，然后报告最早失败；这保留
已创建成员可见、可显式重试或删除的部分成功语义，并避免多个虚拟线程同时进入原生 PTY
创建。

## 进程与恢复

Run 启动采用 DB-first 注册：启动 PTY 后先插入 `agent_runs`，再激活输出回调和
输入注入。如果初始化失败，进程、credential、容量与持久状态按失败阶段回收。

后端重启时：

- 未完成 Run 被标记为 stale/terminal，真实进程不会被假定仍受管理；
- 可用时优先使用 provider-native session；否则注入最近一小时的有界恢复摘要；
- `pending` 和到期的 `retry_wait` Delivery 恢复处理；遗留 `delivering` 变为
  `uncertain`；
- Tasks watcher 与 Terminal viewer 由新连接按需重建。

## 删除语义

Workspace/Worker 删除先在 SQLite 事务中移除 Termestra 所有的关联图，再清理
进程、凭据、输出订阅、Tasks watcher 和投影。数据库失败会回滚元数据删除；
运行时清理失败不会让已提交的数据重新出现。任何删除都不碰用户选择的 Workspace
目录或源文件。

## 框架隔离检查

`ArchitectureTest` 当前保证：

- domain 不依赖 application、adapter、platform、bootstrap 或运行框架；
- application 不依赖 adapter、platform、bootstrap 或运行框架；
- inbound adapter 不直接访问 outbound adapter/SQLite platform；
- shared 不依赖业务上下文或框架。

跨上下文调用的实际允许关系由 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md) 和
`RuntimeWiring` 共同说明。
