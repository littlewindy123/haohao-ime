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

### 经典与立体键帽

- `haohao_keycap_style` 默认 `CLASSIC`，选择 `RAISED` 后采用原生渐变键面、固定键座及按下位移。样式与配色、键盘高度、单手设置分别保存，切换只通过现有输入视图重建监听刷新，不重部署词库或更改词本数据。
- 首页配色区提供两种样式、四张配色缩略图和实际键盘试用入口。其他 Trime 主题禁用首页样式选择，保留其原绘制规则。
- 立体键帽厚度不超过 3dp，按压不超过 2dp，并钳制在原按键底部留白内；键座固定，文字、图标和提示同步下移，触摸区域保持完整。九键左侧连续功能栏保留原来的整体外观与独立触摸目标。
- 从主题配置读取键面颜色，禁止无版本保护调用 API 24 才提供的 `GradientDrawable.getColor()`；最低 Android API 21 同样支持此样式。不要使用 `sp` 计算立体厚度和位移。
- 几何与偏好测试覆盖触摸边界、压下前后尺寸、默认经典与持久化；首页布局覆盖 320/369/800dp、1/1.3/2 倍字号和浅深主题。`KeycapStyleInputTest` 仅在隔离包已设为当前输入法时运行，通过屏幕触摸与实际编辑框文字验证输入，不用 `performClick()` 代替键盘测试。
- 输入测试必须通过实际中英文键切换模式并等待状态刷新；仅设置 Rime 原生选项可能被键盘恢复状态覆盖，导致英文测试仍在中文组词。旋转重建后重新打开试用面板并聚焦编辑框，触摸断言失败也必须释放按下事件。
- 验证结果：263 项 JVM 测试通过，Lint 为 0 个错误、162 个警告。实机首页与偏好共 3 项测试通过，并验证立体键盘连续输入。API 36 隔离模拟器在浅深主题下完成两样式 × 四配色 × 横竖屏的 32 组连续输入（480 次字母触摸），检查实际上屏文字与按压前后触摸边界；左右单手切换及两样式九键绘制均通过。测试结束恢复手机原输入法并移除临时回归包，日常应用未覆盖安装。
- 所有者随后要求手机始终使用最新版；已将包含立体键帽和复习 UI 修复的 20260925 安装到日常包，并完整恢复核对数据。后续直接覆盖升级的约定和本次迁移记录见[日常手机更新说明](internal-test-202609.md#日常手机最新版更新约定20260925-起)。

### 设置与弹窗整理

- 设置首页按外观、输入、翻译与发音、学习、隐私与数据组织；自定义方案、词库、主题文件、组件版本与诊断工具归入高级设置。主页与输入设置的常用菜单不再直接展示部署、开发者入口。
- 关于页展示好好输入法的产品介绍、版本、反馈与帮助；开源许可与致谢页保留 GPL 许可、项目源码链接和第三方依赖信息，不删除源文件版权声明。
- 外观页复用既有按键样式、配色和界面模式偏好；不适用于其他主题的好好键帽选项禁用，并保留更多主题入口。`ListPreference` 即使不自行持久化也必须有稳定 key，才能由偏好页正确打开选择框。
- 工具栏选择使用 `v2:` 前缀保存明确的 0–3 个快捷功能，保持顺序并去重。旧空值和未带版本的配置仍补齐三个默认功能；显式空选择不能被默认功能自动填回。工具箱与收起键始终保留。
- 工具栏编辑支持单面板选择、取消、箭头排序和原位重置；单手模式在快捷设置中直接选择。文本布局测试检查实际行宽、行高和省略字符，覆盖 320/369/800dp、1/1.3/2 倍字号与浅深主题。
- 验证记录：264 项 JVM 测试、6 项 instrumentation 测试通过，覆盖设置导航、旧偏好兼容、界面布局及未授予通知权限时的首页启动与重建。通知管理已移到隐私与数据，首页不再主动申请通知权限；运行该通知回归前，应在隔离包外先撤销通知权限，避免在测试进程中撤销权限导致自身被系统结束。实机覆盖与数据核验见[更新说明](internal-test-202609.md#日常手机最新版更新约定20260925-起)。
- 最终 ARM64 与 x86_64 构建通过，`lintRegression` 为 0 个错误、180 个警告，尚有未使用资源、依赖升级和平台 API 建议等存量提示。26 个新增产品文案键在默认、简体和繁体资源中完整匹配，格式占位符一致；13 份项目 Markdown 校验通过。

### 其他回归约束

- 快捷设置已迁到 `NavigationRoute.InputPreferences`。测试必须通过该路由进入 `MainFragment`；默认首页是 `HaoHaoHomeFragment`，不能沿用旧类型转换，也不能点击旧页面里已经隐藏的首页按钮。试用键盘、主题和更多设置的导航从新首页验证。
- 新增产品文案必须同步到简体、繁体资源；默认资源中的中文不能替代区域资源完整性检查。本轮补齐 46 个键，并核对格式占位符。
- 通知频道使用的类型化 `getSystemService` 放在对应 API 判断内。即使当前语音服务只在 Android 15 以上启动，也保持服务入口自身的版本检查完整。
- 小米手机覆盖安装隔离测试包后可能重置后台弹窗权限，导致 `ActivityScenario` 停在启动阶段。检查测试包的权限和前台状态后再判断测试结果；临时权限及输入法切换只用于 `.regression` 包，结束后恢复原默认输入法并移除本轮安装的测试包。
- 本轮验证：257 项 Android JVM 测试、17 项构建逻辑测试、32 项真机 instrumentation 测试通过；ARM64 Regression 与测试 APK 构建成功；Lint 从 47 个错误降为 0，仍有 132 个警告，主要涉及未使用资源、文案、API 建议和绘制问题。
- 官网 29 项测试与静态校验、语音网关 20 项测试、Go 翻译网关测试及 `go vet` 均通过。官网固定哈希文件的 Windows 换行规则见[官网维护说明](../../website/README.md)。
- 真实付费云翻译、腾讯语音服务连通性和长期使用未在本轮重测；自动测试通过不代表这些边界已经验收。本轮回归测试未覆盖安装用户的主应用。
- 后续修复包的 `versionCode` 递增为 `20260923`。所有者随后明确要求生成新签名并在云服务器备份；新包无法覆盖旧签名安装，不能自动卸载或清除旧应用数据。新签名及跨电脑恢复方式见[签名备份说明](internal-test-202609.md#新签名与跨电脑恢复)。
