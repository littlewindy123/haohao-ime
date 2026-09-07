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

本节记录原内测包的构建流程：沿用当时 `public-signing.properties` 指定的固定证书、`com.osfans.trime.debug` 包名。20260923 的签名替换见下文。
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

## 新签名与跨电脑恢复

### 已装旧签名手机的本机覆盖升级

本机仍保留旧私钥、且手机正在使用旧签名时，可为这台手机生成独立内测升级包：
在内测构建参数中额外指定 `-PlegacyInternalSigning=true`。构建仍严格核对
`certificateSha256.20260921` 的历史证书身份，不跳过签名检查；该开关只允许
`internalTestDistribution=true`，公开构建和普通构建均拒绝使用。
这不会改变公开渠道的新签名，也不授权卸载或清除旧应用。安装前必须核验旧包与
新包证书一致、版本递增，内测 APK 不上传官网、COS 或 Git。

### 公开渠道的新签名

所有者明确授权为 20260923 生成新签名，并将私钥和随机密码备份到现有云服务器。
这是一次签名身份替换：包名仍为 `com.osfans.trime.debug`，但不能覆盖旧签名安装。
保留旧签名记录及旧应用数据；不能通过自动卸载解决签名冲突。后续新包统一使用新签名。

- 新证书 SHA-256：`62b4a4c620df03d8bd6c65e21a8cfa5cd11265465e25070dc53006b31de5a101`。
- 旧证书 SHA-256：`6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215`。
- 云端备份：`root@124.221.187.214:/var/backups/haohao-ime/signing/20260923/`。
- 备份目录权限 `0700`、文件权限 `0600`，均属于 root；位于网站目录之外，仅通过 SSH/SCP 取用。
- 备份含 `public-test.keystore`（PKCS12，RSA 3072）、`signing-passwords.json`、公开证书和指纹文件。密码不打印、不提交；不能继续使用默认的 `android` 密码。
- 本机原始副本在 `%USERPROFILE%/.haohao-ime/signing/`，访问权限限当前用户、SYSTEM 和管理员。

另一台电脑先拉取最新 `main`，准备其已有的服务器 SSH 登录密钥。以下命令在 PowerShell 7.6.1 以上执行，恢复到独立目录，不覆盖任何旧密钥；如 SSH 密钥文件名不同，调整 `$sshKey`：

```powershell
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$restoreDir = "$env:USERPROFILE/.haohao-ime/restored-20260923"
$sshKey = "$env:USERPROFILE/.ssh/haohao_deploy_ed25519"
if (Test-Path -LiteralPath $restoreDir) { throw 'Restore directory already exists; inspect it before continuing' }
New-Item -ItemType Directory -Path $restoreDir -Force | Out-Null
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
icacls $restoreDir /inheritance:r /grant:r "*${currentSid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F'
if ($LASTEXITCODE -ne 0) { throw 'Failed to restrict backup permissions' }
scp -i $sshKey -o StrictHostKeyChecking=yes -r root@124.221.187.214:/var/backups/haohao-ime/signing/20260923/. $restoreDir
if ($LASTEXITCODE -ne 0) { throw 'Signing backup download failed' }
$passwords = [IO.File]::ReadAllText("$restoreDir/signing-passwords.json", [Text.Encoding]::UTF8) | ConvertFrom-Json
$env:HAOHAO_PUBLIC_KEYSTORE = "$restoreDir/public-test.keystore"
$env:HAOHAO_PUBLIC_STORE_PASSWORD = $passwords.storePassword
$env:HAOHAO_PUBLIC_KEY_PASSWORD = $passwords.keyPassword
./gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a -PpublicDistribution=true -PembedInternalCloudSecrets=false
```

首次连接必须先核验服务器主机密钥并加入 `known_hosts`，不得禁用主机身份检查。
构建时会检查私钥和仓库中的固定证书指纹；打包后还须用 `apksigner verify --print-certs` 核对 APK。
此签名备份不含原电脑上的双云翻译或语音内测配置。上面的命令生成不内置这些凭据的包；
恢复原内测功能仍需取回相应配置，并按前文和[语音说明](speech-learning-20260918.md)设置环境变量及构建开关。

本轮实际验证：云端四个文件下载后逐一校验与本地副本一致；使用下载恢复的私钥完成 ARM64
Debug 构建，APK 的 v1/v2 签名验证通过，证书指纹与上文一致。交付文件
`haohao-ime-20260923-new-signature-arm64.apk` 为 52,130,226 字节，SHA-256 为
`2a3462413198e1ebd417a37379ccf3cec3d301da8032202f5215b08ea4bbfca7`。
已检查包名、版本和 ARM64 架构，以及生成配置中内测云开关关闭、翻译密钥和语音令牌为空。
未覆盖安装或卸载旧应用。官网 30 项测试及静态检查通过，旧版下载信息保持不变。
