// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.compact

import com.osfans.trime.data.translation.SentenceCandidateState
import com.osfans.trime.data.translation.SentenceCandidateStatus

/** Normal loading is silent; issues remain discoverable through a non-text control. */
internal fun SentenceCandidateStatus.isTranslationIssue(): Boolean = when (this) {
    SentenceCandidateStatus.UNAVAILABLE, SentenceCandidateStatus.TOO_LONG, SentenceCandidateStatus.FAILED -> true
    else -> false
}

internal fun sentencePreviewText(state: SentenceCandidateState): String = state.translation.orEmpty()
