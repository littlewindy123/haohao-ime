# Sogou layout alignment and translation entry

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
