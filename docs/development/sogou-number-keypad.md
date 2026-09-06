# Numeric keypad reference and private verification

> Historical record for build 20260915. The three-key left rail described below was superseded by the four-key reference layout in build 20260917. See [current layout and translation verification](sogou-layout-parity.md).

Date: 2026-09-06. Reference: the already installed Sogou Xiaomi edition `10.32.21.202606111755`. Both input methods were tested in HaoHao's own Test Input panel. No messaging app or real conversation was used. HaoHao remains the default input method.

## Observed reference and design

- Sogou uses a large central telephone-order `123 / 456 / 789` grid, with zero centered below it. The same numeric layer appears from its `123` key and in a numeric editor.
- Backspace, decimal point and `@` occupy the right edge; symbols, return-to-letters, zero, space and enter form the bottom row. Actual key taps entered `123.45` successfully.
- HaoHao now follows that structure while retaining its cream, mint and yellow theme and compact four-row height. Digit cells grow from 10% to 22% of the keyboard width; side cells use 17% each.
- Instead of Sogou's four smaller left-side symbol cells, HaoHao uses three full-height keys: `+`, `-`, `/`, with `%`, `=`, `*` on long press. The existing dense symbol page is retained behind `符`, including currency signs and brackets. This is interaction-inspired design, not a pixel-identical clone.
- Numeric-specific action presets supply `返回` and `空格` labels. Per-key labels alone are intentionally not used: the engine renders action labels in ASCII mode. Existing alphabetic keyboard labels remain unchanged.

Reference screenshots are outside the repository in the workspace `work` directory: `number-sogou-text.png`, `number-sogou-numeric-field.png`, `number-sogou-decimal.png`, and `number-sogou-return.png`.

## Upgrade correctness

The first device build exposed a pre-existing asset-upgrade issue: the bundled theme had updated but the compiled theme still used version 2.6. Managed source timestamps are aligned to the pinned dictionary build, so Rime's timestamp check can miss a theme-only change.

`invalidateCompiledThemeData` now invalidates generated top-level theme YAML files when a bundled top-level theme changes. Dependent compiled themes are regenerated as well. Canonical-path guards constrain invalidation to the user's build directory. Dictionaries, schema binaries, user theme sources, custom patches and preferences are retained. Unchanged or unrelated assets do not trigger invalidation.

The installed final build generated theme version 2.7 and its numeric-specific presets automatically. No manual reset, application-data clear or uninstall was performed.

## Private delivery

- Package: `com.osfans.trime.debug`; version code `20260915`; version name `3.3.12`; ARM64.
- Artifact: `C:\Users\ADMIN\.haohao-ime\internal-test\haohao-ime-20260915-internal-arm64.apk` (restricted local artifact permissions retained).
- APK SHA-256: `09f9352415551845ecb1b3e4d97058ae7abb3d2df7a09f9612b22192f74723d3`.
- Verified fixed signing certificate SHA-256: `6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215`.
- `adb install -r` returned `Success`. Installed version was verified, and user 0 first-install time remained `2026-09-05 23:50:49`.
- No Git commit/push, website publication or COS upload. Public downloads and cloud configuration are unchanged. No credentials are recorded here.

## Verification

- Regression JVM suite: 232 tests, zero failures, errors or skips. Scoped Kotlin formatting and `git diff --check` pass. No new device instrumentation run is claimed.
- Real screen taps entered `123.45` in both a text editor and a numeric editor, and `7890` on the lower digit rows.
- A single backspace changed `7890` to `789`; holding backspace emptied the test editor.
- Long presses of the three left keys followed by space and `@` produced exactly `%=* @` in the text editor.
- `符` opened the preserved symbol page; its `123` key returned to the new grid; `返回` opened the Chinese keyboard. Switching the test editor to numeric mode automatically opened the grid; switching back restored the Chinese keyboard.
- After those transitions, real key taps composed `我爱你` and displayed `I love you`. The initial coordinate typo (`我爱比`) was corrected and is not used as reference proof.
- Final screenshots: `number-20260915-grid.png`, `number-20260915-decimal-text.png`, `number-20260915-decimal-numeric.png`, `number-20260915-symbol-gestures.png`, `number-20260915-more-symbols.png`, `number-20260915-return-chinese.png`, `number-20260915-auto-numeric.png`, and `number-20260915-chinese-woaini.png`.
- No current-process fatal log entries were observed. The temporary portrait lock used for reproducible tapping was removed; free rotation, font scale 1.0, physical 1200 x 2670 with no size override, and HaoHao as default IME were restored/verified.

This is a portrait single-device check, not a complete landscape or multi-device sign-off. The previously recorded landscape Test Input dialog clipping remains outside this numeric-layout change.
