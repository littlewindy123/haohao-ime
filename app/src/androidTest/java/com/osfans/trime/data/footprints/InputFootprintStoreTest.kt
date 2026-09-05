/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputFootprintStoreTest {
    private lateinit var database: InputFootprintDatabase
    private lateinit var store: InputFootprintStore

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                InputFootprintDatabase::class.java,
            ).allowMainThreadQueries().build()
        store = InputFootprintStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistentDatabaseIsExcludedFromAndroidBackups() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseFile = InputFootprints.databaseFile(context)

        assertEquals(context.noBackupFilesDir, databaseFile.parentFile)
        assertEquals("haohao_vocabulary.db", databaseFile.name)
    }

    @Test
    fun duplicateCommitMovesToFrontAndIncrementsCount() = runBlocking {
        store.record("你好", 100L)
        store.record("中国", 200L)
        store.record("你好", 300L)

        val recent = store.recent.first()
        assertEquals(listOf("你好", "中国"), recent.map { it.text })
        assertEquals(2, recent.first().useCount)
        assertEquals(300L, recent.first().lastUsedAt)
    }

    @Test
    fun concurrentCommitsAreSerializedWithoutLosingCounts() = runBlocking {
        coroutineScope {
            repeat(20) { index ->
                launch { store.record("你好", (index + 1).toLong()) }
            }
        }

        val footprint = store.find("你好")
        assertEquals(20, footprint?.useCount)
        assertEquals(20L, footprint?.lastUsedAt)
    }

    @Test
    fun recentListKeepsOneHundredAndNeverDeletesFavorites() = runBlocking {
        store.record("收藏词", 1L)
        store.setFavorite("收藏词", true, 2L)
        repeat(101) { index -> store.record("词$index", (index + 10).toLong()) }

        assertEquals(100, store.recent.first().size)
        val favorite = store.favorites.first().single()
        assertEquals("收藏词", favorite.text)
        assertNull(favorite.lastUsedAt)
        assertEquals(100, store.counts.first().recent)
        assertEquals(1, store.counts.first().favorites)
    }

    @Test
    fun clearingRecentPreservesFavoritesAndUnfavoritingExpiredFavoriteRemovesIt() = runBlocking {
        store.record("你好", 100L)
        store.record("电脑", 200L)
        store.setFavorite("你好", true, 300L)

        store.clearRecent()

        assertTrue(store.recent.first().isEmpty())
        assertEquals("你好", store.favorites.first().single().text)
        assertNull(store.find("你好")?.lastUsedAt)
        assertNull(store.find("电脑"))

        store.setFavorite("你好", false, 400L)
        assertFalse(store.isFavorite("你好"))
        assertNull(store.find("你好"))
    }

    @Test
    fun clearAllRemovesRecentAndFavorites() = runBlocking {
        store.record("你好", 100L)
        store.setFavorite("你好", true, 200L)
        store.clearAll()

        assertTrue(store.recent.first().isEmpty())
        assertTrue(store.favorites.first().isEmpty())
    }

    @Test
    fun cloudMeaningsSurviveHistoryEvictionAndNeverChangeSilently() = runBlocking {
        val learning = store.learning
        learning.saveMeaning("回家", "go home", null, "cloud", favorite = true, learning = true, now = 1)
        learning.saveMeaning("回家", "return home", null, "cloud", learning = true, now = 2)
        learning.saveMeaning("回家", "go home", "replacement", "offline", favorite = true, now = 3)
        store.record("回家", 1)
        repeat(101) { store.record("词$it", it + 10L) }
        store.clearRecent()
        assertEquals(2, learning.words.first().size)
        val saved = learning.find("回家", "go home")!!
        assertEquals("cloud", saved.source)
        assertNull(saved.phonetic)
        learning.saveMeaning("回家", "go home", null, "cloud", learning = false)
        assertTrue(learning.find("回家", "go home")!!.favorite)
        store.clearAll()
        assertTrue(learning.words.first().isEmpty())
        assertNull(learning.session())
    }

    @Test
    fun answersAreAtomicIdempotentAndSharedWithTheDailyPlan() = runBlocking {
        val learning = store.learning
        learning.saveSettings(true, 1, 10, false)
        learning.saveMeaning("学习", "learn", null, "offline", learning = true, now = 1)
        val session = learning.startSession(daily = false, now = 1_000)
        val token = session.cards.first().token
        // A rating before revealing is rejected, including stale accessibility actions.
        learning.answer(token, RecallRating.REMEMBERED, 1_000)
        assertEquals(0, learning.find("学习", "learn")!!.reviewCount)
        learning.reveal(token)
        val resumed = WordLearningStore(database).session()!!
        assertEquals(token, resumed.cards.first().token)
        assertTrue(resumed.answerVisible)
        learning.answer(token, RecallRating.REMEMBERED, 1_000)
        learning.answer(token, RecallRating.REMEMBERED, 1_000)
        assertEquals(1, learning.find("学习", "learn")!!.reviewCount)
        assertEquals(1, learning.session()!!.completed)
        learning.saveMeaning("明天", "tomorrow", null, "offline", learning = true, now = 2)
        assertTrue(learning.startSession(daily = true, now = 1_000).cards.isEmpty())
        assertEquals(1, learning.remainingCount(1_000))
        assertEquals(1, learning.startSession(daily = true, extra = true, now = 1_000).cards.size)
    }

    @Test
    fun forgottenRetryDoesNotCountAsAnotherWordOrAdvanceTheInterval() = runBlocking {
        val learning = store.learning
        learning.saveMeaning("学习", "learn", null, "offline", learning = true, now = 1)
        val session = learning.startSession(false, now = 100)
        val first = session.cards.first().token
        learning.reveal(first)
        val retry = learning.answer(first, RecallRating.FORGOTTEN, 100)!!.cards.first()
        assertTrue(retry.repeat)
        learning.reveal(retry.token)
        val result = learning.answer(retry.token, RecallRating.REMEMBERED, 200)!!
        assertTrue(result.cards.isEmpty())
        assertEquals(1, result.completed)
        assertEquals(1, learning.find("学习", "learn")!!.reviewCount)
        assertEquals(100 + LEARNING_DAY_MS, learning.find("学习", "learn")!!.nextReviewAt)
    }

    @Test
    fun migratingVersionOneKeepsHistoryAndDoesNotEnrollFavorites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "word-migration-${java.util.UUID.randomUUID()}.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE input_footprints (text TEXT NOT NULL, lastUsedAt INTEGER, useCount INTEGER NOT NULL, favorite INTEGER NOT NULL, favoritedAt INTEGER, PRIMARY KEY(text))")
            old.execSQL("INSERT INTO input_footprints VALUES ('sample', 100, 7, 1, 100)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath)
            .addMigrations(WORD_LEARNING_MIGRATION, WORD_DISPLAY_UNDO_MIGRATION).build()
        try {
            val upgraded = InputFootprintStore(migrated)
            assertEquals(7, upgraded.find("sample")!!.useCount)
            assertTrue(upgraded.find("sample")!!.favorite)
            assertTrue(upgraded.learning.words.first().isEmpty())
            upgraded.learning.saveMeaning("学习", "learn", null, "offline", learning = true)
            assertEquals(1, upgraded.learning.words.first().size)
        } finally {
            migrated.close()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }

    @Test
    fun displayCaseIsPreservedAndCorrectedWithoutChangingProgress() = runBlocking {
        val learning = store.learning
        learning.saveMeaning("我", "I", null, "offline", learning = true, now = 1)
        learning.saveMeaning("中国", "China", null, "cloud", learning = true, now = 2)
        learning.saveMeaning("中国", "CHINA", "new", "offline", favorite = true)
        assertEquals("I", learning.find("我", "i")!!.displayEnglish)
        assertEquals("China", learning.find("中国", "china")!!.displayEnglish)
        assertEquals(2, learning.words.first().size)
        val token = learning.startSession(false, now = 10).cards.first().token
        learning.reveal(token)
        learning.answer(token, RecallRating.REMEMBERED, 10)
        learning.correctCase("我", "i", "i")
        assertEquals(1, learning.find("我", "I")!!.reviewCount)
        assertTrue(runCatching { learning.correctCase("我", "i", "me") }.isFailure)
        learning.undoAnswer(token)
        assertEquals("i", learning.find("我", "I")!!.displayEnglish)
        assertEquals(0, learning.find("我", "I")!!.reviewCount)
    }

    @Test
    fun undoSurvivesRecreationAndCrossDayAndRejectsStaleCallbacks() = runBlocking {
        val learning = store.learning
        learning.saveMeaning("我", "I", null, "offline", learning = true, now = 1)
        val before = learning.find("我", "i")!!
        val token = learning.startSession(false, now = 10).cards.single().token
        learning.reveal(token)
        learning.answer(token, RecallRating.FORGOTTEN, 10)
        assertEquals(0, learning.summary(10).available)
        val reopened = WordLearningStore(database)
        val restored = reopened.undoAnswer(token)!!
        assertTrue(restored.answerVisible)
        assertFalse(restored.cards.single().repeat)
        assertEquals(before, reopened.find("我", "I"))
        assertEquals(1, reopened.summary(10).available)
        assertEquals(1, reopened.summary(10 + LEARNING_DAY_MS).available)
        reopened.answer(token, RecallRating.REMEMBERED, 20)
        assertEquals(0, reopened.find("我", "I")!!.reviewCount)
        assertNull(reopened.undoToken())
        val newToken = restored.cards.single().token
        reopened.answer(newToken, RecallRating.REMEMBERED, LEARNING_DAY_MS + 20)
        assertEquals(0, reopened.summary(LEARNING_DAY_MS + 20).available)
        reopened.undoAnswer(token) // obsolete undo button must not undo a later answer
        assertEquals(1, reopened.find("我", "I")!!.reviewCount)
        reopened.undoAnswer(newToken)
        assertEquals(1, reopened.summary(LEARNING_DAY_MS + 20).available)
    }

    @Test
    fun undoRetryRestoresDueTimeAndIsInvalidatedByRemovalOrNewRound() = runBlocking {
        val learning = store.learning
        learning.saveMeaning("我", "I", null, "offline", favorite = true, learning = true, now = 1)
        val first = learning.startSession(false, now = 10).cards.single()
        learning.reveal(first.token)
        val retry = learning.answer(first.token, RecallRating.FORGOTTEN, 10)!!.cards.single()
        val beforeRetry = learning.find("我", "i")!!
        learning.reveal(retry.token)
        learning.answer(retry.token, RecallRating.FORGOTTEN, 100)
        assertEquals(100 + LEARNING_DAY_MS, learning.find("我", "i")!!.nextReviewAt)
        val restored = learning.undoAnswer(retry.token)!!
        assertEquals(beforeRetry, learning.find("我", "i"))
        assertEquals(0, learning.summary(100).available) // original day's completion is retained
        learning.answer(restored.cards.single().token, RecallRating.REMEMBERED, 100)
        learning.saveMeaning("我", "I", null, "offline", learning = false)
        assertNull(learning.undoToken())
        assertTrue(learning.find("我", "I")!!.favorite)
        learning.saveMeaning("我", "I", null, "offline", learning = true)
        val next = learning.startSession(false, now = LEARNING_DAY_MS + 100).cards.single()
        learning.reveal(next.token)
        learning.answer(next.token, RecallRating.REMEMBERED, LEARNING_DAY_MS + 100)
        learning.startSession(false, now = LEARNING_DAY_MS + 100)
        assertNull(learning.undoToken())
        store.clearAll()
        assertNull(learning.undoAnswer(next.token))
    }

    @Test
    fun migratingVersionTwoBackfillsDisplayWithoutRewritingConfirmedAnswers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "word-v2-${java.util.UUID.randomUUID()}.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE input_footprints (text TEXT NOT NULL, lastUsedAt INTEGER, useCount INTEGER NOT NULL, favorite INTEGER NOT NULL, favoritedAt INTEGER, PRIMARY KEY(text))")
            old.execSQL("CREATE TABLE saved_words (chinese TEXT NOT NULL, english TEXT NOT NULL, phonetic TEXT, source TEXT NOT NULL, createdAt INTEGER NOT NULL, favorite INTEGER NOT NULL, learning INTEGER NOT NULL, stage INTEGER NOT NULL, reviewCount INTEGER NOT NULL, lastReviewedAt INTEGER, nextReviewAt INTEGER, PRIMARY KEY(chinese, english))")
            old.execSQL("CREATE TABLE word_review_days (day TEXT NOT NULL, chinese TEXT NOT NULL, english TEXT NOT NULL, wasNew INTEGER NOT NULL, PRIMARY KEY(day, chinese, english))")
            old.execSQL("CREATE TABLE word_learning_state (id INTEGER NOT NULL, planEnabled INTEGER NOT NULL, newLimit INTEGER NOT NULL, reviewLimit INTEGER NOT NULL, reverse INTEGER NOT NULL, sessionJson TEXT, PRIMARY KEY(id))")
            old.execSQL("INSERT INTO saved_words VALUES ('中国', 'china', '/tʃaɪnə/', 'cloud', 1, 1, 1, 3, 8, 100, 200)")
            old.execSQL("INSERT INTO word_learning_state VALUES (1, 1, 4, 9, 1, NULL)")
            old.version = 2
        }
        val migrated = Room.databaseBuilder(context, InputFootprintDatabase::class.java, file.absolutePath)
            .addMigrations(WORD_LEARNING_MIGRATION, WORD_DISPLAY_UNDO_MIGRATION).build()
        try {
            val learning = WordLearningStore(migrated)
            val word = learning.find("中国", "China")!!
            assertEquals("china", word.displayEnglish)
            assertEquals("cloud", word.source)
            assertEquals(3, word.stage)
            assertEquals(8, word.reviewCount)
            assertEquals(200L, word.nextReviewAt)
            assertTrue(word.favorite)
            assertTrue(learning.settings().reverse)
            assertEquals(4, learning.settings().newLimit)
            assertNull(learning.undoToken())
        } finally {
            migrated.close()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }

    @Test
    fun anEntirelyRemovedSessionDoesNotHideNewlyAddedWords() = runBlocking {
        val learning = store.learning
        learning.saveMeaning("我", "I", null, "offline", favorite = true, learning = true, now = 1)
        learning.startSession(false, now = 10)
        learning.saveMeaning("我", "I", null, "offline", learning = false)
        learning.saveMeaning("中国", "China", null, "offline", learning = true, now = 2)
        assertEquals("中国", learning.startSession(false, now = 10).cards.single().chinese)
    }

    @Test
    fun clearingHistoryOrRemovingUnrelatedFavoritesDoesNotInvalidateUndo() = runBlocking {
        val learning = store.learning
        learning.saveMeaning("我", "I", null, "offline", learning = true, now = 1)
        learning.saveMeaning("中国", "China", null, "offline", favorite = true, now = 2)
        val card = learning.startSession(false, now = 10).cards.single()
        learning.reveal(card.token)
        learning.answer(card.token, RecallRating.REMEMBERED, 10)
        store.clearRecent()
        learning.saveMeaning("中国", "China", null, "offline", favorite = false)
        assertEquals(card.token, learning.undoToken())
        learning.undoAnswer(card.token)
        assertEquals(0, learning.find("我", "I")!!.reviewCount)
    }
}
