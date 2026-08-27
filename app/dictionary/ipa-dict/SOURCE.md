# 美式 IPA 数据来源

本目录固定使用 [open-dict-data/ipa-dict](https://github.com/open-dict-data/ipa-dict) 提交 `43c3570eb3553bdd19fccd2bd0091534889af023` 的 `en_US` 数据，共 125,927 行，采用 [MIT](https://github.com/open-dict-data/ipa-dict/blob/master/LICENSE) 许可。

- 原始文件 SHA-256：`2af6f154a5c363275f052d1f85acedef38ed185ca9745aa4314be77f6b70de67`
- 仓库压缩文件 SHA-256：`303f81f4008888fcabf8a0aa449c898c4db0433b073742dcd9697a9685349be4`
- 构建行为：Gradle 只读取仓库内文件，不联网；生成器固定选择首个美式读音，并将可完整匹配的音标合并到中文候选索引。

无法完整匹配英文释义的词条不生成音标，不进行部分拼接或读音猜测。
