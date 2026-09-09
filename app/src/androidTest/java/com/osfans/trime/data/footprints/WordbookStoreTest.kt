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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class WordbookStoreTest {
    private val now = 1_788_998_400_000L
    private fun test(block: suspend (InputFootprintDatabase, InputFootprintStore, LearningBackupStore) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, InputFootprintDatabase::class.java).build()
        val store = InputFootprintStore(db)
        val file = File(context.cacheDir, "books-${UUID.randomUUID()}.json")
        try {
            block(db, store, LearningBackupStore(store, file))
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun sharedMeaningRetainsMembershipThroughRatingUndoAndPause() = test { _, s, _ ->
        val a = s.wordbooks.create("A")
        val b = s.wordbooks.create("B")
        s.wordbooks.importWords(a.id, listOf(ImportWord("Hello", "你好")))
        s.wordbooks.importWords(b.id, listOf(ImportWord("hello", "你好"), ImportWord("hello", "喂")))
        assertEquals(2, s.learning.savedWords().size)
        assertEquals(3, s.wordbooks.dao.members().size)
        val keys = listOf("你好" to "hello")
        s.wordbooks.setLearning(keys, true)
        val card = s.learning.startSession(false, now = now).cards.single()
        s.learning.reveal(card.token)
        s.learning.answer(card.token, RecallRating.REMEMBERED, now)
        assertEquals(3, s.wordbooks.dao.members().size)
        val scheduled = s.learning.find("你好", "hello")!!
        assertEquals(1, scheduled.reviewCount)
        s.wordbooks.importWords(b.id, listOf(ImportWord("HELLO", "你好", "changed")))
        assertEquals(scheduled, s.learning.find("你好", "hello"))
        s.learning.undoAnswer(s.learning.undoToken()!!)
        assertEquals(3, s.wordbooks.dao.members().size)
        s.wordbooks.setLearning(keys, false)
        assertFalse(s.learning.find("你好", "hello")!!.learning)
        assertEquals(0, s.wordbooks.dao.counts(a.id, "", now).learning)
        assertEquals(0, s.wordbooks.dao.counts(b.id, "", now).learning)
    }

    @Test fun deletingAndMovingGroupsNeverDeletesContent() = test { _, s, _ ->
        val a = s.wordbooks.create("A")
        val b = s.wordbooks.create("B")
        s.wordbooks.importWords(a.id, listOf(ImportWord("hello", "你好")))
        val words = s.learning.savedWords()
        s.wordbooks.transfer(listOf("你好" to "hello"), a.id, b.id, true)
        assertEquals(0, s.wordbooks.dao.counts(a.id, "", now).total)
        assertEquals(1, s.wordbooks.dao.counts(b.id, "", now).total)
        s.wordbooks.delete(b.id)
        assertEquals(words, s.learning.savedWords())
        assertEquals(words, s.wordbooks.dao.page("", "", 50, 0))
    }

    @Test fun importCannotChangeFrozenDailySessionOrExistingFlags() = test { _, s, backup ->
        s.learning.saveMeaning("你好", "hello", null, "offline", favorite = true, learning = true, now = now)
        s.learning.saveSettings(true, 1, 10, false)
        s.learning.startSession(true, now = now)
        val before = backup.snapshot(now)
        val book = s.wordbooks.create("批量")
        val rows = (0..999).map { ImportWord("w" + ('a' + it / 676) + ('a' + it / 26 % 26) + ('a' + it % 26), "释义$it") }
        s.wordbooks.importWords(book.id, rows + ImportWord("hello", "你好", "changed"))
        s.wordbooks.setLearning(rows.map { it.chinese to it.english }, true)
        val after = backup.snapshot(now)
        assertEquals(before.words.single(), s.learning.find("你好", "hello"))
        assertEquals(before.settings, after.settings)
        assertEquals(before.days, after.days)
        assertEquals(before.tasks, after.tasks)
        assertEquals(before.events, after.events)
        assertEquals(50, s.wordbooks.dao.page(book.id, "", 50, 0).size)
        assertEquals(1, s.wordbooks.dao.page(book.id, "", 50, 1000).size)
        assertEquals(1, s.wordbooks.dao.counts(book.id, "释义999", now).total)
    }

    @Test fun importFailureAndCancellationLeaveNoPartialBook() = test { db, s, backup ->
        val before = backup.snapshot(now)
        val catalog = WordbookEntity("builtin-basic", "基础", 1, "basic")
        assertTrue(runCatching { s.wordbooks.importWords(catalog.id, listOf(ImportWord("hello", "你好"), ImportWord("bad1", "坏")), catalog) }.isFailure)
        assertEquals(before, backup.snapshot(now))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_book BEFORE INSERT ON wordbook_members BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertTrue(runCatching { s.wordbooks.importWords(catalog.id, listOf(ImportWord("hello", "你好")), catalog) }.isFailure)
        assertEquals(before, backup.snapshot(now))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_book")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.IO)
        val lock = scope.launch {
            db.withTransaction {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val job = scope.launch { s.wordbooks.importWords(catalog.id, listOf(ImportWord("hello", "你好")), catalog) }
        job.cancelAndJoin()
        release.complete(Unit)
        lock.join()
        assertEquals(before, backup.snapshot(now))
    }

    @Test fun v2RoundTripRollbackAndV1DefaultGrouping() = test { _, s, backup ->
        val a = s.wordbooks.create("同名")
        s.wordbooks.importWords(a.id, listOf(ImportWord("long", "中".repeat(512))))
        val original = backup.snapshot(now)
        assertEquals(original, LearningBackupCodec.read(LearningBackupCodec.encode(original).inputStream()))
        s.wordbooks.create("另一本")
        backup.restore(original, false)
        assertEquals(original, backup.snapshot(now))
        backup.rollBack()
        assertEquals(2, s.wordbooks.dao.books().size)
        backup.restore(original.copy(version = 1, books = emptyList(), memberships = emptyList()), false)
        assertEquals(listOf(WordbookMember(DEFAULT_WORDBOOK, "中".repeat(512), "long")), s.wordbooks.dao.members())
        assertEquals(original.words, s.learning.savedWords())
    }

    @Test fun mergeStableIdsDistinguishesNamesAndPreservesLocalProgress() = test { _, s, backup ->
        val a = s.wordbooks.create("同名")
        s.wordbooks.importWords(a.id, listOf(ImportWord("hello", "你好")))
        val original = backup.snapshot(now)
        val b = WordbookEntity("another-stable-id", a.name, 2)
        val foreign = original.copy(words = original.words.map { it.copy(reviewCount = 5, stage = 1) }, books = listOf(b), memberships = listOf(WordbookMember(b.id, "你好", "hello")))
        backup.restore(foreign, true)
        backup.restore(foreign, true)
        assertEquals(original.words, s.learning.savedWords())
        assertEquals(2, s.wordbooks.dao.books().size)
        assertEquals(2, s.wordbooks.dao.books().map { it.name }.toSet().size)
        assertEquals(2, s.wordbooks.dao.members().size)
    }

    @Test fun malformedRelationsAndStaleImportCannotWrite() = test { _, s, backup ->
        val a = s.wordbooks.create("A")
        s.wordbooks.importWords(a.id, listOf(ImportWord("hello", "你好")))
        val before = backup.snapshot(now)
        for (bad in listOf(before.copy(memberships = listOf(WordbookMember("missing", "你好", "hello"))), before.copy(memberships = before.memberships + before.memberships), before.copy(version = 99))) {
            assertTrue(runCatching { backup.restore(bad, false) }.isFailure)
            assertEquals(before, backup.snapshot(now))
        }
        val generation = s.wordbooks.generation
        backup.restore(before, false)
        assertTrue(runCatching { s.wordbooks.importWords(a.id, listOf(ImportWord("world", "世界")), generation = generation) }.isFailure)
        assertTrue(runCatching { s.wordbooks.delete(a.id, generation) }.isFailure)
        assertEquals(before, backup.snapshot(now))
    }

    @Test fun catalogIsValidSharedAndRepeatSelectionDoesNotOverwriteAnswers() = test { _, s, _ ->
        val catalog = BuiltinWordbooks.load(ApplicationProvider.getApplicationContext())
        assertEquals(listOf(1000, 3846, 5406), catalog.books.map { it.words.size })
        assertEquals(6379, catalog.words.size)
        assertEquals(catalog.words.size, catalog.words.map { it.english }.toSet().size)
        catalog.words.forEach {
            assertTrue(validLearningMeaning(it.chinese))
            assertNotNull(normalizeSavedEnglish(it.english))
            assertFalse(it.chinese.contains("\\n"))
        }
        catalog.books.forEach { b -> s.wordbooks.importWords("builtin-${b.id}", catalog.entries(b), WordbookEntity("builtin-${b.id}", b.name, 1, b.id)) }
        assertEquals(6379, s.learning.savedWords().size)
        assertTrue(s.learning.savedWords().none { it.learning || it.favorite || it.reviewCount != 0 })
        val before = s.learning.savedWords()
        val b = catalog.books.first()
        s.wordbooks.importWords("builtin-${b.id}", catalog.entries(b), WordbookEntity("builtin-${b.id}", b.name, 1, b.id))
        assertEquals(before, s.learning.savedWords())
    }

    @Test fun versionSevenMigrationPreservesEveryLearningTableAndGroupsOldWords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "v7-${UUID.randomUUID()}.db")
        val fixture = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath).build()
        val old = InputFootprintStore(fixture)
        old.learning.saveMeaning("你好", "hello", null, "offline", learning = true, now = now)
        old.learning.setReviewMode(ReviewMode.SPELLING)
        val card = old.learning.startSession(false, now = now).cards.single()
        old.learning.checkSpelling(card.token, "helo")
        old.learning.answer(card.token, RecallRating.UNCERTAIN, now)
        val rollback = File(context.cacheDir, "v7-backup-${UUID.randomUUID()}.json")
        val before = LearningBackupStore(old, rollback).snapshot(now)
        fixture.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, 0).use {
            it.execSQL("DROP TABLE wordbook_members")
            it.execSQL("DROP TABLE wordbooks")
            it.version = 7
        }
        val db = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath).addMigrations(WORDBOOK_MIGRATION).build()
        try {
            val s = InputFootprintStore(db)
            val after = LearningBackupStore(s, rollback).snapshot(now)
            assertEquals(before, after.copy(books = emptyList(), memberships = emptyList()))
            assertEquals(listOf(WordbookMember(DEFAULT_WORDBOOK, "你好", "hello")), after.memberships)
            assertEquals("helo", s.learning.undoAnswer(card.token)!!.spellingDraft)
            assertEquals(1, s.wordbooks.dao.members().size)
        } finally {
            db.close()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
            rollback.delete()
        }
    }
}
