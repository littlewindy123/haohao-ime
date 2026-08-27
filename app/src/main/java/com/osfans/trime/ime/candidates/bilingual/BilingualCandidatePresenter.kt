/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.dependency.InputDependencyManager
import org.kodein.di.instance
import kotlin.math.ceil

private const val TRANSLATION_TEXT_SIZE_RATIO = 0.9f
private const val TRANSLATION_LINE_HEIGHT_RATIO = 1.2f
private const val PHONETIC_TEXT_SIZE_RATIO = 0.72f
private const val PHONETIC_LINE_HEIGHT_RATIO = 1.2f
internal const val UNROLLED_CANDIDATE_COLUMNS = 3
internal const val UNROLLED_CANDIDATE_MIN_HEIGHT_DP = 72
internal const val UNROLLED_CANDIDATE_PHONETIC_HEIGHT_DP = 90
internal const val UNROLLED_CANDIDATE_ACTION_RAIL_WIDTH_DP = 60
internal const val UNROLLED_CANDIDATE_ACTION_HEIGHT_DP = 64
internal const val UNROLLED_CANDIDATE_ACTION_GAP_DP = 6
internal const val UNROLLED_CANDIDATE_START_INDEX = 0
internal const val CANDIDATE_TRANSLATION_MAX_WIDTH_DP = 160

internal fun bilingualTranslationTextSize(
    candidateTextSize: Float,
    commentTextSize: Float,
): Float = maxOf(commentTextSize, candidateTextSize * TRANSLATION_TEXT_SIZE_RATIO)

internal fun bilingualTranslationLineHeight(
    textSize: Float,
    configuredHeight: Int,
): Int = maxOf(configuredHeight, ceil(textSize * TRANSLATION_LINE_HEIGHT_RATIO).toInt())

internal fun bilingualPhoneticTextSize(candidateTextSize: Float): Float = candidateTextSize * PHONETIC_TEXT_SIZE_RATIO

internal fun bilingualPhoneticLineHeight(textSize: Float): Int = ceil(textSize * PHONETIC_LINE_HEIGHT_RATIO).toInt()

internal data class CandidatePresentation(
    val candidate: CandidateProto,
    val translation: String?,
    val phonetic: String?,
    val revealState: CandidateTranslationRevealState,
    val reserveTranslationLine: Boolean,
    val reservePhoneticLine: Boolean,
)

internal class BilingualCandidatePresenter(
    private val repository: CandidateTranslationRepository,
    private val revealState: () -> CandidateTranslationRevealState = {
        CandidateTranslationRevealState.READY
    },
    private val isPhoneticEnabled: () -> Boolean = { false },
    private val isEnabled: () -> Boolean,
) {
    fun present(candidate: CandidateProto): CandidatePresentation {
        val translationEnabled = isEnabled()
        val phoneticEnabled = translationEnabled && isPhoneticEnabled()
        val state = if (translationEnabled) revealState() else CandidateTranslationRevealState.HIDDEN
        val entry =
            if (state == CandidateTranslationRevealState.READY) {
                repository.lookup(candidate.text)
            } else {
                null
            }
        return CandidatePresentation(
            candidate = candidate,
            translation = entry?.translation,
            phonetic = entry?.phonetic.takeIf { phoneticEnabled },
            revealState = state,
            reserveTranslationLine = translationEnabled,
            reservePhoneticLine = phoneticEnabled,
        )
    }
}

private fun currentTranslationRevealState(): CandidateTranslationRevealState {
    val controller: CandidateTranslationRevealController by
        InputDependencyManager.getInstance().di.instance()
    return controller.state
}

internal val defaultBilingualCandidatePresenter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    BilingualCandidatePresenter(
        repository = OfflineCandidateTranslationRepository,
        isEnabled = { AppPrefs.defaultInstance().candidates.bilingualTranslation.getValue() },
        isPhoneticEnabled = { AppPrefs.defaultInstance().candidates.bilingualPhonetic.getValue() },
        revealState = ::currentTranslationRevealState,
    )
}
