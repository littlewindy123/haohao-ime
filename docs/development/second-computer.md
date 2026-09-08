# 另一台电脑搭建

目标分支：`main`。真实配置和签名文件绝不进入 Git；不生成替代签名。

## 代码与工具

```powershell
git clone --recursive --branch main git@github.com:littlewindy123/haohao-ime.git
cd haohao-ime
git submodule update --init --recursive
```

已有工作目录先 `git status` 检查本机修改；`git fetch origin` 后检查差异，干净且可快进才 `git pull --ff-only`，不要强推或覆盖另一台电脑的工作。

安装 JDK 21、Android SDK 36 / Build Tools 36.0.0、NDK 28.0.13004108、CMake 3.31.6、Python 3.13 和 Git。用 Android Studio SDK Manager 安装 Android 组件，设置 `JAVA_HOME`、`ANDROID_HOME`。仓库根目录创建被忽略的 `local.properties`，写本机 SDK 路径（例如 `sdk.dir=C:/Users/yourname/AppData/Local/Android/Sdk`）。使用仓库 Gradle wrapper，不升级依赖。原生子模块较大，首次构建需要下载依赖；离线学习资料已经随代码固定，不需重新下载。

## 独立加密迁移

本次交付按用户后续“简单点”的要求，已改成随机强口令：仓库外提供 **`haohao-migration-final.7z`** 和独立的 **`haohao-migration-final-password.txt`**。旧的 `ready` 迁移包误选了早期签名，不再使用。最终包已在内存解密并逐文件对照原文件验证，7z AES-256 同时加密内容和文件名。

另一台电脑用 7-Zip 双击打开，粘贴口令，解压到 `%USERPROFILE%/.haohao-ime` 即可。不要一起公开发送包和口令，也不要将口令文件提交 Git。

包内只有四个相对路径：`signing/public-test.keystore`、`signing/signing-passwords.json`、`internal-test/cloud.properties`、`internal-test/speech.properties`。私钥取自已核验的现行签名恢复目录，而非仍保留旧签名的本机默认目录。只通过私人渠道自行传输 `.7z`，密码走另一条渠道。不含 SSH 私钥、腾讯云主账号凭据、聊天、学习数据库或旧 APK。

恢复目录的 Windows ACL 应仅当前用户、SYSTEM、管理员可访问。建议 7-Zip 本机解压后检查目录属性→安全，移除不相关账号的访问权限。文件既不放网盘公开链接，也不传 GitHub Release/Actions 工件。

## 构建、固定签名、验证

无共享云密钥的本地测试包（`publicDistribution` 是构建策略，**不会自动发布**）：

```powershell
$privateConfigRoot = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.haohao-ime'
$signingState = Get-Content -LiteralPath (Join-Path $privateConfigRoot 'signing/signing-passwords.json') -Raw | ConvertFrom-Json
$env:HAOHAO_PUBLIC_KEYSTORE = Join-Path $privateConfigRoot 'signing/public-test.keystore'
$env:HAOHAO_PUBLIC_STORE_PASSWORD = $signingState.storePassword
$env:HAOHAO_PUBLIC_KEY_PASSWORD = $signingState.keyPassword
$signingState = $null
./gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a -PpublicDistribution=true -PembedInternalCloudSecrets=false
./gradlew.bat :app:testRegressionUnitTest -PbuildABI=arm64-v8a
python app/dictionary/learning/test_lexicon.py
```

仅私下内测需要沿用现有测试云配置时：

```powershell
$privateConfigRoot = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.haohao-ime'
$env:HAOHAO_INTERNAL_CLOUD_SECRETS_FILE = Join-Path $privateConfigRoot 'internal-test/cloud.properties'
$env:HAOHAO_INTERNAL_SPEECH_CONFIG_FILE = Join-Path $privateConfigRoot 'internal-test/speech.properties'
./gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a -PinternalTestDistribution=true -PembedInternalCloudSecrets=true
```

到期日仍为 **2026-09-30**，不延长。不要将内测 APK 上传官网、COS、Git 或 CI。语音是否可用仍由现有配置与授权决定，不因为编译成功就代表云服务上线。

固定签名 SHA-256：`62b4a4c620df03d8bd6c65e21a8cfa5cd11265465e25070dc53006b31de5a101`，包名 `com.osfans.trime.debug`。用 SDK `apksigner verify --print-certs <APK>` 核对，再用 `adb install -r <APK>` 覆盖安装。构建脚本也会校验 `public-signing.properties`，不匹配应停止，不重新生成密钥。私钥容器密码若另外设置，通过本机环境变量 `HAOHAO_PUBLIC_STORE_PASSWORD` / `HAOHAO_PUBLIC_KEY_PASSWORD` 提供，不写入 Git。

脱敏配置格式见 `docs/development/internal-test-202609.md`；语音本地文件仅含 `SPEECH_ENDPOINT` 与 `SPEECH_CLIENT_TOKEN`，可留空，不是腾讯云 SecretId/SecretKey。

测试隔离：regression 默认独立包，设备验收不要对日常包执行 `pm clear`、卸载或导入测试学习数据。本轮产物仅本地交付，官网安装包和下载元数据保持原样。
