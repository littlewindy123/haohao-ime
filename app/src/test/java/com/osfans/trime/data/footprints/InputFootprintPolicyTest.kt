/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class InputFootprintPolicyTest :
    FunSpec({
        test("ordinary text fields allow footprint recording") {
            InputFootprintPolicy.canRecord(InputType.TYPE_CLASS_TEXT, 0) shouldBe true
        }

        test("all password field variants reject footprint recording") {
            listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ).forEach { inputType ->
                InputFootprintPolicy.canRecord(inputType, 0) shouldBe false
            }
        }

        test("no personalized learning flag rejects footprint recording") {
            InputFootprintPolicy.canRecord(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ) shouldBe false
        }

        test("Rime learning uses the same sensitive editor boundary") {
            InputFootprintPolicy.shouldDisablePersonalizedLearning(
                InputType.TYPE_CLASS_TEXT,
                0,
            ) shouldBe false
            InputFootprintPolicy.shouldDisablePersonalizedLearning(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            ) shouldBe true
            InputFootprintPolicy.shouldDisablePersonalizedLearning(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ) shouldBe true
        }

        test("HaoHao native translator skips memory for protected editors") {
            val nativeSource = File("src/main/jni/librime_jni/rime_jni.cc").readText()
            nativeSource.contains("_haohao_no_personalized_learning") shouldBe true
            nativeSource.contains("class HaoHaoScriptTranslator") shouldBe true
            nativeSource.contains("ScriptTranslator::Memorize(commit_entry)") shouldBe true
        }

        test("disabled history or missing translation rejects recording") {
            InputFootprintPolicy.shouldRecord(
                enabled = false,
                inputType = InputType.TYPE_CLASS_TEXT,
                imeOptions = 0,
                hasTranslation = true,
            ) shouldBe false
            InputFootprintPolicy.shouldRecord(
                enabled = true,
                inputType = InputType.TYPE_CLASS_TEXT,
                imeOptions = 0,
                hasTranslation = false,
            ) shouldBe false
        }
    })
