# 好好公共翻译网关

这是好好输入法可选公共翻译服务的低内存 Go 网关。它仅接收用户主动请求翻译的文字，默认使用阿里云 `TranslateGeneral`，在阿里云限流、鉴权失败、超时、故障或月度额度耗尽时，单次切换到百度文本翻译。

## 隐私与限额

- 服务不记录原文或译文；日志只包含云厂商、状态码、耗时、字符数和故障切换原因。
- 安装标识在落盘前经过 SHA-256，不保存设备硬件标识。
- 默认每个安装实例每日最多 20 次、300 个源字符。
- 阿里云按自然月最多预占 900,000 字符；百度累计最多预占 4,500,000 字符。
- 请求在发送到云厂商前预占额度，上游失败也不返还，避免并发或重试产生意外费用。
- 阿里云本地限流为 20 QPS，百度为 8 QPS；确定性的参数错误和敏感内容拒绝不会跨云重试。
- 服务默认只监听 `127.0.0.1:8787`，应由配置 HTTPS 的 Nginx 反向代理。

## 配置

先撤销所有曾出现在聊天、终端历史或截图中的凭据。阿里云应使用仅具备 `alimt:TranslateGeneral` 权限的 RAM 子账号；百度应使用重新生成的 API Key 和 Secret Key。

将轮换后的凭据写入服务器权限为 `0600` 的环境文件，不要提交到 Git、APK、网页或 Nginx 配置：

```text
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
BAIDU_API_KEY=
BAIDU_SECRET_KEY=
HAOHAO_GATEWAY_ADDR=127.0.0.1:8787
HAOHAO_QUOTA_FILE=/var/lib/haohao-translation/quota.json
HAOHAO_DAILY_REQUEST_LIMIT=20
HAOHAO_DAILY_CHARACTER_LIMIT=300
HAOHAO_ALIYUN_MONTHLY_CHARACTER_LIMIT=900000
HAOHAO_BAIDU_LIFETIME_CHARACTER_LIMIT=4500000
HAOHAO_ALIYUN_QPS=20
HAOHAO_BAIDU_QPS=8
```

例如：

```bash
sudo install -d -m 0700 /etc/haohao-translation /var/lib/haohao-translation
sudo chmod 0600 /etc/haohao-translation/gateway.env
```

配额文件会原子写入并跨重启保留。旧版单云配额文件会自动迁移：原月度计数归入阿里云，旧每日请求次数继续有效；百度累计计数从零开始。正式域名和 HTTPS 可用前，不应在 Android 构建中配置公共服务地址。

## 验证

```bash
go test ./...
go vet ./...
```

启动后先检查仅本机监听和健康状态，再由 Nginx 提供 HTTPS：

```bash
curl --fail http://127.0.0.1:8787/healthz
ss -lntp | grep 127.0.0.1:8787
```

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
