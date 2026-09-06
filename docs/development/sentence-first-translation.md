# Sentence-first candidate translation

## Behavior

- `SENTENCE_FIRST` is the default only when no candidate mode has been saved. Explicit `WORD` and `ADAPTIVE` preferences remain unchanged.
- The Chinese candidate row and the two-line translation strip are separate views. Translation completion cannot change Chinese touch targets or key positions.
- Short translations align with their candidate cells. The first multiword, punctuated, or overwide translation spans the strip; overflow exposes an in-keyboard, scrollable full-text preview.
- In sentence-first mode, the first Chinese candidate claims its natural width before trailing candidates. As it grows, trailing padding and then trailing candidates give way; a full-row first sentence leaves other choices in the expanded candidate panel. The toolbox button gives up its space while composing but remains available on the idle toolbar. The entire candidate row is no longer horizontally scrollable.
- A source longer than the row fits gently down to approximately 85% of its normal width, then pans inside its own cell. New source text follows the tail; swiping back can inspect the beginning without revealing secondary candidates or committing text. The English strip and keyboard height remain fixed. Legacy modes retain their fixed-cell fitting behavior, and selection still uses the original candidate index and complete source.
- In the expanded grid, a first sentence wider than one normal cell spans all columns. Overlong content remains readable and can be inspected horizontally from its beginning; other choices remain in the following rows. Dividers respect the actual grid spans.
- Loading is silent: no waiting/translating copy is inserted into the English lane. The expanded preview contains only the translation, with icon-only expand/back controls. Errors and input limits use a small accessible warning/retry control; an explanation appears only when explicitly tapped.
- Word-aligned translations stay aligned with the allocated cells; whole-sentence English stays pinned below. Translation completion does not reset manual inspection of the Chinese source. Preview controls neither commit English nor send messages.
- Candidate translation only uses the first candidate from the current composition. It does not read existing editor text or chat history.

## Request lifecycle

`SentenceCandidateTranslationSession` debounces network work for 800 ms and uses the existing sentence request boundary with a six-second timeout. Input is limited to 200 Unicode code points; excess input produces a visible limit state, not truncation. Sentence results bypass lexical limits on word count, punctuation, and length.

The session keeps up to 32 successful results in memory. Source/provider/privacy context is part of the cache key. Input-session, hide, and configuration changes invalidate work and clear the session as appropriate. Generation checks reject late results, including results from non-cooperative requests. Long sentences are not written to the persistent lexical cache. Secondary candidates use available dictionary/cache entries rather than issuing fragment network requests in sentence-first mode.

Consent, source selection, private-field restrictions, configuration expiry, cancellation, and explicit retry behavior remain enforced. Missing local results and cloud errors do not block Chinese input.

## Rotation crash discovered during validation

Composition state could replay while a newly created IME view had no window token. The preedit touch receiver attempted `PopupWindow.showAtLocation` immediately and raised `WindowManager.BadTokenException`. `TouchEventReceiverWindow` now waits for an attached, visible, measured host with a token, updates after attachment/layout, cancels on detach, and handles a token disappearing during the window operation.

## Automated verification

- `SentenceCandidateTranslationTest`: cached phrases, punctuation/numbers, sentences longer than four words, Unicode input bounds, debounce, stale results, cache/session boundaries, configuration/privacy restrictions, retry, and timeout states.
- `TouchEventReceiverWindowTest`: unattached, missing-token, hidden, unmeasured, and valid host states.
- `SentenceTranslationStripTest` (Android instrumentation): fixed Chinese geometry and strip height at multiple widths/font scales, phrase display, overflow, and hidden states.
- Regression JVM suite: 228 tests passed, zero failures/errors/skips, for build `20260912` on 2026-09-06. First-candidate priority coverage includes progressive growth, trailing-space compression, readable overflow sizing, and responsive expanded-grid spans.
- Instrumentation sources compile, but the device refused installation of the instrumentation test APK. These UI tests have not been executed automatically on the phone.

## Device verification and limits

The fixed-signature internal build with version code `20260908` was installed as an update without uninstalling the existing input method. On the connected Xiaomi device, manual testing confirmed a two-line long translation, in-keyboard expansion, full Chinese candidate commitment, normal landscape rendering, 900×2000 portrait rendering, and 1.3 font scale. Configuration changes no longer reproduced the observed window-token crash during this run.

Follow-up build `20260909` was also installed as an update. Portrait swiping reached the first sentence's tail without inserting text; tapping that tail still committed the complete Chinese sentence. Normal landscape displayed the entire candidate at its natural size. The full preview displayed English without repeating the Chinese source, and its icon-only back control returned to the keyboard.

Build `20260912` replaces that whole-row scrolling behavior. Its reference is the actually installed Sogou Xiaomi edition `10.32.21.202606111755`, observed using on-screen key taps in HaoHao's own Test Input panel. Short, medium, and overlong compositions established the priority allocation, tail-following, manual source inspection, and expanded-candidate behavior; this is not a claim to replicate Sogou's prediction engine or every device/version's styling. See [Sogou reference verification](sogou-candidate-reference.md) for the installed build and observations.

An artificial 2000×900 landscape configuration exposed insufficient vertical space/clipping in the overall keyboard. That extreme combination is not signed off; it needs a separate keyboard-height/layout follow-up. The manual checks are not a substitute for the blocked instrumentation suite or an exhaustive multi-device matrix. Offline/cancellation/expiry outcomes are covered by automated request-boundary tests, not all by live device/network manipulation in this run.

This delivery is private/internal only. No Git push, website release, or COS upload was performed. No secret values belong in this document or the repository.
