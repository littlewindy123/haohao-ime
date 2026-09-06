# 2026 年 9 月临时内测包

仅供本机交付，不发布到官网、COS、Git 或 CI 工件。现有公开下载不变。

## 密钥前置条件

最初方案要求先轮换密钥。2026-09-06 所有者明确改变此条件，授权本次临时内测
继续使用其提供的现有密钥。此例外仅用于本机内测交付，不扩展到公开包或公开发布。
仍建议使用独立、受限、可撤销的测试密钥，并在云厂商设置权限、额度及停用时间；
不要擅自撤销其他服务可能在用的旧密钥。免费额度不代表已经核实存在硬性计费上限。
APK 内置密钥可以被提取，客户端到期判断不构成服务端安全边界。

通过仅当前 Windows 用户、SYSTEM 和管理员可读的本机文件提供，建议位于仓库外：
`C:\Users\ADMIN\.haohao-ime\internal-test\cloud.properties`。
文件包含下面五个属性，前四个值由所有者填写，不发送到聊天、不打印、不提交：

```properties
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
BAIDU_API_KEY=
BAIDU_SECRET_KEY=
TEST_CLOUD_EXPIRES_AT=2026-09-30
```

云请求到北京时间 2026-10-01 00:00 停止；本地输入不受影响。
构建拒绝空值、过期或非本轮约定的到期日。

## 构建与签名

临时内测开关必须同时指定，且不可与 `publicDistribution=true` 并用：

```powershell
$env:HAOHAO_INTERNAL_CLOUD_SECRETS_FILE = 'C:\Users\ADMIN\.haohao-ime\internal-test\cloud.properties'
.\gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a -PinternalTestDistribution=true -PembedInternalCloudSecrets=true
```

沿用 `public-signing.properties` 指定的固定证书、`com.osfans.trime.debug` 包名。
缺失或不匹配的签名将阻止构建，不生成新密钥。
键盘修复包版本号为 20260906；本轮启用云的内测包为 20260907，覆盖升级并保留数据。

没有有效测试密钥时，只能生成禁用云功能的键盘修复测试包：

```powershell
.\gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a -PpublicDistribution=true -PembedInternalCloudSecrets=false
```

这里 `publicDistribution` 只选用现有的安全构建和固定签名策略，不执行发布。
不得把此包称为已恢复云翻译的内测包。Release 和 Regression 始终不嵌入共享密钥。

## 验证

- `:app:testRegressionUnitTest`：请求成功、异常、超时、主备、取消、过期、几何与重复挂载策略。
- `:app:connectedRegressionAndroidTest`：独立 Regression 包，加载页旋转/尺寸/触摸及未设置高度的默认值。
- 总请求期限 6 秒；主用 2.5 秒、备用 3 秒，网络请求不得阻塞中文输入。
- 使用 `apksigner verify --print-certs` 核对固定证书，再 `adb install -r`，不卸载、不清数据。
- 真机微信连续展开/收起 30 次、前后台和冷启动；不发送消息，不读取聊天内容。
- 仅使用所有者明确授权的密钥，以固定测试句验证真实阿里云/百度连通；不打印密钥或完整鉴权响应。

真实服务测试需要额外显式开关，正常单元测试不读取密钥或发起网络请求：

```powershell
$env:HAOHAO_INTERNAL_CLOUD_SECRETS_FILE = 'C:\Users\ADMIN\.haohao-ime\internal-test\cloud.properties'
.\gradlew.bat :app:testRegressionUnitTest -PrunLiveCloudTests=true -PbuildABI=arm64-v8a -PpublicDistribution=true -PembedInternalCloudSecrets=false
```

2026-09-06 实测阿里云、百度及主备组合均成功；20260907 已覆盖安装，手机设置页
“测试连接”显示成功。键盘高度仍为 COMPACT，云授权仍为已同意，候选来源仍为 LOCAL_ONLY，
未自动开启候选上传。详细记录在本机工作区 `work/internal-cloud-20260906-status.md`。
