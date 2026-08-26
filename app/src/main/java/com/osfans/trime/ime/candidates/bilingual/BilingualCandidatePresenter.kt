/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.prefs.AppPrefs

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
    BilingualCandidatePresenter(DemoCandidateTranslationRepository) {
        AppPrefs.defaultInstance().candidates.bilingualTranslation.getValue()
    }
}
