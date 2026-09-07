# 好好输入法官网

原生 HTML、CSS 和 JavaScript，无前端框架、外部字体或统计脚本。GSAP 核心库固定版本自托管。

## 本地检查与预览

```sh
node --test website/demo-model.test.mjs website/main.test.mjs website/vendor.test.mjs website/verify-apk.test.mjs
node website/check.mjs
python -m http.server 4173 --bind 127.0.0.1 --directory website
```

首页包含正式品牌首屏、双语输入与学习功能、真实键盘画面与本地隐私、GitHub Star 邀请和下载。
章节背景铺满视口，正文按流式边距展开，不再固定限制为 1160px；段落与输入演示单独控制可读宽度。
导航和社区 Star 按钮均打开真实仓库；访客仍须在 GitHub 登录后自行点 Star。没有 API、虚假计数或自动点赞。
复用 Android 正式金色小狗 Logo，奶油白、薄荷绿、蜂蜜金、可可棕来自 App 色板。
安装包技术细节收进安装说明，开源与致谢收进页脚；不能在营销文案中隐藏测试版性质。
支持系统深色模式，整页使用同一套语义色板；无随机深浅色章节切换。
截图只通过 CSS 裁切底部键盘，不修改原始图片，也不把旧测试画面宣传为新版截图。
网页演示只收录三组拼音，其他输入不虚构翻译。音标可以开关；切换示例不唤起手机键盘。
GSAP 时间线按顺序展示 Logo 与文案，章节只入场一次；页面隐藏或减少动态效果时还原样式。
未加载 GSAP 时保留原生轻量入场降级；无脚本时正文和下载可用。不使用滚动劫持或循环漂浮。
`main.test.mjs` 使用隔离的事件模拟检查实际演示脚本，不依赖浏览器或第三方测试库。

Windows 检出时，`.gitattributes` 为 `vendor/*.min.js` 固定 LF 换行，保证第三方库的字节与固定 SHA-256 一致。
如果 `check.mjs` 报 GSAP 哈希不符，先用 `git ls-files --eol website/vendor/gsap-3.15.0.min.js`
检查是否被 `core.autocrlf` 转为 CRLF；确认没有本地修改后恢复 LF，再运行上述校验和测试。
不要为了通过检查修改固定哈希，也不要关闭完整性校验。

## 发布构建

先构建 Android ARM64 测试包并验证固定签名：

```sh
./gradlew :app:assembleDebug -PpublicDistribution=true -PembedInternalCloudSecrets=false -PbuildABI=arm64-v8a
```

公开下载包必须使用 `-PpublicDistribution=true -PembedInternalCloudSecrets=false`。
固定身份位于仓库根目录 `public-signing.properties`（只有公开指纹，无私钥）。
私钥默认读取用户目录的 `.haohao-ime/signing/public-test.keystore`，也可通过
`HAOHAO_PUBLIC_KEYSTORE` 指向备份恢复后的文件；禁止每次发布重新生成。
沿用本次测试渠道签名，默认密码仍为 Android 调试签名的标准密码，仅用于
受文件权限保护的测试密钥；如重设密钥文件密码但保留同一私钥，可分别设置
`HAOHAO_PUBLIC_STORE_PASSWORD` 和 `HAOHAO_PUBLIC_KEY_PASSWORD` 环境变量。
这不是新建正式商店发行签名的流程，不要在公开存储、代码仓库或聊天中分享私钥。

换电脑必须先恢复同一份密钥，公开构建缺少密钥或指纹不一致会立即失败。
普通 Debug CI 产物不作为官网更新包。保持包名不变，每次实际发版递增 `versionCode`。
2026-09-05 之前的网站旧包使用另一签名，无法用本次身份直接覆盖；用户已选择
不再追查旧签名，从本次身份开始固定。不得宣称历史包已兼容覆盖升级，也不要
在未备份用户词本和配置的情况下建议卸载旧应用。

更新 `release.json` 和首页的版本、体积、下载地址，然后执行：

```sh
node website/build.mjs --apk /path/to/verified.apk --out /path/to/new-empty-release
```

设置 `JAVA_HOME`（JDK 17）和 `ANDROID_HOME`（含 Build Tools 36.0.0）。
构建会通过 Android 官方 apksigner 验证 APK 完整签名及固定指纹，再校验 SHA-256
和字节数，合并演示模块以减少请求，为脚本和样式
生成内容哈希缓存版本，输出 gzip 副本、校验文件和公开资源清单。
仅白名单内的文件会进入产物；测试代码、SSH 密钥、云凭据和本地配置不会打包。

## 腾讯云部署约定

- 网站：`http://124.221.187.214/`。
- Nginx 网站配置：`/www/server/panel/vhost/nginx/haohao-ime.conf`。
- 发布目录：`/www/wwwroot/haohao-ime/releases/`。
- 当前线上指针：`/www/wwwroot/haohao-ime/current`。
- 将产物上传到新的版本目录，核对哈希、资源及下载后再原子切换 `current`。
- 保留上一版本和旧 APK 链接，不原地覆盖旧版本安装包；需要时回切原指针。
- COS 上海桶 `haohao-1476026962`（2026-08-30 创建）已上传本次 APK 和 `.sha256`，
  仅这两个对象为公有读私有写，未公开整个桶。用户授权月预算 50 元，不买额外套餐、CDN 或加速服务。
- 免费标准存储容量包为 50GB，页面显示到期日 2027-02-28，仅抵扣对应存储费用。
- COS 默认域名对本桶的 APK 下载实际返回 `DownloadForbidden`（HEAD 可返回 200，
  所以仅验证 HEAD 不足以证明能下载）。须绑定自定义域名后再将官网 APK 链接切到 COS；
  当前官网继续使用现有服务器下载。不要把会过期的临时签名链接当成官网永久地址。
- 不得把私钥或
  云凭据上传到公开存储桶，也不得把临时签名 URL 当成永久下载地址。
- COS 是按量计费，预算提醒不等于硬消费上限；当前仅获得 50 元/月预算授权，
  尚未配置腾讯云自动费用告警，不得声称超预算会自动停费。

当前发布渠道仍为 Debug 测试版，不宣传为正式签名的稳定 Release。
