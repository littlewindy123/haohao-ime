/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.prefs.AppPrefs
import kotlin.math.ceil

private const val TRANSLATION_TEXT_SIZE_RATIO = 0.9f
private const val TRANSLATION_LINE_HEIGHT_RATIO = 1.2f

internal fun bilingualTranslationTextSize(
    candidateTextSize: Float,
    commentTextSize: Float,
): Float = maxOf(commentTextSize, candidateTextSize * TRANSLATION_TEXT_SIZE_RATIO)

internal fun bilingualTranslationLineHeight(
    textSize: Float,
    configuredHeight: Int,
): Int = maxOf(configuredHeight, ceil(textSize * TRANSLATION_LINE_HEIGHT_RATIO).toInt())

internal data class CandidatePresentation(
    val candidate: CandidateProto,
    val translation: String?,
)

internal class BilingualCandidatePresenter(
    private val repository: CandidateTranslationRepository,
    private val isEnabled: () -> Boolean,
) {
    fun present(candidate: CandidateProto): CandidatePresentation {
        val translation = if (isEnabled()) repository.lookup(candidate.text) else null
        return CandidatePresentation(candidate, translation)
    }
}

internal val defaultBilingualCandidatePresenter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    BilingualCandidatePresenter(OfflineCandidateTranslationRepository) {
        AppPrefs.defaultInstance().candidates.bilingualTranslation.getValue()
    }
}
