<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community
SPDX-FileCopyrightText: 2026 HaoHao IME contributors

SPDX-License-Identifier: GPL-3.0-or-later
-->

<img src="app/src/main/res/mipmap-xxxhdpi/ic_app_icon.png" alt="好好输入法图标" width="112" />

# 好好输入法（HaoHao IME）

面向中文用户的 Android 双语输入法实验项目。

[![Debug CI](https://github.com/littlewindy123/haohao-ime/actions/workflows/debug-ci.yml/badge.svg?branch=main)](https://github.com/littlewindy123/haohao-ime/actions/workflows/debug-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Upstream: Trime](https://img.shields.io/badge/upstream-osfans%2Ftrime-0969da)](https://github.com/osfans/trime)

## 项目目标

我们的长期目标是：用户输入中文时，在中文候选词中同步显示对应的英文翻译，帮助中文用户在输入过程中自然地接触和使用英语。

> 默认仍使用完整离线 CC-CEDICT 数据源：候选命中可靠的单词释义时，以“中文在上、英文在下”展示。需要整句翻译时，用户可以另外主动配置云翻译；密码等敏感输入框始终禁止上传。

## 当前状态

- [x] 原版 Trime 可在 Windows 隔离工具链中编译
- [x] Android API 36、x86_64 模拟器安装和启动正常
- [x] “好好拼音”可输入“你好”“中国”
- [x] 原版中文输入、Rime 部署和中英文切换通过回归测试
- [x] 建立公开 Fork、中文项目说明和轻量 Debug CI
- [x] 使用完整离线 CC-CEDICT 与常用词覆盖表提供双语候选
- [x] 接入万象 `v17.7.1` 的 141.8 万条现代简体基础词库
- [x] 建立 100 词现代简体输入回归集与独立设备测试包
- [x] 支持固定英文行、防抖显示、候选数量设置和可选美式 IPA
- [x] 新安装默认启用“好好拼音”和“好好 26 键”主题
- [x] 打开 App 直接显示中文常用设置，并保留完整高级设置入口
- [x] 支持本地输入足迹、收藏、双语回看与系统英文发音
- [x] 支持用户自有阿里云、自定义 HTTPS 接口和可选公共网关的云翻译框架
- [x] 顶部英文严格收口为单个词，并建立人工与高频翻译质量回归
- [ ] 继续处理多义词语境和翻译质量问题
- [ ] 真机兼容性与触屏体验验证

详细阶段安排见 [ROADMAP.md](ROADMAP.md)。

### 现代简体词库

- “好好拼音”保留 `luna_pinyin_simp` 方案 ID、原有用户词典和学习数据，在其上组合好好热词、万象现代词库与原版 Luna 词典。
- 现代词库固定使用万象拼音 `v17.7.1` 的完整 `jichu.dict.yaml`，共生成 `1,418,352` 条带权词条；来源、哈希、许可与唯一已知排除项记录在 `app/dictionary/wanxiang/`。
- 发布维护时离线校验固定 gzip，确定性移除拼音声调，并用与 Android 端一致的 librime 版本生成固态词典；普通 Gradle 构建只校验并打包固定的预编译产物。
- APK 不再携带展开后的 40.3MB 万象 YAML，也不会在用户首次打开键盘时现场编译 142 万词。预编译文件缺失或损坏时会从 APK 自动恢复，版本不匹配则进入明确的引擎故障状态。
- App 与首次引导会尽早启动同一个 Rime 后台准备过程；首次安装或升级只在复制预编译文件时流式计算一次 SHA-256，完成后最后更新 `checksums.json`。后续启动在清单版本一致时只检查文件存在性和大小，不得再次读取整份约 50MB 固态词典计算哈希。
- 词库升级仍随新版 APK 发布，不增加网络权限、后台下载或热词上报。预编译产物的版本、输入哈希、文件哈希与维护流程见 `app/dictionary/rime-prebuilt/`。

### 中文输入回归

- `app/dictionary/regression/haohao_pinyin.tsv` 固定维护 100 条均衡用例，覆盖日常词、生活短语、品牌应用、科技 AI 与网络常用语；一半要求首选，一半要求进入顶部 4 项。
- `regression` 构建使用 `.regression` 包名和应用专属 `regression-rime` 数据目录。仅设置 `applicationIdSuffix` 不能隔离 Rime 默认的 `/sdcard/rime`，设备测试必须在启动 Rime 前显式设置独立用户数据目录。
- 设备测试逐字输入拼音，读取 Rime 前 16 个原始候选，再经过正式紧凑栏的词组优选逻辑检查默认 4 项；它不选择或提交候选，不产生用户学习数据。
- 热词补丁只接受能够追溯到回归集的明确失败项。首选目标使用权重 `1000000`，顶部 4 项目标使用权重 `300000`，避免用大量强制词频掩盖真实候选质量。

### 离线双语候选

- “候选窗口 → 显示英文翻译”默认开启，修改后在下一次候选刷新时生效。
- 英文翻译默认在候选稳定 `300ms` 后显示；等待或未命中时仍保留统一的英文行位置，避免候选栏跳动。
- 竖屏紧凑栏默认以 4 个候选为目标，横屏默认以 6 个为目标；英文可读宽度会随候选目标数自动分配，展开页仍从首选词开始展示完整的 3×3 网格。
- “显示美式英语音标”默认关闭。开启后，能够完整匹配英文释义的候选会增加第三行 IPA，并与英文共用防抖时间。
- 紧凑候选栏、展开候选页和悬浮候选窗使用同一展示规则；未收录候选只显示中文。
- 翻译仅添加在应用展示层，不修改 Rime 候选数据、顺序、索引或提交行为。
- 词典固定使用 CC-CEDICT `2026-08-24` 版本，构建全程离线；来源、SHA-256 与许可记录见 `app/dictionary/cc-cedict/`。
- 美式 IPA 固定来自 `open-dict-data/ipa-dict` 提交 `43c3570eb3553bdd19fccd2bd0091534889af023`；来源、SHA-256 与 MIT 许可记录见 `app/dictionary/ipa-dict/`。
- 固定源包含 124,925 条记录和 121,165 个唯一简体词头；当前按严格单词规则和人工校准生成 51,430 个可展示翻译，其余词头不会用截断文本或短语凑数。
- 每个候选最多显示一个不超过 18 个字符的英文单词；允许单词内部的连字符和撇号。`to`、`a`、`an`、`the` 只会在后面恰好仍是一个单词时被规范化移除，例如 `to sigh → sigh`；`go home`、`input method` 等短语直接隐藏。人工覆盖表仍拥有最高优先级，使用 `-` 可明确隐藏不合适的释义。
- 词典通过只读内存映射和二分查询使用，不会把十几万词头全部载入 Java `HashMap`；文件损坏或加载失败时自动隐藏英文，不影响中文输入。
- 离线词典按完整中文候选查词，不拆分或拼接整句翻译，查询过程不会产生网络请求。

### 可选云翻译

- 云翻译默认关闭，离线候选与中文提交不依赖网络。首次开启时会明确提示“仅待翻译文字会发送至所选服务”。
- 可选择用户自有阿里云 RAM 账号或自定义 HTTPS `POST` 接口；AccessKey Secret 和 Bearer Token 使用 Android Keystore 与 AES-GCM 加密，并排除系统备份、诊断和日志。
- “翻译输入”使用独立中文草稿，停顿 `800ms` 后异步翻译。原文或译文只有在用户明确点击上屏时才写入当前 App，整句内容不保存历史。
- “本地无释义时在线补全”默认关闭；开启后最多上传五个顶部可见且离线未命中的候选。云候选结果也必须通过同一单词规则，短语结果会被拒绝并短期负缓存；该限制不影响用户主动打开的整句翻译模式。云结果不修改 Rime 顺序、索引或中文提交内容，并按服务指纹隔离缓存。
- 好好公共网关位于 `service/translation-gateway/`，默认绑定 `127.0.0.1`，只记录状态码、耗时和字符数。正式域名和 HTTPS 就绪前，Android 构建中的公共服务地址保持为空，界面显示“准备中”。
- 公共网关使用阿里云官方 Go V2 SDK，并在服务端执行每安装实例每日 50 次、公共账号每月 900,000 字符的硬限制；阿里云密钥只允许存在于服务器权限为 `0600` 的环境文件中。
- 异步防抖、取消过期结果和失败不阻塞输入的职责划分参考了 [水杉输入法](https://github.com/metasequoiaime/MetasequoiaImeTsf) 的公开设计；Android 实现为本项目独立代码，没有复制其 Windows 源码。

### 翻译质量回归

- `translation_quality_zh_en.tsv` 固定维护 200 条人工标准答案，覆盖 50 个单字、70 个常用词、35 个生活短语、25 个现代词和 20 个高风险多义词；`-` 明确表示该候选不应展示英文。
- `:app:verifyTranslationQuality` 从固定万象词库确定性选取权重最高的 5000 个唯一词头，检查覆盖率、单词结构、长度、括号与结构说明、异常字符、空白和 IPA 完整匹配；当前严格单词基线为覆盖 2725 项（54.50%）、0 个硬错误。
- 缺少翻译只会进入 `app/build/reports/translation-quality/missing.tsv`，不会自动编造英文或阻断构建；覆盖率下降、硬错误增加、人工标准答案漂移或无法追溯的人工覆盖会使质量检查失败。
- 长尾释义继续由固定 CC-CEDICT 自动生成。每个版本只从高频待处理报告和用户 Issue 中校准少量真实问题，逐步扩展人工基线，不尝试一次人工审核全部词条。

### 简体优先与好好 26 键

- 全新安装只启用 `luna_pinyin_simp`，首次输入无需选择方案即可得到简体候选。
- “好好输入法”主题提供四排 26 键主键盘，以及独立的数字页和常用符号页；主键盘不显示方案切换、复杂长按提示或技术型入口。
- 默认值只应用于全新安装，不会强制覆盖已有用户选择；原版 Trime 主题与其他 Rime 方案文件继续保留，便于兼容和开发对照。

### 常用设置

- 首页采用奶油白、薄荷绿与蜂蜜黄组成的金毛品牌界面；金毛形象只用于顶部品牌区，设置内容保持清晰克制，并提供对应深色资源令牌。
- 打开 App 默认显示双语候选、候选栏和键盘体验三组常用设置；方案、用户词典、配置及其他原版功能继续从“更多高级设置”进入。
- 常用首页直接复用原有 `AppPrefs` 键和值，不建立第二套配置。升级安装会保留翻译延迟、IPA、候选数量、主题和敲击反馈等已有选择。
- 关闭“显示英文翻译”时只禁用延迟与 IPA 控件，不清除其保存值；重新开启后恢复原设置。

### 编辑手势与单手键盘

- 好字工具箱提供输入足迹、文本编辑、剪贴板、表情符号、语音输入和常用设置六个明确入口；文本编辑面板复用 `InputConnection`，不保存选区或输入框正文。
- 好好主题默认开启空格滑动移动光标和退格滑动删除。退格反向滑动只恢复同一次未结束手势删除的内容；松手、切换输入框或关闭键盘后立即清空临时缓冲。
- 正在组合拼音时两种编辑手势均不修改正文；密码框禁用可恢复滑动删除，已有文本选区交给普通退格处理。
- 键盘高度提供紧凑、标准和宽松三档。单手模式仅在竖屏生效，固定保留 `52dp` 侧栏用于切换左右或恢复全宽；横屏临时恢复全宽但保留用户选择。

### 输入足迹与收藏

- 仅在用户提交带离线英文释义的 Rime 中文候选时记录词头、最近输入时间和累计次数；不监听剪贴板、英文按键、标点、整句上下文、所在 App 或输入框内容。
- 密码类输入框和带 `IME_FLAG_NO_PERSONALIZED_LEARNING` 标记的输入框始终不记录。用户可关闭“记录输入足迹”，关闭后保留已有数据。
- 足迹使用独立的 `haohao_vocabulary.db`，并存放在 Android 排除自动备份和设备迁移的应用目录中；最近输入最多保留 100 个去重词，收藏不会被最近列表淘汰。英文和 IPA 不写入数据库，展示时始终读取当前离线词典。
- 清空最近输入会保留收藏，清空全部足迹与收藏需要二次确认。英文发音复用 Android `TextToSpeech` 的 `Locale.US` 语音，不打包音频，也不自动下载缺失的语音数据。

## 开发与测试约定

- 当前主要交互测试环境为 Android 模拟器 `Trime_API_36`（Android API 36、x86_64）。
- 每项功能需要通过 Debug APK 构建、模拟器安装、Rime 部署、输入交互和 Logcat 检查。
- 新增常用设置入口时必须复用现有 Preference 键，并保留通往完整设置页的兼容入口，禁止复制或迁移同一项用户配置。
- 现代词库或热词改动还需执行 `:app:connectedRegressionAndroidTest`；该测试会连续启动两次 Rime，验证 100 词排名稳定且第二次不重复编译组合词典。
- 翻译词典或人工覆盖改动必须执行 `:app:verifyTranslationQuality`；报告位于 `app/build/reports/translation-quality/`，不得通过降低既有覆盖基线或放宽硬错误上限来掩盖回归。
- 顶部候选英文必须始终是单个词；允许内部连字符和撇号，禁止空格短语及省略截断。离线生成器、人工质量集和云候选缓存必须使用一致规则；整句翻译只允许存在于独立翻译输入模式。
- 输入足迹只能从 Rime 候选提交事件写入，必须继续执行敏感 `EditorInfo` 过滤；英文、IPA、剪贴板、应用名和整句内容不得持久化到足迹数据库，数据库必须保存在 `noBackupFilesDir` 中以排除 Android 自动备份和设备迁移。
- 云翻译必须先经过离线查询和显式同意，密码及 `IME_FLAG_NO_PERSONALIZED_LEARNING` 输入框必须完全禁用。密钥不得进入默认偏好、备份、诊断或日志，网络失败和过期异步结果不得改变中文候选或阻塞提交。
- 好好主题的滑动步长必须按屏幕密度把设置中的 `dp` 换算为像素；不能改变其他 Trime 主题沿用的手势计算方式。组合拼音、密码框和选区保护需要同时覆盖单元测试与真实输入框交互。
- 输入法视图可能早于 Rime 部署完成被系统请求；任何主题读取都必须先确认 `RimeRuntimeState.READY` 且 `ThemeManager.isInitialized`。准备或失败期间只能显示不依赖 Rime/主题的轻量状态页，禁止直接读取 `ThemeManager.activeTheme`。
- 懒加载输入法视图构造期间，不得同步收集会立即发射的 `StateFlow` 并回入同一视图；状态订阅必须等根视图完成挂载后再启动，避免重复创建容器或重复挂载子视图。
- 正常首启必须复用 `shared/build` 中与当前 librime 精确匹配的预编译词典。普通构建和启动不得展开或现场编译完整万象词库；预编译输入、版本或 SHA-256 变化时必须先重新生成固定产物。
- 预编译清单必须覆盖默认方案的传递依赖；当前除 `haohao_pinyin` 与 `luna_pinyin_simp` 外，还必须携带 `stroke` 的 schema、table、reverse 和 prism。真机清数据首启的 Logcat 若出现 `building table`，即视为预编译集合不完整。
- 键盘与双语候选至少在 360dp、411dp 两种模拟器宽度回归，检查按键、候选和点击区域是否重叠或错位。
- CI 同时构建 `arm64-v8a` 与 `x86_64`，为未来 Android 真机测试保留 ARM64 产物。
- 在真实设备条件具备前，模拟器测试作为阶段验收依据；稳定版本发布前仍必须补充真机测试。

## 构建

### 环境要求

- JDK 17
- Android Platform 36
- Android Build Tools 36.0.0
- Android NDK 28.0.13004108
- CMake 3.31.6
- Git 子模块和符号链接支持

Windows 用户应先开启“开发人员模式”，然后只为本仓库启用 Git 符号链接：

```powershell
git config --local core.symlinks true
```

### 获取源码

```sh
git clone --filter=blob:none --branch main https://github.com/littlewindy123/haohao-ime.git
cd haohao-ime
git submodule update --init --recursive --filter=blob:none
```

### 构建 Debug APK

Windows：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

如果仓库路径包含中文，AGP/CMake 可能在 `android_gradle_build.json` 报 `Invalid escape sequence`。可把仓库临时映射到一个空闲的纯 ASCII 盘符后构建，完成后删除映射；文件仍保存在原目录，不会复制源码或修改全局环境：

```powershell
subst H: "D:\好好输入法"
H:
.\gradlew.bat :app:assembleDebug --no-daemon
subst H: /d
```

Linux 或 macOS：

```sh
./gradlew :app:assembleDebug --no-daemon
```

APK 默认生成在 `app/build/outputs/apk/debug/`。

需要长期覆盖安装同一 Debug 包时，应固定 `ANDROID_USER_HOME`；Android Debug 证书默认位于该目录的 `debug.keystore`。更换该目录会生成不同证书，模拟器会以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝覆盖安装。遇到此错误时应先比较 APK 证书并恢复原 Android 用户目录，不能直接卸载而丢失设置、词频和足迹数据。

## 与上游同步

本项目保留 [osfans/trime](https://github.com/osfans/trime) 作为 `upstream`。`develop` 分支用于跟踪官方开发分支，项目自己的功能开发在 `main` 和功能分支上进行。

```sh
git fetch upstream
git switch develop
git merge --ff-only upstream/develop
```

同步上游后，应通过独立分支和 Pull Request 将需要的更新合入 `main`，避免直接覆盖项目改动。

## 参与贡献

欢迎通过 Issue 讨论中文输入体验、双语候选设计、翻译来源、性能和隐私方案。提交代码前请从 `main` 创建功能分支，并确保 Debug CI 通过。

## 上游与许可证

好好输入法基于 [Trime](https://github.com/osfans/trime) 和 [Rime](https://rime.im) 开发。原项目的作者、贡献者、历史说明和第三方依赖信息请参阅 [Trime 简体中文说明](README_sc.md) 与上游仓库。

本项目延续 GNU General Public License v3.0 or later，完整条款见 [LICENSE](LICENSE)。代码中的原始版权和 SPDX 声明均予以保留。随 APK 分发的 CC-CEDICT 数据遵循 CC BY-SA 4.0；万象拼音现代词库遵循 CC BY 4.0。两者均在应用“开源软件许可”和各自数据来源文档中署名。
