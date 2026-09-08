# Translation restoration and refresh work — 2026-09-08

## Findings

- The previously installed 3.3.16 daily APK was built with publicDistribution=true: the public translation endpoint was empty and INTERNAL_CLOUD_ENABLED=false. It could not serve cloud sentence translations. This was a distribution/build mistake, not a user setting error.
- Source modes remained implemented, but were below provider configuration and labeled Off/On/Hybrid in Simplified Chinese. They now appear first as 本地词典 / 云端 / 混合. Existing source preference and consent are preserved.
- Native diagnostics on the connected phone measured long-sentence key processing/presentation maxima around 100–137 ms, even with correction disabled. This patch does NOT claim to fix native long-sentence decoding or achieve a particular frame rate.

## Changes

- Do not perform dictionary/cache lookups for an unrevealed sentence lane or use translation hints to build sentence-mode Chinese geometry.
- Reuse translation TextViews across hide/reveal/content changes instead of removing/recreating every word view. Clear obsolete long-press handlers when rebinding.
- Replace per-byte Formatter allocation in candidate/provider SHA-256 formatting with equivalent hexadecimal encoding. Existing cache keys remain compatible.
- Restore the locally authorized internal cloud and speech configurations in the fixed-signed daily APK. Expiry remains 2026-09-30; no cloud account changes or publishing.

## Verification

- 278 JVM tests passed, including explicit live Aliyun/Baidu/dual-provider smoke requests using fixed `你好` text. All three succeeded; credentials and responses were not logged.
- Four device tests passed: source selections/recreation; sentence geometry/overflow; 100 hide/reveal cycles reusing the same view instances; rapid sentence input and deletion with exact raw-input assertions after every delete.
- Native deletion diagnostics: 6 characters total 23 ms/max 10 ms; 33 characters total 447 ms/max 100 ms. These are engine timings, not UI frame latency, and are not a before/after improvement claim.
- The first UI attempt was blocked by isolated-app setup and Xiaomi cross-app launch confirmation. After enabling the isolated IME and allowing its test runner, the rerun passed.
- Daily APK installed with `adb install -r`; version 3.3.17 / 20261002. Visual smoke in the app-owned input page confirmed `我爱你 → I love you` and `我们明天晚上一起去吃饭 → We’ll go out to dinner together tomorrow night.` No messages sent and no candidate committed.
- Both isolated test packages removed; default IME restored to the daily package. No daily data clear/uninstall, website/APK download change, Git commit or push.

## Private artifact

- `../work/haohao-ime-3.3.17-20261002-private-arm64.apk`
- SHA-256: `f0be0a28c09b712873cfbe4bc4fe073e0a0e2e832e93b5595863329b7838e531`
- Certificate SHA-256: `62b4a4c620df03d8bd6c65e21a8cfa5cd11265465e25070dc53006b31de5a101`
- This is a PRIVATE internal APK containing the previously authorized test configuration, NOT a public credential-free artifact. Do not upload it to Git, the website or COS.

Remaining work: profile and reduce native long-sentence decoding/continuous-delete latency. UI allocation fixes and restored cloud functionality do not establish that all reported lag is resolved.
