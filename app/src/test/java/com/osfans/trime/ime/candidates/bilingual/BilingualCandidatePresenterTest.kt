// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.candidates.unrolled.toDisplayableUnrolledCandidates
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BilingualCandidatePresenterTest :
    StringSpec({
        "enabled presenter adds a translation without changing the Rime candidate" {
            val repository = CandidateTranslationRepository { if (it == "你好") "hello" else null }
            val candidate = CandidateProto(text = "你好", comment = "nǐ hǎo", label = "1")

            val presentation = BilingualCandidatePresenter(repository) { true }.present(candidate)

            presentation.candidate shouldBe candidate
            presentation.translation shouldBe "hello"
            candidate.comment shouldBe "nǐ hǎo"
            candidate.label shouldBe "1"
        }

        "disabled presenter does not query or expose translations" {
            var lookupCount = 0
            val repository = CandidateTranslationRepository {
                lookupCount += 1
                "unused"
            }
            val candidate = CandidateProto(text = "中国", comment = "", label = "2")

            val presentation = BilingualCandidatePresenter(repository) { false }.present(candidate)

            presentation.translation shouldBe null
            lookupCount shouldBe 0
        }

        "unmapped candidates keep an empty translation" {
            val repository = CandidateTranslationRepository { if (it == "你好") "hello" else null }

            val presentation =
                BilingualCandidatePresenter(repository) { true }
                    .present(CandidateProto(text = "未收录", comment = "original", label = "3"))

            presentation.translation shouldBe null
            presentation.candidate.comment shouldBe "original"
        }

        "translation typography stays just below candidate size" {
            val textSize = bilingualTranslationTextSize(candidateTextSize = 22f, commentTextSize = 10f)

            textSize shouldBe 19.8f
            bilingualTranslationLineHeight(textSize, configuredHeight = 12) shouldBe 24
        }

        "expanded candidate layout uses a dense three-column grid" {
            UNROLLED_CANDIDATE_COLUMNS shouldBe 3
            UNROLLED_CANDIDATE_MIN_HEIGHT_DP shouldBe 72
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
    })
