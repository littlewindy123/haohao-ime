/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum

enum class CompactCandidateMode(
    override val stringRes: Int,
) : PreferenceDelegateEnum {
    NEVER_FILL(R.string.horizontal_candidate_never_fill),
    AUTO_FILL(R.string.horizontal_candidate_auto_fill),
    ALWAYS_FILL(R.string.horizontal_candidate_always_fill),
}

internal const val COMPACT_ADAPTIVE_TRANSLATION_MAX_WIDTH_DP = 160

enum class CompactTranslationMode(
    override val stringRes: Int,
) : PreferenceDelegateEnum {
    WORD(R.string.compact_translation_mode_word),
    ADAPTIVE(R.string.compact_translation_mode_adaptive),
}

internal val DEFAULT_COMPACT_TRANSLATION_MODE = CompactTranslationMode.WORD

internal data class CompactTranslationHint(
    val text: String,
    val requiredWidth: Int,
)

private val COMPACT_ENGLISH_WORD = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")

internal fun compactTranslationHint(
    mode: CompactTranslationMode,
    translation: String?,
    requiredWidth: Int,
    adaptiveMaximumWidth: Int,
): CompactTranslationHint? {
    val text = translation?.takeIf(String::isNotBlank) ?: return null
    if (mode == CompactTranslationMode.WORD && !COMPACT_ENGLISH_WORD.matches(text)) return null
    if (mode == CompactTranslationMode.ADAPTIVE && requiredWidth > adaptiveMaximumWidth) return null
    return CompactTranslationHint(text, requiredWidth.coerceAtLeast(1))
}

internal fun compactTranslationTextForCell(
    hint: CompactTranslationHint?,
    cellWidth: Int,
): String? = hint?.text?.takeIf { hint.requiredWidth <= cellWidth }
