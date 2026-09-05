/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File

internal data class InputFootprintCounts(
    val recent: Int,
    val favorites: Int,
)

internal class InputFootprintStore(
    private val database: InputFootprintDatabase,
) {
    private val dao = database.inputFootprintDao()
    val learning = WordLearningStore(database)

    val recent: Flow<List<InputFootprintEntity>> = dao.recent(RECENT_LIMIT)
    val favorites: Flow<List<InputFootprintEntity>> = dao.favorites()
    val counts: Flow<InputFootprintCounts> =
        combine(dao.recentCount(), dao.favoriteCount(), ::InputFootprintCounts)

    suspend fun find(text: String): InputFootprintEntity? = dao.find(text)

    suspend fun isFavorite(text: String): Boolean = dao.find(text)?.favorite == true

    suspend fun record(
        text: String,
        timestamp: Long,
    ) = database.withTransaction {
        val current = dao.find(text)
        if (current == null) {
            dao.insert(InputFootprintEntity(text = text, lastUsedAt = timestamp, useCount = 1))
        } else {
            dao.update(
                current.copy(
                    lastUsedAt = maxOf(timestamp, current.lastUsedAt ?: Long.MIN_VALUE),
                    useCount = current.useCount + 1,
                ),
            )
        }
        dao.deleteUnfavoritedBeyond(RECENT_LIMIT)
        dao.detachFavoritesBeyond(RECENT_LIMIT)
    }

    suspend fun setFavorite(
        text: String,
        favorite: Boolean,
        timestamp: Long,
    ) = database.withTransaction {
        val current = dao.find(text)
        when {
            favorite && current == null -> dao.insert(
                InputFootprintEntity(
                    text = text,
                    favorite = true,
                    favoritedAt = timestamp,
                ),
            )
            favorite -> dao.update(current!!.copy(favorite = true, favoritedAt = timestamp))
            current == null -> Unit
            current.lastUsedAt == null -> dao.delete(current)
            else -> dao.update(current.copy(favorite = false, favoritedAt = null))
        }
    }

    suspend fun clearRecent() = database.withTransaction {
        dao.deleteUnfavorited()
        dao.detachFavoritesFromRecent()
    }

    suspend fun clearAll() = database.withTransaction {
        dao.deleteAll()
        learning.clearAll()
    }

    companion object {
        const val RECENT_LIMIT = 100
    }
}

internal object InputFootprints {
    private const val DATABASE_NAME = "haohao_vocabulary.db"
    private lateinit var storeInstance: InputFootprintStore

    val isAvailable: Boolean
        get() = ::storeInstance.isInitialized

    val storeOrNull: InputFootprintStore?
        get() = if (isAvailable) storeInstance else null

    val store: InputFootprintStore
        get() = storeInstance

    internal fun databaseFile(context: Context): File = context.noBackupFilesDir.resolve(DATABASE_NAME)

    fun init(context: Context) {
        val database =
            Room.databaseBuilder(
                context.applicationContext,
                InputFootprintDatabase::class.java,
                databaseFile(context).absolutePath,
            ).addMigrations(WORD_LEARNING_MIGRATION, WORD_DISPLAY_UNDO_MIGRATION).build()
        try {
            database.openHelper.writableDatabase
            storeInstance = InputFootprintStore(database)
        } catch (error: Throwable) {
            database.close()
            throw error
        }
    }
}
