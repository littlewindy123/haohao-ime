// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.footprints

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

@Entity(tableName = "saved_sentences", primaryKeys = ["chinese", "english"])
data class SavedSentenceEntity(
    val chinese: String, val english: String, val source: String,
    val createdAt: Long, val lastUsedAt: Long, val favorite: Boolean = false,
)

@Entity(tableName = "sentence_settings")
data class SentenceSettingsEntity(@PrimaryKey val id: Int = 1, val automatic: Boolean = false)

@Dao
internal interface SentenceDao {
    @Query("SELECT * FROM saved_sentences ORDER BY lastUsedAt DESC, chinese, english")
    fun observe(): Flow<List<SavedSentenceEntity>>
    @Query("SELECT * FROM sentence_settings WHERE id = 1")
    suspend fun settings(): SentenceSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun settings(value: SentenceSettingsEntity)
    @Query("SELECT * FROM saved_sentences WHERE chinese = :chinese AND english = :english")
    suspend fun find(chinese: String, english: String): SavedSentenceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: SavedSentenceEntity)
    @Delete suspend fun delete(value: SavedSentenceEntity)
    @Query("DELETE FROM saved_sentences WHERE favorite = 0 AND rowid NOT IN (SELECT rowid FROM saved_sentences WHERE favorite = 0 ORDER BY lastUsedAt DESC, rowid DESC LIMIT 100)")
    suspend fun prune()
    @Query("DELETE FROM saved_sentences WHERE :all OR favorite = 0") suspend fun clear(all: Boolean)
    @Query("DELETE FROM saved_sentences WHERE favorite = 1") suspend fun clearFavorites()
}

internal fun validSentencePair(chinese: String, english: String): Boolean =
    chinese.isNotBlank() && english.isNotBlank() &&
        chinese.codePointCount(0, chinese.length) <= 200 && english.codePointCount(0, english.length) <= 2000 &&
        chinese.any { it in '\u3400'..'\u9fff' } && english.any { it in 'a'..'z' || it in 'A'..'Z' } &&
        (chinese + english).none { it.isISOControl() && it !in "\n\r\t" }

/** Serializes mode changes, clear and writes; an epoch also invalidates already queued work. */
internal class SentenceStore(private val database: InputFootprintDatabase) {
    private val dao = database.sentenceDao()
    private val lock = Mutex()
    private val generation = AtomicLong()
    val rows = dao.observe()
    fun ticket(): Long = generation.get()
    suspend fun automatic(): Boolean = dao.settings()?.automatic == true
    suspend fun setAutomatic(enabled: Boolean) {
        generation.incrementAndGet()
        lock.withLock { database.withTransaction { dao.settings(SentenceSettingsEntity(automatic = enabled)) } }
    }
    suspend fun save(chinese: String, english: String, source: String, favorite: Boolean, ticket: Long = ticket(), valid: () -> Boolean = { true }): Boolean {
        val zh = chinese.trim()
        val en = english.trim()
        if (!validSentencePair(zh, en)) return false
        return lock.withLock {
            if (ticket != generation.get() || !valid() || (!favorite && !automatic())) return@withLock false
            try {
                database.withTransaction {
                    val previous = dao.find(zh, en)
                    val now = maxOf(System.currentTimeMillis(), (previous?.lastUsedAt ?: 0) + 1)
                    dao.put(SavedSentenceEntity(zh, en, previous?.source ?: source, previous?.createdAt ?: now, now, favorite || previous?.favorite == true))
                    dao.prune()
                    if (ticket != generation.get() || !valid()) throw StaleSentenceWrite()
                    true
                }
            } catch (_: StaleSentenceWrite) { false }
        }
    }
    suspend fun favorite(value: SavedSentenceEntity, enabled: Boolean) = lock.withLock {
        database.withTransaction {
            dao.find(value.chinese, value.english)?.let { dao.put(it.copy(favorite = enabled)) }
            dao.prune()
        }
    }
    suspend fun delete(value: SavedSentenceEntity) {
        generation.incrementAndGet()
        lock.withLock { dao.delete(value) }
    }
    suspend fun clear(all: Boolean) {
        generation.incrementAndGet()
        lock.withLock { dao.clear(all) }
    }
    suspend fun clearFavorites() {
        generation.incrementAndGet()
        lock.withLock { dao.clearFavorites() }
    }
    private class StaleSentenceWrite : RuntimeException()
}
