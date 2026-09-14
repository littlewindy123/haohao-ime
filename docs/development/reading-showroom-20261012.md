# Focused learning and website showroom refresh

Date: 2026-09-15. Local daily build: 3.3.27 / 20261012.

## Scope

This change preserves the already-approved cream/mint/honey/cocoa brand, the golden dog, ten keyboard skins, and the current native Android widgets. `design-taste-frontend` guided an audit-first, preservation redesign at 7/6/4; GSAP core/timeline/performance guidance kept animations cancellable and transform-based. No framework migration, new artwork, cloud endpoint, database migration, review algorithm, statistics or rewards change was introduced by this refresh.

The working tree already contained the earlier input-performance and learning-entry fixes. Those edits were preserved. No Git commit or push is part of this delivery.

## App

- Word details keep the saved meaning, then the first attributed usage example, then three other definitions. Remaining definitions, English explanations, additional examples and forms are disclosures.
- Favorite and learning controls are outside the reading scroll view. Dictionary arrival, disclosure changes, favorite/learning state and capitalization edits do not rebuild the page or move the action area.
- Generation-checked, atomic writes disable repeat submissions and restore controls after failure. Existing confirmation semantics use the branded sheet; capitalization is under More.
- Reading position and disclosure state survive recreation. Reverse review still hides English, phonetic, examples and playback until reveal.
- Review uses a compact mode/progress header, green word, orange saved meaning and one bilingual example; feedback/undo/scheduling are unchanged.

## Website

- Complete 26-letter hero keyboard with the existing three automatic greetings; a complete HTML keyboard is present before JavaScript or WebGL.
- One story: 照常打字 → 顺手收藏 → 每天记一点. The duplicate pinyin demo/controller and old static learning screenshots are removed. Existing anchors remain available, including aliases for merged sections.
- The skin studio uses the existing Three.js scene with projected semantic DOM key targets, real editing, accessible keyboard access and functional no-WebGL fallback. Case/language/theme changes share one input state. Only the existing three pinyin examples are demonstrated.
- All ten existing skins remain; latest-selection guards prevent out-of-order image callbacks. Renderer resources, observers, listeners and animation state are released on departure or preference changes.
- Videos load near the viewport, are silent and inline, pause in the background/offscreen, and respect reduced motion and data saving. Autoplay refusal does not loop. All `<source>` failures expose an explicit retry, even when the browser never emits a `<video>` error. Controls sit below the video rather than covering App actions.
- The original App logo is retained byte-for-byte as source; a 176px WebP derivative is served. Headline text is stationary from first paint, avoiding a delayed LCP caused by moving it after load.
- HTTPS sharing, privacy, installation, attribution and existing links remain. Fan-skin disclosure is shown once.

## Recording provenance

`WebsiteLearningRecordingTest` runs only in `com.osfans.trime.regression`, with synthetic `learn / 学习` data. It captures actual Activity content and only its own confirmation sheet, positioned at real window coordinates, without system surfaces, chat, notification, audio or compositor-level window animations. It does not score cards or change daily learning data.

The two 390×867 recordings contain 100 frames each; actual capture timestamps, not an assumed fixed rate, drive encoding. Output is padded by one pixel to 390×868 for video codecs. Source manifests, frame hashes and encoder report remain in `work/reading-showroom-20260915` outside Git. These captures demonstrate UI behavior, not input-latency or smoothness measurements.

| Clip | Duration | MP4 | WebM | Public label |
| --- | --- | --- | --- | --- |
| Save a word | About 10 seconds | 63,988 B | 31,864 B | 开发预览 · 3.3.27 |
| Reveal a review card | About 10 seconds | 24,553 B | 15,823 B | 开发预览 · 3.3.27 |

The existing real sentence-input clip remains unchanged. No unavailable translation or speech result was fabricated. New App reading screens are explicitly development previews because the website download remains older.

## Protected public delivery

The page deployment must preserve the existing public APK and the exact `release.json` bytes. The local private daily APK is never placed in the website directory or uploaded.

- Public version: **3.3.25 / 20261010**
- Public package: `com.osfans.trime.debug`, arm64-v8a
- Public APK bytes: **57,809,966**
- Public APK SHA-256: `813e4aabdcaf4e6dfa83b3c18a15d71b5ffab97160a8a7304d9b0eef84896cba`
- Public metadata SHA-256: `51b502c0adbb747179103590ebee3665c51a59de612435adeb283a6563f3b60e`
- Fixed signer SHA-256: `62b4a4c620df03d8bd6c65e21a8cfa5cd11265465e25070dc53006b31de5a101`

Page publishing uses a new version directory, per-file hashes, protected-download checks and an atomic symlink switch. The previous release is retained for rollback. No Nginx/cloud configuration is changed.

## Validation records

- App JVM regression: 333 tests passed.
- V4 UI: default 4/4; dark landscape with 1.3 font 3/3; approximately 360dp portrait with 1.3 font 3/3.
- Capitalization confirmation/state recovery: 1/1. Modes/dashboard full suite: 2/2 after repairing the test harness foreground precondition for each ActivityScenario launch.
- Recording fixture: 1/1, both actual captures complete; feedback count remains zero.
- Earlier incomplete Modes runs are retained as incomplete, not retrospectively counted as passes. Harness fixes preserve all original assertions and add time limits/stage logging, with no production workaround.
- Browser checks cover 360/390/768/1440px, all ten skins, real key presses, selection, grapheme deletion, input-preserving skin switches, no-script static content, reduced motion, data saver, dark styling, WebGL failure and video-source failure.
- Local source Lighthouse after headline/logo optimization: mobile performance 98, LCP 1.8s, TBT 150ms, CLS 0; desktop 100, LCP 0.5s, TBT 0ms, CLS 0. These are lab measurements, not a guarantee for every user's network/GPU. Final production audit and deployment evidence are retained separately.

Device restoration, final daily installation/input regression and production release verification are recorded in the delivery evidence before handoff.

## Final delivery evidence

- **Website deployed** to `/www/wwwroot/haohao-ime/releases/20260915-reading-showroom-20261012`; rollback remains `/www/wwwroot/haohao-ime/releases/20260915-input-feel-20261010-public`. Only 27 page files were uploaded. The combined retained manifest has 44 entries.
- Read-only HTTPS verification matched all 28 current page/metadata files to the production build. Certificate validation, APK length/range response and all three MP4 range responses passed. Server-side full protected-file hashes and exact public release metadata were checked before the atomic switch. Public 3.3.25 remains unchanged.
- Final production Lighthouse: **mobile 98**, LCP **1.7s**, TBT **130ms**, CLS **0**; **desktop 100**, LCP **0.4s**, TBT **0ms**, CLS **0**. Accessibility, best practices and SEO are 100 on both runs. Complete HTML paints before double-rAF/idle progressive 3D loading, with cancellable scheduling and no long fixed delay. Earlier source/intermediate scores are retained separately rather than overwritten.
- Final website unit/contract suite: **74/74**, including deferred renderer cancellation, stale textures, no-op state updates, source-level video failures and stale play promises. The CI Playwright script has been updated but was not run through a separate browser automation process; interactive visual/function checks were performed through the supported browser tool.
- **Daily App 3.3.27 installed** with the fixed signer. Local frozen APK and the device's installed base APK both hash to `eb214dd56ff8ec3bae00bda1035d8c76a9e328d15b3c04ac8e97714987e12ea9`. It remains private/local.
- Daily smoke: 66/66 letters at 12Hz, exact spelling, no queue remaining at the first post-injection sample; 1.5s delete hold processed 37 repeats, then no additional deletion after release plus one second. Numeric selection/replacement/deletion, password protection and Chinese/manual-English restoration passed. These are correctness checks, not end-to-end latency measurements.
- The daily test editor was protected with a temporary `NO_PERSONALIZED_LEARNING` flag before testing; a future new editor needs this guard re-established. The extra-ESC cleanup guard failure and its actual-empty-editor resolution remain in the device log, not hidden by rerunning the test.
- Both isolated test packages and their synthetic fixtures were removed after testing. Only `com.osfans.trime.debug` remains; no daily database was cleared. The test field is empty, original display/IME settings restored and temporary ADB forwards removed.

Detailed local evidence lives in `work/reading-showroom-20260915`: `deployment.json`, `live-verification.json`, Lighthouse JSON reports, `device/DEVICE-REPORT.md`, the frozen private APK and recording manifests. No credentials, private APK or test fixtures were uploaded. No Git commit or push was made.
