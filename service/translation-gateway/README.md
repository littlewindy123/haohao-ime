# 好好公共翻译网关

这是好好输入法可选公共翻译服务的低内存 Go 网关。它仅接收用户主动请求翻译的文字，通过阿里云机器翻译 `TranslateGeneral` 获取结果。

## 隐私与限额

- 服务不记录原文或译文；日志只包含状态码、耗时和字符数。
- 安装标识在落盘前经过 SHA-256，不保存设备硬件标识。
- 默认每个安装实例每日最多 50 次、公共账号每月最多 900,000 字符。
- 服务默认只监听 `127.0.0.1:8787`，应由配置 HTTPS 的 Nginx 反向代理。

## 配置

将以下变量写入服务器权限为 `0600` 的环境文件，不要提交到 Git：

```text
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
HAOHAO_GATEWAY_ADDR=127.0.0.1:8787
HAOHAO_QUOTA_FILE=/var/lib/haohao-translation/quota.json
```

启动前确保 RAM 用户仅拥有 `alimt:TranslateGeneral` 权限。正式域名和 HTTPS 可用前，不应在 Android 构建中配置公共服务地址。

## 接口

- `GET /healthz`
- `POST /v1/translate`，请求头必须包含随机安装标识 `X-HaoHao-Install`

```json
{
  "texts": ["你好"],
  "source_lang": "zh",
  "target_lang": "en",
  "purpose": "sentence",
  "request_id": "random-request-id"
}
```
