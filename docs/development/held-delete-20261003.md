# Held-delete backpressure — 2026-09-08

## Cause and fix

- GestureFrame emitted repeats on a fixed timer irrespective of decoder/presentation completion. The lossless input queue could therefore accumulate deletes while native long-sentence processing was slow; consecutive keys share a presentation refresh, explaining the delayed visual jump.
- Long-press ACTION_UP additionally dispatched LONG_CLICK through the repeatable-key branch, which generated one more click/delete on release.
- For on-screen Delete repeats only, allow another timer tick to dispatch when the input pipeline has finished queued/native work AND the presentation flush. Missed timer opportunities are not replayed; ordinary physical taps/typing remain lossless and ordered.
- Pipeline exposes pending work including an in-flight batch/flush. Other repeatable keys keep their existing timing. Repeatable-key release only cancels/cleans up, without an extra click. Detachment cancels gesture timers.
- Native sentence decoding is unchanged. This addresses accumulated long-hold deletes, not every source of long-sentence CPU latency. A delete already in flight at release can still finish.

## Verification

- 277 JVM regression tests passed, including a new suspended-native/suspended-presentation test proving backpressure remains active until both finish.
- Real touch instrumentation on the connected phone: isolated app-owned input page, 66-letter synthetic pinyin composition, aggressive 20 ms repeat setting, 4-second hold. Asserts at least three processed deletes, no increased queue peak beyond one repeat (apart from pre-existing setup maximum), at most one in-flight completion after release, then stable count for a further 500 ms. Test passed.
- Initial assertion used a timestamp before touch-up injection/main-thread handoff and failed; corrected to sample inside the actual release cleanup callback, without relaxing the one-in-flight limit. Rerun passed. No claim of measured frame-rate improvement.
- First install attempt was canceled by the phone. User requested retry; retry succeeded. An isolated-app background launch required bringing its own main page to foreground before the passing rerun.
- Daily version 3.3.18 / 20261003 installed via `adb install -r`. Daily IME restored, both isolated packages uninstalled. No daily uninstall/data clear, Git commit/push, cloud change, or website upload.

## Private artifact

- `../work/haohao-ime-3.3.18-20261003-private-arm64.apk`
- SHA-256: `e79b3b38dd9ee25d330288da494b90e029a32192c02991ebbe27cdc035e33c77`
- Fixed certificate SHA-256: `62b4a4c620df03d8bd6c65e21a8cfa5cd11265465e25070dc53006b31de5a101`
- Preserves authorized private cloud/speech configuration and 2026-09-30 expiry. This APK is not for public distribution.
