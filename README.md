<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community
SPDX-FileCopyrightText: 2026 HaoHao IME contributors

SPDX-License-Identifier: GPL-3.0-or-later
-->

# 好好输入法（HaoHao IME）

面向中文用户的 Android 双语输入法实验项目。

[![Debug CI](https://github.com/littlewindy123/haohao-ime/actions/workflows/debug-ci.yml/badge.svg?branch=main)](https://github.com/littlewindy123/haohao-ime/actions/workflows/debug-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Upstream: Trime](https://img.shields.io/badge/upstream-osfans%2Ftrime-0969da)](https://github.com/osfans/trime)

## 项目目标

我们的长期目标是：用户输入中文时，在中文候选词中同步显示对应的英文翻译，帮助中文用户在输入过程中自然地接触和使用英语。

> 当前已完成双语候选最小原型：命中内置演示词表时，候选以“中文在上、英文在下”展示。该原型用于验证界面和数据流，尚不是完整翻译能力。

## 当前状态

- [x] 原版 Trime 可在 Windows 隔离工具链中编译
- [x] Android API 36、x86_64 模拟器安装和启动正常
- [x] “朙月拼音·简化字”可输入“你好”“中国”
- [x] 原版中文输入、Rime 部署和中英文切换通过回归测试
- [x] 建立公开 Fork、中文项目说明和轻量 Debug CI
- [x] 使用内置离线演示词表完成可关闭的双语候选原型
- [ ] 扩展翻译覆盖率并处理多义词、短语和性能问题
- [ ] 真机兼容性与触屏体验验证

详细阶段安排见 [ROADMAP.md](ROADMAP.md)。

### 双语候选原型

- “候选窗口 → 显示英文翻译”默认开启，修改后在下一次候选刷新时生效。
- 紧凑候选栏、展开候选页和悬浮候选窗使用同一展示规则；未收录候选只显示中文。
- 翻译仅添加在应用展示层，不修改 Rime 候选数据、顺序、索引或提交行为。
- 演示词表位于 `app/src/main/assets/bilingual_demo_zh_en.tsv`，只覆盖少量验收词汇；加载失败时自动隐藏英文，不影响中文输入。

## 开发与测试约定

- 当前主要交互测试环境为 Android 模拟器 `Trime_API_36`（Android API 36、x86_64）。
- 每项功能需要通过 Debug APK 构建、模拟器安装、Rime 部署、输入交互和 Logcat 检查。
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

本项目延续 GNU General Public License v3.0 or later，完整条款见 [LICENSE](LICENSE)。代码中的原始版权和 SPDX 声明均予以保留。
