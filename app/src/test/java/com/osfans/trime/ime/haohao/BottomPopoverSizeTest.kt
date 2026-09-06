/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.haohao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomPopoverSizeTest {
    @Test
    fun popoverFitsBetweenCandidateTopAndExistingBottomActions() {
        for (width in listOf(240, 320, 369, 800)) {
            for (height in listOf(120, 180, 300, 400)) {
                val result = bottomPopoverSize(width, height, 40, 8, 336, 320)
                assertTrue(result.width <= width - 16)
                assertTrue(result.height + 40 + 16 <= height)
                assertTrue(result.width > 0 && result.height > 0)
            }
        }
    }

    @Test
    fun unmeasuredOrTooShortKeyboardDoesNotCreateANegativeWindow() {
        assertEquals(BottomPopoverSize(0, 0), bottomPopoverSize(0, 0, 40, 8, 336, 320))
        assertEquals(0, bottomPopoverSize(320, 40, 40, 8, 336, 320).height)
    }
}
