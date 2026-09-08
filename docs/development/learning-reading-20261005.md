# Borderless word reading — 3.3.20 / 20261005

User requested fewer boxes/borders, stronger colors and clearer word meanings inspired by MaiMemo's learning organization. Reference: https://www.maimemo.com/ and its public word/meaning/example documentation. No competitor dictionary, images, fonts or application data were copied. Browser inspection was unavailable; no claim of pixel-identical reproduction.

## Changes

- Remove review card outline/background and nested example surface. Word, saved meaning and bilingual example share a continuous reading layout.
- Add light/dark reading colors: emerald word, terracotta meaning, blue reading actions. Three feedback buttons retain labels with coral/amber/green backgrounds; reveal uses green. Existing brand canvas stays unchanged.
- Place phonetic and borderless normal/slow playback in one row. Only reading pages opt into flat speech controls; playback logic, consent, cache and other screens are unchanged.
- Detail page uses the same hierarchy. Highlight existing POS prefixes without changing dictionary text. Move dictionary attribution to the end, keeping notices accessible in the branded sheet. Tatoeba authors, sentence URLs and license remain inline with each example; remove duplicated link rows.
- Preserve saved meanings, rating/undo/session rules, reverse-answer privacy and background lexicon lookup. No keyboard, cloud, database/schema, website or Git publication changes.

## Checks

- 280 JVM tests, 0 failed/errors/skips; debug and isolated regression builds succeed; diff whitespace check passes (pre-existing GestureFrame line-ending warning only).
- Isolated LearningV4UiTest passes both methods: review/recreation/search/details and reverse English/phonetic/audio hiding. Checked light normal portrait, dark 360dp/1.3-font portrait, dark landscape and final light landscape. App-only screenshots under `../work/reading020-*` inspected.
- Tests assert no review/example background, saved meaning color/text preserved, highlight still present, text not vertically clipped, footer stability and minimum touch height. Synthetic fixtures only; no daily learning data used.
- Final private APK fixed certificate verified; original cloud flag, HTTPS speech config and September 30 expiry preserved. This is a private configuration-bearing APK, not for website/COS/Git.
- Cover-installed daily 3.3.20 / 20261005. Restored physical display, font 1.0, portrait and daily default IME. Removed disposable isolated app and test runner; keep only daily package.

Artifact: `../work/haohao-ime-3.3.20-20261005-private-arm64.apk`

SHA-256: `eb032038da08c91bdfb31d28fcef27c78bf02e89c4d3bcfd527030ecf2922891`

Certificate: `62b4a4c620df03d8bd6c65e21a8cfa5cd11265465e25070dc53006b31de5a101`
