# ADR-0009：Workspace 注册沿用目录当前 checkout

- 状态：Accepted
- 日期：2026-09-02
- 取代：[ADR-0008](0008-durable-workspace-registration-around-git.md)

## 背景

Git 不允许同一个本地分支同时被多个 worktree 检出。Codex 为并行任务创建的分支通常
已经绑定到对应 worktree，因此 Termestra 在另一个目录的 Workspace 创建界面列出这些
分支时，大部分候选项既不可选，也不代表可安全迁移的源码目录。Agent 进程退出也不会
删除 Git worktree；把进程生命周期误当作分支占用生命周期会持续产生误导。

Workspace 的稳定身份本来就是规范本地目录，而不是某个分支。注册流程中切换用户工作树
还引入了 SQLite 无法事务包围的外部副作用、短期 token、未知结果和人工核对状态。

## 决策

创建 Workspace 时只注册用户所选目录，并沿用该目录当时的 checkout。Termestra 不再：

- 枚举本地分支或暴露分支选择接口；
- 签发 Git inspection/selection token；
- 在 Workspace 注册期间执行 `git switch`；
- 把当前分支持久化为 Workspace 权威状态。

`registration_id`、规范路径 claim、`preparing/active` 可见性、元数据初始化、原子激活和
有界恢复台账继续保留。新注册的成功路径只走
`reserved -> checkout_applied -> completed`；元数据初始化或台账转换失败会进入
`failed` 并释放 `preparing` Workspace。其中
`checkout_applied` 是为既有数据库兼容保留的状态名，不再表示发生过 checkout。

旧客户端提交 `revision_selection.kind=current` 仍被接受；任何非 `current` 值明确返回
400，避免静默忽略旧的分支意图。既有 schema 中的 selection、checkout 和 observed HEAD
列不删除，以便升级时读取旧数据库。旧版本留下的 `switching` 或 `uncertain` attempt
保留诊断证据，但转为 `failed` 并释放不可见的 `preparing` Workspace claim；用户检查目录
当前 checkout 后可用新的 `registration_id` 重新注册。已经是 `checkout_applied` 的 attempt
可继续幂等初始化元数据并激活。新版本不会创建 `switching`、`uncertain` 或新的 Git
副作用证据。

## 结果

- Workspace 创建不再受其他 Codex worktree 的分支占用影响，也不会改变用户 checkout。
- 前后端删除分支列表、选择器、token 和 Git mutation adapter，注册契约更小。
- 用户若要使用另一分支，应选择已经处于该分支的目录或 worktree，再创建 Workspace。
- SQLite 保留少量历史字段与状态名，直到未来专门的数据迁移证明可安全移除。
