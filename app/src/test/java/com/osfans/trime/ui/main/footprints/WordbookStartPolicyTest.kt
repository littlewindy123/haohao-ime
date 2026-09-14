// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.footprints

import org.junit.Assert.assertEquals
import io.kotest.core.spec.style.StringSpec

class WordbookStartPolicyTest : StringSpec({
    "browsing is the safe default and selection is explicit" {
        assertEquals(WordbookViewMode.BROWSE, wordbookViewMode(null))
        assertEquals(WordbookViewMode.BROWSE, wordbookViewMode("future-mode"))
        WordbookViewMode.entries.forEach { assertEquals(it, wordbookViewMode(it.name)) }
        assertEquals(WordbookStartAction.SELECT, wordbookStartAction(false, 0))
        assertEquals(WordbookStartAction.START, wordbookStartAction(false, 7))
        assertEquals(WordbookStartAction.RESUME, wordbookStartAction(true, 7))
    }

    "disabled plan always requires confirmation even with an old session" {
        for (hasSession in listOf(false, true)) {
            assertEquals(DailyStartDestination.SETTINGS, dailyStartDestination(false, hasSession, true, 3, 5))
        }
    }

    "unfinished session resumes without replanning" {
        assertEquals(DailyStartDestination.REVIEW, dailyStartDestination(true, true, true, 0, 0))
    }

    "fixed task never grows because more words were added" {
        assertEquals(DailyStartDestination.HOME, dailyStartDestination(true, false, true, 0, 50))
        assertEquals(DailyStartDestination.REVIEW, dailyStartDestination(true, false, true, 2, 50))
    }

    "first start needs actual available words" {
        assertEquals(DailyStartDestination.HOME, dailyStartDestination(true, false, false, 0, 0))
        assertEquals(DailyStartDestination.REVIEW, dailyStartDestination(true, false, false, 0, 5))
    }
})
