# Reliable Dispatch 详细设计

> 状态：实现基线  
> 日期：2026-08-11  
> 范围：Team/Dispatch 的可靠任务投递；不包含通用 Workflow 自动化

本文记录实现级算法和 schema v29 基线。系统级入口见
[关键运行流程](../architecture/runtime-flows.md)，长期取舍见
[ADR-0004](../adr/0004-team-owned-dispatch-delivery-outbox.md) 与
[ADR-0005](../adr/0005-exact-key-bounded-runtime-coordination.md)。

## 1. 目标

可靠派单保证：只要 `team send` 已被 Termestra 接受，任务就不会因为请求超时、进程退出或
PTY 暂时不可用而静默丢失；系统也不会在无法确定 Worker 是否已经看到任务时自动重复派发。

完成后的用户语义是：

1. 派单首先原子落入 SQLite，并立即获得稳定的 `dispatch_id`。
2. 任务由后台投递器送往真实、可见的 Termestra Worker PTY。
3. 明确未触达 PTY 的失败可以有界重试。
4. 可能已经触达 PTY 的失败进入 `uncertain`，只能通过 Worker 汇报或显式人工重试解决。
5. 后端重启会恢复未开始的投递，并把重启前正在投递的任务隔离为 `uncertain`。
6. 相同 `idempotency_key` 的重复请求返回同一个 Dispatch，不生成重复任务。

## 2. 非目标

- 不增加通用 Workflow、DAG、cron、循环或条件执行。
- 不改变 Termestra 公开 Dispatch 状态：`queued/submitted/reported/cancelled`。
- 不改变 Agent 公开状态：`idle/working/stopped`。
- 不把 CLI 内置 subagent 映射为 Termestra TeamMember。
- 不承诺 exactly-once 执行。PTY 是非事务型副作用，系统提供的是可解释的
  at-least-once admission 与不确定投递隔离。

## 3. 领域语言

### Dispatch

指挥官交给一个真实 TeamMember 的业务任务。它从创建到成员汇报或取消，拥有稳定 ID。

### Delivery

把 Dispatch 的任务正文交给 Worker PTY 的技术过程。Delivery 不表示 Worker 已理解或完成任务。

### Delivery Attempt

一次对 PTY 的具体投递尝试。每次尝试有独立 `attempt_id`，并记录是否可能触达输入端。

### Transactional Delivery Outbox

与 Dispatch 和 Message 在同一 SQLite 事务中创建的 `dispatch_deliveries` 行。它既是待投递命令，
也是投递状态的权威记录；本项目不另建通用事件总线或重复的 Outbox 表。

### Uncertain Delivery

系统无法证明任务没有触达 PTY。该状态禁止自动重试，避免 Worker 重复执行有副作用的任务。

### Idempotency Key

调用方为一次逻辑派单生成的稳定键。在同一 Workspace 和调用方范围内重复使用时，返回原 Dispatch。

## 4. 不变量

1. `dispatches`、对应 `messages` 和 `dispatch_deliveries` 必须在一个事务内创建。
2. SQLite 提交成功前，不更新 PendingTaskProjection，不唤醒后台投递器。
3. 一个 Dispatch 最多有一条 Delivery Outbox 行；历史尝试通过 attempt 字段累计记录当前结论。
4. 同一 Worker 同时最多执行一个 Delivery Attempt，保持 PTY 输入顺序。
5. `submitted` 仅表示完整任务正文已经写入 PTY，不表示 Worker 开始或完成任务。
6. `reported/cancelled` 是业务终态，优先于任何迟到的投递确认。
7. `input_attempted=true` 或进程在 `delivering` 中退出时，禁止自动重试。
8. 所有错误文本、列表、重试次数和后台并发都必须有明确上限。
9. 手工重试 `uncertain/failed` 必须复用原 Dispatch ID，并产生新的 attempt ID。
10. 公共 HTTP/JSON 字段保持 snake_case。
11. RuntimeOperationCoordinator 竞争发生在调用 PTY 之前；释放 Delivery claim 时不得消耗
    attempt 次数、设置 `input_attempted` 或调用 notifier。

## 5. 模块与 seam

```text
TeamUseCase.send
  -> TeamLedger.enqueue                         SQLite 事务
       -> messages
       -> dispatches
       -> dispatch_deliveries                   Team-owned Outbox
  -> DispatchDeliveryScheduler.wake             after commit

DispatchDeliveryRuntime                         有界后台驱动 adapter
  -> DispatchDeliveryUseCase.processNext         application seam
       -> TeamLedger.claimNext
       -> AgentTeamNotifier.deliver
       -> TeamLedger.markSubmitted / retry / uncertain / failed
```

- `TeamLedger` 是 Team 持久化的深模块接口，隐藏三表事务、幂等冲突、claim 和状态转换 SQL。
- `DispatchDeliveryUseCase` 隐藏投递分类、退避和迟到结果处理。
- `DispatchDeliveryRuntime` 只负责有界并发、唤醒、生命周期和异常日志，不包含业务判断。
- `AgentTeamNotifier` 保持为跨到 Agent Execution 的现有 outbound port。

## 6. 数据模型

### 6.1 `dispatches` 增量字段

```sql
idempotency_key TEXT NULL
```

约束：

```sql
UNIQUE(workspace_id, from_agent_id, idempotency_key)
WHERE idempotency_key IS NOT NULL
```

### 6.2 `messages` 增量字段

```sql
dispatch_id TEXT NULL
```

`send` 和 `report` Message 记录关联 Dispatch；普通 status 消息保持 NULL。

### 6.3 `dispatch_deliveries`

```sql
CREATE TABLE dispatch_deliveries (
  dispatch_id       TEXT PRIMARY KEY,
  workspace_id      TEXT NOT NULL,
  to_agent_id       TEXT NOT NULL,
  runtime_port      TEXT NOT NULL,
  state             TEXT NOT NULL,
  attempt_id        TEXT,
  attempt_count     INTEGER NOT NULL DEFAULT 0,
  input_attempted   INTEGER NOT NULL DEFAULT 0,
  last_error        TEXT,
  next_attempt_at   INTEGER,
  lease_owner       TEXT,
  lease_expires_at  INTEGER,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);
```

索引覆盖：

- `(state, next_attempt_at, created_at)`：claim 下一项。
- `(workspace_id, to_agent_id, state)`：Worker 健康投影与同 Worker 串行。

字段上限：

- `runtime_port`：5 个字符且必须是 1..65535。
- `attempt_count`：最大 5 次自动尝试。
- `last_error`：最多 2,048 个字符，入库前截断。
- Worker/Workspace/Dispatch ID 继续使用现有输入上限。

## 7. 状态机

### 7.1 公开 Dispatch 状态

```text
queued -> submitted -> reported
   |          |
   +----------+------> cancelled
```

### 7.2 内部 Delivery 状态

```text
pending -> delivering -> submitted
              |
              +-> retry_wait -> delivering
              +-> uncertain
              +-> failed

pending/retry_wait/delivering/uncertain/failed -> closed
uncertain/failed --explicit retry--> pending
```

| 状态 | 含义 | 自动动作 |
| --- | --- | --- |
| `pending` | 已持久化、尚未 claim | 可 claim |
| `delivering` | 一个实例已取得租约并调用 PTY | 不被其他线程 claim |
| `retry_wait` | 明确未触达 PTY，等待退避 | 到期后可 claim |
| `submitted` | 完整输入已写入 PTY | 等待 Worker report |
| `uncertain` | 可能触达 PTY | 禁止自动重试 |
| `failed` | 明确未触达但已耗尽重试 | 等待人工重试或取消 |
| `closed` | Dispatch 已 reported/cancelled | 无投递动作 |

## 8. 派单与幂等流程

1. TeamUseCase 完成身份、角色、Worker 名称与任务长度校验。
2. 规范化 `idempotency_key`；缺省允许 NULL，CLI 每次逻辑调用生成 UUID。
3. 创建新的 Dispatch 候选对象。
4. `TeamLedger.enqueue` 在事务内先查询/约束幂等键：
   - 已存在：返回已有 `dispatch_id`，不创建 Message/Delivery。
   - 不存在：插入 Message、Dispatch 和 Delivery。
5. 事务提交后失效 PendingTaskProjection 并唤醒投递器。
6. HTTP 返回 202 与稳定 `dispatch_id`；不等待 CLI prompt。

## 9. Claim、并发与租约

- 单进程最多并行处理 8 个不同 Worker 的 Delivery。
- SQL claim 按 Dispatch sequence/created_at FIFO。
- claim 排除已经有 `delivering` 行的同一 Worker。
- claim 原子写入新的 UUID `attempt_id`、增加 `attempt_count`，并设置 90 秒有界诊断租约。
- 90 秒覆盖 RuntimeOperationCoordinator 的 2 秒总获取上限、CLI 的 30 秒 prompt 与 3 秒
  paste-ack 上限；冷启动 Worker 可能先后经历启动指令和任务正文两轮 prompt/paste，剩余时间作为
  调度与 SQLite 确认余量。系统不靠 heartbeat 延长租约。
- 每个实际投递仍经过 RuntimeOperationCoordinator 的 per-agent 锁，保证 Worker 删除、启动和输入互斥。
- RuntimeOperationCoordinator 使用精确 `(workspace_id, agent_id)` 键，并在一个有界总期限内依次取得
  Workspace 共享锁和 Agent 锁；不同 Workspace 或同 Workspace 的不同 Agent 不因哈希碰撞相互阻塞。
- 若有界期限内无法取得锁，投递器将当前 `delivering` claim 原子改为延迟 1 秒可重试的 `retry_wait`：
  清空 attempt/lease、把 `attempt_count` 减一（抵消 claim 时的递增）并保持
  `input_attempted=false`。这属于调度 defer，不是一次 Delivery Attempt，PTY notifier 不会被调用。
- Termestra 目前是单本地实例模型；租约用于崩溃证据和未来扩展，不宣称多实例 leader election。

## 10. 失败分类与重试

| 观察结果 | Delivery 结论 | 后续 |
| --- | --- | --- |
| `forwarded=true` | `submitted` | 原子推进公开 Dispatch 为 submitted；终态 Dispatch 不被覆盖 |
| `forwarded=false,input_attempted=false` | `retry_wait` 或 `failed` | 指数退避 1/2/4/8 秒，最多 5 次 |
| `input_attempted=true` 或 `uncertain=true` | `uncertain` | 不自动重试 |
| notifier 调用发生未分类异常 | `uncertain` | 保存有界错误并记录异常，不假设未产生副作用 |
| RuntimeOperationCoordinator 取得 Workspace/Agent 锁超时 | `retry_wait` | 释放 claim 并延迟 1 秒；不调用 notifier、不消耗 attempt 次数 |
| Worker 已删除/Dispatch 已终态 | `closed` | 不启动或投递 |
| 提交 Delivery 结果时 SQLite 失败 | 保持 `delivering` | 当前进程重试持久化；进程退出后按 uncertain 恢复 |
| submitted acknowledgement 的 attempt 已失效 | 显式 `InactiveDeliveryAttempt` | 禁止静默吞掉迟到确认；保留权威 Delivery 状态供诊断 |

退避只适用于“明确未触达”。系统不使用错误字符串猜测类别，而使用现有 typed
`DeliveryResult(forwarded,inputAttempted,uncertain,error)`。

## 11. 重启恢复

启动顺序：

1. 完成 schema migration。
2. 将遗留 `delivering` 标记为 `uncertain`，因为旧进程可能已写入任意数量字节。
3. 保留 `pending` 与到期 `retry_wait`，启动后台 Runtime 并立即 wake。
4. `reported/cancelled` 对应 Delivery 修正为 `closed`。

重启绝不把旧 `delivering` 直接改回 `pending`。

## 12. Report、Cancel 与迟到结果

- Worker 可以从 `queued` 或 `submitted` 直接 report；report 与 Message 在同一事务提交，Delivery 同时 close。
- cancel 对 `queued/submitted` 生效，Delivery 同时 close；已经开始的 notifier 可能返回迟到结果，但 guarded SQL 不得复活 Dispatch。
- 若 uncertain 的 Worker 后来用原 `dispatch_id` report，report 获胜并关闭任务。
- 无 dispatch ID 的 report 仍按最老 open Dispatch 关联；新代码和提示继续要求显式 ID。

## 13. 查询与 UI

现有 UI Dispatch summary/detail 增加固定大小字段：

```json
{
  "delivery_state": "retry_wait",
  "delivery_attempt_count": 2,
  "delivery_input_attempted": false,
  "delivery_error": "...bounded...",
  "delivery_next_attempt_at": 1786425600000
}
```

前端只轮询专用的有界异常查询，不读取终端历史，也不会先截取普通队列再在浏览器中过滤：

```text
GET /api/ui/workspaces/{workspace_id}/dispatch-delivery-issues?limit=100
```

该查询在 SQLite 中直接筛选 `uncertain/failed`，即使普通 queued 队列超过 100 条，也不会漏掉
排序较后的异常投递。完整交付状态仍可从普通 summary/detail 查询；
活动 Workspace 的常驻告警只展示必须由用户处理的状态：

- `uncertain/failed`：投递异常，并显示有界原因与显式重试；
- `pending/delivering/retry_wait/submitted/closed`：不产生常驻告警，仍可通过接口诊断；
- Dispatch `reported/cancelled`：业务终态优先并关闭 Delivery。

## 14. 手工重试

UI 写接口：

```text
POST /api/ui/workspaces/{workspace_id}/dispatches/{dispatch_id}/retry
```

只允许公开 Dispatch 仍为 `queued` 且 Delivery 为 `uncertain/failed`。操作：

- 清除错误、租约和 `input_attempted`；
- state 改为 `pending`；
- 不创建新 Dispatch，不改任务正文；
- 下一次 claim 生成新的 attempt ID；
- 提交后 wake 后台投递器。

## 15. 容量与清理

- 后台最大并发：8。
- 单次 claim：1；空闲时不忙等，由 after-commit wake 与最长 1 秒定时检查共同驱动。
- 自动尝试：最多 5 次。
- 错误文本：2,048 字符。
- 普通 summary 与异常查询：各最多 100 行；detail 继续使用现有限额。
- Worker/Workspace 硬删除必须在同一事务删除其 Delivery 行。
- Delivery 与 Dispatch 同生命周期，不建立无界独立历史表。

## 16. 验证矩阵

### Domain/Application

- 明确未触达时进入退避并最终失败。
- uncertain 永不自动重试。
- report/cancel 赢过迟到的 delivery ack。
- 手工 retry 只接受 queued + uncertain/failed。

### SQLite

- Message、Dispatch、Delivery 三写原子回滚。
- 同幂等键并发请求只有一个 Dispatch。
- claim FIFO、同 Worker 串行、不同 Worker 可并行。
- 90 秒租约不会把 70 秒内完成的合法冷启动 PTY Delivery 提前回收为 uncertain。
- 已失效 attempt 的 submitted acknowledgement 产生 typed failure，不允许 0 行状态转换静默成功。
- Workspace 或 Agent runtime 锁繁忙时无损 defer：attempt 次数保持不变、`input_attempted=false`、
  notifier 调用次数为零；锁释放后仍可正常提交。
- schema v28 -> v29 保留旧 Dispatch，并将旧 queued Dispatch 保守迁移为 uncertain Delivery。
- Worker/Workspace 硬删除无 Delivery 孤儿。

### Runtime/HTTP/CLI/PTY

- HTTP 202 在持久化后、PTY 就绪前返回。
- 后端重启恢复 pending；遗留 delivering 变 uncertain。
- 真实 CLI 生成 snake_case `idempotency_key`。
- 真实 PTY 完整收到一份任务并将 Dispatch 标记 submitted。
- UI summary 大小不依赖终端历史。

### Frontend

- 普通查询可诊断所有投递状态；活动页面突出显示 uncertain/failed。
- retry 防重复点击并在成功后刷新。
- 页面隐藏时停止轮询，失败时有退避。

## 17. Schema 迁移

Schema v29：

1. 给 `dispatches` 增加 nullable `idempotency_key` 和部分唯一索引。
2. 给 `messages` 增加 nullable `dispatch_id`。
3. 创建 `dispatch_deliveries`。
4. 为历史 `queued` Dispatch 创建 `uncertain` Delivery，并要求显式重试。旧实现可能已写入 PTY 却未确认，
   因而不得自动重投；历史记录缺少 runtime port 时保存兼容默认值 `3000`。
5. 历史 `submitted` Dispatch 创建 `submitted` Delivery；终态创建 `closed` Delivery或不创建。

公共 `team send/cancel/report/status/list` 路径和现有 Dispatch wire state 保持不变；新增字段只出现在 UI
summary/detail，新增 retry 是 UI 专用写接口。
