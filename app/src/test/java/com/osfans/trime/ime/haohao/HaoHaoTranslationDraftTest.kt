/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class HaoHaoTranslationDraftTest :
    StringSpec({
        "draft limit counts unicode code points" {
            appendTranslationDraft("你好", "😀", maximumCodePoints = 3) shouldBe "你好😀"
            appendTranslationDraft("你好😀", "啊", maximumCodePoints = 3).shouldBeNull()
        }

        "delete removes a whole supplementary code point" {
            removeLastTranslationCodePoint("你好😀") shouldBe "你好"
            removeLastTranslationCodePoint("") shouldBe ""
        }

        "new request invalidates previous result" {
            val generation = TranslationRequestGeneration()
            val first = generation.next()
            val second = generation.next()

            generation.isCurrent(first) shouldBe false
            generation.isCurrent(second) shouldBe true

            generation.invalidate()
            generation.isCurrent(second) shouldBe false
        }
    })
