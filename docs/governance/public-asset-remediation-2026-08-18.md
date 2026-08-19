# 公开资产与 README 截图整改记录

> 日期：2026-08-18<br>
> 状态：带日期的事实记录；不是法律意见，也不改写此前的许可审查快照。<br>
> 范围：公开源码、PWA 静态资源和 `@termestra/cli` npm 包中曾使用的第三方视觉素材，以及 README 产品导览图片。

## 已完成的整改

1. 删除 `frontend/web/public/cli-agent-icons/` 和
   `frontend/web/public/open-target-icons/` 中的第三方产品图标、应用图标、favicon
   及其来源清单。此前来源链接不能单独证明再分发权利，因此未保留这些素材。
2. 将 CLI Agent 选择器、成员头像和“打开工作区”目标改为 `lucide-react` 提供的
   中性 SVG 图形；产品名称只作为兼容性标识保留，未使用对应的商标图案。
3. 从 PWA service worker 的预缓存和静态资源匹配规则、npm CLI 白名单、Maven 分发
   staging 中移除已删除的目录；发布校验显式拒绝在 npm 包中再次出现它们。
4. 用内置只读 Demo 的页面重新生成 `frontend/web/public/screenshots/1.png` 至
   `4.png`。Demo 只使用固定的 `demo-todo-app` 示例数据和 `/workspace/demo-todo-app`
   示例路径，不连接真实 Workspace、Agent CLI 或终端。

## 留存的边界

- 本记录不判断第三方产品名称、商标文字或用户自行配置的外部工具是否需要额外许可；
  它仅记录 Termestra 不再随源码或 npm 包分发对应的视觉资产。
- `licensing-review.md` 是 2026-08-12 的历史性审查快照，其中关于旧图标目录的描述
  保留原样，不能被当作当前发行物清单。
- 后续若加入任何第三方视觉素材，必须先保存可覆盖公开源码与二进制再分发的许可证、
  品牌政策或书面授权，并更新根目录 `THIRD_PARTY_NOTICES.md`。

## 验证

整改后的发布前验证命令为：

```bash
mvn clean verify
```

该命令会执行前端类型检查和测试、构建 PWA、检查 npm staging 与从 tarball 的隔离式全局安装路径。
