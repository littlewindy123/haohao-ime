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
