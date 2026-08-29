# 好好拼音预编译数据

这里保存正常安装时直接加载的 Rime 固态词典，避免用户首次启用时在手机上编译 142 万词。

- librime：`1.17.0`，commit `33e78140250125871856cdc5b42ddc6a5fcd3cd4`
- 维护工具：官方 `rime-33e7814-Windows-msvc-x64.7z`
- 万象词库：`v17.7.1`，commit `4b86b19fbc3e5968da3af3271ce36d8b320c5e73`
- 维护输入：`compile-user/`、`compile-shared/`、`../wanxiang/` 与 `app/src/main/assets/shared/`
- 产物及 SHA-256：见 `prebuilt.properties`

维护版本时先运行 `prepareWanxiangDictionary` 生成固定 YAML，把所有编译输入时间统一为
`prebuilt.properties` 的 `sourceTimestampEpochSeconds`，再用匹配版本的
`rime_deployer --compile luna_pinyin_simp.schema.yaml <user> <shared> <staging>` 生成四个必需文件。
在两个全新的 staging 目录重复编译并比较 SHA-256，完全一致后再替换 `files/` 和元数据。

普通 Gradle 构建只校验和复制 `files/`，不会展开万象源，也不会把组合词典源或 40MB YAML 装进 APK。
源码 gzip 继续留在仓库用于发布维护；手机上的“修复输入法”会重新校验并恢复随 APK 提供的固态数据，
不会再次现场编译完整词库。
