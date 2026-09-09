package com.osfans.trime.data.footprints

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceStoreTest {
    @Test fun versionThreeMigrationPreservesAnswersUndoAndSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "sentence-v3-${java.util.UUID.randomUUID()}.db")
        val fixture = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath).build()
        val learning = WordLearningStore(fixture)
        learning.saveMeaning("我自己的解释", "learn", "/lɜːn/", "offline", favorite = true, learning = true, now = 1)
        val token = learning.startSession(false, now = 100).cards.first().token
        learning.reveal(token)
        learning.answer(token, RecallRating.REMEMBERED, 100)
        val word = learning.find("我自己的解释", "learn")
        val session = learning.session()
        fixture.close()
        // Remove exactly the v4 additions; the unchanged word/review tables are v3 schema.
        android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, 0).use {
            it.execSQL("ALTER TABLE word_learning_state DROP COLUMN reviewMode")
            it.execSQL("DROP TABLE learning_practice_state")
            it.execSQL("DROP TABLE wordbook_members")
            it.execSQL("DROP TABLE wordbooks")
            it.execSQL("DROP TABLE learning_practice_events")
            it.execSQL("DROP TABLE learning_tasks")
            it.execSQL("DROP TABLE learning_events")
            it.execSQL("DROP TABLE saved_sentences")
            it.execSQL("DROP TABLE sentence_settings")
            it.version = 3
        }
        val upgraded = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath)
            .addMigrations(SENTENCE_MIGRATION, LEARNING_PROGRESS_MIGRATION, LEARNING_MODES_MIGRATION, LEARNING_PRACTICE_MIGRATION, WORDBOOK_MIGRATION).build()
        try {
            val after = WordLearningStore(upgraded)
            assertEquals(word, after.find("我自己的解释", "learn"))
            assertEquals(session, after.session())
            assertEquals(token, after.undoToken())
            val sentences = SentenceStore(upgraded)
            assertFalse(sentences.automatic())
            assertTrue(sentences.save("我爱你", "I love you!", "test", true))
            after.undoAnswer(token)
            assertEquals(0, after.find("我自己的解释", "learn")!!.reviewCount)
        } finally {
            upgraded.close()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }
    private fun test(block: suspend (InputFootprintStore) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), InputFootprintDatabase::class.java).build()
        try {
            block(InputFootprintStore(db))
        } finally {
            db.close()
        }
    }

    @Test fun defaultsManualAndPreservesFullPunctuation() = test { data ->
        val store = data.sentences
        assertFalse(store.automatic())
        assertFalse(store.save("我爱你", "I love you!", "test", false))
        assertTrue(store.save("明天上午九点我们一起去学习吧", "Let's study together tomorrow at 9:00 a.m.!", "test", true))
        assertEquals("Let's study together tomorrow at 9:00 a.m.!", store.rows.first().single().english)
        assertTrue(data.learning.words.first().isEmpty())
    }

    @Test fun recentLimitAndFavoritesAndDuplicatePairs() = test { data ->
        val store = data.sentences
        store.setAutomatic(true)
        store.save("收藏", "Favorite sentence.", "test", true)
        repeat(101) { store.save("句子$it", "Sentence $it.", "test", false) }
        val rows = store.rows.first()
        assertEquals(100, rows.count { !it.favorite })
        assertEquals(1, rows.count { it.favorite })
        store.save("句子100", "Sentence 100.", "test", false)
        assertEquals(101, store.rows.first().size)
        store.save("收藏", "A different translation.", "test", true)
        assertEquals(2, store.rows.first().count { it.favorite })
        store.clear(false)
        assertEquals(2, store.rows.first().size)
    }

    @Test fun disableClearAndInvalidSessionRejectQueuedWrites() = test { data ->
        val store = data.sentences
        store.setAutomatic(true)
        val old = store.ticket()
        store.setAutomatic(false)
        store.setAutomatic(true)
        assertFalse(store.save("旧句子", "Old sentence.", "test", false, old))
        val beforeClear = store.ticket()
        store.clear(true)
        assertFalse(store.save("旧句子", "Old sentence.", "test", true, beforeClear))
        assertFalse(store.save("旧句子", "Old sentence.", "test", false, valid = { false }))
        assertTrue(store.rows.first().isEmpty())
    }

    @Test fun generationChangeInsideTransactionRollsBackWrite() = test { data ->
        val store = data.sentences
        var checks = 0
        assertFalse(store.save("旧句子", "Old sentence.", "test", true, valid = { ++checks == 1 }))
        assertTrue(store.rows.first().isEmpty())
    }

    @Test fun lexicalDataIsSeparateAndOffline() = test { data ->
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        data.learning.saveMeaning("学习", "learn", null, "offline", learning = true)
        val original = data.learning.find("学习", "learn")
        val word = StudyLexicon.lookup(context, "learn")!!
        assertTrue(word.meanings.isNotEmpty())
        assertTrue(word.examples.isNotEmpty())
        assertTrue(word.examples.all { it.englishAuthor.isNotEmpty() && it.chineseAuthor.isNotEmpty() })
        assertNotNull(StudyLexicon.lookup(context, "learning"))
        assertNull(StudyLexicon.lookup(context, "not-a-real-dictionary-word-xyz"))
        assertEquals(original, data.learning.find("学习", "learn"))
    }
}
