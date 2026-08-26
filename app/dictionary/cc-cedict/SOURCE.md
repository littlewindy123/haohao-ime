# CC-CEDICT 数据来源

本目录固定使用 CC-CEDICT `2026-08-24 05:05:01 UTC` 版本，共 124,925 条原始词条。数据来自 [MDBG 的 CC-CEDICT 发布页](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)，采用 [Creative Commons Attribution-ShareAlike 4.0 International](https://creativecommons.org/licenses/by-sa/4.0/) 许可。

官方固定下载地址只提供最新版，不提供按日期命名的历史包。仓库中的压缩包依据官方 v1 导出文件和公开变更记录恢复到日志条目 `97290` 的发布边界；从后续版本逆向移除 `97291` 至 `97310` 的变更后，词条数由 124,936 精确恢复为 124,925。压缩结果采用固定换行和无时间戳 GZip 输出，两次生成的 SHA-256 一致。

- 文件：`cedict_1_0_ts_utf-8_mdbg.txt.gz`
- SHA-256：`f719ca9d8fae0de5b8836a0251225a0bd7af7f1411fc4fa5bb0e8c08a2b99c29`
- 发布边界：[CC-CEDICT change log 97290](https://cc-cedict.org/editor/editor.php?log_id=97290&return=ListChanges&handler=ViewLogEntry)
- 构建行为：Gradle 只读取仓库内文件，不联网；生成资产写入 `app/build/generated/assets/cedict`。

`common_overrides_zh_en.tsv` 是好好输入法维护的小型常用词展示覆盖表，优先级高于 CC-CEDICT 自动选择的短释义。它属于本项目代码的一部分，遵循仓库的 GPL-3.0-or-later 许可。
