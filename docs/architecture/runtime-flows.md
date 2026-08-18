# 关键运行流程

本文把最容易跨上下文的流程串成可追踪路径。类名是定位入口，不是要求调用方
绕过 application interface。

## 创建 Workspace 与 Orchestrator

```mermaid
sequenceDiagram
    participant UI as Browser UI
    participant W as WorkspaceApplicationService
    participant WR as WorkspaceRepository
    participant T as TasksDocumentStore
    participant E as Agent Execution
    participant DB as SQLite

    UI->>W: CreateWorkspaceCommand
    W->>W: resolve canonical path + single-flight
    W->>WR: register(candidate)
    WR->>DB: idempotent registration transaction
    DB-->>WR: created or existing Workspace
    W->>T: initialize `.termestra/` files
    T-->>W: tasks.md + refreshed PROTOCOL.md
    alt new Workspace and autostart enabled
        W->>E: configureAndStart Orchestrator
        E->>DB: persist launch config and Run
        E->>E: activate PTY and inject startup/recovery input
    end
    W-->>UI: Workspace + orchestrator_start
```

同一规范路径的并发创建由 path single-flight 和数据库唯一约束收敛为一个
Workspace。元数据初始化或 Orchestrator 准备失败时，新注册会回滚；已有
Workspace 不会因重复创建失败而被删除。

## 可靠派单

```mermaid
sequenceDiagram
    participant O as Orchestrator `team` CLI
    participant Team as TeamApplicationService
    participant Ledger as TeamLedger
    participant Runtime as DispatchDeliveryRuntime
    participant Delivery as DispatchDeliveryApplicationService
    participant Exec as Agent Execution
    participant PTY as Worker PTY

    O->>Team: send(worker, task, idempotency_key)
    Team->>Team: authenticate role + validate limits
    Team->>Ledger: enqueue Dispatch + Message + Delivery
    Ledger-->>Team: committed dispatch_id
    Team->>Runtime: wake after commit
    Team-->>O: 202 accepted
    Runtime->>Delivery: processNext()
    Delivery->>Ledger: claim ready Delivery + attempt lease
    Delivery->>Exec: deliver committed Dispatch
    Exec->>PTY: prompt-aware automatic input
    alt complete input accepted
        Exec-->>Delivery: forwarded
        Delivery->>Ledger: Delivery submitted + Dispatch submitted
    else proven no input
        Exec-->>Delivery: failed, input_attempted=false
        Delivery->>Ledger: bounded retry_wait or failed
    else input may have reached PTY
        Exec-->>Delivery: uncertain
        Delivery->>Ledger: uncertain; no automatic retry
    end
```

Worker `report` 或 Orchestrator `cancel` 可以终结公开 Dispatch，并关闭 Delivery；
迟到的投递确认不能复活终态。完整状态机和恢复分类见
[可靠派单设计](../design/reliable-dispatch.md)。

## 打开 Terminal Viewer

```mermaid
sequenceDiagram
    participant UI as xterm client
    participant WS as TerminalWebSocketHandler
    participant Mirror as HeadlessTerminalMirror
    participant Exec as Agent Execution
    participant PTY as PTY output

    UI->>WS: connect `/io` with clientId
    WS->>Exec: open Run output
    Exec-->>WS: bounded snapshot + live subscription
    PTY-->>Exec: output bytes
    Exec-->>WS: ordered decoded output
    WS->>Mirror: apply snapshot and queued live output
    UI->>WS: connect `/control`
    WS-->>UI: restore snapshot + cursor handoff
    WS-->>UI: live text frames
    UI->>WS: output_ack(bytes)
    Note over WS,Exec: slow viewers create pressure; all viewers clear before Run resumes
```

IO 和 Control 必须成对绑定同一 `clientId`。关闭 viewer 只清理该连接和 flow
lease；`stop` control message 才请求终止 Run。

## 编辑 Tasks Document

```mermaid
sequenceDiagram
    participant UI as Tasks UI
    participant HTTP as TasksController
    participant App as TasksApplicationService
    participant File as `.termestra/tasks.md`
    participant Watch as NioTasksFileWatcher
    participant WS as Tasks WebSocket

    UI->>HTTP: GET document
    HTTP->>App: readDocument
    App->>File: bounded read
    File-->>UI: content + sha256 revision
    UI->>HTTP: PUT content + expected_revision
    HTTP->>App: writeDocument
    App->>File: compare revision + atomic replace
    alt revision matches
        App-->>UI: new content + revision
        Watch-->>WS: tasks-updated
        WS-->>UI: latest document
    else stale revision
        App-->>UI: 409 + current content/revision
    end
```

本地编辑器修改和浏览器修改使用同一文件权威。文件 watcher 只在有订阅者时
存在，最后一个订阅者断开或 Workspace 删除时关闭。

## 重启恢复

后端重启后的恢复顺序是：

1. 打开数据目录并把 SQLite schema 迁移到 v29；
2. 将未完成的旧 Run 标记为 terminal/stale；活进程不会自动重新收编；
3. 把遗留 `delivering` Delivery 隔离为 `uncertain`，恢复 `pending` 和到期
   `retry_wait`；
4. 用户再次启动 Agent 时优先恢复 provider-native session，否则注入有界恢复
   摘要；
5. Browser 重连时重建 Terminal mirror、Tasks watcher 和所有 viewer 投影。

## 删除 Workspace 或 Worker

删除是“持久状态优先”的补偿流程：

1. 在精确 Workspace/Agent 协调锁下提交 SQLite 图删除；
2. 停止并遗忘匹配 Run，撤销 credential；
3. 清理 Terminal output subscription、Tasks watcher、pending projection 与
   optimistic UI state；
4. 保留用户的 Workspace 目录、源文件和普通工具进程。

SQLite 事务失败时第一步整体回滚；后续运行时清理失败会被监督重试或记录，不能
撤销已经完成的权威删除。
