/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface InputFootprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InputFootprintEntity)

    @Update
    suspend fun update(item: InputFootprintEntity)

    @Delete
    suspend fun delete(item: InputFootprintEntity)

    @Query("SELECT * FROM ${InputFootprintEntity.TABLE_NAME} WHERE text = :text LIMIT 1")
    suspend fun find(text: String): InputFootprintEntity?

    @Query(
        "SELECT * FROM ${InputFootprintEntity.TABLE_NAME} " +
            "WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC, text ASC LIMIT :limit",
    )
    fun recent(limit: Int): Flow<List<InputFootprintEntity>>

    @Query(
        "SELECT * FROM ${InputFootprintEntity.TABLE_NAME} " +
            "WHERE favorite = 1 ORDER BY favoritedAt DESC, text ASC",
    )
    fun favorites(): Flow<List<InputFootprintEntity>>

    @Query("SELECT COUNT(*) FROM ${InputFootprintEntity.TABLE_NAME} WHERE lastUsedAt IS NOT NULL")
    fun recentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ${InputFootprintEntity.TABLE_NAME} WHERE favorite = 1")
    fun favoriteCount(): Flow<Int>

    @Query(
        "DELETE FROM ${InputFootprintEntity.TABLE_NAME} WHERE favorite = 0 AND lastUsedAt IS NOT NULL " +
            "AND text NOT IN (SELECT text FROM ${InputFootprintEntity.TABLE_NAME} " +
            "WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC, text ASC LIMIT :limit)",
    )
    suspend fun deleteUnfavoritedBeyond(limit: Int)

    @Query(
        "UPDATE ${InputFootprintEntity.TABLE_NAME} SET lastUsedAt = NULL " +
            "WHERE favorite = 1 AND lastUsedAt IS NOT NULL " +
            "AND text NOT IN (SELECT text FROM ${InputFootprintEntity.TABLE_NAME} " +
            "WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC, text ASC LIMIT :limit)",
    )
    suspend fun detachFavoritesBeyond(limit: Int)

    @Query("DELETE FROM ${InputFootprintEntity.TABLE_NAME} WHERE favorite = 0")
    suspend fun deleteUnfavorited()

    @Query("UPDATE ${InputFootprintEntity.TABLE_NAME} SET lastUsedAt = NULL WHERE favorite = 1")
    suspend fun detachFavoritesFromRecent()

    @Query("DELETE FROM ${InputFootprintEntity.TABLE_NAME}")
    suspend fun deleteAll()
}
