# 本地优先输入与词本：实现及验证报告

本报告记录 2026-09-05 在 Windows 主机和 Android API 36 模拟器上的实际验证。测试使用隔离的 `com.osfans.trime.regression` 应用及合成词汇，没有读取或导出用户真实输入。ARM64 安装包用于后续真机测试，模拟器结果不能直接代表手机表现，也不能证明已达到搜狗的体验水平。

## 本轮完成的行为

- 新装默认仅本地，缺少本地释义不自动请求云翻译；既有明确同意的配置继续沿用。没有增加账号、中转服务器、同步或训练上传。发布构建不嵌入共享服务密钥。
- 离线词典在后台预热，主线程不等待完整校验；近期查询使用有界缓存。云缓存加载及序列化移到后台，中文候选和上屏不等待学习记录或英文释义。
- Room 升级到版本 3，保留 1→2→3 路径。英文展示文本与去重键分开，保留 `I`、`China`，可只修大小写而保留进度；后台新释义不覆盖已确认答案、来源或音标。
- 首屏显示当前会话或实际任务数；每日计划先进入概览，设置独立保存，当前会话明确显示模式与方向。
- 最近一次评分可持久撤销，恢复答案可见状态、卡片、阶段、下次时间及原日期的计数；重复点击、旧卡片回调、清理和遗忘重练均有事务约束。
- 词本发音只选择系统声明为已安装且无需网络的英语音色。初始化、音库和播放错误分别提示，提供重试与系统语音设置；不自动下载音库。
- 键盘静态引用改为弱引用；修复编辑工具按钮的无障碍点击路径。修复旧 Rime 会话延迟清理关闭新会话的问题，并拒绝同名旧实例的回调。

完整数据契约见[个人词本与复习规则](../business/personal-words.md)，联网与备份边界见[隐私说明](../../PRIVACY.md)。

## 功能检查

| 检查 | 实际结果 |
| --- | --- |
| JVM 单元测试 | 183 项通过；包括排程、输入保护、默认配置、离线模式不进入云查询／请求分支、阻塞 HTTP 请求取消 |
| Room 设备测试 | 16 项通过；覆盖迁移、大小写、去重、撤销、跨日、遗忘重练、共享进度、历史淘汰与清理隔离 |
| 候选回归 | 固定 100 条语料连续运行两遍，全部满足原排名要求；编译词典未被重建 |
| Rime 生命周期 | 快速替换最后一个客户端 10 次，均能继续得到“你好”候选 |
| 原生词本 UI | 四种配置下跑通词本、回想、答案、评分、撤销、页面重建、每日计划；保存设置不会启动复习 |
| 大小写详情 | 页面将 `China` 修正为 `CHINA`，展示更新且复习计数不变 |
| 实际键帽输入 | 360dp 浅色左单手、411dp 深色右单手、360dp／1.3 倍字号左单手、横屏右单手设置下输入并上屏“你好”；横屏沿用现有全宽行为 |
| 发音错误恢复 | 临时禁用模拟器的系统 TTS 服务，真实显示初始化失败、重试和系统语音设置入口；之后重新启用服务 |
| 静态检查 | Lint 0 错误、106 警告；未批量压制，静态键盘引用泄漏告警已消除 |

上述输入与 UI 验证时模拟器 Wi-Fi、移动数据均关闭。离线发音的音色筛选和错误恢复已检查；本轮没有录音验证成功发声，厂商 TTS 的实际离线行为仍需在具备音库的真机上验证。网络取消行为通过本地可控 HTTP 测试验证，没有使用共享密钥访问外部翻译服务。

## 界面证据

截图来自实际 Android 窗口，包含正常主题、密度、字号和方向变化。代表截图如下；[完整截图目录](../../design-demos/haohao-learning/screenshots/word-ui/)包含回想、答案、撤销和每日计划各阶段。

| 页面 | 截图 |
| --- | --- |
| 浅色 360dp 我的单词 | [实际截图](../../design-demos/haohao-learning/screenshots/word-ui/v3-light-360-words.png) |
| 深色 411dp 右单手键盘 | [实际截图](../../design-demos/haohao-learning/screenshots/word-ui/v3-dark-411-keyboard-RIGHT.png) |
| 1.3 倍字号复习答案 | [实际截图](../../design-demos/haohao-learning/screenshots/word-ui/v3-font130-360-review-answer.png) |
| 横屏键盘 | [实际截图](../../design-demos/haohao-learning/screenshots/word-ui/v3-landscape-keyboard-RIGHT.png) |
| 发音失败恢复 | [实际截图](../../design-demos/haohao-learning/screenshots/word-ui/v3-tts-recovery-speech-state.png) |

已人工检查代表截图，关键候选、英文、音标及复习按钮未发现重叠或裁切；大字号英文同时检查字形上下界。未把实体键盘事件当作屏幕键帽可用性的证据。真实 TalkBack 操作、不同手机系统的边缘手势和键盘触感仍需真机检查。

## 性能方法与对比

环境：同一个 API 36 x86_64 模拟器，1080×2400、480 dpi（360dp），字号 1.0、浅色、竖屏、关闭单手，Swiftshader 软件渲染，断网。测试输入框设置 `IME_FLAG_NO_PERSONALIZED_LEARNING`，不积累本次测试的个人词频或足迹。旧包为本轮实现前保存的 v2 回归包，新包为本轮 v3 回归包；两者使用同一测试 APK。

固定输入为 `nihao`、`zhongguo`、`wojintianxiangqugongyuansanbu`、`nh`、`shanghai`、`xuexi`，运行两遍，共 114 个字母样本；同时执行空格上屏、连续删除、光标移至开头后输入、退出并重新打开测试输入页。中英混输、模糊音开关以及跨外部 App 的系统输入框切换尚未完整纳入这组性能样本，应补入真机基准。

第一项指标从自动化驱动准备点击到原生按键处理完成后的下一帧，包含查找键帽、注入和 `waitForIdleSync` 的开销，不能称为纯中文候选显示延迟。旧新交替安装，重复三组，避免早晚主机负载漂移：

| 组 | 旧版 P50 / P95（ms） | 新版 P50 / P95（ms） | P95 变化 |
| --- | --- | --- | --- |
| 1 | 196.78 / 499.93 | 177.29 / 523.96 | +4.81% |
| 2 | 198.16 / 535.95 | 192.69 / 525.50 | −1.95% |
| 3 | 190.36 / 585.45 | 199.78 / 531.22 | −9.26% |

三组没有稳定超过 5% 的回退，但不能据此宣称中文候选或手机输入已明显加速。早期未交替的测量出现约 15% 的差异，随后旧包复测也变慢，因此没有采用时间间隔较大的那组结果作为验收依据。六份完整样本、PSS 和 `gfxinfo` 已保存在[测量目录](../../design-demos/haohao-learning/measurements/)。

| 组 | 旧版首次／再次显示（ms） | 新版首次／再次显示（ms） | 旧版 PSS 前→后（KiB） | 新版 PSS 前→后（KiB） |
| --- | --- | --- | --- | --- |
| 1 | 4692 / 1467 | 4802 / 1100 | 81154→185024 | 80531→187548 |
| 2 | 5415 / 1458 | 4976 / 1514 | 80923→177509 | 80470→177037 |
| 3 | 4334 / 1535 | 5386 / 1488 | 80404→176537 | 80522→178419 |

首次／再次显示是 Activity 启动到键帽可用的联合耗时，不含 `Application.onCreate` 前的进程启动，不能替代纯 IME 冷启动测量。软件渲染环境下旧版 `gfxinfo` 卡顿帧比例为 39.76%、41.97%、41.08%，新版为 42.09%、42.28%、43.34%，略有增加；应在真机 GPU 环境继续定位，不把模拟器帧率当作手机体验结论。

### 中文候选绘制测量

为去掉等待界面空闲的开销，另运行三组旧新交替测试：计时从实际键帽 `ACTION_DOWN` 注入前开始，到包含更新后候选版本的 `OnDraw` 为止，同时确认候选列表已完成待处理更新且存在可见中文。这是 Android 界面开始绘制的边界，仍包含事件注入开销，也不等于屏幕像素实际亮起的时间；没有等待英文淡入完成。

部分不完整拼音没有中文候选，不能把等待不存在的候选算成延迟。每轮 114 次字母按键中，110 次产生中文候选并进入统计，另 4 次在原生候选更新后确认无中文候选，单独记录在 `keysWithoutChineseCandidates`。两种版本的样本数量一致。

| 组 | 旧版 P50 / P95（ms） | 新版 P50 / P95（ms） | P95 变化 |
| --- | --- | --- | --- |
| 1 | 103.71 / 426.04 | 109.90 / 402.52 | −5.52% |
| 2 | 105.69 / 478.00 | 103.45 / 453.24 | −5.18% |
| 3 | 109.87 / 476.68 | 107.00 / 407.95 | −14.42% |

这三组中文候选绘制测量满足本轮模拟器条件下「P95 不出现超过 5% 的稳定回退」。P50 没有一致改善，不能表述为所有输入都已加速。原始记录为测量目录中的 `draw-old-1..3-performance.json` 和 `draw-new-1..3-performance.json`，保留每个样本、唤起时间、内存和帧统计；运行这些对比时没有并行执行 Gradle 编译或 Lint。

新测法下旧版首次显示为 4605／5023／4904ms，新版为 5398／4986／5277ms；再次显示旧版为 1577／1534／1459ms，新版为 1616／1593／1476ms，尚未呈现唤起加速。输入结束后的 PSS 旧版约 176758–177581KiB，新版约 177561–177838KiB。旧版卡顿帧比例为 41.92%／43.71%／41.16%，新版为 45.08%／40.59%／44.84%，仍需要真机分析。

另行采集[本机 Perfetto 系统追踪](../../design-demos/haohao-learning/measurements/haohao-synthetic.perfetto-trace)，覆盖同一套合成输入、Activity 与 IME 再次唤起。追踪单独运行，不混入上述性能对比数据，没有启用上传。初次 16MB 环形缓冲只能保留后半段，交付前扩大为 64MB 重新采集。采集命令如下，文件从模拟器拉回当前工作区：

交付文件为 49,893,016 字节，完整解析到 17,657 个顶层数据包、1,204,538 条 ftrace 事件，事件跨度 59.22 秒，覆盖本次 36.09 秒的测试。验证摘要另存为测量目录内的 `trace-validation.json`。

系统追踪二进制只保存在本机并由 Git 忽略，不随代码推送；仓库保留合成测量 JSON、校验摘要及实际截图。上方追踪与下方 APK 链接指向本地交付产物，远端源码克隆不包含这些二进制文件。

```text
adb shell perfetto --background -t 60s -b 64mb -a com.osfans.trime.regression -o /data/misc/perfetto-traces/haohao-synthetic-full.perfetto-trace sched freq idle am wm gfx view input binder_driver
```

测绘制时间的测试命令在 `syntheticTypingBenchmark` 的 instrumentation 参数中增加 `-e measureDraw true`；用 `-e capturePrefix draw-new-1` 区分每次输出。默认不传该参数时保留原自动化链路指标，便于对照，两个口径不能混用。

## Lint 分类

| 类别 | 数量与处理 |
| --- | --- |
| 资源与文案 | UnusedResources 40、PluralsCandidate 15、HardcodedText 7、TypographyDashes 3、ButtonCase 1、SetTextI18n 1；包含上游页面及保留资源，未自动删除或整体改写 |
| API 与依赖 | UseKtx 13、InlinedApi 8、OldTargetApi 1、InvalidManifestAttribute 1、GradleDependency 1、PrivateResource 1、UsableSpace 1；保留原兼容范围，不在本轮混入框架升级 |
| 绘制和方向 | Overdraw 7、RtlSymmetry 3、TooManyViews 1、RtlEnabled 1；现有页面结构待专项优化，实际布局矩阵已验证 |
| 触摸与无障碍 | ClickableViewAccessibility 1；编辑工具的原生 TextView 触摸监听仍被提示未覆写 performClick，已提供真实 click listener 并防止触摸重复执行；读屏实测待真机 |

## 复现与交付边界

[ARM64 测试安装包](../../app/build/outputs/apk/debug/com.osfans.trime-trime-upstream-f144408a-35-g8f051ee4-arm64-v8a-debug.apk)，大小 53,110,874 字节。SHA-256：

```text
02832696d4e7cfdfe28547470bcb07437bee9410296ca83ea390a4f351e4ccc7
```

该包为 debug 测试构建，版本名 `trime-upstream-f144408a-35-g8f051ee4`。已核查生成配置中共享云开关关闭、共享密钥为空；不是应用商店签名发布包。性能旧包 SHA-256 为 `4d942ce707dfdfa4b23ad0916d24eafaa686a00b10a392b88dee841da788eee8`，最终 x86_64 回归包为 `19a8b32508a54a04f96a5fca789f0984fa7ce89c289f4d39f48940e16836b2d9`。

构建使用 JDK 17、项目固定 Android SDK/NDK 与已缓存 Gradle 依赖。Windows 上使用 ASCII 工作区别名以避免原生构建路径问题：

```powershell
./gradlew.bat :app:testRegressionUnitTest :app:assembleRegression :app:assembleRegressionAndroidTest :app:assembleDebug :app:lintRegression --offline --no-daemon --no-watch-fs '-Pkotlin.incremental=false'
```

UI 测试入口为 `com.osfans.trime.ui.main.WordLearningUiTest`，设备数据测试为 `InputFootprintStoreTest`，候选与生命周期测试为 `HaoHaoPinyinRegressionTest`。只安装、初始化和清理隔离回归包，不覆盖用户的 debug 应用。调整模拟器配置后等待窗口重建；连续 instrumentation 可先切回系统键盘，再由下一轮测试重新选择被测 IME，避免沿用已退出进程的绑定。

本轮实现没有新增服务器或联网依赖。尚无 Android 真机接入，因此 ARM64 包尚未在真机安装，成功离线发音、纯冷启动、真实触控／读屏、长期内存表现及与搜狗同设备同文本的比较均不提前标记通过。
