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

> 当前已接入完整离线 CC-CEDICT 数据源：命中词或固定短语时，候选以“中文在上、英文在下”展示。本功能不是任意整句翻译，长句和未生成合适短释义的候选仍只显示中文。

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
- [ ] 继续处理多义词、短语语境和翻译质量问题
- [ ] 真机兼容性与触屏体验验证

详细阶段安排见 [ROADMAP.md](ROADMAP.md)。

### 现代简体词库

- “好好拼音”保留 `luna_pinyin_simp` 方案 ID、原有用户词典和学习数据，在其上组合好好热词、万象现代词库与原版 Luna 词典。
- 现代词库固定使用万象拼音 `v17.7.1` 的完整 `jichu.dict.yaml`，共生成 `1,418,352` 条带权词条；来源、哈希、许可与唯一已知排除项记录在 `app/dictionary/wanxiang/`。
- 构建时离线校验固定 gzip，确定性移除拼音声调并生成 Rime 资产；构建过程不联网，也不会把生成文件写回源码目录。
- APK 首次部署大型词库会明显慢于后续启动。词库升级随新版 APK 发布，不增加网络权限、后台下载或热词上报。

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
- 固定源包含 124,925 条记录和 121,165 个唯一简体词头；按短释义规则生成 83,340 个可展示翻译，其余词头不会用截断文本凑数。
- 每个候选最多显示一个不超过 18 个字符的真实短释义；生成器优先保留词典中的常用释义顺序，并避免同词头的专名释义覆盖普通含义。人工覆盖表仍拥有最高优先级。
- 词典通过只读内存映射和二分查询使用，不会把十几万词头全部载入 Java `HashMap`；文件损坏或加载失败时自动隐藏英文，不影响中文输入。
- 当前支持词和固定短语，不拆分或拼接整句翻译，也不需要网络权限。

### 简体优先与好好 26 键

- 全新安装只启用 `luna_pinyin_simp`，首次输入无需选择方案即可得到简体候选。
- “好好输入法”主题提供四排 26 键主键盘，以及独立的数字页和常用符号页；主键盘不显示方案切换、复杂长按提示或技术型入口。
- 默认值只应用于全新安装，不会强制覆盖已有用户选择；原版 Trime 主题与其他 Rime 方案文件继续保留，便于兼容和开发对照。

## 开发与测试约定

- 当前主要交互测试环境为 Android 模拟器 `Trime_API_36`（Android API 36、x86_64）。
- 每项功能需要通过 Debug APK 构建、模拟器安装、Rime 部署、输入交互和 Logcat 检查。
- 现代词库或热词改动还需执行 `:app:connectedRegressionAndroidTest`；该测试会连续启动两次 Rime，验证 100 词排名稳定且第二次不重复编译组合词典。
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
