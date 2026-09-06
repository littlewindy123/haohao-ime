# Sogou candidate-priority reference and private verification

Date: 2026-09-06. Reference: the already installed Sogou Xiaomi edition `10.32.21.202606111755` (`com.sohu.inputmethod.sogou.xiaomi`). Tests used the app's own Test Input panel, not a conversation in WeChat or another messaging app. The default input method was restored to HaoHao after comparison.

## Observed reference

Real on-screen key taps composed `我爱你`, `我爱你明天晚上`, and `我爱你明天晚上我们一起去吃饭然后去公园散步`. Android shell `input text` bypassed Sogou composition, so the initial Latin-only captures were discarded as reference evidence.

- Short candidates occupy natural widths; a growing first sentence displaces trailing alternatives.
- An overlong first sentence owns the visible row, gently fits its lettering, and follows the newest tail. Its beginning can be inspected by panning inside that source.
- The right-hand arrow opens other candidates. A long first candidate occupies a full first row in the expanded panel.
- Sogou's exact font-fitting constants are not known; HaoHao uses an approximately 85% readable-width floor based on the observed behavior. This is interaction parity for the candidate area, not an engine, branding, or pixel-identical clone.

Reference screenshots are retained outside the repository in `../../../work/` (relative to this document): `sogou-touch-short.png`, `sogou-touch-medium.png`, `sogou-touch-long-settled.png`, `sogou-touch-long-scroll-start.png`, and `sogou-touch-expanded.png`.

## Private delivery

- Package: `com.osfans.trime.debug`; version code `20260912`; version name `3.3.12`; ARM64.
- Artifact: `C:\Users\ADMIN\.haohao-ime\internal-test\haohao-ime-20260912-internal-arm64.apk`.
- APK SHA-256: `b237ce3bd8e5df27ccf768c6ee2a8b66a31f6d70bce88ea33ccc1a33b277c99a`.
- Fixed signing certificate SHA-256: `6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215`.
- `adb install -r` returned `Success`; installed version was verified. User 0 first-install time remained `2026-09-05 23:50:49`. No uninstall or app-data clear was used.
- No website, COS, Git commit, or Git push. Existing public download remains unchanged. No secret values are recorded here.

## Verification

- Regression JVM suite: 228 tests, zero failures/errors/skips. Android instrumentation sources compile; the earlier device restriction on installing the instrumentation APK remains, so these are not claimed as executed device instrumentation tests.
- Scoped Kotlin formatting and `git diff --check` were checked without formatting unrelated website work.
- Short and medium compositions were compared directly. The full-row allocation was checked with the same reference sentence, then extended to `我爱你明天晚上我们一起去吃饭然后去公园散步然后我们一起回家吧`.
- `sogou-match-20260912-overlong-tail.png` and `sogou-match-20260912-overlong-start.png`: source tail visible automatically; manual pan reaches the beginning without entering text. The English preview remains pinned.
- The same implementation in the intermediate `20260911` build was additionally checked by panning to the beginning, appending `ba`, and observing automatic tail-following resume (`sogou-match-20260911-growth-tail.png`).
- `sogou-match-20260912-candidates-expanded.png` and `sogou-match-20260912-expanded-tail.png`: readable first sentence spans the grid; panning reaches its end; secondary candidates remain in subsequent rows.
- `sogou-match-20260912-english-preview.png`: the full English translation is shown within the keyboard, without duplicate Chinese source or loading copy.
- After tapping the first candidate, the Test Input accessibility node contained exactly the full extended Chinese sentence above, and Rime logged selection of global index 0. Short-candidate clicking was checked separately (`sogou-match-20260912-short-commit.png`). No message was sent.
- The active app process remained running through rotation attempts. Final settings: HaoHao default input method, free rotation, physical 1200×2670 resolution with no override, font scale 1.0.

## Limits and follow-up

This is not a complete multi-device or landscape sign-off. In this run the app's own Test Input dialog was recreated on rotation, and the landscape dialog had clipped controls/input space, preventing a reliable final long-composition landscape check. That test-panel/height issue is separate from the candidate priority allocation and remains a follow-up. The earlier extreme 2000×900 keyboard-height limitation is also not resolved here.

The cloud request limit remains 200 Unicode code points, with existing consent, cancellation, expiry, and timeout boundaries. Visually panning a Chinese sentence does not imply unlimited cloud translation input. The current change does not expand access to editor history or alter cloud credentials.
