/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.view.KeyEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class HaoHaoTranslationDraftTest :
    StringSpec({
        "translation leaves delete and enter with the active pinyin composition" {
            for (key in listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER)) {
                handlesTranslationDraftKey(true, true, key) shouldBe false
                handlesTranslationDraftKey(true, false, key) shouldBe true
                handlesTranslationDraftKey(false, false, key) shouldBe false
            }
            handlesTranslationDraftKey(true, true, KeyEvent.KEYCODE_BACK) shouldBe true
            handlesTranslationDraftKey(true, false, KeyEvent.KEYCODE_A) shouldBe false
        }

        "translation preview retains the complete English sentence without duplicating Chinese" {
            val english = "I love you, and I hope we can spend more time together tomorrow. ".repeat(6)
            val state = HaoHaoTranslationState(true, "我爱你", english, HaoHaoTranslationStatus.READY)
            translationPreviewText(state) shouldBe english
            translationPreviewText(state.copy(status = HaoHaoTranslationStatus.WAITING, translation = null)) shouldBe "我爱你"
            translationPreviewText(HaoHaoTranslationState(active = true)) shouldBe "Chinese → English"
            HAOHAO_TRANSLATION_BAR_HEIGHT_DP shouldBe 48
        }
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
