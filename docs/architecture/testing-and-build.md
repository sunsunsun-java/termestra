# 构建与测试

根 `pom.xml` 把 frontend、backend、distribution 串成一个验证链。非平凡变更的
完成门槛是从仓库根执行：

```bash
mvn clean verify
```

## Reactor 做了什么

1. **frontend / validate**：`pnpm install --frozen-lockfile`；
2. **frontend / initialize**：TypeScript `tsc --noEmit`；
3. **frontend / test**：Node tests 与 Vitest；
4. **frontend / generate-resources**：Vite 构建 `frontend/web/dist`；
5. **backend**：编译 Java，把 web dist 嵌入 `static/`，运行单元与集成测试，构建
   Spring Boot fat JAR；
6. **distribution**：用 jlink 创建 host runtime，组装 npm CLI 和平台包，运行
   真实 Java/npm smoke 验证，并从实际 `.tgz` 在隔离 npm registry 与全局 prefix 中
   安装，再执行已安装的 `termestra team --help`；
   [`verify-npm-runtime.mjs`](../../distribution/scripts/verify-npm-runtime.mjs) 断言 macOS
   应用 JAR 不含 Linux/Windows 或错误架构的原生内容，并核验 pty4j Mach-O slice，
   [`verify-npm-install.mjs`](../../distribution/scripts/verify-npm-install.mjs) 把 runtime
   tarball 限制在 75,000,000 bytes；
7. **root / verify**：校验品牌和许可边界文件。

完整 reactor 与 runtime 组装只支持 macOS；CI 分别在 Apple Silicon 与 Intel runner
构建原生包。

## 测试分层

| 风险 | 首选测试位置 | 观察内容 |
| --- | --- | --- |
| 纯状态转换/策略 | `*/domain/*Test` | 不变量、非法转换、边界值 |
| application 编排 | `*/application/service/*Test` | port 调用顺序、补偿、并发、typed failure |
| SQLite | `*/adapter/out/persistence/*Test`、migration test | 事务原子性、索引/claim、legacy 数据、回滚 |
| HTTP/认证/JSON | `backend/.../bootstrap/*IntegrationTest` | 真实 WebFlux status、字段、大小、cookie/token |
| PTY/进程 | `execution/adapter/out/pty`、`platform/process` | macOS PTY 输入/退出、进程组与进程树终止 |
| Terminal/Tasks stream | WebSocket integration + handler test | snapshot/live 顺序、背压、重连、清理 |
| filesystem | Workspace/Tasks NIO adapter test | symlink 防护、大小、atomic replace、watch 恢复 |
| CLI | `TeamCliTest`、npm tarball install smoke | 参数、stdin、环境、真实 HTTP、版本/更新路径、可选 runtime 解析、bin/Java 执行权限 |
| frontend | `frontend/tests` | stale request、single-flight、polling、UI 状态、PWA |
| 架构 | `ArchitectureTest` | domain/application/adapter/shared 依赖方向 |

单元测试使用 fake port 隔离策略；声称“边界可用”时必须补真实 adapter/transport
测试。只 mock controller 或 JDBC 不能证明公共契约。

## 常用局部命令

全量验证仍是完成门槛；开发中可以缩短反馈：

```bash
# 前端类型、Node/Vitest 测试与构建
cd frontend
pnpm check
pnpm test
pnpm build

# 只运行后端测试（需要已经存在或先生成 frontend/web/dist）
cd ..
mvn -pl backend test

# 运行一个 Java 测试类
mvn -pl backend -Dtest=DispatchDeliveryApplicationServiceTest test
```

前端热更新的双进程命令以根 README 的 Development 章节为准，避免在架构文档
缓存 Vite 端口配置。

## 变更到验证的映射

- 修改 public DTO：更新真实 HTTP test 的 exact field set，并检查 payload budget。
- 修改 schema/repository：覆盖从旧 schema 的 migration、事务失败和 legacy
  超限/损坏行。
- 修改 Dispatch/Delivery：覆盖 request admission、后台 claim、重启、迟到 ack、
  report/cancel 竞争和显式 retry。
- 修改 Agent lifecycle：覆盖并发 start/stop/delete、进程树确认、terminal state
  持久化失败和 capacity release。
- 修改 WebSocket：覆盖 snapshot 与 live 原子交接、slow consumer、最后连接清理。
- 修改 Workspace 文件：覆盖 symlink、超限、外部编辑、revision conflict 和原子写。
- 移动 README/docs/assets：更新 distribution staging 和 npm runtime verifier。
- 修改 npm package、jlink 打包或 GitHub release workflow：检查
  [`release/npm.md`](../release/npm.md) 的 tarball、平台、dist-tag 与 OIDC 要求。

## 失败报告

若 `mvn clean verify` 不能完成，交付说明必须包含：

- 完整失败命令；
- Maven module 或前端 script；
- 第一个真实错误，而不是最后一行摘要；
- 属于代码回归、平台限制、缺少外部工具还是网络/环境问题；
- 已经成功运行的较小验证，且不得把它表述为全量通过。
