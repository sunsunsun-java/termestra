# 前端架构

前端是同一页面内的 React 工作台，没有服务端路由或独立状态服务器。它把
SQLite/后端投影当作权威，把 optimistic state 仅用于缩短用户可见延迟。

## 源码布局

```text
frontend/
├── src/shared/          与 UI/测试共享的 wire 类型和 open-target 定义
├── web/src/
│   ├── api.ts           HTTP adapter、snake_case 映射、超时和集合上限
│   ├── AppInner.tsx     工作台顶层状态编排
│   ├── demo/            完全本地、只读的演示状态
│   ├── terminal/        双 WebSocket 客户端、tab、xterm 视图
│   ├── tasks/           Tasks 文档、revision、解析和编辑队列
│   ├── worker/          TeamMember、Scenario、Dispatch issue UI
│   ├── workspace/       创建、浏览、选择和 open-target UI
│   ├── marketplace/     随包目录的读取和预览
│   ├── notifications/   从有界投影派生的用户通知
│   ├── pwa/             service worker、升级和 offline shell
│   └── lib/             single-flight、有界缓存/并发、polling 等机制
└── tests/               Node 与 Vitest 行为测试
```

`AppProviders` 只装配 i18n、tooltip、toast 与通知能力；`AppInner` 组合 Workspace
选择、Worker 轮询、Run 轮询、Tasks 流、Demo 和 optimistic presentation。
较大的 dialog/panel 通过 `lazy` 延迟加载。

## 状态来源

| 状态 | 权威来源 | 前端策略 |
| --- | --- | --- |
| Workspace/TeamMember/Run summary | 后端有界 HTTP projection | 页面可见时轮询，失败退避，响应设上限 |
| Terminal output/screen | Run output + Terminal mirror | IO/Control 双 WebSocket，先 restore 后 live |
| Tasks Document | Workspace 文件 | HTTP 初读/写入 + WebSocket 更新 + revision 冲突 |
| active Workspace 等偏好 | Configuration `app_state` | 后端保存，浏览器持有当前副本 |
| 创建/启动后的短暂状态 | 后端结果尚未进入下一次轮询 | 有界 optimistic map，权威结果到达后收敛 |
| Demo | `demo-fixture.ts` | 本地只读，不发 Agent/Workspace 变更请求 |

前端不会把 Terminal detail 作为 TeamMember 或 Run list 的来源。卡片上的
`last_pty_line` 只是固定长度的提示，正式 Worker 结果只能来自 Report。

## HTTP adapter

`web/src/api.ts` 与 `lib/ui-session-fetch.ts` 集中处理：

- 首次获取 UI Session cookie；session 失效时受控重取；
- 热查询、交互查询和 Marketplace 查询的不同超时；
- 后端 `snake_case` 到内部 `camelCase` 的显式映射；
- 集合硬上限和异常响应解析；
- Workspace 创建等易重复操作的 single-flight 约束。

UI 组件不应重复发明 fetch、认证刷新或 wire 映射。新增 endpoint 时在 adapter
层定义 payload 类型和大小预期，再向 hook 暴露 UI 语义。

## 轮询与并发

`visible-page-poller`、`visible-single-flight-probe` 与
`workspace-worker-poll-plan` 共同保证：

- 页面隐藏时暂停非必要轮询；
- 同一资源只有一个在途请求；
- 失败使用有界退避，不形成 timer/request 堆积；
- Workspace 列表批量读取使用固定并发；
- 缓存使用有界 LRU，写队列只保留一个执行中值和一个最新待写值。

增加新轮询前，先确认不能由现有投影或流提供，并写出频率、暂停条件、错误退避
和集合上限。

## Terminal 与 Tasks

`terminal/terminal-client.ts` 为一个 viewer 创建：

- `/io`：文本输出与 raw input；
- `/control`：restore、resize、output acknowledgement、stop、error、exit。

客户端只在 control restore 完成后把 live output 交给 xterm，并按字节确认已消费
输出。断开只结束 viewer，不等同于停止 Run。

对话历史沿用 CLI 的 terminal-native scrollback：完成内容留在 xterm normal buffer，
用户在同一对话区持续向上滚动即可查看当前 Run 的可用历史。浏览器 scrollback 固定为
10,000 行；重连 Restore Snapshot 仍服从 Terminal 上下文的有界 screen projection
契约，不承诺完整、永久的消息 transcript。

`terminal/terminal-bookmarks.ts` 在用户向 Orchestrator PTY 成功提交单独的 Enter
输入时，为 xterm normal buffer 的当前行创建浏览器本地 marker。React rail 提供快速
预览和跳转，但只是连续滚动之上的辅助入口，不是独立历史列表。registry 上限为 200；
marker 被 scrollback 淘汰、viewer 失败/退出、Run 卸载或视图销毁时同步清理。
Shell、Worker terminal 和 alternate screen 不展示书签；书签不写入后端，也不承诺跨
刷新、重连 restore 或 Run 重启恢复。

`tasks/useTasksFile.ts` 通过 `/ws/tasks/{workspaceId}` 接收 snapshot/update，写入
则携带 expected revision。`latest-write-queue` 防止连续编辑形成无界 Promise 链；
409 冲突保留远端内容和 revision 给 UI 明确处理。

## PWA 与离线边界

Service worker 只缓存带版本的静态 app shell。`/api` 和 `/ws` 明确不缓存；运行
时离线页只表示本地 Java 进程不可达，不能伪造 Workspace 或 Team 数据。更新
提示会考虑活动 Worker/Run，避免在有工作进行时无提示刷新。

## 前端变更准则

- wire payload 在 `api.ts` 或 shared wire type 中显式声明；组件使用映射后的类型。
- optimistic state 必须可由下一次权威读取淘汰，并有容量或 Workspace 生命周期
  清理。
- hook 处理副作用和竞态，presentation 组件保持可测试的 props interface。
- 新增浏览器资源必须覆盖成功、失败、stale response、Workspace 切换和卸载清理。
