# 万象现代简体词库数据来源

本目录固定使用 [万象拼音](https://github.com/amzxyz/rime-wanxiang) `v17.7.1` 的完整基础词库 `dicts/jichu.dict.yaml`，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 许可。

- Commit：`4b86b19fbc3e5968da3af3271ce36d8b320c5e73`
- 原始文件：`44,896,039` 字节
- 原始 SHA-256：`ca3e83cd3ff1b6896a055c26cd24dc98b79f2c1fe56acd983eb7479a319b4240`
- 有效带权词条：`1,418,352`
- 构建行为：Gradle 验证固定来源后，离线移除拼音声调并生成 Rime 资产，不联网、不写回源码资产目录。

固定源另有一条缺少词频的 `省行政` 记录。元数据将其声明为唯一已知排除项；如果来源中出现额外缺失词频或格式异常的记录，构建会失败。

`haohao_hotwords.dict.yaml` 是好好输入法维护的小型高优先级补充词典，遵循仓库的 GPL-3.0-or-later 许可，不属于万象词库。
