# ADR-0008：用持久注册台账包围 Git switch

- 状态：Accepted
- 日期：2026-08-27

## 背景

添加 Workspace 时允许选择已有本地分支，会在 SQLite 写入与用户 Git 工作树之间
形成跨资源操作。Git switch 不能加入数据库事务；进程退出或超时后，调用方可能
无法判断工作树是否已经改变。把它当作普通可重试失败可能二次改变用户源码状态，
而先把 Workspace 暴露给其他上下文又会让运行时读到半完成注册。

## 决策

Workspace 用 `workspace_registration_attempts` 持久化注册意图、幂等 ID、状态和
checkout 观察证据。对应 `workspaces` 行先以 `preparing` 保存，只有 checkout
已确认（或明确选择 current）且 `.termestra/` 元数据初始化完成后，才在同一 SQLite
事务中将 Workspace 改为 `active` 并把 attempt 改为 `completed`。

Git 适配器只允许扫描和切换已有本地 `refs/heads/*`，不 fetch、不操作远端、不
force、不 stash，也不创建分支。选择 token 绑定工作树身份、观察到的 HEAD、目标
OID 和其他 worktree 占用状态，并在 mutation 前重新验证。

若 Git 调用超时、中断、输出被截断或成功结果无法再次观察，attempt 进入
`uncertain`，公开 `source_revision_changed=null`，禁止自动重试。启动恢复只自动
释放尚未触达 Git 的 `reserved`，或继续已经持久记录为 `checkout_applied` 的注册。

分支选择是 Workspace 注册时的一次性指令，不进入 Workspace 聚合，也不成为
“当前分支”的持久权威；后续用户可用普通 Git 工具改变工作树。

## 结果

- 其他上下文只读取 `active` Workspace，不会观察半完成注册。
- 超时和崩溃后的结论可通过 registration ID 查询，不依赖内存 future。
- SQLite 无法回滚 Git，但系统不会把未知副作用误报成安全失败或自动重试。
- v1 不提供注册后切换、远端分支或新建分支；这些能力若引入，需要新的显式策略。
