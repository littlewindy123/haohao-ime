# Product UI review — build 20260922

## Scope

- Replaced the release-facing home with a branded, concise dashboard: keyboard trial, saved words, daily review, four palette previews, input preferences, translation/speech, common phrases, and settings.
- Kept detailed input preferences on their own route. Removed the normal-engine status and exact-pinyin explanation from everyday pages; diagnostics remain available under developer settings. Candidate-count controls are hidden in sentence-first mode, where natural widths supersede them.
- Added shortcuts in the existing 40 dp HaoHao portrait keyboard footer. Left lists enabled input methods and keyboard settings; right opens clipboard/common-phrase tabs. No added keyboard height or duplicate navigation inset. Landscape/custom themes keep their existing toolbar entry points.
- Popovers use IME-window coordinates, stay above the bottom buttons, and close on editor change, hide, or detach. Both clipboard tabs use the same bounded height to keep hit targets stable.
- Common phrases reuse the existing collection database, including previously saved collections. Added creation, editing and removal; clipboard actions retain pin, edit, save-as-phrase and delete. Clearing clipboard history requires confirmation and does not clear phrases or the system clipboard.
- Phrase editing now waits for the database write before finishing, avoiding cancellation of pending saves. Clipboard capture respects the existing listening preference and skips Android-marked sensitive clips.
- Added a translation/speech settings entry with separate speech consent and cache-clear confirmation. No cloud service activation or extension of the test expiry.
- Unified native settings/dialog background, text and accent colors, with matching dark colors. Home becomes two columns in wide windows.

## Verification

- Gradle ARM64 internal debug/regression builds and JVM tests pass (257 tests, zero failures).
- Five device instrumentation tests pass: home layout (320/369/800 dp, 1.0/1.3 font, light/dark), persistent phrase CRUD/pinning, compact test input panel, long Chinese candidate readability, and fixed English preview geometry.
- On Xiaomi 24129PN74C, added a fixed test phrase, reopened it and pasted it into the app's test field; then deleted only that test phrase. Original clipboard history was not cleared.
- Verified input-method list and clipboard/common-phrase tabs on the actual IME. Corrected the initial popup offset from physical-screen to parent-window coordinates; verified bounds above the footer.
- Installed over the existing app with the fixed certificate, retaining original install history and preferences. No uninstall or data reset of the main app.

## Delivery boundaries

- The APK is internal-only; the public website remains on 20260921. Source, tests and this review record are approved for a separate Git commit/push. No internal APK, private configuration, website deployment or COS upload is included.
- Tencent TTS activation remains gated on the separately documented credentials/billing prerequisites. UI completion does not claim live speech service availability.
- Build 20260922 retains the internal test expiry of 2026-09-30 and the existing package/signature.

Final APK: `haohao-ime-20260922-internal-arm64.apk`, stored in the local private internal-test directory.

- SHA-256: `dfa0fc9969dbda923b9f90be5bdfa72472913a099624c6ce439c86877a8af573`
- Signing certificate SHA-256: `6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215`
- Verified speech-token isolation between internal and regression APKs; neither contains Tencent cloud credentials.
- Final portrait clipboard frame: `(82,1734)–(1174,2462)` on the 1200×2670 test display, above the footer. Verified common-phrase settings open without leaving the test panel over the page, and wide home uses two columns.
- Restored the phone's original rotation settings and removed only the isolated regression test packages. Main app remains installed at version 20260922.

## 合并后的回归维护

- 快捷设置已迁到 `NavigationRoute.InputPreferences`。测试必须通过该路由进入 `MainFragment`；默认首页是 `HaoHaoHomeFragment`，不能沿用旧类型转换，也不能点击旧页面里已经隐藏的首页按钮。试用键盘、主题和更多设置的导航从新首页验证。
- 新增产品文案必须同步到简体、繁体资源；默认资源中的中文不能替代区域资源完整性检查。本轮补齐 46 个键，并核对格式占位符。
- 通知频道使用的类型化 `getSystemService` 放在对应 API 判断内。即使当前语音服务只在 Android 15 以上启动，也保持服务入口自身的版本检查完整。
- 小米手机覆盖安装隔离测试包后可能重置后台弹窗权限，导致 `ActivityScenario` 停在启动阶段。检查测试包的权限和前台状态后再判断测试结果；临时权限及输入法切换只用于 `.regression` 包，结束后恢复原默认输入法并移除本轮安装的测试包。
- 本轮验证：257 项 Android JVM 测试、17 项构建逻辑测试、32 项真机 instrumentation 测试通过；ARM64 Regression 与测试 APK 构建成功；Lint 从 47 个错误降为 0，仍有 132 个警告，主要涉及未使用资源、文案、API 建议和绘制问题。
- 官网 29 项测试与静态校验、语音网关 20 项测试、Go 翻译网关测试及 `go vet` 均通过。官网固定哈希文件的 Windows 换行规则见[官网维护说明](../../website/README.md)。
- 真实付费云翻译、腾讯语音服务连通性和长期使用未在本轮重测；自动测试通过不代表这些边界已经验收。本轮回归测试未覆盖安装用户的主应用。
- 后续修复包的 `versionCode` 递增为 `20260923`。所有者随后明确要求生成新签名并在云服务器备份；新包无法覆盖旧签名安装，不能自动卸载或清除旧应用数据。新签名及跨电脑恢复方式见[签名备份说明](internal-test-202609.md#新签名与跨电脑恢复)。
