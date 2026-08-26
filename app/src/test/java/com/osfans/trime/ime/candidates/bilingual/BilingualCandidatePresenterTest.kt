// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.StringReader

class BilingualCandidatePresenterTest :
    StringSpec({
        "enabled presenter adds a translation without changing the Rime candidate" {
            val repository =
                TsvCandidateTranslationRepository.load {
                    StringReader("你好\thello\n")
                }
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
            val repository =
                TsvCandidateTranslationRepository.load {
                    StringReader("你好\thello\n")
                }

            val presentation =
                BilingualCandidatePresenter(repository) { true }
                    .present(CandidateProto(text = "未收录", comment = "original", label = "3"))

            presentation.translation shouldBe null
            presentation.candidate.comment shouldBe "original"
        }

        "TSV loader skips comments blank lines and malformed entries" {
            val repository =
                TsvCandidateTranslationRepository.load {
                    StringReader(
                        "# demo dictionary\n" +
                            "\n" +
                            "malformed\n" +
                            "你好\thello\n" +
                            "中国\tChina\n" +
                            "\tmissing source\n" +
                            "缺少译文\t\n",
                    )
                }

            repository.lookup("你好") shouldBe "hello"
            repository.lookup("中国") shouldBe "China"
            repository.lookup("malformed") shouldBe null
            repository.lookup("") shouldBe null
            repository.lookup("缺少译文") shouldBe null
        }

        "TSV loader uses the last value for duplicate entries" {
            val repository =
                TsvCandidateTranslationRepository.load {
                    StringReader("中国\tChina\n中国\tthe Middle Kingdom\n")
                }

            repository.lookup("中国") shouldBe "the Middle Kingdom"
        }

        "TSV loader falls back to an empty repository when reading fails" {
            val repository =
                TsvCandidateTranslationRepository.load {
                    error("boom")
                }

            repository.lookup("你好") shouldBe null
        }
    })
