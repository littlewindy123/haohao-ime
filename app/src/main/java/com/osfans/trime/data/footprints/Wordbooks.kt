package com.osfans.trime.data.footprints

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import java.util.UUID

internal const val DEFAULT_WORDBOOK = "personal-default"
internal const val LEARNING_MEANING_LIMIT = 512

/** The saved definition stays intact; only the unrevealed question masks its answer. */
internal fun recallMeaning(chinese: String, english: String): String = Regex("(?<![A-Za-z])${Regex.escape(english)}(?![A-Za-z])", RegexOption.IGNORE_CASE).replace(chinese, "＿")

@Serializable
@Entity(tableName = "wordbooks", primaryKeys = ["id"])
data class WordbookEntity(val id: String, val name: String, val createdAt: Long, val catalogId: String? = null)

@Serializable
@Entity(
    tableName = "wordbook_members",
    primaryKeys = ["bookId", "chinese", "english"],
    foreignKeys = [
        ForeignKey(entity = WordbookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SavedWordEntity::class, parentColumns = ["chinese", "english"], childColumns = ["chinese", "english"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["chinese", "english"])],
)
data class WordbookMember(val bookId: String, val chinese: String, val english: String)

internal data class BookCounts(val total: Int, val learning: Int, val fresh: Int, val due: Int)

@Dao
internal interface WordbookDao {
    @Query("SELECT * FROM wordbooks ORDER BY createdAt, id")
    suspend fun books(): List<WordbookEntity>

    @Query("SELECT * FROM wordbooks WHERE id = :id")
    suspend fun find(id: String): WordbookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: WordbookEntity)

    @Query("UPDATE wordbooks SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM wordbooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM wordbooks")
    suspend fun clear()

    @Query("SELECT * FROM wordbook_members")
    suspend fun members(): List<WordbookMember>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(member: WordbookMember)

    @Query("DELETE FROM wordbook_members WHERE bookId = :id AND chinese = :chinese AND english = :english")
    suspend fun detach(id: String, chinese: String, english: String)

    @Query("SELECT w.* FROM saved_words w WHERE (:id IS NULL OR (:id = '' AND NOT EXISTS (SELECT 1 FROM wordbook_members m WHERE m.chinese=w.chinese AND m.english=w.english)) OR EXISTS (SELECT 1 FROM wordbook_members m WHERE m.bookId=:id AND m.chinese=w.chinese AND m.english=w.english)) AND (instr(lower(w.displayEnglish),lower(:search))>0 OR instr(w.chinese,:search)>0) ORDER BY w.english,w.chinese LIMIT :limit OFFSET :offset")
    suspend fun page(id: String?, search: String, limit: Int, offset: Int): List<SavedWordEntity>

    @Query("SELECT count(*) AS total, coalesce(sum(w.learning),0) AS learning, coalesce(sum(CASE WHEN w.reviewCount=0 THEN 1 ELSE 0 END),0) AS fresh, coalesce(sum(CASE WHEN w.learning=1 AND w.reviewCount>0 AND w.nextReviewAt<=:now THEN 1 ELSE 0 END),0) AS due FROM saved_words w WHERE (:id IS NULL OR (:id = '' AND NOT EXISTS (SELECT 1 FROM wordbook_members m WHERE m.chinese=w.chinese AND m.english=w.english)) OR EXISTS (SELECT 1 FROM wordbook_members m WHERE m.bookId=:id AND m.chinese=w.chinese AND m.english=w.english)) AND (instr(lower(w.displayEnglish),lower(:search))>0 OR instr(w.chinese,:search)>0)")
    suspend fun counts(id: String?, search: String, now: Long): BookCounts
}

internal class WordbookStore(private val db: InputFootprintDatabase) {
    val dao = db.wordbookDao()
    val generation get() = db.learningGeneration.get()
    suspend fun create(name: String, generation: Long = this.generation): WordbookEntity = db.withTransaction {
        check(generation == this.generation) { "stale_learning_write" }
        val book = WordbookEntity(UUID.randomUUID().toString(), uniqueName(name), System.currentTimeMillis())
        dao.insert(book)
        book
    }
    suspend fun uniqueName(name: String): String {
        val base = name.trim()
        require(base.isNotEmpty() && base.length <= 40 && base.none { it.isISOControl() })
        val names = dao.books().map { it.name }.toSet()
        var result = base
        var n = 2
        while (result in names) result = "${base.take(30)} ($n)".also { n++ }
        return result
    }
    suspend fun rename(id: String, name: String, generation: Long = this.generation) = db.withTransaction {
        check(generation == this.generation) { "stale_learning_write" }
        val current = requireNotNull(dao.find(id))
        if (current.name != name.trim()) dao.rename(id, uniqueName(name))
    }
    suspend fun delete(id: String, generation: Long = this.generation) = db.withTransaction {
        check(generation == this.generation) { "stale_learning_write" }
        dao.delete(id)
    }
    suspend fun transfer(keys: List<Pair<String, String>>, from: String?, to: String?, move: Boolean, generation: Long = this.generation) = db.withTransaction {
        check(generation == this.generation) { "stale_learning_write" }
        if (to != null) requireNotNull(dao.find(to))
        keys.forEach { (zh, en) ->
            if (db.wordLearningDao().find(zh, en) != null) {
                if (to != null) dao.attach(WordbookMember(to, zh, en))
                if (move && !from.isNullOrEmpty() && from != to) dao.detach(from, zh, en)
            }
        }
    }
    suspend fun setLearning(keys: List<Pair<String, String>>, active: Boolean, generation: Long = this.generation) = db.withTransaction {
        check(generation == this.generation) { "stale_learning_write" }
        val learning = WordLearningStore(db)
        keys.forEach { (zh, en) ->
            db.wordLearningDao().find(zh, en)?.let { w ->
                // A batch pause must retain even an ungrouped, unfavourited word.
                db.wordLearningDao().save(w.copy(learning = active))
                if (!active) {
                    learning.progress.exclude(zh, en, System.currentTimeMillis())
                    learning.invalidateUndoFor(zh, en)
                }
            }
        }
    }
    suspend fun importWords(bookId: String, rows: List<ImportWord>, catalog: WordbookEntity? = null, generation: Long = this.generation): Int = db.withTransaction {
        check(generation == this.generation) { "stale_learning_write" }
        if (catalog != null && dao.find(bookId) == null) dao.insert(catalog.copy(name = uniqueName(catalog.name)))
        requireNotNull(dao.find(bookId))
        require(rows.size <= LEARNING_BACKUP_RECORD_LIMIT)
        var added = 0
        val now = System.currentTimeMillis()
        rows.forEach { row ->
            require(validLearningMeaning(row.chinese) && row.chinese == row.chinese.trim() && normalizeSavedEnglish(row.english) != null && (row.phonetic?.length ?: 0) <= 512 && row.phonetic.orEmpty().none(Char::isISOControl))
            val en = requireNotNull(normalizeSavedEnglish(row.english))
            if (db.wordLearningDao().find(row.chinese, en) == null) {
                db.wordLearningDao().save(SavedWordEntity(row.chinese, en, row.phonetic, if (catalog == null) "import" else "offline", now, displayEnglish = requireNotNull(displaySavedEnglish(row.english))))
                added++
            }
            dao.attach(WordbookMember(bookId, row.chinese, en))
        }
        added
    }
}

internal fun validLearningMeaning(text: String) = text.isNotBlank() && text.codePointCount(0, text.length) <= LEARNING_MEANING_LIMIT && text.none { it.isISOControl() && it !in "\n\t" }
