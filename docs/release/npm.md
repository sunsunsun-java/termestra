# npm 运行时包发布

Termestra 面向终端用户发布三个 npm 包：一个 `@termestra/cli` 启动器和两个携带
jlink Java runtime 与应用 JAR 的 macOS 架构包。用户只需要安装 CLI：

```bash
npm install -g @termestra/cli
termestra
```

CLI 通过 `optionalDependencies` 选择与本机匹配的平台 runtime，并在 `postinstall`
阶段确认对应 runtime 确实存在；如果 npm 因连接重置而跳过 runtime，postinstall 会用
macOS 自带的 `curl` 续传固定版本 tarball、核对 npm SHA-512 后解压到 CLI 自有的
`.runtime/` 恢复目录。恢复共享 10 分钟截止时间和最多 96 次请求：收到新字节后立即
续传，TLS 连接失败等没有进度的请求则等待 15 秒再试。边界耗尽时整个安装会明确失败，
不再留下一个无法启动却显示安装成功的 CLI。Termestra 不是一个会替代
Claude Code、Codex、Gemini 或其他 CLI Agent 执行任务的编码 Agent，只在本机展示、
协调和监控这些外部 CLI 产品。

| 包 | 用途 |
| --- | --- |
| `@termestra/cli` | `termestra`、`team`、`update` 命令与用户文档 |
| `@termestra/runtime-darwin-arm64` | macOS Apple Silicon runtime |
| `@termestra/runtime-darwin-x64` | macOS Intel runtime |

当前发行仅支持 macOS；历史 Linux/Windows 包不撤回，但不再发布新版本。用户安装包时
需要 Node.js 20+，不需要另行安装 JDK。runtime 组装会从 SQLite、pty4j、JNA 与 Netty
依赖中删除非目标平台/架构的原生内容、把 pty4j 通用 Mach-O 裁成目标架构，但保留
跨平台共享入口在 JVM 链接时仍会解析的实现字节码。发行验证使用最终裁剪产物启动真实
PTY，并把每个 `.tgz` 限制在 75,000,000 bytes。

## 公开发布的资产与隐私核验

2026-08-18 的整改已将第三方产品图标和应用图标从源码、PWA 缓存与 npm 包中移除，
并以只读 Demo 的匿名界面截图替换 README 图片。事实记录见
[公开资产整改记录](../governance/public-asset-remediation-2026-08-18.md)。

每次创建 release tag 前仍须核验：

1. 没有重新加入第三方产品图标、favicon、应用 artwork，除非同时保留覆盖源码和二进制
   再分发的许可证或品牌授权证据。
2. README 截图仅来自内置只读 Demo，或经明确审查的测试数据；不得包含真实用户名、
   本机绝对路径、工作区名称、终端记录、凭据或旧协议目录。
3. 从根目录运行 `mvn clean verify`；发行包校验会断言 npm CLI 不再携带已退役的图标目录。

这类核验不能由 Token、OIDC 或 README 声明替代。新的第三方素材或隐私敏感展示应先
更新 [第三方 notices](../../THIRD_PARTY_NOTICES.md) 和 `docs/governance/` 中带日期的证据。

## 代码发布链

`.github/workflows/platform-packages.yml` 是唯一的 npm 发布工作流。

1. `vX.Y.Z` 或 `vX.Y.Z-prerelease` tag 触发 Apple Silicon 与 Intel 两个 macOS runner。
2. 每个 runner 要求根 `pom.xml` 恰好为同一版本的 `-SNAPSHOT`，再仅在 CI 工作区
   改成 tag 中的发行版本。
3. Maven 全量验证会构建对应 jlink runtime、运行 Java/npm 静态校验，执行真实的
   临时 npm registry 全局安装，并用已安装的 `termestra team --help` 启动嵌入 Java。
4. 通过的 `npm pack` `.tgz` 才会被上传为 GitHub Artifact；不会跨 job 传递原始目录，
   因此 macOS 的 Java 执行权限不会在 Artifact ZIP 过程中丢失。
5. `publish` job 先发布两个 runtime tarball，全部成功后才发布 CLI。每个包只有在
   版本与 dist-tag 可见、完整 tarball 可下载且 SHA-512 与本地候选包一致后才算发布成功。
   完整 tarball 校验若遭遇 CDN 中途断连、传播期不可用或重连尚未收到响应头便失败，会
   每 15 秒重试，并在单包 10 分钟、最多 48 次请求的边界内保留已接收字节的摘要与偏移，
   使用严格核对过的 HTTP Range 续传；每次不可用响应会记录具体 HTTP、长度或摘要原因。
   CDN 忽略 Range 时会清空旧摘要并从完整 `200` 响应重新校验。若重试遇到已发布版本，
   工作流只会接受相同字节；不一致时必须发新版本。

稳定 tag 发布到 npm `latest`；带 prerelease 标识的 tag 发布到 `next`。`termestra
update` 始终追踪 `latest`，预发布用户应显式安装 `@next`。

## 首次公开发布（一次性 Token 引导）

npm 的 Trusted Publishing 只能绑定已经存在的包，因此第一次发布三个活跃包需要一个短期、
最小权限的 npm granular access token。后续发布不再使用它。

1. 创建或确认 npm Organization `@termestra`，确认它拥有三个活跃包的命名空间，并为
   发布者启用 2FA。Scoped public package 的首次发布需要 public access。
2. 在 GitHub 仓库创建 `npm-production` Environment。建议只允许受保护的 `v*` tag
   部署并要求审批者；工作流的发布 job 固定使用这个 Environment。
3. 创建短有效期的 npm granular token：仅限 `@termestra` scope 的发布权限，允许
   2FA bypass，仅作为初始发布使用。把它保存为 `npm-production` Environment 的
   `NPM_TOKEN` secret，绝不提交到仓库或本机配置文件。
4. 确认根 `pom.xml` 是例如 `0.1.0-SNAPSHOT`，完成全部发布前门槛后创建并推送
   `v0.1.0` tag。工作流会检测二者是否匹配。
5. 在 GitHub Actions 中等待两个 macOS job 和 publish job 完成。首次 token 发布使用
   provenance；不要在任何一个包失败后换成同一个版本重新构建并发布。

Scoped public package、发布访问控制及 Token 的官方说明见 [npm 的 scoped package
发布指南](https://docs.npmjs.com/creating-and-publishing-scoped-public-packages/) 与
[访问 Token 指南](https://docs.npmjs.com/about-access-tokens/)。

## 首发后迁移到 Trusted Publishing

首发成功后，依次打开三个活跃 npm package 的 Settings → Trusted publishing，为每个包
配置同一个 GitHub Actions 发布者：

| 字段 | 值 |
| --- | --- |
| GitHub owner | `sunsunsun-java` |
| Repository | `termestra` |
| Workflow filename | `platform-packages.yml` |
| Environment | `npm-production` |
| Allowed action | `npm publish` |

发布包中的 `repository.url` 已固定为
`git+https://github.com/sunsunsun-java/termestra.git`，并由构建校验；不要在 fork 或
改名后继续使用旧地址。工作流已经拥有 `id-token: write`，并在 publish job 显式使用
npm 11.5.1，因此删除 `NPM_TOKEN` 后，npm 会用 OIDC 自动完成后续发布并自动生成
provenance。

完成一次无 Token 的新版本发布后：删除 GitHub `NPM_TOKEN` secret，撤销该 npm Token，
并在每个包的 Publishing access 中选择 “Require two-factor authentication and
disallow tokens”。Trusted Publishing 配置必须与 workflow 文件名和 Environment 完全
一致。[npm Trusted Publishing 文档](https://docs.npmjs.com/trusted-publishers/) 说明了
该迁移顺序与 GitHub Actions 所需的 OIDC 权限。

## 本地验证与发布后验收

发布前从仓库根运行：

```bash
mvn clean verify
```

distribution 验证会将已验证的 tarball 写到：

```text
distribution/target/npm-tarballs/
```

它在临时目录使用独立 npm cache、私有安装 prefix 和本地 registry，不会写入你的全局
npm 安装或复用本机 registry。只重跑发行包验证时可使用：

```bash
node distribution/scripts/verify-npm-install.mjs distribution/target
```

第一次发布成功后，在一个干净的用户环境做真实验收：

```bash
npm view @termestra/cli version
npm install -g @termestra/cli
termestra --version
termestra
```

确认 `termestra` 启动本地服务后，再选择一个可信的 Workspace 和已经登录的 CLI Agent。
