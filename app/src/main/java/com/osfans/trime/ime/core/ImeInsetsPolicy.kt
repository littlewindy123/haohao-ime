// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

/** Window-relative geometry. An absent/unmeasured panel must never claim the whole screen. */
internal object ImeInsetsPolicy {
    fun contentTop(
        windowHeight: Int,
        inputVisible: Boolean,
        measuredTop: Int?,
        measuredHeight: Int,
        fallbackHeight: Int,
    ): Int {
        val height = windowHeight.coerceAtLeast(0)
        if (!inputVisible || height <= 1) return height
        if (
            measuredTop != null && measuredTop in 1 until height &&
            measuredHeight > 0 && measuredHeight <= height - measuredTop
        ) {
            return measuredTop
        }
        // A cached expanded panel or old display size must not become a full-screen fallback.
        return height - fallbackHeight.coerceIn(1, height / 2)
    }
}
