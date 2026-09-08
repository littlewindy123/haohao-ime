package com.osfans.trime.data.footprints

import org.junit.Assert.*
import io.kotest.core.spec.style.StringSpec
import java.util.TimeZone

class LearningProgressPolicyTest : StringSpec({
    "emptyOrRemovedGoalsNeverCheckIn" {
        assertFalse(taskIsComplete(emptyList(), emptySet()))
        assertFalse(taskIsComplete(listOf(DailyLearningTarget("你好", "hello", true, excluded = true)), setOf("你好" to "hello")))
    }
    "allActiveGoalsAndRetriesAreRequired" {
        val word = DailyLearningTarget("你好", "hello", true)
        assertFalse(taskIsComplete(listOf(word), emptySet()))
        assertFalse(taskIsComplete(listOf(word.copy(pendingRepeat = true)), setOf(word.key)))
        assertTrue(taskIsComplete(listOf(word), setOf(word.key)))
        assertTrue(taskIsComplete(listOf(word, DailyLearningTarget("再见", "bye", false, excluded = true)), setOf(word.key)))
    }
    "streakIncludesYesterdayUntilTodayIsDone" {
        val days = setOf("2026-09-07", "2026-09-08")
        assertEquals(2, learningStreak(days, "2026-09-09"))
        assertEquals(2, learningStreak(days, "2026-09-08"))
        assertEquals(0, learningStreak(days, "2026-09-10"))
        assertEquals(3, learningStreak(setOf("2024-02-28", "2024-02-29", "2024-03-01"), "2024-03-01"))
    }
    "localDatesRespectZoneWithoutChangingStoredKeys" {
        val now = 0L
        val utc = learningDay(now, TimeZone.getTimeZone("UTC"))
        val west = learningDay(now, TimeZone.getTimeZone("America/Los_Angeles"))
        assertNotEquals(utc, west)
        assertEquals(utc, learningDay(now, TimeZone.getTimeZone("UTC")))
    }
})
