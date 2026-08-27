// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.candidates.compact.compactCandidateCellBasis
import com.osfans.trime.ime.candidates.compact.resolveCompactCandidateCount
import com.osfans.trime.ime.candidates.compact.toCompactCandidateItems
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

        "compact candidate limits keep portrait and landscape compatibility ranges" {
            resolveCompactCandidateCount(false, portraitValue = 6, landscapeValue = 8) shouldBe 6
            resolveCompactCandidateCount(true, portraitValue = 6, landscapeValue = 8) shouldBe 8
            resolveCompactCandidateCount(false, portraitValue = 1, landscapeValue = 8) shouldBe 3
            resolveCompactCandidateCount(false, portraitValue = 10, landscapeValue = 8) shouldBe 8
            resolveCompactCandidateCount(true, portraitValue = 6, landscapeValue = 2) shouldBe 4
            resolveCompactCandidateCount(true, portraitValue = 6, landscapeValue = 20) shouldBe 12
        }

        "compact bilingual candidates use equal fixed-width cells" {
            compactCandidateCellBasis(3) shouldBe (1f / 3)
            compactCandidateCellBasis(5) shouldBe (1f / 5)
            compactCandidateCellBasis(6) shouldBe (1f / 6)
            compactCandidateCellBasis(8) shouldBe (1f / 8)
        }

        "compact candidates skip invisible rare characters and preserve global indexes" {
            val rareExtensionCharacter = String(Character.toChars(0x2B500))
            val candidates = arrayOf(
                CandidateProto(text = "网页", comment = "", label = ""),
                CandidateProto(text = rareExtensionCharacter, comment = "", label = ""),
                CandidateProto(text = "王爷", comment = "", label = ""),
                CandidateProto(text = "王业", comment = "", label = ""),
            )

            val visible = candidates.toCompactCandidateItems(maxCount = 3)

            visible.map { it.candidate.text } shouldBe listOf("网页", "王爷", "王业")
            visible.map { it.globalIndex } shouldBe listOf(0, 2, 3)
            compactCandidateCellBasis(visible.size) shouldBe (1f / 3)
        }
    })
