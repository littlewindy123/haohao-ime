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
}
