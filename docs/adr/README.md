# Architecture Decision Records

ADR 记录难以逆转且不从代码表面自然显现的架构取舍。`Accepted` 文档是当前决策
依据；若改变方向，新建 ADR 并把旧记录标为 `Superseded`，不要重写历史理由。

| ADR | 状态 | 决策 |
| --- | --- | --- |
| [0001](0001-architecture-baseline.md) | Accepted | DDD、六边形、轻 CQRS、SQLite authority 和单 composition root |
| [0002](0002-package-by-feature-modular-monolith.md) | Accepted | 三构建单元与 package-by-feature modular monolith |
| [0003](0003-bounded-read-models-and-streams.md) | Accepted | Summary、Detail、Stream 分离和容量约束 |
| [0004](0004-team-owned-dispatch-delivery-outbox.md) | Accepted | Team 自有 Delivery outbox 与 uncertain 隔离 |
| [0005](0005-exact-key-bounded-runtime-coordination.md) | Accepted | 精确键、限时、引用计数的 runtime coordination |
| [0006](0006-context-ownership-and-lifecycle-deletion.md) | Accepted | 明确 context 数据所有权和单事务 lifecycle deletion 例外 |
| [0007](0007-macos-only-distribution.md) | Accepted | 发行与运行时支持收缩为 macOS arm64/x64，并裁剪非目标平台内容 |

当前架构的解释性文档位于 [`../architecture/`](../architecture/README.md)。
