/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal object InputFootprintPolicy {
    fun shouldRecord(
        enabled: Boolean,
        inputType: Int,
        imeOptions: Int,
        hasTranslation: Boolean,
    ): Boolean = enabled && hasTranslation && canRecord(inputType, imeOptions)

    fun canRecord(
        inputType: Int,
        imeOptions: Int,
    ): Boolean {
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation !in TEXT_PASSWORD_VARIATIONS
            InputType.TYPE_CLASS_NUMBER -> variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> true
        }
    }

    fun shouldDisablePersonalizedLearning(
        inputType: Int,
        imeOptions: Int,
    ): Boolean = !canRecord(inputType, imeOptions)

    private val TEXT_PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
}
