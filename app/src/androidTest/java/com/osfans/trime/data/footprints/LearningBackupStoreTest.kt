package com.osfans.trime.data.footprints

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class LearningBackupStoreTest {
    private val now = 1_788_998_400_000L
    private fun test(block: suspend (InputFootprintDatabase, InputFootprintStore, LearningBackupStore, File) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, InputFootprintDatabase::class.java).build()
        val store = InputFootprintStore(db)
        val folder = File(context.cacheDir, "learning-backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            block(db, store, LearningBackupStore(store, File(folder, "rollback.json")), folder)
        } finally {
            db.close()
            folder.deleteRecursively()
        }
    }
    private suspend fun seed(store: InputFootprintStore) {
        store.record("历史", now)
        store.learning.saveMeaning("你好", "hello", null, "offline", favorite = true, learning = true, now = now)
        store.sentences.save("你好呀", "Hello there!", "test", true)
    }
    private suspend fun failures(db: InputFootprintDatabase) {
        repeat(2) { i -> db.learningProgressDao().record(LearningReviewEvent("failure-$i", "你好", "hello", now - 1000 + i, learningDay(now), RecallRating.FORGOTTEN.name, "review", mode = ReviewMode.SPELLING.name, spellingOutcome = SpellingOutcome.WRONG.name)) }
    }

    @Test fun roundTripPreservesDataAndUndoWhileInvalidatingOldCallbacks() = test { _, store, backup, _ ->
        seed(store)
        store.sentences.setAutomatic(true)
        val session = store.learning.startSession(false, now = now)
        val token = session.cards.first().token
        store.learning.reveal(token)
        store.learning.answer(token, RecallRating.REMEMBERED, now)
        val data = backup.snapshot(now)
        val bytes = LearningBackupCodec.encode(data)
        assertEquals(data, LearningBackupCodec.read(bytes.inputStream()))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("input_footprints"))
        store.learning.saveMeaning("再见", "goodbye", null, "offline", favorite = true, now = now)
        backup.restore(data, false)
        assertEquals(data.words, store.learning.savedWords())
        assertNotNull(store.find("历史"))
        assertTrue(store.sentences.automatic())
        assertTrue(backup.hasRollback())
        val renewed = store.learning.undoToken()!!
        assertNotEquals(token, renewed)
        store.learning.undoAnswer(token)
        assertEquals(1, store.learning.find("你好", "hello")!!.reviewCount)
        store.learning.undoAnswer(renewed)
        assertEquals(0, store.learning.find("你好", "hello")!!.reviewCount)
        backup.rollBack()
        assertNotNull(store.learning.find("再见", "goodbye"))
        assertFalse(backup.hasRollback())
    }

    @Test fun mergeIsIdempotentAndNeverImportsProgressOrOverwritesDuplicates() = test { _, store, backup, _ ->
        seed(store)
        val before = backup.snapshot(now)
        val altered = before.words.single().copy(stage = 5, reviewCount = 99)
        val added = altered.copy(chinese = "世界", english = "world", displayEnglish = "World")
        val imported = before.copy(words = listOf(altered, added))
        backup.restore(imported, true)
        backup.restore(imported, true)
        assertEquals(before.words.single(), store.learning.find("你好", "hello"))
        assertEquals(0, store.learning.find("世界", "world")!!.reviewCount)
        assertNull(store.learning.find("世界", "world")!!.nextReviewAt)
        assertEquals(2, store.learning.savedWords().size)
        val after = backup.snapshot(now)
        assertEquals(before.settings, after.settings)
        assertEquals(before.days, after.days)
        assertEquals(before.events, after.events)
        assertFalse(backup.hasRollback())
    }

    @Test fun invalidFutureOversizedAndDuplicateFilesCannotChangeData() = test { _, store, backup, _ ->
        seed(store)
        val before = backup.snapshot(now)
        for (bad in listOf(before.copy(version = 99), before.copy(words = before.words + before.words), before.copy(words = listOf(before.words.single().copy(stage = -1))))) {
            assertTrue(runCatching { backup.restore(bad, false) }.isFailure)
            assertEquals(before, backup.snapshot(now))
        }
        assertTrue(runCatching { LearningBackupCodec.read("{broken".byteInputStream()) }.isFailure)
        val invalidUtf8 = LearningBackupCodec.encode(before).also { bytes -> bytes[bytes.indexOfFirst { it < 0 }] = 0xff.toByte() }
        assertTrue(runCatching { LearningBackupCodec.read(invalidUtf8.inputStream()) }.isFailure)
        val missingHeader = LearningBackupCodec.json.encodeToString(before).replace("\"version\":2,", "")
        assertTrue(runCatching { LearningBackupCodec.read(missingHeader.byteInputStream()) }.isFailure)
        assertTrue(runCatching { LearningBackupCodec.read(ByteArray(LEARNING_BACKUP_LIMIT + 1).inputStream()) }.isFailure)
        assertFalse(backup.hasRollback())
    }

    @Test fun rollbackStorageFailureLeavesTheEntireDatabaseUntouched() = test { _, store, backup, folder ->
        seed(store)
        val before = backup.snapshot(now)
        val blocked = File(folder, "not-a-directory").apply { writeText("occupied") }
        val failing = LearningBackupStore(store, File(blocked, "rollback.json"))
        assertTrue(runCatching { failing.restore(before.copy(words = emptyList()), false) }.isFailure)
        assertEquals(before, backup.snapshot(now))
    }

    @Test fun practiceDoesNotChangeDailyPlanAndRestoresItsOwnUndoAndDraft() = test { db, store, backup, _ ->
        seed(store)
        failures(db)
        store.learning.startSession(true, now = now)
        val formal = backup.snapshot(now)
        val practice = store.learning.practice
        val session = practice.startPractice(WeaknessKind.SPELLING, now)
        assertEquals(1, session.total)
        val token = session.cards.first().token
        practice.saveSpellingDraft(token, "helo")
        assertEquals("helo", WordLearningStore(db, true).session()!!.spellingDraft)
        practice.checkSpelling(token, "helo")
        practice.answer(token, RecallRating.REMEMBERED, now)
        assertEquals(0, db.learningExtraDao().practiceEvents().size)
        practice.answer(token, RecallRating.UNCERTAIN, now)
        practice.answer(token, RecallRating.UNCERTAIN, now)
        assertEquals(1, db.learningExtraDao().practiceEvents().size)
        val after = backup.snapshot(now)
        assertEquals(formal.words, after.words)
        assertEquals(formal.settings, after.settings)
        assertEquals(formal.days, after.days)
        assertEquals(formal.events, after.events)
        assertEquals(formal.tasks, after.tasks)
        val exported = LearningBackupCodec.read(LearningBackupCodec.encode(after).inputStream())
        backup.restore(exported, false)
        val undo = practice.undoToken()!!
        val restored = practice.undoAnswer(undo)!!
        assertEquals("helo", restored.spellingDraft)
        assertEquals(SpellingOutcome.WRONG, restored.spellingOutcome)
        assertTrue(db.learningExtraDao().practiceEvents().isEmpty())
    }

    @Test fun removedWordsAreSkippedAndDirectRevealIsNotASpellingFailure() = test { db, store, _, _ ->
        seed(store)
        failures(db)
        val p = store.learning.practice
        val s = p.startPractice(WeaknessKind.SPELLING, now)
        p.reveal(s.cards.first().token)
        p.answer(s.cards.first().token, RecallRating.UNCERTAIN, now)
        assertEquals(SpellingOutcome.REVEALED.name, db.learningExtraDao().practiceEvents().single().event().spellingOutcome)
        assertEquals(2, store.learning.weaknesses(WeaknessKind.SPELLING, now).single().failures)
        val next = p.startPractice(WeaknessKind.SPELLING, now)
        store.learning.saveMeaning("你好", "hello", null, "offline", favorite = true, learning = false, now = now)
        assertTrue(p.skipRemoved(next.cards.first().token)!!.cards.isEmpty())
        assertTrue(store.learning.weaknesses(WeaknessKind.SPELLING, now).isEmpty())
    }

    @Test fun databaseFailureRollsBackEveryTableAndPreservesOriginalSnapshot() = test { db, store, backup, _ ->
        seed(store)
        val before = backup.snapshot(now)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON saved_words BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
        try {
            assertTrue(runCatching { backup.restore(before, false) }.isFailure)
            assertEquals(before, backup.snapshot(now))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore")
        }
        backup.rollBack()
        assertEquals(before, backup.snapshot(now))
    }

    @Test fun crossDayPracticeSuccessRemovalAndUndoNeverChangeFormalSchedule() = test { db, store, backup, _ ->
        seed(store)
        failures(db)
        store.learning.saveSettings(true, 5, 10, false)
        store.learning.startSession(true, now = now)
        val before = backup.snapshot(now)
        val p = store.learning.practice
        repeat(2) { index ->
            val time = now + index * LEARNING_DAY_MS
            val card = p.startPractice(WeaknessKind.SPELLING, time).cards.single()
            p.checkSpelling(card.token, "hello")
            p.answer(card.token, RecallRating.REMEMBERED, time)
        }
        assertTrue(store.learning.weaknesses(WeaknessKind.SPELLING, now + LEARNING_DAY_MS).isEmpty())
        p.undoAnswer(p.undoToken()!!)
        assertEquals(1, store.learning.weaknesses(WeaknessKind.SPELLING, now + LEARNING_DAY_MS).size)
        val after = backup.snapshot(now)
        assertEquals(before.words, after.words)
        assertEquals(before.settings, after.settings)
        assertEquals(before.days, after.days)
        assertEquals(before.tasks, after.tasks)
        assertEquals(before.events, after.events)
    }

    @Test fun versionSixMigrationPreservesEventsDraftAndUndoWithoutInventingOutcomes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "learning-v6-${UUID.randomUUID()}.db")
        val fixture = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath).build()
        val old = InputFootprintStore(fixture)
        seed(old)
        old.learning.setReviewMode(ReviewMode.SPELLING)
        val card = old.learning.startSession(false, now = now).cards.single()
        old.learning.checkSpelling(card.token, "helo")
        old.learning.answer(card.token, RecallRating.UNCERTAIN, now)
        val before = old.learning.savedWords()
        fixture.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, 0).use {
            it.execSQL("DROP TABLE wordbook_members")
            it.execSQL("DROP TABLE wordbooks")
            it.execSQL("DROP TABLE learning_practice_state")
            it.execSQL("DROP TABLE learning_practice_events")
            it.execSQL("ALTER TABLE learning_events DROP COLUMN mode")
            it.execSQL("ALTER TABLE learning_events DROP COLUMN spellingOutcome")
            it.version = 6
        }
        val db = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath).addMigrations(LEARNING_PRACTICE_MIGRATION, WORDBOOK_MIGRATION).build()
        try {
            val store = InputFootprintStore(db)
            assertEquals(before, store.learning.savedWords())
            assertNull(db.learningProgressDao().events().single().mode)
            assertNull(db.learningProgressDao().events().single().spellingOutcome)
            assertTrue(store.learning.weaknesses(WeaknessKind.SPELLING, now).isEmpty())
            assertEquals("helo", store.learning.undoAnswer(card.token)!!.spellingDraft)
            assertEquals(0, store.learning.find("你好", "hello")!!.reviewCount)
        } finally {
            db.close()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }

    @Test fun mixedPracticeRotatesSingleWordAndPauseInvalidatesPracticeUndo() = test { db, store, backup, _ ->
        seed(store)
        failures(db)
        val original = store.learning.savedWords()
        val p = store.learning.practice
        for (mode in listOf(ReviewMode.ENGLISH, ReviewMode.CHINESE, ReviewMode.SPELLING)) {
            val s = p.startPractice(WeaknessKind.FORGOTTEN, now)
            assertEquals(mode, s.currentMode)
            p.reveal(s.cards.first().token)
            p.answer(s.cards.first().token, RecallRating.UNCERTAIN, now)
        }
        assertEquals(original, store.learning.savedWords())
        assertNotNull(p.undoToken())
        store.learning.saveMeaning("你好", "hello", null, "offline", learning = false)
        assertNull(p.undoToken())
        LearningBackupCodec.encode(backup.snapshot(now))
    }

    @Test fun cancelledQueuedRestoreAndOversizedRecordSetPreserveData() = test { db, store, backup, _ ->
        seed(store)
        val before = backup.snapshot(now)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.IO)
        val blocker = scope.launch {
            db.withTransaction {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            val job = scope.launch { backup.restore(before, false) }
            job.cancelAndJoin()
        } finally {
            release.complete(Unit)
            blocker.join()
        }
        assertEquals(before, backup.snapshot(now))
        assertFalse(backup.hasRollback())
        val tooMany = before.copy(words = List(LEARNING_BACKUP_RECORD_LIMIT + 1) { before.words.single() })
        assertEquals("backup_too_large", runCatching { LearningBackupCodec.validate(tooMany) }.exceptionOrNull()?.message)
        val badSession = WordReviewSession(cards = listOf(ReviewCard("你好", "hello")), practiceKind = WeaknessKind.SPELLING, mode = ReviewMode.SPELLING)
        assertTrue(runCatching { backup.restore(before.copy(settings = before.settings.copy(sessionJson = LearningBackupCodec.json.encodeToString(badSession))), false) }.isFailure)
        assertEquals(before, backup.snapshot(now))
    }

    @Test fun restoredGenerationRejectsOldContentAndSettingsWrites() = test { _, store, backup, _ ->
        seed(store)
        val before = backup.snapshot(now)
        val generation = store.learning.generation
        val ticket = store.sentences.ticket()
        backup.restore(before, false)
        assertTrue(runCatching { store.learning.saveMeaning("旧页面", "stale", null, "offline", learning = true, generation = generation) }.isFailure)
        store.learning.setReviewMode(ReviewMode.SPELLING, generation)
        assertFalse(store.sentences.save("旧句子", "A stale sentence.", "test", true, ticket))
        assertEquals(before.words, store.learning.savedWords())
        assertEquals(before.settings, store.learning.settings())
    }

    @Test fun clearingAllRemovesPracticeAndLocalRollbackButRecentClearDoesNot() = test { db, store, backup, folder ->
        seed(store)
        failures(db)
        store.learning.practice.startPractice(WeaknessKind.SPELLING, now)
        backup.restore(backup.snapshot(now), false)
        val configured = InputFootprintStore(db, File(folder, "rollback.json"))
        configured.clearRecent()
        assertTrue(backup.hasRollback())
        configured.clearAll()
        assertFalse(backup.hasRollback())
        assertTrue(db.learningExtraDao().practiceEvents().isEmpty())
        assertNull(db.learningExtraDao().state())
    }
}
