# Continuous typing and learning journal — local build 3.3.19

## Scope

App-only changes, no Git commit/push, website/download changes or cloud service mutations. Preserve the daily package, signing identity, private cloud/speech settings, September 30 test expiry and existing learning/sentence data. No schema migration.

## Continuous input

- Split long input-processing batches at a 32 ms work budget and yield between presentation flushes. This is not a guaranteed frame interval: one native decoder call may itself take longer. No input key is dropped/coalesced; only presentation batches are split, with barriers and input ordering retained.
- Do not cancel the in-progress Chinese candidate model on every physical key. Continue displaying a valid snapshot while newer native input is pending. New candidate snapshots still supersede old models, editor changes cancel them, and candidate selection retains version checks. Translation cancellation/privacy remain independent.
- The previous held-delete backpressure/release fix is retained.
- Added a deterministic slow-key queue test asserting intermediate snapshots at 2/4/6/8/10 keys and exact input order. Added a device test for a 66-key burst through the real service pipeline, checking intermediate presentations and final exact raw spelling; **not yet run on device**.

## Product/UI changes

- Shared cream/mint/honey palette and rounded surfaces across learning metrics, review cards, sentence cards, calendar and sheets; no new animation/library or keyboard redesign.
- Sentence book: distinct bilingual cards, quieter Chinese text, compact search/tabs and a real record count. Move destructive clearing into Manage instead of a persistent full-width bottom button. Keep consent/automatic mode, search, full details and explicit deletion confirmation.
- Calendar: summary numbers, one compact month navigation row, light date cells, mint completion stamps, today outline and accessible per-date descriptions. Replace its platform AlertDialog with the existing branded sheet. Historical-record explanation is opt-in.
- Statistics: clear day/due counts, relative rating bars and grouped date records. Ratings remain self-reported behavior, not measured memory accuracy.
- Review: remove repeated headings around the saved meaning/example; preserve bilingual example highlighting, source attribution, answer hiding, feedback/undo and stable controls. Missing examples no longer create a filler block in the review card; full details retain source coverage information.
- Local reward collection: unlock six named stamps after 1/3/7/14/30/100 cumulative valid check-in days. Show progress toward the next stamp, earned/locked states and a collection sheet from completion, home and calendar. Derived from real completed tasks, so undo/clear recompute eligibility. No streak penalty, notifications, currency, payment, sharing or fake historical rewards. These are local collectible badges, not cash/prizes or newly unlocked keyboard skins.

## Validation/status

- 280 JVM tests passed, zero failures/errors. Regression app and device test APK compile successfully. `git diff --check` passed (existing CRLF normalization warning only).
- Private daily APK built and fixed signature verified. INTERNAL_CLOUD_ENABLED=true, HTTPS speech setting retained, expiry unchanged.
- Initial build was not installed because the phone disconnected. On September 9 the phone reconnected and **3.3.19 / 20261004 was cover-installed successfully** over daily 3.3.18 with the same certificate. No daily data was cleared.
- Added reward earned/locked assertions and app-owned sheet capture to LearningProgressUiTest. Existing sentence/review/calendar data tests retained.
- Device acceptance: rapid burst of 66 letters passed with 33 observed intermediate presentation versions and exact final raw input. Held-delete passed with maximum sampled queue depth 1 and one already-in-flight completion after release, then stable. These assertions do not guarantee all native decoder calls fit a frame or prove zero subjective jank for every sentence.
- LearningProgressUiTest and LearningV4UiTest passed in normal light portrait, dark 360dp/1.3 font portrait, dark 360dp/1.3 font landscape and light 360dp/1.3 font landscape. App-only captures inspected under `../work/journal019-*`. Large-font testing found a clipped calendar completion cell; disabled row baseline alignment and allowed month buttons to grow. Reran affected tests successfully. 280 JVM tests passed again, no failures/errors/skips.
- Test harness: starting instrumentation while its package is the selected IME can leave the service unbound on this phone. Initial input tests failed before performance assertions. Rebinding the IME after instrumentation starts made both input tests pass. The test also waits for activity window focus and taps its own editor. No chat activity was used.
- Restored physical display size, font scale 1.0, portrait rotation and daily default IME. Removed the isolated app and its instrumentation runner (only disposable fixtures); package listing confirms only `com.osfans.trime.debug` remains.

## Private artifact

- Final installed artifact: `../work/haohao-ime-3.3.19-20261004-private-arm64-final.apk`
- SHA-256: `47b9b5939ddd0eac8004611eefa7f975e28121d71930f78e33bbf6a621e55e8c`
- Certificate SHA-256: `62b4a4c620df03d8bd6c65e21a8cfa5cd11265465e25070dc53006b31de5a101`
- Contains previously authorized internal test configuration; not a public credential-free APK. Do not publish to website/COS/Git.
