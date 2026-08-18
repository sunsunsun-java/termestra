# 当前架构

本目录解释仓库当前已经实现的架构。它面向第一次定位代码的开发者，也为变更
评审提供共同地图。

| 文档 | 解决的问题 |
| --- | --- |
| [架构总览](overview.md) | 系统由哪些运行单元和上下文组成，依赖方向是什么？ |
| [后端架构](backend.md) | Java 模块如何分层、装配、持久化并协调运行时资源？ |
| [前端架构](frontend.md) | React UI 如何组织状态、轮询、WebSocket、Demo 和 PWA？ |
| [关键运行流程](runtime-flows.md) | Workspace 创建、派单、终端、Tasks、重启和删除如何串起来？ |
| [契约与数据](contracts-and-data.md) | 公共状态、端点族、认证、SQLite 所有权和容量规则是什么？ |
| [构建与测试](testing-and-build.md) | Maven reactor 如何工作，变更应落在哪一层测试？ |

历史理由位于 [`../adr/`](../adr/README.md)，可靠派单的实现级细节位于
[`../design/reliable-dispatch.md`](../design/reliable-dispatch.md)。
