package com.osfans.trime.data.footprints

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LearningProgressStoreTest {
    @Test fun spellingPersistsChecksGuardsAndUndoAcrossStoreRecreation() = test { db, store ->
        seed(store, 1)
        store.setReviewMode(ReviewMode.SPELLING)
        val session = store.startSession(false, now = noon)
        val token = session.cards.single().token
        store.saveSpellingDraft(token, "helo")
        val reopened = WordLearningStore(db)
        assertEquals("helo", reopened.session()!!.spellingDraft)
        assertFalse(reopened.checkSpelling(token, "helo")!!.spellingCorrect!!)
        reopened.answer(token, RecallRating.REMEMBERED, noon)
        assertEquals(0, reopened.find("测试0", "hello")!!.reviewCount)
        reopened.answer(token, RecallRating.UNCERTAIN, noon)
        val restored = reopened.undoAnswer(token)!!
        assertEquals("helo", restored.spellingDraft)
        assertEquals(false, restored.spellingCorrect)
        assertEquals(0, reopened.progress.events().size)
        assertNotEquals(token, restored.cards.single().token)
        assertEquals(ReviewMode.SPELLING, reopened.settings().selectedMode())
    }

    @Test fun modeChangesApplyNextRoundAndSpellingCannotLeakAcrossCards() = test { _, store ->
        seed(store, 2)
        store.setReviewMode(ReviewMode.SPELLING)
        val first = store.startSession(false, now = noon)
        store.setReviewMode(ReviewMode.MIXED)
        assertEquals(ReviewMode.SPELLING, store.startSession(false, now = noon).currentMode)
        val token = first.cards.first().token
        assertTrue(store.checkSpelling(token, "HELLO")!!.spellingCorrect!!)
        val next = store.answer(token, RecallRating.REMEMBERED, noon)!!
        assertFalse(next.answerVisible)
        assertEquals("", next.spellingDraft)
        assertNull(next.spellingCorrect)
        store.saveSpellingDraft(token, "stale callback")
        assertEquals("", store.session()!!.spellingDraft)
        val second = next.cards.first().token
        store.reveal(second)
        store.answer(second, RecallRating.UNCERTAIN, noon)
        assertEquals(ReviewMode.CHINESE, store.startSession(false, now = noon + LEARNING_DAY_MS).currentMode)
    }

    @Test fun mixedModeRotatesEvenForOneWordAndRetryKeepsItsDirection() = test { _, store ->
        seed(store, 1)
        store.setReviewMode(ReviewMode.MIXED)
        assertEquals(ReviewMode.ENGLISH, store.startSession(false, now = noon).currentMode)
        answer(store, noon, RecallRating.FORGOTTEN)
        assertEquals(ReviewMode.ENGLISH, store.session()!!.currentMode)
        answer(store, noon)
        assertEquals(ReviewMode.CHINESE, store.startSession(false, now = noon + LEARNING_DAY_MS).currentMode)
        answer(store, noon + LEARNING_DAY_MS)
        assertEquals(ReviewMode.SPELLING, store.startSession(false, now = noon + 4 * LEARNING_DAY_MS).currentMode)
    }
    private val noon get() = Calendar.getInstance().apply {
        set(2026, 8, 8, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun test(block: suspend (InputFootprintDatabase, WordLearningStore) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), InputFootprintDatabase::class.java).build()
        try {
            block(db, WordLearningStore(db))
        } finally {
            db.close()
        }
    }
    private suspend fun seed(store: WordLearningStore, count: Int = 3) {
        listOf("hello", "learn", "friend").take(count).forEachIndexed { i, word ->
            store.saveMeaning("测试$i", word, null, "offline", learning = true, now = noon - 100 + i)
        }
    }
    private suspend fun answer(store: WordLearningStore, now: Long, rating: RecallRating = RecallRating.REMEMBERED): String {
        val card = store.session()!!.cards.first()
        store.reveal(card.token)
        store.answer(card.token, rating, now)
        return card.token
    }

    @Test fun disabledPlanCollectsFactsButDoesNotCheckIn() = test { _, store ->
        seed(store, 1)
        store.startSession(false, now = noon)
        answer(store, noon)
        val data = store.progress.dashboard(noon)
        assertNull(data.task)
        assertEquals(1, data.today.fresh)
        assertEquals(0, data.checkins)
    }

    @Test fun frozenGoalsNewWordsAndSettingsDoNotIncreaseTarget() = test { _, store ->
        seed(store)
        store.saveSettings(true, 1, 10, false)
        assertEquals(1, store.startSession(true, now = noon).cards.size)
        store.saveSettings(true, 3, 10, false)
        answer(store, noon)
        assertTrue(store.progress.dashboard(noon).task!!.completed)
        assertEquals(1, store.progress.dashboard(noon).total)
        assertTrue(store.startSession(true, now = noon).cards.isEmpty())
        assertEquals(2, store.startSession(true, extra = true, now = noon).cards.size)
        answer(store, noon)
        assertEquals(1, store.progress.dashboard(noon).total)
    }

    @Test fun quickSessionsContributeAndDoubleTapIsIdempotent() = test { db, store ->
        seed(store, 1)
        store.saveSettings(true, 5, 10, false)
        val card = store.startSession(false, now = noon).cards.single()
        store.answer(card.token, RecallRating.REMEMBERED, noon)
        assertEquals(0, store.progress.dashboard(noon).today.total)
        store.reveal(card.token)
        store.answer(card.token, RecallRating.REMEMBERED, noon)
        store.answer(card.token, RecallRating.REMEMBERED, noon)
        assertEquals(1, db.learningProgressDao().events().size)
        assertTrue(store.progress.dashboard(noon).task!!.completed)
    }

    @Test fun retryBlocksCheckInAndUndoRetractsIt() = test { _, store ->
        seed(store, 1)
        store.saveSettings(true, 5, 10, false)
        store.startSession(true, now = noon)
        answer(store, noon, RecallRating.FORGOTTEN)
        assertEquals(1, store.progress.dashboard(noon).today.total)
        assertEquals(0, store.progress.dashboard(noon).done)
        assertFalse(store.progress.dashboard(noon).task!!.completed)
        val repeated = answer(store, noon + 1)
        assertTrue(store.progress.dashboard(noon).task!!.completed)
        store.undoAnswer(repeated)
        assertFalse(store.progress.dashboard(noon).task!!.completed)
        assertEquals(1, store.progress.dashboard(noon).ratings.values.sum())
        val again = answer(store, noon + 2)
        assertTrue(store.progress.dashboard(noon).task!!.completed)
        store.undoAnswer(again)
        assertEquals(1, store.progress.dashboard(noon).today.total)
    }

    @Test fun undoInitialFeedbackRemovesDayAndCompletion() = test { _, store ->
        seed(store, 1)
        store.saveSettings(true, 5, 10, false)
        store.startSession(true, now = noon)
        val token = answer(store, noon)
        store.undoAnswer(token)
        val data = store.progress.dashboard(noon)
        assertEquals(0, data.today.total)
        assertFalse(data.task!!.completed)
        assertTrue(data.ratings.isEmpty())
    }

    @Test fun removingAllTasksCannotEarnCheckIn() = test { _, store ->
        seed(store, 1)
        store.saveSettings(true, 5, 10, false)
        store.startSession(true, now = noon)
        store.saveMeaning("测试0", "hello", null, "offline", learning = false, now = noon)
        assertEquals(0, store.progress.dashboard(noon).total)
        assertFalse(store.progress.dashboard(noon).task!!.completed)
    }

    @Test fun removingOneOutstandingGoalPreservesEarnedCompletionAndReaddingDoesNotInflateGoal() = test { _, store ->
        seed(store, 2)
        store.saveSettings(true, 5, 10, false)
        store.startSession(true, now = noon)
        answer(store, noon)
        store.saveMeaning("测试1", "learn", null, "offline", learning = false, now = noon)
        assertTrue(store.progress.dashboard(noon).task!!.completed)
        store.saveMeaning("测试1", "learn", null, "offline", learning = true, now = noon)
        assertEquals(1, store.progress.dashboard(noon).total)
    }

    @Test fun midnightCarriesUnansweredCardsAndDoesNotCountSkippedWords() = test { _, store ->
        seed(store, 2)
        store.saveSettings(true, 5, 10, false)
        store.startSession(true, now = noon)
        answer(store, noon)
        answer(store, noon + LEARNING_DAY_MS)
        val data = store.progress.dashboard(noon + LEARNING_DAY_MS)
        // Yesterday's answered word is due today and must also be reviewed to finish today's goal.
        assertEquals(2, data.total)
        assertFalse(data.task!!.completed)
        store.startSession(true, now = noon + LEARNING_DAY_MS)
        answer(store, noon + LEARNING_DAY_MS)
        assertTrue(store.progress.dashboard(noon + LEARNING_DAY_MS).task!!.completed)
        assertFalse(store.progress.dashboard(noon).task!!.completed)
    }

    @Test fun midnightCarriesPendingRetryWithoutBackdatingCompletion() = test { _, store ->
        seed(store, 1)
        store.saveSettings(true, 5, 10, false)
        store.startSession(true, now = noon)
        answer(store, noon, RecallRating.FORGOTTEN)
        answer(store, noon + LEARNING_DAY_MS)
        val data = store.progress.dashboard(noon + LEARNING_DAY_MS)
        assertTrue(data.task!!.completed)
        assertEquals(1, data.today.reviewed)
        assertFalse(data.days.first { it.day == learningDay(noon) }.completed)
    }

    @Test fun timezoneChangesDoNotRewriteEventDate() = test { db, store ->
        seed(store, 1)
        store.startSession(false, now = noon)
        val token = answer(store, noon)
        val old = db.learningProgressDao().event(token)!!.day
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals(old, db.learningProgressDao().event(token)!!.day)
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test fun historySurvivesMonthAndClearLeavesSentencesAlone() = test { db, store ->
        seed(store, 1)
        store.startSession(false, now = noon)
        answer(store, noon)
        store.startSession(false, now = noon + 90 * LEARNING_DAY_MS)
        assertEquals(1, store.progress.dashboard(noon).studyDays)
        val sentences = SentenceStore(db)
        assertTrue(sentences.save("学习", "Learn every day.", "test", true))
        store.clearAll()
        assertEquals(0, store.progress.dashboard(noon).studyDays)
        assertEquals(1, sentences.rows.first().size)
    }

    @Test fun zeroAvailableTasksDoNotCheckInAndLegacyDaysAreNotInventedEvents() = test { db, store ->
        store.saveSettings(true, 5, 10, false)
        store.startSession(true, now = noon)
        assertFalse(store.progress.dashboard(noon).task!!.completed)
        db.wordLearningDao().recordDay(WordReviewDayEntity("2026-09-01", "旧词", "old", true))
        assertEquals(1, store.progress.dashboard(noon).studyDays)
        assertEquals(0, store.progress.dashboard(noon).checkins)
        assertTrue(store.progress.dashboard(noon).ratings.isEmpty())
    }

    @Test fun versionFourMigrationPreservesWordsSessionsSentencesAndLegacyDays() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "progress-v4-${java.util.UUID.randomUUID()}.db")
        val fixture = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath).build()
        val store = WordLearningStore(fixture)
        seed(store, 1)
        store.startSession(false, now = noon)
        val token = answer(store, noon)
        SentenceStore(fixture).save("学习", "Learn every day.", "test", true)
        val word = store.find("测试0", "hello")
        val session = store.session()
        fixture.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, 0).use {
            it.execSQL("ALTER TABLE word_learning_state DROP COLUMN reviewMode")
            it.execSQL("DROP TABLE learning_practice_state")
            it.execSQL("DROP TABLE wordbook_members")
            it.execSQL("DROP TABLE wordbooks")
            it.execSQL("DROP TABLE learning_practice_events")
            it.execSQL("DROP TABLE learning_tasks")
            it.execSQL("DROP TABLE learning_events")
            it.version = 4
        }
        val upgraded = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath)
            .addMigrations(LEARNING_PROGRESS_MIGRATION, LEARNING_MODES_MIGRATION, LEARNING_PRACTICE_MIGRATION, WORDBOOK_MIGRATION).build()
        try {
            val after = WordLearningStore(upgraded)
            assertEquals(word, after.find("测试0", "hello"))
            assertEquals(session, after.session())
            assertEquals(token, after.undoToken())
            assertEquals(1, SentenceStore(upgraded).rows.first().size)
            assertEquals(1, after.progress.dashboard(noon).studyDays)
            assertEquals(0, after.progress.dashboard(noon).checkins)
            assertTrue(after.progress.dashboard(noon).ratings.isEmpty())
            after.undoAnswer(token)
            assertEquals(0, after.find("测试0", "hello")!!.reviewCount)
        } finally {
            upgraded.close()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }
}
