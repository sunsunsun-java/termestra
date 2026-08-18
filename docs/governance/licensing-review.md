# Termestra 公开许可审查（事实性工作稿）

> 日期：2026-08-12  
> 状态：带日期的事实快照；文件移动不代表重新审计  
> 范围：Termestra 仓库根许可证、发布包元数据、已捆绑第三方内容与 Hive
> 许可证边界。  
> 本文只整理许可证文本和仓库事实，不构成法律意见。有关代码是否构成
> “复制”“修改”或“衍生作品”的最终判断，应由权利人或专业律师结合提交
> 历史、设计资料和逐文件来源记录确认。

## 结论摘要

1. **若 Termestra 的全部自有代码确实由 Termestra 权利人独立创作，且发布物中
   不包含 Hive 的 BSL 代码或其修改版，可以为 Termestra 自有代码选择
   Apache-2.0 或 MIT。** GitHub 官方文档也明确：公开仓库若要成为开源项目，
   应明确授予使用、修改和分发权，并建议在仓库根目录放置标准许可证文件
   （[GitHub：Licensing a repository](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)）。
2. **项目名称、技术栈或目录重写不能单独消除既有代码的许可证。** BSL 1.1
   明文规定，原作、修改副本和衍生作品均受 BSL 约束，并要求在原版或修改版
   副本中显著展示该许可证
   （[MariaDB：BSL 1.1 Terms](https://mariadb.com/bsl11/)）。MariaDB 的官方
   FAQ 进一步明确：修改 BSL 软件后不能将修改版改以 MIT 分发；只有开发出的
   软件**不包含任何 BSL 代码**时，才可以采用其他许可证
   （[MariaDB：Adopting and Developing BSL Software](https://mariadb.com/bsl-faq-adopting/)）。
3. **因此，只有在逐文件来源审计确认 Termestra 发布物不再包含 Hive BSL
   内容时，才能删除 Hive 的 `LICENSE.BSL` 和 Hive 专用 `NOTICE`。** 若仍保留
   任何 BSL 原作、修改代码或衍生内容，则不能用新的 Apache/MIT 根许可证覆盖
   这部分，也不能删除适用的 BSL 文本。Hive 自己的
   [`LICENSE.BSL`](https://github.com/tt-a1i/hive/blob/main/LICENSE.BSL)
   指定的 Licensed Work 是 “Hive 0.6.0-alpha.8 and later”；Hive 的
   [`NOTICE`](https://github.com/tt-a1i/hive/blob/main/NOTICE) 还要求重分发、分叉
   和修改版本保留该 notice 与适用许可证文件。
4. **第三方图标的“来源链接”不等于再分发许可。** 每个被提交或打包的图标、
   声音、提示词快照和依赖都应有可验证的许可证/品牌使用依据，并保留该依据
   要求的完整许可证、版权和归属文本。没有明确许可或品牌规则允许当前用途的
   图标，应在公开发布前替换为 Termestra 自制的中性图标或取得书面许可。

## 方案 A：Termestra 自有代码采用 Apache-2.0

### 仓库与发布物需要的文件

| 文件/位置 | 要求 | 依据 |
| --- | --- | --- |
| 根目录 `LICENSE` | 放置**未经改写的完整 Apache License 2.0 文本**；不要在标准文本前混入历史说明，否则 GitHub 的许可证识别可能失败。 | Apache 官方要求把 `LICENSE-2.0.txt` 内容复制到发布物顶层的 `LICENSE`（[Applying ALv2](https://www.apache.org/legal/apply-license)）；标准全文见 [LICENSE-2.0.txt](https://www.apache.org/licenses/LICENSE-2.0.txt)。GitHub 建议复杂说明移到 README，以保持 `LICENSE` 易于识别（[GitHub licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)）。 |
| 根目录 `NOTICE` | 建议为 Termestra 建立简洁 notice，并汇总**许可证明确要求**保留的第三方归属；发布的源码包、二进制包、npm CLI 与平台 runtime 都应携带相同适用 notice。 | Apache 2.0 第 4(d) 节要求：上游 Work 含 `NOTICE` 时，衍生分发必须保留与实际内容有关的 notice；Apache 官方应用指南也要求分发物携带正确的 `NOTICE`（[Apache 2.0 §4](https://www.apache.org/licenses/LICENSE-2.0.txt)、[Applying ALv2](https://www.apache.org/legal/apply-license)）。 |
| `THIRD_PARTY_NOTICES.md`（或 `THIRD_PARTY_NOTICES`） | 建立完整清单：文件/组件、固定版本或 commit、来源 URL、许可证标识和全文位置、版权/商标 notice、是否修改、进入哪些发布包。 | Apache 的第三方作品指南要求不得移除第三方版权或许可证，并要求每个第三方作品随附其许可证；第三方媒体明显关联的版权 notice 应进入 NOTICE（这是 ASF 项目政策，但可作为 Apache-2.0 项目的保守发布清单；[ASF Source Header and Copyright Notice Policy](https://www.apache.org/legal/src-headers.html)）。 |
| 各第三方目录 | 对 MIT、CC0 或其他内容保留各自完整许可证；不要给第三方文件加 Termestra 的 Apache 版权头。 | [ASF 对第三方作品的处理规则](https://www.apache.org/legal/src-headers.html)。 |
| README 与元数据 | README 标注 `Apache-2.0`；Maven `<licenses>`、两个 npm `package.json`、发布脚本与校验脚本统一为 `Apache-2.0`，且打包根 `LICENSE`/`NOTICE`/第三方 notices。 | GitHub 的准确识别依赖标准根许可证；GitHub 使用的 SPDX 关键词为 `Apache-2.0`（[GitHub licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)）。 |
| 源文件头 | 对 Termestra 自有代码可采用 Apache 附录的简短声明；至少确保根许可证和发布物完整携带许可证。 | Apache 2.0 附录给出应用声明；ASF 的官方应用指南建议原创代码和文档带简短头，同时说明每个发布物只需一份完整许可证（[Applying ALv2](https://www.apache.org/legal/apply-license)）。 |

### Apache-2.0 的实质特点

- 除版权许可外，Apache-2.0 第 3 节包含贡献者的明确专利许可及专利诉讼终止
  条款；这通常比 MIT 更适合希望明确处理贡献者专利授权的协作型软件项目
  （[Apache License 2.0 §§2–3](https://www.apache.org/licenses/LICENSE-2.0.txt)）。
- 第 4 节要求向接收者提供许可证、标记修改文件、保留相关版权/专利/商标/
  归属 notice，并在上游含 NOTICE 时处理该 notice
  （[Apache License 2.0 §4](https://www.apache.org/licenses/LICENSE-2.0.txt)）。
- 第 6 节不授予商标权。因此，Apache-2.0 不能代替对第三方产品图标和名称的
  品牌使用审核
  （[Apache License 2.0 §6](https://www.apache.org/licenses/LICENSE-2.0.txt)）。

## 方案 B：Termestra 自有代码采用 MIT

### 仓库与发布物需要的文件

| 文件/位置 | 要求 | 依据 |
| --- | --- | --- |
| 根目录 `LICENSE` | 放置标准 MIT 全文，填入真实年份和实际版权持有人。不要沿用 Hive 的权利人，也不要在权属未确认时猜测个人/组织名称。 | MIT 唯一明确的再分发条件是：版权 notice 和许可 notice 必须包含在所有副本或实质性部分中（[OSI：MIT License](https://opensource.org/license/mit)）。 |
| 根目录 `NOTICE` | MIT 本身不要求项目级 `NOTICE`；可不建或仅作信息性文件。但第三方许可证要求的 notice 仍必须保留。 | 标准 MIT 文本只要求保留版权与许可 notice，没有单独 NOTICE 条款（[OSI：MIT License](https://opensource.org/license/mit)）。 |
| `THIRD_PARTY_NOTICES.md` 与第三方目录许可证 | 与 Apache 方案相同：MIT 只能许可 Termestra 有权许可的内容，不能覆盖第三方资产的条件。保留每个第三方组件自己的许可证/归属。 | MIT 的许可主体是版权持有人提供的 “Software”；第三方权利不会因根项目采用 MIT 自动转移（[OSI：MIT License](https://opensource.org/license/mit)）。 |
| README 与元数据 | README、Maven `<licenses>`、npm `license`、发布脚本与校验脚本统一为 SPDX `MIT`；每个源码/二进制/npm 分发物包含根 MIT `LICENSE` 及适用第三方 notices。 | GitHub 官方许可证关键词包含 `MIT`，并建议根目录放置标准 license 文件（[GitHub licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)）。 |

### MIT 的实质特点

- MIT 文本更短，允许使用、复制、修改、合并、发布、分发、再许可和销售；
  条件是保留版权和许可文本
  （[OSI：MIT License](https://opensource.org/license/mit)）。
- 标准 MIT 文本没有 Apache-2.0 那样单列的贡献者专利许可和专利诉讼终止
  条款。若项目关注贡献者专利边界，应在选择前由权利人评估。

## BSL/Hive 边界：何时能删，何时不能删

### 可以删除 Hive 专用文件的前提

只有同时满足并留存证据时，才可把 Termestra 当作纯自有 Apache/MIT 项目处理：

1. 发布源码与构建产物中不包含 Hive BSL 版本的源代码、复制片段、修改文件、
   prompt/协议文档或其他受版权保护表达；
2. 不是仅做改名、语言移植、结构重排或语法翻译；这些事实本身不能证明独立
   创作；
3. 对相似功能保有独立需求来源、架构决策、测试与实现记录，能够解释其来源；
4. 已清除源码、README、包元数据、构建脚本、发布模板与生成物中的 Hive BSL
   许可声明；
5. 若存在疑似继承内容，已经得到上游权利人的另外授权，或由专业人士确认不在
   BSL Licensed Work/衍生范围内。

满足以上事实后，Hive 的许可证不再是 Termestra **自有且独立内容**的许可证，
可以从 Termestra 的发布物移除；但审计记录应内部留存，而不是通过删除历史来
替代来源证明。GitHub 同样提醒，在已有许可证的仓库中应用新许可证属于需要
谨慎处理的法律问题，并建议有疑问时咨询专业人士
（[GitHub licensing disclaimer](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository#disclaimer)）。

### 不能删除或改成 MIT/Apache 的情况

- 保留了 Hive BSL 原代码或其修改/衍生版本：BSL 要求原版、修改版和衍生作品
  继续受 BSL 约束，并显著展示 BSL
  （[BSL 1.1 Terms](https://mariadb.com/bsl11/)）。
- 只是把 TypeScript/Node 实现逐句或结构性移植为 Java：语言转换不会自动产生
  无来源的新版权边界；是否构成衍生需结合表达相似度和形成过程判断。
- 修改后想统一标为 MIT：MariaDB 官方 FAQ 对此明确回答 “No”
  （[BSL FAQ：modified BSL code and MIT](https://mariadb.com/bsl-faq-adopting/)）。
- 同一发布物混合 BSL 与其他许可：在 Change Date 之前，混合作品同时受到
  BSL 和其他组件许可证约束，不能用一个根 MIT/Apache 声明掩盖 BSL 部分
  （[BSL FAQ：mixing BSL code](https://mariadb.com/bsl-faq-adopting/)）。

## Termestra 仓库当前事实（2026-08-12 快照）

以下只是当前文件内容，不是对衍生关系的法律结论：

- 根 `LICENSE` 仍以 “This Apache License 2.0 applies only to versions of
  Hive…” 开头，不是可直接作为 Termestra 独立许可证的干净标准文本。
- `README.md`、`README.zh-CN.md` 仍声明 “Portions derived from Hive”，并引用
  `LICENSE.BSL`、`NOTICE` 和 `TRADEMARK.md`。
- `distribution/npm/cli/package.json` 与
  `distribution/npm/runtime-template/package.json` 仍声明 `BUSL-1.1`。
- `distribution/pom.xml`、`distribution/scripts/build-npm-runtime.sh` 和
  `distribution/scripts/verify-npm-runtime.mjs` 仍要求把 `LICENSE.BSL`、
  `LICENSE`、`NOTICE` 打进发布包或验证其存在。
- `distribution/target/**` 中仍有旧 Hive `LICENSE.BSL`/`NOTICE` 副本；这些是
  构建生成物，不应用作源许可判断，但重新打包前应 `clean` 并验证新产物。
- `backend/src/main/resources/vendor/marketplace/{en,zh}/` 已分别保存固定 commit
  的 `SOURCES.md` 和 MIT `LICENSE`，这是正确的第三方来源记录方向。
- `frontend/web/public/sounds/LICENSE-KENNEY.txt` 保存了 Kenney Interface Sounds
  的 CC0 说明；发布脚本也显式携带它。
- `frontend/web/public/cli-agent-icons/SOURCES.md` 与
  `frontend/web/public/open-target-icons/SOURCES.md` 目前主要记录下载或提取来源，
  **没有为多数图标记录可再分发许可证或品牌规则版本**。

在切换许可证前，应让一次来源审计给出“逐文件清单 + 来源证据”，再决定删除
Hive 许可文件；不能仅依据产品方口头声明“没有关系”来覆盖现有仓库文字和形成
历史。

## 第三方图标、vendor 与其他 assets 的发布处理

### 当前可以保留但要完整打包的内容

1. `vendor/marketplace/en` 和 `vendor/marketplace/zh`：继续随快照携带各自 MIT
   `LICENSE` 和 pinned `SOURCES.md`。MIT 要求所有副本或实质性部分保留版权和
   许可 notice（[OSI：MIT License](https://opensource.org/license/mit)）。
2. Kenney 音效：继续随音频携带 `LICENSE-KENNEY.txt`，并在
   `THIRD_PARTY_NOTICES.md` 记录 `CC0-1.0`、素材包名称和来源。CC0 是否要求
   署名应以素材随附文本为准；现有文件说明署名非强制。
3. jlink/JDK runtime：不要删除 runtime 自带的 `legal/**`。这些文件记录 JDK
   与其第三方组件的许可，平台 runtime 发布包应原样保留。

### 当前图标清单的缺口

“来自官网 favicon”“从已安装 App 中提取”“来自官方仓库图片”只能证明来源，
不能证明允许把图片提交到 Termestra 仓库并随 npm/runtime 再分发。至少应逐项
补齐：

- 资产权利人；
- 精确来源与获取日期/commit；
- 适用于**该图片文件**的许可证或官方品牌规则永久链接；
- 允许的具体用途（例如“Open in …”操作项、兼容性标识）；
- 是否允许复制、随软件再分发、缩放/裁切/加背景；
- 要求显示的商标/版权 notice；
- 无明确许可时的替换决定或书面授权记录。

例如，Visual Studio Code 官方品牌页允许特定的文档、链接和 “Open in VS
Code”动作格式，但同时禁止用图标标识/推广自己的产品、修改图标或形成不当
品牌关联，并要求超出规则时联系品牌方
（[VS Code icons and names usage guidelines](https://code.visualstudio.com/brand)）。
这说明仅在 `SOURCES.md` 写 “Microsoft approved icon” 仍不足以证明所有当前
UI 和再分发方式都获准；需要把 Termestra 的具体用途逐条对照官方规则。

对从 Codex/IntelliJ/Finder/Terminal 应用包提取的图标，以及从 Claude、Gemini、
Qwen、Cursor、Hermes 等网站获取的 favicon，如果没有找到明确覆盖再分发的
许可或品牌条款，公开发布前的低风险做法是换成 Termestra 原创的中性 CLI/
编辑器/文件管理器图标，只用文字显示产品名称；否则应取得权利人的书面许可。

### 建议的 `THIRD_PARTY_NOTICES.md` 条目模板

```text
## <组件或资产名称>

- Included files: <仓库相对路径>
- Upstream: <官方 URL>
- Version/commit/retrieved: <固定版本、commit 或获取日期>
- Copyright/trademark owner: <权利人>
- License/brand terms: <SPDX + 永久链接；必要时附全文文件路径>
- Modifications: <none / resize / crop / recolor ...>
- Distribution: <source / web bundle / npm CLI / platform runtime>
- Required notice: <必须展示的原文>
```

`SOURCES.md` 可继续保留为可追溯清单，但不能替代许可证全文或明确的品牌许可。

## 发布前执行清单

1. 确认真实版权持有人和年份；不要自动沿用 Hive 的 “Shaokun Tu (tt-a1i)” 或
   “tt-a1i contributors”。
2. 完成逐文件来源审计，形成三类：Termestra 原创、明确许可的第三方、待移除/
   待授权。
3. 只有审计确认没有 Hive BSL 内容后，才删除 Hive `LICENSE.BSL`、Hive NOTICE
   和 Hive 商标说明；否则保留并采用多许可证结构。
4. 在 Apache-2.0 与 MIT 中由权利人作出选择，然后放入干净、标准的根
   `LICENSE`。
5. 新建 Termestra `NOTICE`（Apache 方案建议/发布实践需要）和
   `THIRD_PARTY_NOTICES.md`，补齐所有 vendor、声音、图标和 runtime notices。
6. 统一 README、Maven、npm package、构建脚本、校验脚本与发布模板的 SPDX
   标识和随包文件。
7. 清理 `target/`、`dist/` 等旧产物后重新构建；检查每个 npm tarball 和平台
   runtime 的许可证内容，而不只检查源仓库。
8. 对缺少明确再分发依据的官方图标，先移除/替换，或在公开发布前取得授权。
9. 用 GitHub Licensee/仓库页面确认根许可证能被正确识别；复杂的第三方说明放
   README/NOTICE，不要污染标准 `LICENSE`
   （[GitHub：Detecting a license](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository#detecting-a-license)）。
10. 首次公开发布前，由熟悉开源与软件版权的专业人士复核 BSL 边界和第三方
    商标资产。

## 官方/原始来源

- [Apache License 2.0 标准全文](https://www.apache.org/licenses/LICENSE-2.0.txt)
- [Apache：Applying the Apache License, Version 2.0](https://www.apache.org/legal/apply-license)
- [Apache：Source Header and Copyright Notice Policy](https://www.apache.org/legal/src-headers.html)
- [MIT License（OSI）](https://opensource.org/license/mit)
- [MariaDB：Business Source License 1.1](https://mariadb.com/bsl11/)
- [MariaDB：Adopting and Developing BSL Software FAQ](https://mariadb.com/bsl-faq-adopting/)
- [GitHub Docs：Licensing a repository](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
- [Hive `LICENSE.BSL`](https://github.com/tt-a1i/hive/blob/main/LICENSE.BSL)
- [Hive `NOTICE`](https://github.com/tt-a1i/hive/blob/main/NOTICE)
- [Hive historical `LICENSE`](https://github.com/tt-a1i/hive/blob/main/LICENSE)
- [Visual Studio Code brand/icon usage rules](https://code.visualstudio.com/brand)
