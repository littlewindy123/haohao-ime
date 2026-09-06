// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.unrolled

internal fun sentenceCandidateSpanSize(
    sentencePriority: Boolean,
    position: Int,
    sourceWidth: Int,
    availableWidth: Int,
    columns: Int,
): Int {
    val spanCount = columns.coerceAtLeast(1)
    return if (sentencePriority && position == 0 && availableWidth > 0 && sourceWidth > availableWidth / spanCount) spanCount else 1
}
