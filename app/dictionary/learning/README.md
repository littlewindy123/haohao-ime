# 离线学习资料 v1

候选翻译词典保持不变。这是独立、只读的学习参考库，不覆盖已保存答案。

## 固定来源

- ECDICT `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`，MIT，原始 `ecdict.csv`。
- Tatoeba 2026-09-05 的 `eng_sentences_detailed.tsv.bz2`、`cmn_sentences_detailed.tsv.bz2`、`eng-cmn_links.tsv.bz2`。导出地址前缀为 `https://downloads.tatoeba.org/exports/per_language/`，分别在 `eng/`、`cmn/`、`eng/` 下。
- SHA-256 固定在生成脚本与 `app/src/main/assets/learning/manifest.json`；不要替换成移动的“最新版”。
- Tatoeba 每条保留双方句子编号、作者、CC-BY-2.0-FR 许可和可还原的来源链接。只选直接关联、署名齐全的文本；不使用音频。完整声明随 App 打包在 `learning/NOTICE.txt`。

## 覆盖与筛选

30,000 个词头，按有效 BNC/FRQ 较小排名、词头字典序筛选，重复词头只保留一次。保留音标、多义项原文、英文定义与显式词形。英文定义单独展示，不伪造双语义项配对。

9,674 个词有例句，共 21,163 条按词索引的例句（同一句可用于多个词）；每词最多 3 条，优先约 9 词的短句。英文 5–20 词、不超过 180 字符，中文不超过 100 字符，按整词或明确词形匹配；同一英文句去重。基础敏感词过滤不能代替内容审核。例句是“用法例句”，没有义项级标注；缺失时显示“暂未收录”。

## 离线重建与验证

需要 Python 3.13（仅标准库），无网络、无账号：

```powershell
python app/dictionary/learning/build_lexicon.py
python app/dictionary/learning/test_lexicon.py
```

`selected.jsonl.gz` 是已固定的公开资料快照；生成后对照 manifest 的数据库校验和。SQLite 文件字节受 Python 内置 SQLite 版本影响；测试验证结构与全部内容，不将字节差异误当成词条变化。需要重新选择时，先手动取得上述固定源文件，用脚本要求的短文件名置于仓库外目录，再运行 `--sources <目录>`；哈希不符会拒绝生成。Android 构建本身不下载资料。

资料只进入 `assets/learning`，App 后台复制到 `noBackupFilesDir`，按 SHA-256 验证后只读打开，128 条内存缓存。词形索引不改写候选引擎。
