# Workspace 本地分支注册详细设计

> 状态：实现基线
> 日期：2026-08-27
> 范围：添加 Workspace 时扫描并选择已有本地 Git 分支

## 产品边界

分支选择只出现在 Add Workspace 流程。非 Git 目录沿用 current 注册；Git 工作树
默认保留当前 checkout，用户也可从有界、可搜索的本地分支列表选择一个分支。
界面没有“新建分支”按钮，也没有 remote、fetch、pull、stash、reset 或 force。

## 模块

- `WorkspaceRegistrationUseCase` 是 HTTP 与注册算法之间的单一入口。
- `GitWorktreeAccess` 隐藏受限进程执行、worktree 身份、HEAD、dirty 摘要、分支上限
  和 checkout 结果分类。
- `WorkspaceRegistrationLedger` 隐藏 `preparing/active` Workspace 与 attempt 的事务、
  CAS 状态转换、恢复查询和 4096 条保留上限。
- `WorkspaceRegistrationTokenCodec` 签发 10 分钟进程级 HMAC token；服务重启后旧
  token 自然失效。

## 状态与顺序

```text
reserved ----------------> checkout_applied -> completed  (current)
        \-> switching ---> checkout_applied -> completed  (local branch)
                      \-> failed
                      \-> uncertain
```

1. probe 签发 path inspection token；options 用它重新解析并验证工作树根。
2. options 读取 HEAD、dirty 摘要、最多 4096 个本地分支和 worktree 占用；HTTP 每页
   最多 100 项，并为可选项签发 selection token。
3. POST 以 `registration_id` 和请求 hash 幂等 reserve `preparing` Workspace。
4. current 选择从 `reserved` 直接记录 `not_attempted` 证据；本地分支选择才先持久化
   `switching`，再重新检查 selection token 与提交 OID，执行
   `git switch --no-guess -- <branch>`，随后重新 inspect 并同时校验分支名与 OID。
5. 先写 checkout 证据，再初始化 Workspace Metadata，最后原子 activate。
6. Orchestrator 在 activate 之后准备；其失败只进入 `orchestrator_start`，不补偿删除
   Workspace。

同一路径使用 `RuntimeOperationCoordinator` 的精确规范路径键、公平锁和统一两秒获取
期限降低并发 Git 竞争；owner 与 waiter 释放后键会清理，SQLite 的规范路径唯一约束
是最终仲裁。选择已在其他 worktree 检出的分支会在 options 中禁用，并在 mutation
前再次拒绝。

## 恢复分类

| attempt 状态 | 启动动作 | 理由 |
| --- | --- | --- |
| `reserved` | 标记 failed 并释放 preparing claim | 已证明未调用 Git |
| `switching` | 标记 uncertain，保留 claim | Git 是否改变工作树未知 |
| `checkout_applied` | 重做幂等元数据初始化并 activate | checkout 证据已先持久化 |
| `uncertain` | 不自动操作 | 需要用户检查工作树与状态接口 |

启动恢复一次读取最多 256 条 actionable attempt（等于 Workspace 产品容量上限）；
`uncertain` 不进入自动恢复查询，避免人工处理项占满恢复窗口。

客户端收到未知结果后保留原 `registration_id`。用户显式重试同一请求时，服务只做
fresh inspect：若目标 branch 与 OID 已被观察到，则确认原 attempt 并继续激活；若
未观察到则释放 claim 并要求重新选择。该路径不会再次执行 `git switch`。

## 验证边界

- `ProcessGitWorktreeAccessTest` 使用真实 Git 仓库验证本地分支、switch、嵌套目录和
  多 worktree 占用。
- `JdbcWorkspaceRegistrationLedgerTest` 验证 preparing 不可见、原子 activate、恢复窗口、
  容量修剪与失败释放 claim。
- `WorkspaceRegistrationServiceTest` 验证 reservation 后 token 失效、错误 OID 的 applied
  结果不得激活，以及恢复期元数据失败的终态清理。
- `WorkspaceBranchRegistrationHttpIntegrationTest` 贯穿 probe、options、opaque token、
  POST、真实 Git switch、SQLite lifecycle 和 status endpoint。
- `workspace-create-flow.test.tsx` 验证 UI 只从扫描结果选择已有分支并提交 opaque token。
