package com.osfans.trime.data.footprints

import io.kotest.core.spec.style.StringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LearningNextPolicyTest : StringSpec() {
    init {
        "weakness thresholds and recovery" { weaknessNeedsTwoFailuresAndTwoSuccessesClearIt() }
        "only observed spelling failures count" { unknownRevealedUndoneRepeatedAndOldSpellingDoNotInventFailures() }
        "spelling feedback preserves grading" { spellingFeedbackSeparatesMissingExtraAndReplacementWithoutChangingGrading() }
    }
    private val now = 40 * LEARNING_DAY_MS
    private val word = SavedWordEntity("你好", "hello", source = "offline", createdAt = 1, learning = true)
    private fun event(index: Int, rating: RecallRating, outcome: String? = null) = LearningReviewEvent(
        "event-$index", word.chinese, word.english, now - (10 - index) * 1000, "2026-09-09", rating.name, "review",
        mode = ReviewMode.SPELLING.name, spellingOutcome = outcome,
    )

    private fun weaknessNeedsTwoFailuresAndTwoSuccessesClearIt() {
        val failures = listOf(event(1, RecallRating.FORGOTTEN), event(2, RecallRating.FORGOTTEN))
        assertTrue(weakWords(listOf(word), failures.take(1), WeaknessKind.FORGOTTEN, now).isEmpty())
        assertEquals(2, weakWords(listOf(word), failures, WeaknessKind.FORGOTTEN, now).single().failures)
        assertEquals(1, weakWords(listOf(word), failures + event(3, RecallRating.REMEMBERED), WeaknessKind.FORGOTTEN, now).size)
        assertTrue(weakWords(listOf(word), failures + listOf(event(3, RecallRating.REMEMBERED), event(4, RecallRating.REMEMBERED)), WeaknessKind.FORGOTTEN, now).isEmpty())
    }

    private fun unknownRevealedUndoneRepeatedAndOldSpellingDoNotInventFailures() {
        val base = event(1, RecallRating.FORGOTTEN, SpellingOutcome.WRONG.name)
        val excluded = listOf(base.copy(undone = true), base.copy(kind = "repeat"), base.copy(occurredAt = now - 31 * LEARNING_DAY_MS), base.copy(spellingOutcome = null), base.copy(spellingOutcome = SpellingOutcome.REVEALED.name))
        assertTrue(weakWords(listOf(word), excluded, WeaknessKind.SPELLING, now).isEmpty())
        assertTrue(weakWords(listOf(word.copy(learning = false)), listOf(base, base.copy(token = "another")), WeaknessKind.SPELLING, now).isEmpty())
    }

    private fun spellingFeedbackSeparatesMissingExtraAndReplacementWithoutChangingGrading() {
        assertEquals(listOf(SpellingEditKind.MISSING), spellingEdits("helo", "hello").filter { it.kind != SpellingEditKind.MATCH }.map { it.kind })
        assertEquals(listOf(SpellingEditKind.EXTRA), spellingEdits("helllo", "hello").filter { it.kind != SpellingEditKind.MATCH }.map { it.kind })
        assertEquals(listOf(SpellingEditKind.REPLACE), spellingEdits("hallo", "hello").filter { it.kind != SpellingEditKind.MATCH }.map { it.kind })
        assertTrue(spellingEdits("  HELLO ", "hello").all { it.kind == SpellingEditKind.MATCH })
        assertFalse(spellingMatches("hel-lo", "hello"))
    }
}
