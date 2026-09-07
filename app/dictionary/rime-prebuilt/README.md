# 好好拼音预编译数据

这里保存正常安装时直接加载的 Rime 固态词典，避免用户首次启用时在手机上编译 142 万词。

- librime：`1.17.0`，commit `33e78140250125871856cdc5b42ddc6a5fcd3cd4`
- 维护工具：官方 `rime-33e7814-Windows-msvc-x64.7z`
- 万象词库：`v17.7.1`，commit `4b86b19fbc3e5968da3af3271ce36d8b320c5e73`
- 维护输入：`compile-user/`、`compile-shared/`、`../wanxiang/` 与 `app/src/main/assets/shared/`
- 产物及 SHA-256：见 `prebuilt.properties`

维护版本时先运行 `prepareWanxiangDictionary` 生成固定 YAML，把所有编译输入时间统一为
`prebuilt.properties` 的 `sourceTimestampEpochSeconds + 500ms`（librime 的 Windows 文件时钟换算在整秒边界可能落到前一秒），再用匹配版本的
分别运行 `rime_deployer --compile luna_pinyin_simp.schema.yaml <user> <shared> <staging>` 与
`rime_deployer --compile stroke.schema.yaml <user> <shared> <staging>`，生成主词典和笔画反查所需的八个文件。
再编译 `haohao_pinyin_9.schema.yaml`，补齐九键 schema 和 prism，清单共十个文件。
九键复用 `haohao_pinyin` 词典和 `luna_pinyin` 用户词频，不复制主词表。
在两个全新的 staging 目录重复编译并比较 SHA-256，完全一致后再替换 `files/` 和元数据。

普通 Gradle 构建只校验和复制 `files/`，不会展开万象源，也不会把组合词典源或 40MB YAML 装进 APK。
源码 gzip 继续留在仓库用于发布维护；手机上的“修复输入法”会重新校验并恢复随 APK 提供的固态数据，
不会再次现场编译完整词库。

## Android 启动约定

- 九键拼写规则同时保留全拼和 2–9 数字编码，供连续输入与显式拼音筛选共用。筛选只替换当前未确认段，不能覆盖已选词前缀。
- 九键必须继续使用 `haohao_script_translator`，沿用禁用个性化学习的原生保护；不可为了编译方便把随包方案改成普通 `script_translator`。
- 改动 shared 方案或编译输入后，更新 `inputSha256`、产物大小与哈希，并通过 `verifyRimePrebuiltData`；仅修改 `.trime.yaml` 键盘外观不重编词典。

- App 首页和首次引导都通过 `RimeDaemon` 会话尽早触发同一个准备过程；多个页面不得各自启动第二个 Rime 实例。
- 预编译集合必须包含默认方案使用的所有固态数据及传递依赖。`luna_pinyin_simp` 通过反查使用 `stroke`；只打包主词表会使真机首启重新构建笔画表并可能触发维护超时。
- 首次安装或 APK 升级时，预编译文件写入同目录临时文件，并在复制流中同步计算 SHA-256；大小和哈希都正确后才替换正式文件。全部预编译文件成功后，最后更新 `checksums.json`。
- 清单版本一致且正式文件存在、大小正确时直接复用，稳定启动不得重新读取约 50MB 的 `haohao_pinyin.table.bin` 计算 SHA-256。
- 同尺寸的罕见损坏由 Rime 加载失败暴露；用户执行“修复输入法”时重新校验并从 APK 恢复，不得删除用户词频、输入足迹或收藏。
- “复制诊断信息”必须保留数据同步耗时、原生启动耗时、复制文件数、复制字节数和是否复用预编译数据，且不得包含输入内容。
- 发布前必须在 ARM64 真机清空调试包数据后验证一次：首次进入 `READY` 不超过 10 秒、后续冷启动不超过 2 秒，且 Logcat 不得出现 `building table`。
