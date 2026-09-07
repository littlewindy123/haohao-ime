# Private sentence-correction trial — 2026-09-07

## Scope

- Local changes on `ab95dbd7`, package `com.osfans.trime.debug`, version code `20260924`.
- Preserve the existing full-feature internal configuration, original signing identity, user data, QWERTY and compact layout. No website/COS upload or Git commit/push.
- This is **offline spelling correction**, not a semantic language model. Correctly spelled homophones (for example `xing` versus `xiang`) are deliberately not rewritten.

## Implementation

- Localize a suspected typo using the first mismatch between raw input and the original candidate's full pinyin. Search only the nearby syllable (within six characters).
- Generate one adjacent-key replacement, insertion, deletion or adjacent transposition. Validate full syllables against the existing prism, memoize only during the query, and decode at most four variants. Limit QWERTY input to 4–96 letters; longer input, explicit separators and mid-composition caret positions use the original decoder.
- Reject partial, completion and abbreviated variant results; use local phrase weights to select one correction, followed by the original candidates. Correct full-pinyin sentences and ordinary prefix completions retain their original decoding. Keep the conservative short-word fallback and the existing nine-key path.
- Preserve native Phrase/Sentence objects, dictionary codes and word components. Map corrected syllable/word boundaries back to the real typed offsets; never mutate the original input string. This matters for selection, commit, learning and a correction after a partially selected word.
- Move the delayed ASCII-mode tip refresh onto the Rime dispatcher. Previously that timer called native candidate generation from DefaultDispatcher, concurrently with the single-threaded engine. This fixes a concrete unsafe call site consistent with the observed native candidate-iterator crash; a stress-test pass is not proof that all possible native crashes are gone.

## Validation

- JVM regression: 262 tests passed.
- Fixed synthetic QWERTY cases assert first candidate `我今天想去公园散步` for `wojintianxiamgqugongyuansanbu`, `wojintiianxiangqugongyuansanbu`, `wojintainxiangqugongyuansanbu` and `wojintianxiagqugongyuansanbu`.
- Assert correct/explicit input leaders unchanged, raw spelling unchanged, complete commit text, correction after selecting `我`, caret movement, deletion/retyping, two-error rejection and input over 96 letters.
- Stress: 20 ASCII-mode toggles overlapping the delayed refresh with continuous typing, candidate paging and deletion, on the independent `com.osfans.trime.local.regression` app. No chat messages or audio playback.
- Known existing failure: nine-key `64436 → 你好` is absent from the first 128 candidates. Rebuilt the original `ab95dbd7` native translator and reproduced the identical failure on the same test app. Do not count that old failure as fixed or remove its assertion.
- Timings in the diagnostic test measure complete JNI key processing/candidate generation, not UI frame times. Long-input decoding still has noticeable cost; this trial does not claim Sogou-level latency or broad real-world accuracy from a handful of examples.

## Artifact

- Private local file: `%USERPROFILE%/.haohao-ime/internal-test/haohao-ime-20260924-sentence-correction-arm64.apk`.
- APK SHA-256: `27da4de2abc58b9a1656924f1b2a0b0f9ce9bcd20a4f700546d8b660fe2f92b4`.
- Signing certificate SHA-256: `6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215`.
- Internal/regression credential isolation verified; no Tencent cloud access key, private keystore or configuration file packaged. Cloud test expiry remains 2026-09-30. Tencent network speech remains unconfigured, and no new live cloud availability claim is made.
- `adb install -r` succeeded at 2026-09-07 22:42:14. Original first install remains 2026-09-05 23:50:49, and the default IME remains the daily app. No uninstall or data clear.
- Daily-app visual smoke: synthetic `wojintianxiamgqugongyuansanbu` shows first candidate `我今天想去公园散步`, with original spelling visible. QWERTY, COMPACT and `pinyin_smart_correction=true` confirmed. Cleared the composing input afterward; the own-app test field is empty. No fatal log entry was returned for that daily-app process during the smoke check.
- In the final combined 8-test run, six tests passed; the old nine-key assertion failed and the 180-second stress test timed out while the isolated app was in the background. The new assertion test (all four edits, commit, partial selection, caret, two-error rejection and 100-letter input), diagnostics, the 100-case corpus run twice, nine-key decode/selection and session-replacement tests passed. Do not present that combined run as fully green.
- Foreground stress rerun: **passed**, 20 cycles in 49.344 seconds (`sentence024-stress-foreground.log`). Across the combined run and isolated rerun, seven of the eight test methods pass; the remaining known nine-key assertion is reproducible with the original native implementation.
