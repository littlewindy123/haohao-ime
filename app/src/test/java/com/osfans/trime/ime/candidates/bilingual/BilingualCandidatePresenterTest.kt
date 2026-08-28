// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_HORIZONTAL_PADDING_DP
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_LANDSCAPE_DEFAULT
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_LANDSCAPE_MAX
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_LANDSCAPE_MIN
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_MAX_WIDTH_DP
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_MIN_WIDTH_DP
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_PORTRAIT_DEFAULT
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_PORTRAIT_MAX
import com.osfans.trime.ime.candidates.compact.COMPACT_CANDIDATE_PORTRAIT_MIN
import com.osfans.trime.ime.candidates.compact.CompactCandidateWidthBounds
import com.osfans.trime.ime.candidates.compact.CompactTranslationWidthLimits
import com.osfans.trime.ime.candidates.compact.compactCandidateAvailableWidth
import com.osfans.trime.ime.candidates.compact.compactCandidateCellWidth
import com.osfans.trime.ime.candidates.compact.fitCompactCandidateRow
import com.osfans.trime.ime.candidates.compact.resolveCompactCandidateCount
import com.osfans.trime.ime.candidates.compact.toCompactCandidateItems
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateItem
import com.osfans.trime.ime.candidates.unrolled.toDisplayableUnrolledCandidates
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BilingualCandidatePresenterTest :
    StringSpec({
        "enabled presenter adds a translation without changing the Rime candidate" {
            val repository = CandidateTranslationRepository {
                if (it == "你好") CandidateTranslationEntry("hello", "/h\u0259\u02c8\u026bo\u028a/") else null
            }
            val candidate = CandidateProto(text = "你好", comment = "nǐ hǎo", label = "1")

            val presentation = BilingualCandidatePresenter(repository) { true }.present(candidate)

            presentation.candidate shouldBe candidate
            presentation.translation shouldBe "hello"
            presentation.phonetic shouldBe null
            candidate.comment shouldBe "nǐ hǎo"
            candidate.label shouldBe "1"
        }

        "disabled presenter does not query or expose translations" {
            var lookupCount = 0
            val repository = CandidateTranslationRepository {
                lookupCount += 1
                CandidateTranslationEntry("unused", "/unused/")
            }
            val candidate = CandidateProto(text = "中国", comment = "", label = "2")

            val presentation = BilingualCandidatePresenter(repository) { false }.present(candidate)

            presentation.translation shouldBe null
            lookupCount shouldBe 0
        }

        "unmapped candidates keep an empty translation" {
            val repository = CandidateTranslationRepository {
                if (it == "你好") CandidateTranslationEntry("hello", null) else null
            }

            val presentation =
                BilingualCandidatePresenter(repository) { true }
                    .present(CandidateProto(text = "未收录", comment = "original", label = "3"))

            presentation.translation shouldBe null
            presentation.candidate.comment shouldBe "original"
        }

        "phonetic display is opt-in and shares the translation lookup" {
            var lookupCount = 0
            val repository = CandidateTranslationRepository {
                lookupCount += 1
                CandidateTranslationEntry("hello", "/h\u0259\u02c8\u026bo\u028a/")
            }
            val candidate = CandidateProto(text = "你好", comment = "", label = "")

            val hidden = BilingualCandidatePresenter(repository, isPhoneticEnabled = { false }) { true }
                .present(candidate)
            val visible = BilingualCandidatePresenter(repository, isPhoneticEnabled = { true }) { true }
                .present(candidate)

            hidden.phonetic shouldBe null
            hidden.reservePhoneticLine shouldBe false
            visible.phonetic shouldBe "/h\u0259\u02c8\u026bo\u028a/"
            visible.reservePhoneticLine shouldBe true
            lookupCount shouldBe 2
        }

        "enabled translation always reserves its line while missing entries stay empty" {
            val presentation = BilingualCandidatePresenter(CandidateTranslationRepository { null }) { true }
                .present(CandidateProto(text = "未收录", comment = "", label = ""))

            presentation.translation shouldBe null
            presentation.reserveTranslationLine shouldBe true
        }

        "translation typography stays just below candidate size" {
            val textSize = bilingualTranslationTextSize(candidateTextSize = 22f, commentTextSize = 10f)

            textSize shouldBe 19.8f
            bilingualTranslationLineHeight(textSize, configuredHeight = 12) shouldBe 24
        }

        "expanded candidate layout uses a dense three-column grid" {
            UNROLLED_CANDIDATE_COLUMNS shouldBe 3
            UNROLLED_CANDIDATE_MIN_HEIGHT_DP shouldBe 72
            UNROLLED_CANDIDATE_PHONETIC_HEIGHT_DP shouldBe 90
            UNROLLED_CANDIDATE_ACTION_RAIL_WIDTH_DP shouldBe 60
            UNROLLED_CANDIDATE_ACTION_HEIGHT_DP shouldBe 64
            UNROLLED_CANDIDATE_ACTION_GAP_DP shouldBe 6
            UNROLLED_CANDIDATE_START_INDEX shouldBe 0
            CANDIDATE_TRANSLATION_MAX_WIDTH_DP shouldBe 160
        }

        "expanded candidate filtering preserves original Rime indexes" {
            val rareExtensionCharacter = String(Character.toChars(0x2B500))
            val candidates = arrayOf(
                CandidateProto(text = "炼", comment = "", label = ""),
                CandidateProto(text = rareExtensionCharacter, comment = "", label = ""),
                CandidateProto(text = "恋", comment = "", label = ""),
            )

            val visible = candidates.toDisplayableUnrolledCandidates(startIndex = 12)

            visible.map { it.candidate.text } shouldBe listOf("炼", "恋")
            visible.map { it.globalIndex } shouldBe listOf(12, 14)
        }

        "compact candidate targets use honest portrait and landscape ranges" {
            COMPACT_CANDIDATE_PORTRAIT_MIN shouldBe 3
            COMPACT_CANDIDATE_PORTRAIT_MAX shouldBe 5
            COMPACT_CANDIDATE_PORTRAIT_DEFAULT shouldBe 4
            COMPACT_CANDIDATE_LANDSCAPE_MIN shouldBe 5
            COMPACT_CANDIDATE_LANDSCAPE_MAX shouldBe 8
            COMPACT_CANDIDATE_LANDSCAPE_DEFAULT shouldBe 6

            resolveCompactCandidateCount(false, portraitValue = 4, landscapeValue = 6) shouldBe 4
            resolveCompactCandidateCount(true, portraitValue = 4, landscapeValue = 6) shouldBe 6
            resolveCompactCandidateCount(false, portraitValue = 1, landscapeValue = 8) shouldBe 3
            resolveCompactCandidateCount(false, portraitValue = 8, landscapeValue = 8) shouldBe 5
            resolveCompactCandidateCount(true, portraitValue = 4, landscapeValue = 4) shouldBe 5
            resolveCompactCandidateCount(true, portraitValue = 4, landscapeValue = 12) shouldBe 8
        }

        "compact candidates prioritize complete phrases for explicit pinyin syllables" {
            val candidates = arrayOf(
                CandidateProto(text = "你好", comment = "", label = ""),
                CandidateProto(text = "妳好", comment = "", label = ""),
                CandidateProto(text = "你", comment = "", label = ""),
                CandidateProto(text = "拟", comment = "", label = ""),
                CandidateProto(text = "逆号", comment = "", label = ""),
                CandidateProto(text = "拟好", comment = "", label = ""),
                CandidateProto(text = "你号", comment = "", label = ""),
            )

            val visible = candidates.toCompactCandidateItems(maxCount = 6, preedit = "ni hao\u2038")

            visible.map { it.candidate.text } shouldBe listOf("你好", "妳好", "逆号", "拟好", "你号", "你")
            visible.map { it.globalIndex } shouldBe listOf(0, 1, 4, 5, 6, 2)
        }

        "compact phrase priority supports apostrophes and keeps fallback order" {
            val candidates = arrayOf(
                CandidateProto(text = "先", comment = "", label = ""),
                CandidateProto(text = "西安", comment = "", label = ""),
                CandidateProto(text = "现", comment = "", label = ""),
            )

            candidates.toCompactCandidateItems(maxCount = 3, preedit = "xi'an")
                .map { it.candidate.text } shouldBe listOf("西安", "先", "现")
        }

        "compact candidates prioritize fang zi phrases before single characters" {
            val candidates = arrayOf(
                CandidateProto(text = "房子", comment = "", label = ""),
                CandidateProto(text = "方", comment = "", label = ""),
                CandidateProto(text = "坊子", comment = "", label = ""),
                CandidateProto(text = "房", comment = "", label = ""),
            )

            candidates.toCompactCandidateItems(maxCount = 4, preedit = "fang zi")
                .map { it.candidate.text } shouldBe listOf("房子", "坊子", "方", "房")
        }

        "compact candidates keep Rime order for one syllable or uncertain input" {
            val candidates = arrayOf(
                CandidateProto(text = "是", comment = "", label = ""),
                CandidateProto(text = "时", comment = "", label = ""),
                CandidateProto(text = "世界", comment = "", label = ""),
            )

            candidates.toCompactCandidateItems(maxCount = 3, preedit = "shi")
                .map { it.candidate.text } shouldBe listOf("是", "时", "世界")
            candidates.toCompactCandidateItems(maxCount = 3, preedit = "ni-hao")
                .map { it.candidate.text } shouldBe listOf("是", "时", "世界")
        }

        "compact candidate width is driven by the primary row and clamped" {
            COMPACT_CANDIDATE_MIN_WIDTH_DP shouldBe 48
            COMPACT_CANDIDATE_MAX_WIDTH_DP shouldBe 112
            COMPACT_CANDIDATE_HORIZONTAL_PADDING_DP shouldBe 10

            compactCandidateCellWidth(10, minWidth = 48, horizontalPadding = 10, maxWidth = 112) shouldBe 48
            compactCandidateCellWidth(44, minWidth = 48, horizontalPadding = 10, maxWidth = 112) shouldBe 64
            compactCandidateCellWidth(200, minWidth = 48, horizontalPadding = 10, maxWidth = 112) shouldBe 112
        }

        "compact candidate width excludes the branded and expand controls" {
            compactCandidateAvailableWidth(totalWidth = 360, leadingWidth = 48, trailingWidth = 40) shouldBe 272
            compactCandidateAvailableWidth(totalWidth = 80, leadingWidth = 48, trailingWidth = 40) shouldBe 0
        }

        "compact translation reservation does not change the content width boundaries" {
            compactCandidateCellWidth(
                contentWidth = 10,
                minWidth = 48,
                horizontalPadding = 10,
                maxWidth = 112,
                reservedWidth = 88,
            ) shouldBe 88
            compactCandidateCellWidth(
                contentWidth = 100,
                minWidth = 48,
                horizontalPadding = 10,
                maxWidth = 112,
                reservedWidth = 88,
            ) shouldBe 112
        }

        "compact candidate row drops only trailing items whose Chinese minimum cannot fit" {
            val candidates = arrayOf(
                CandidateProto(text = "你好", comment = "", label = ""),
                CandidateProto(text = "妳好", comment = "", label = ""),
                CandidateProto(text = "逆号", comment = "", label = ""),
                CandidateProto(text = "拟好", comment = "", label = ""),
            ).toCompactCandidateItems(maxCount = 4, preedit = "ni hao")

            fitCompactCandidateRow(candidates, targetCount = 4, availableWidth = 190) {
                CompactCandidateWidthBounds(minimum = 64, preferred = 64)
            }
                .map { it.item.candidate.text } shouldBe listOf("你好", "妳好")
            fitCompactCandidateRow(candidates, targetCount = 3, availableWidth = 300) {
                CompactCandidateWidthBounds(minimum = 64, preferred = 64)
            }
                .map { it.item.candidate.text } shouldBe listOf("你好", "妳好", "逆号")
        }

        "compact translation width grows automatically when the target count gets smaller" {
            val candidates = arrayOf(
                CandidateProto(text = "你好", comment = "", label = ""),
                CandidateProto(text = "妳好", comment = "", label = ""),
                CandidateProto(text = "逆号", comment = "", label = ""),
                CandidateProto(text = "拟好", comment = "", label = ""),
                CandidateProto(text = "你号", comment = "", label = ""),
            ).toCompactCandidateItems(maxCount = 5, preedit = "ni hao")
            val translationLimits = CompactTranslationWidthLimits(
                primaryMinimum = 80,
                primaryMaximum = 100,
                secondaryMaximum = 80,
            )
            val widthOf: (UnrolledCandidateItem) -> CompactCandidateWidthBounds = {
                CompactCandidateWidthBounds(minimum = 48, preferred = 48)
            }

            val three = fitCompactCandidateRow(candidates, 3, 320, translationLimits, widthOf)
            val four = fitCompactCandidateRow(candidates, 4, 320, translationLimits, widthOf)
            val five = fitCompactCandidateRow(candidates, 5, 320, translationLimits, widthOf)

            three.size shouldBe 3
            four.size shouldBe 4
            five.size shouldBe 5
            three.first().width shouldBe 100
            four.first().width shouldBe 80
            five.first().width shouldBe 80
            three[1].width shouldBe 80
            (four[1].width > five[1].width) shouldBe true
            (three.sumOf { it.width } / three.size > four.sumOf { it.width } / four.size) shouldBe true
            (four.sumOf { it.width } / four.size > five.sumOf { it.width } / five.size) shouldBe true
        }

        "compact translation pending ready and missing states share preallocated widths" {
            val candidates = arrayOf(
                CandidateProto(text = "鸡", comment = "", label = ""),
                CandidateProto(text = "牛", comment = "", label = ""),
                CandidateProto(text = "扣", comment = "", label = ""),
                CandidateProto(text = "纽", comment = "", label = ""),
            ).toCompactCandidateItems(maxCount = 4)
            val limits = CompactTranslationWidthLimits(80, 100, 80)
            val bounds: (UnrolledCandidateItem) -> CompactCandidateWidthBounds = {
                CompactCandidateWidthBounds(48, 52)
            }

            val pending = fitCompactCandidateRow(candidates, 4, 320, limits, bounds)
            val ready = fitCompactCandidateRow(candidates, 4, 320, limits, bounds)
            val missing = fitCompactCandidateRow(candidates, 4, 320, limits, bounds)

            pending.map { it.width } shouldBe ready.map { it.width }
            ready.map { it.width } shouldBe missing.map { it.width }
        }

        "compact monolingual layout does not reserve translation width" {
            val candidates = arrayOf(
                CandidateProto(text = "你好", comment = "", label = ""),
                CandidateProto(text = "妳好", comment = "", label = ""),
                CandidateProto(text = "逆号", comment = "", label = ""),
            ).toCompactCandidateItems(maxCount = 3)

            fitCompactCandidateRow(candidates, targetCount = 3, availableWidth = 320) {
                CompactCandidateWidthBounds(minimum = 48, preferred = 64)
            }.map { it.width } shouldBe listOf(64, 64, 64)
        }

        "compact candidates skip invisible rare characters and preserve global indexes" {
            val rareExtensionCharacter = String(Character.toChars(0x2B500))
            val candidates = arrayOf(
                CandidateProto(text = "网页", comment = "", label = ""),
                CandidateProto(text = rareExtensionCharacter, comment = "", label = ""),
                CandidateProto(text = "王爷", comment = "", label = ""),
                CandidateProto(text = "王业", comment = "", label = ""),
            )

            val visible = candidates.toCompactCandidateItems(maxCount = 3, preedit = "wang ye")

            visible.map { it.candidate.text } shouldBe listOf("网页", "王爷", "王业")
            visible.map { it.globalIndex } shouldBe listOf(0, 2, 3)
        }
    })
