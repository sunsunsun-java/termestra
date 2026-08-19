# Termestra 文档导航

这里按“文档的用途”而不是按代码目录组织。代码与测试始终是行为事实的最高
来源；本文档树负责解释当前设计、历史决策和专项证据。

## 新读者路线

1. 先读根目录 [README](../README.md)，了解产品解决什么问题以及如何运行。
2. 再读 [架构总览](architecture/overview.md)，建立系统、上下文和代码目录的全景。
3. 按任务选择 [后端架构](architecture/backend.md)、
   [前端架构](architecture/frontend.md) 或
   [关键运行流程](architecture/runtime-flows.md)。
4. 修改协议或持久化前，阅读
   [契约与数据](architecture/contracts-and-data.md)。
5. 准备提交前，按 [构建与测试](architecture/testing-and-build.md) 选择验证边界。
6. 准备 npm 公开发布时，按 [npm 运行时包发布](release/npm.md) 完成发布门槛与
   GitHub/npm 配置。

## 文档分类

| 目录 | 内容 | 权威性与维护方式 |
| --- | --- | --- |
| [`architecture/`](architecture/README.md) | 当前系统如何工作 | 与代码同步；只写已经实现的架构 |
| [`adr/`](adr/README.md) | 已接受的长期决策及其理由 | 历史记录；改变决策时新增“取代”记录 |
| [`design/`](design/reliable-dispatch.md) | 复杂机制的实现级设计 | 以当前实现为基线，算法变化时更新 |
| [`research/`](research/pi-orchestrator-assessment.md) | 外部项目或方案评估 | 参考材料，不定义 Termestra 产品行为 |
| [`governance/`](governance/licensing-review.md) | 带日期的许可与治理证据，例如[公开资产整改记录](governance/public-asset-remediation-2026-08-18.md) | 快照材料，不替代法律意见或当前代码 |
| [`product/`](product/roadmap.md) | 已交付能力和发布缺口 | 产品状态，完成或改变范围时更新 |
| [`release/`](release/npm.md) | npm 包发布、验证和运维步骤 | 与实际发布工作流、npm 元数据同步 |

## 领域语言

根目录 [上下文地图](../CONTEXT-MAP.md) 描述后端业务能力的所有权和依赖关系；
每个链接的 `CONTEXT.md` 只定义该上下文的领域词汇，不记录框架、表结构或实现
细节。

## 写作约定

- 当前架构文档必须能反向导航到实际包、入口、端口、迁移或测试。
- 公共协议使用代码中的 wire 名称，例如 `snake_case` 字段和状态值。
- 已接受的 ADR 不通过改写历史来追认新设计；用新的 ADR 明确取代关系。
- 日期型审查在标题处标出快照日期，避免被误读为持续保证。
- 移动文档时同步修改 README、分发打包清单和校验脚本。
