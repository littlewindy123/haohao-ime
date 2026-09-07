# Sogou layout alignment and translation entry

## 工具栏与剪贴板隔离交付（2026-09-07）

本轮扩展既有 Android 工具界面，沿用键盘主题的文字、背景、圆角与按压反馈，不建立新的视觉体系。工具页为可滚动双列入口，标题和说明分层；行高随系统字号增加。快捷设置使用可滚动原生对话框，保留横屏和单手模式下的内容访问。

- 好好工具栏固定两端的“工具”和“收起”，中间三个等宽快捷位默认是剪贴板、译成英文、键盘。可替换为常用语、文本编辑、输入足迹或表情；选择已占用动作时交换位置，非法或重复配置会过滤并补足三位。替换及恢复默认写入本地偏好并即时刷新；其他主题保持原有工具栏行为。
- 工具页提供剪贴板、常用语、快捷设置、工具栏设置及既有工具入口。不可用工具显示原因；翻译仍遵守配置、同意与输入状态检查。
- 快捷设置复用现有音标、英文显示延迟、云端等待时间和单手模式偏好，另可进入键盘选择。两个延迟独立保存，滑块按 100 ms 步进；云端等待上限为 2000 ms。
- 常用语复用本地 `collection.db`，可手动新增，或从剪贴板长按收藏；新增、编辑、置顶和重开均保留记录。点击条目提交完整文本，列表预览截断不改变上屏内容；是否粘贴后返回键盘遵循既有偏好。剪贴板与常用语有独立空态，剪贴板暂停记录时明确提示状态。

验证：JVM 262 项通过；`PhrasePersistenceTest` 与 `HaoHaoPinyinRegressionTest` 共 6 项真机测试通过，执行于最终视觉微调前，后续微调未修改这些测试覆盖的逻辑。实际界面验证新增 `ToolbarTest0907`、置顶、粘贴上屏、快捷位替换和恢复默认即时刷新，以及云端等待调至 2000 ms 后恢复 800 ms。浅色、深色、1.3 倍字号、单手与横屏复核完成，独立最终视觉复核结论为 `ship`。

最终截图在本机 `work/`：`toolbar-final-tools-light.png`、`toolbar-final-settings-light.png`、`toolbar-final-tools-dark.png`、`toolbar-final-settings-dark.png`、`toolbar-final-reset-dark.png`、`toolbar-phrases-dark.png`、`toolbar-final-one-hand.png`、`toolbar-final-tools-landscape.png`、`toolbar-final-settings-landscape.png`、`toolbar-final-settings-landscape-bottom.png`。这些是局部验收证据，不代表全部设备或所有用户主题均已逐一验证；本轮未真实调用云服务。

交付：隔离包 `com.osfans.trime.regression`，`work/haohao-toolbar-isolated-arm64.apk`，56,139,605 字节；SHA-256 `38aa8d9432dcecc2b2efd94f8fcbde97dc52fd68329420a0a01c0c6de583c0b1`。最终视觉修正包已覆盖安装；官网和公开 APK 不更新，不执行 Git commit/push。结束时恢复原默认好好 debug 输入法、字号 1.0、浅色及自动旋转；隔离包恢复工具栏默认、单手关闭、云端等待 800 ms，测试短语 `ToolbarTest0907` 已单条删除。

## 离线纠错与云端等待时间试验（2026-09-07）

入口在主页的“输入设置”。智能纠错默认关闭；云端翻译等待时间默认 800 ms，可按 100 ms 步进调整为 0–2000 ms，独立于英文显示延迟。单词候选与句子优先两条云端请求路径都读取此设置；修改设置会取消旧等待，继续输入也会取消过期请求。缓存命中不额外联网；网络响应时间不包含在此数值中。

纠错复用 Rime prism、词典和原生 Phrase，不修改用户输入串，不调用云端。首版限定输入末尾、4–32 位输入中的最后一个音节：仅一次相邻键替换、增删或相邻字符交换；九键按三行数字布局判断相邻关系。只搜索剩余尾段且一次消耗完整尾段，避免串联多次纠错。保留原始翻译的前四个候选，再补充最多四个完整候选。原生词频保存继续经过禁用个性化学习检查。

直接将纠错混入 Rime 排序曾让准确输入 `nihao` 的后续候选被挤走，因此必须保留准确候选前缀，不能仅依赖词频惩罚系数。首版不包含整句语义纠错，也不新增模糊音配置或纠错索引文件。

验证：最终 JVM 261 项通过；真机输入回归 5 项通过。纠错覆盖 `nihaoo`、`nihap`、`niha`、`644266`、`64436`、`6442`，均可找到“你好”；开关前后的准确输入前四项一致，原始输入不被改写。手机实际点击 `64436` 后，从展开候选选择“你好”成功上屏。滑块 2000 ms 与开关重启持久化已验证，浅色、深色及 1.3 倍字号下新控件显示正常。

云端等待使用模拟提供方验证零等待、动态取值和取消旧请求；没有使用真实凭据发起联网计时，不宣称外部服务延迟达标。隔离包保持原签名与版本，官网和公开 APK 不更新。

交付：`work/haohao-correction-cloud-delay-isolated-arm64.apk`，56,139,605 字节；SHA-256 `5721a03eaa8a5ac4f3bce4d9ad636ec473ce4a154b2d98b34703ccb94c7e93a1`。已覆盖安装至 `com.osfans.trime.regression`；测试结束恢复原默认输入法、字号和系统浅色模式，隔离设置恢复纠错关闭及等待 800 ms。

## 中文九键隔离测试（2026-09-07）

本轮恢复中文九键，参照同一部手机中的搜狗小米版 `10.32.21.202606111755`。保留好好配色、系统字体、图标与双语候选；英文继续使用 26 键。以下记录独立于下方旧版交付，不代表官网或公开 APK 已更新。

- 布局沿用三行字母组、左侧四个标点／可滚动拼音筛选、右侧退格／重输／0、底部符号／数字／空格／中英／回车。数字副标使用配色中的 `comment_text_color`，主题配置版本为 `3.3`。
- 工具栏键盘菜单提供“拼音九键”和“拼音全键”。默认仍全键；用户选择持久化。存在未上屏内容时延后切换，先发布本次提交，再切换方案；清空同样可完成待切换请求。
- `haohao_pinyin_9` 仅新增 schema 和 prism，复用主词典及 `luna_pinyin` 用户词频。预编译清单包含十个文件，两个独立 staging 的新产物 SHA-256 一致。筛选保留已确认前缀并拒绝过期输入。
- 隔离包：`com.osfans.trime.regression`，显示名“好好输入法（隔离测试）”，ARM64，版本 `3.3.12` / `20260923`。本轮不改正式包版本和签名，测试包不配置云翻译或云语音凭据。
- 本地交付文件：`work/haohao-nine-key-isolated-arm64.apk`，57,353,359 字节；SHA-256 `597599849e36447090da8307cb6fc02de35b44fbba5342c810c0df510ba840d5`。

### 已完成验证

- 最终构建和 JVM 回归：35 个测试组、260 项测试，失败／错误／跳过均为 0；预编译完整性和 `git diff --check` 通过。
- ARM64 真机输入回归 4 项通过：连续“你好”“我爱你”、先／西安重码和分词、拼音筛选、过期筛选、部分选词、退格清空、延后布局切换后完整提交“你好”、隐私选项跨方案保留、原 26 键词序和会话切换。原词表第二次启动未重新构建。
- 真机数据回归 17 项通过：短语重开、词本迁移、收藏保留、复习进度、撤销与过期回调等。
- 实际点击验证英文 26 键、数字与符号返回九键、数字输入 `123`、长按退格；左手和横屏均实际输入并上屏“你好”。检查浅色、深色、1.3 倍字号、左右单手及横屏布局；展开候选完整显示英文和音标。
- 视觉复核发现并修复数字副标对比度，修正复核结论为 `ship`。截图保存在本机 `work/nine-final-*.png`，不是公开宣传素材。
- 采用覆盖安装隔离包，没有卸载或清空原 `com.osfans.trime.debug`。结束时恢复原默认输入法、字号 1.0、自由旋转、浅色系统模式；搜狗保留 26 键。

### 尚未完成的验收

词本页面完整 UI 自动化两次未越过启动阶段，已停止该测试，不计入通过结果；数据层测试通过不能替代页面端到端验收。本轮未清除隔离包重做全新安装计时，也未逐一截图全部配色或验证所有用户自定义配置。当前交付用于内部试用，不宣称完成公开发布验收。

九键筛选音节表为 `app/src/main/assets/haohao/nine_key_syllables.txt`，由当前万象拼音去声调、保留 `ü → v` 得到。后续扩充词典音节时应同步筛选表；不要把字母组按钮改成直接提交数字，以免跳过 Rime 候选与个性化学习保护。

Date: 2026-09-06. Reference: the Sogou Xiaomi edition already installed on the test phone, version `10.32.21.202606111755`. Both IMEs were inspected in HaoHao's own Test Input activity. No messages were sent and no conversation text was used as translation input.

## Scope and observed alignment

- The numeric grid now uses a continuous left rail with four independent direct targets, `% / - +`, next to three telephone-order digit rows. Side columns are 16.5% each and the three middle columns share 67%. Backspace, decimal point and `@` remain on the right. The bottom row is symbols, return to letters, zero, space and enter.
- A pure geometry function creates all 21 bounds, including the four-on-three left rail. Unit tests check coverage, rounding, non-overlap and multiple viewport widths. Visual grouping does not merge the four touch targets.
- Chinese QWERTY uses uppercase cap labels and the reference's secondary symbol arrangement. Bottom-row widths, shift/backspace/space icons, toolbar order and bottom inset were adjusted against the installed reference. Compact cap height is retained.
- The toolbar exposes tools, keyboard selection, emoji, text editing, voice and hide in six positions. Long-pressing text editing retains clipboard access. The existing dense symbol page remains available.
- HaoHao keyboard/toolbox transitions no longer slide or fade over one another. Other themes retain their keyboard transitions.
- Theme version 2.8 is delivered through the existing safe compiled-theme invalidation path, without resetting user data.

This is not a claim of full product or pixel identity. HaoHao's branding, colors, bilingual candidates and explicit translation insertion are retained. Sogou's proprietary services, OEM bottom controls, automatic language detection and full toolbox contents are not replicated. Voice availability still depends on an installed supported voice IME; this phone reports it unavailable.

## Translation entry fix

The former `翻译输入` entry is now `译成英文`. It translates an explicit Chinese draft into English for insertion; it is separate from the bilingual candidate preview.

- Activating it returns from the toolbox to the keyboard and adds a 48 dp preview bar above the normal candidate/toolbar area.
- Candidate height remains fixed while drafting so composing or receiving a translation does not move the key rows.
- The preview preserves the entire result without ellipsis. A horizontal scroll reveals overflow; the beginning is shown when a result arrives. `上屏` and `关闭` remain visible.
- Chinese composition retains Rime's delete/enter behavior. Closing cancels the draft/request and clears pending composition. Insertion remains explicit and never sends a message.
- Existing consent, provider configuration, expiry, cancellation, 200-character limit and request timeout remain unchanged. No credentials are recorded here.

## Private delivery

- Package `com.osfans.trime.debug`, version code `20260917`, version name `3.3.12`, ARM64.
- Restricted local APK: `C:\Users\ADMIN\.haohao-ime\internal-test\haohao-ime-20260917-internal-arm64.apk`.
- APK SHA-256: `9cedf7035932a726526e4bd75cf33698cbcd3221c761e953ab7d2e009db3b956`.
- Fixed signing certificate SHA-256: `6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215`.
- Covering installation returned `Success`; installed version is verified and user 0's original first-install time remains `2026-09-05 23:50:49`. No uninstall or data clear.
- No Git commit/push, public website update or COS upload.

## Verification

- Regression JVM suite: 236 tests, zero failures, errors or skips. The internal ARM64 build, scoped Kotlin formatting check and `git diff --check` pass. No new device instrumentation run is claimed.
- Build 20260916 verified each numeric symbol, decimal input, held deletion, return to Chinese, and `我爱你` → `I love you`. English preview did not modify the editor; explicit insertion did.
- Final build 20260917 visually verified the new QWERTY, four-symbol numeric rail, automatic numeric-editor layout, toolbar, translation entry and absence of the previously observed overlapping toolbox transition in the captured state.
- Actual taps produced `%/-+123.45` after an existing leading space. The editor was then cleared.
- Actual key taps composed `我们明天晚上一起去吃饭`; the resulting full English text was `We’ll go out to dinner together tomorrow night.` The editor remained empty before insertion. Scrolling exposed `together tomorrow night.`; tapping `上屏` inserted the full result, including punctuation.
- With `ni` still composing inside translation mode, tapping `关闭` followed by space inserted only a space, not the discarded Chinese composition. The test editor was cleared afterward.
- Current-process AndroidRuntime/libc fatal logs were empty. HaoHao remains the default IME. Temporary rotation locking was removed; free rotation, font scale 1.0 and physical 1200 × 2670 without a size override were verified.

This is a portrait, single-device check, not full multi-device or landscape acceptance. The previously recorded landscape Test Input dialog clipping is not claimed fixed here. Reference and verification screenshots are local workspace artifacts, not publication assets.
