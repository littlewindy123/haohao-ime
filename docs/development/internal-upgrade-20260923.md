# Local in-place internal upgrade, 2026-09-07

- Source: `ab95dbd7` plus the guarded `legacyInternalSigning` build option. No change to the public channel certificate.
- Package: `com.osfans.trime.debug`, version `3.3.12` / `20260923`, ARM64.
- Local artifact: `%USERPROFILE%/.haohao-ime/internal-test/haohao-ime-20260923-full-legacy-arm64.apk`.
- APK SHA-256: `990a24e36e54bace98f5f22345997dd15e9d8753f7d9762f07199dd0b41d6eac`.
- Certificate SHA-256: `6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215`, matching the installed original test channel.
- `adb install -r` succeeded at 2026-09-07 21:53:13. Original first install remains 2026-09-05 23:50:49; no uninstall or data clear.

## Checks

- ARM64 debug/regression/test APK builds pass. JVM regression: 262 tests, zero failures, errors or skips.
- Verified dual-cloud credentials and speech gateway token exist only in the internal APK, not the regression APK. No Tencent access key, private keystore or private configuration file is packaged. Nine-key assets and theme 3.3 are present.
- On the daily app, synthetic `wojintianxiangqugongyuansanbu` produced first candidate `我今天想去公园散步`.
- Enabled the existing local smart-correction switch for the requested trial; kept QWERTY, compact height and existing cloud consent. Synthetic `nihap` exposes `你好` / `Hello` in expanded candidates. The corrected result is not first; existing conservative ranking and last-syllable limitations remain.
- Only the app's own test field was used, then cleared. No chat/call interaction or speech playback. No fatal entry was returned for the upgraded daily app process during these smoke checks.

## Limits

- The already-installed isolated regression app uses a different signature. Its update was rejected; it was not uninstalled or cleared. Do not count it as a device test of the newly built regression APK.
- Before this upgrade, the existing isolated package crashed once in native candidate iteration; the short correction test and corpus test then passed individually. This intermittent issue has not been declared fixed by rebuilding or by the limited daily-app smoke check.
- Tencent network speech remains unconfigured. Internal translation/speech expiry remains 2026-09-30. Presence of credentials is not proof of a new live cloud-service test.
- APK remains private and local. No Git commit/push, website deployment or COS upload in this upgrade.
