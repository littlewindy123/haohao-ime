package com.osfans.trime.data.footprints

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable internal enum class WeaknessKind { FORGOTTEN, SPELLING }

@Serializable internal enum class SpellingOutcome { CORRECT, WRONG, REVEALED }
internal data class WeakWord(val word: SavedWordEntity, val failures: Int, val lastFailure: Long)

internal fun weakWords(words: List<SavedWordEntity>, events: List<LearningReviewEvent>, kind: WeaknessKind, now: Long): List<WeakWord> {
    val history = events.filter { !it.undone && it.kind != "repeat" && it.occurredAt in (now - 30 * LEARNING_DAY_MS)..now }
        .distinctBy { it.token }.groupBy { it.chinese to it.english }
    return words.filter { it.learning }.mapNotNull { word ->
        val relevant = history[word.chinese to word.english].orEmpty().filter {
            kind != WeaknessKind.SPELLING || it.spellingOutcome != null
        }.sortedWith(compareBy({ it.occurredAt }, { it.token }))
        fun failed(e: LearningReviewEvent) = if (kind == WeaknessKind.FORGOTTEN) e.rating == RecallRating.FORGOTTEN.name else e.spellingOutcome == SpellingOutcome.WRONG.name
        fun succeeded(e: LearningReviewEvent) = if (kind == WeaknessKind.FORGOTTEN) e.rating == RecallRating.REMEMBERED.name else e.spellingOutcome == SpellingOutcome.CORRECT.name
        val failures = relevant.filter(::failed)
        if (failures.size < 2 || (relevant.size >= 2 && relevant.takeLast(2).all(::succeeded))) {
            null
        } else {
            WeakWord(word, failures.size, failures.last().occurredAt)
        }
    }.sortedWith(compareByDescending<WeakWord> { it.failures }.thenByDescending { it.lastFailure }.thenBy { it.word.english }.thenBy { it.word.chinese })
}

internal enum class SpellingEditKind { MATCH, MISSING, EXTRA, REPLACE }
internal data class SpellingEdit(val kind: SpellingEditKind, val expected: String, val actual: String)

/** Bounded character alignment is feedback only; spellingMatches remains the grading authority. */
internal fun spellingEdits(draft: String, expected: String): List<SpellingEdit> {
    fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ").replace('’', '\'').lowercase(Locale.ROOT)
    val a = normalize(draft).take(128)
    val b = normalize(expected).take(32)
    val costs = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) costs[i][0] = i
    for (j in 0..b.length) costs[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) costs[i][j] = minOf(costs[i - 1][j] + 1, costs[i][j - 1] + 1, costs[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
    var i = a.length
    var j = b.length
    val result = mutableListOf<SpellingEdit>()
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && costs[i][j] == costs[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1 -> {
                result += SpellingEdit(if (a[i - 1] == b[j - 1]) SpellingEditKind.MATCH else SpellingEditKind.REPLACE, b[j - 1].toString(), a[i - 1].toString())
                i--
                j--
            }
            j > 0 && costs[i][j] == costs[i][j - 1] + 1 -> {
                result += SpellingEdit(SpellingEditKind.MISSING, b[j - 1].toString(), "")
                j--
            }
            else -> {
                result += SpellingEdit(SpellingEditKind.EXTRA, "", a[i - 1].toString())
                i--
            }
        }
    }
    return result.reversed()
}

@Entity(tableName = "learning_practice_state")
@Serializable
internal data class LearningPracticeState(@PrimaryKey val id: Int = 1, val sessionJson: String? = null, val undoJson: String? = null)

@Entity(tableName = "learning_practice_events")
@Serializable
internal data class LearningPracticeEvent(@PrimaryKey val token: String, val eventJson: String) {
    fun event(): LearningReviewEvent = Json.decodeFromString(eventJson)
}

@Dao
internal interface LearningExtraDao {
    @Query("SELECT * FROM learning_practice_state WHERE id = 1")
    suspend fun state(): LearningPracticeState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun state(value: LearningPracticeState)

    @Query("SELECT * FROM learning_practice_events")
    suspend fun practiceEvents(): List<LearningPracticeEvent>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun record(value: LearningPracticeEvent)

    @Query("DELETE FROM learning_practice_events WHERE token = :token")
    suspend fun undo(token: String)

    @Query("DELETE FROM learning_practice_events")
    suspend fun clearEvents()

    @Query("DELETE FROM learning_practice_state")
    suspend fun clearState()

    @Query("SELECT * FROM learning_events")
    suspend fun allEvents(): List<LearningReviewEvent>

    @Query("SELECT * FROM word_review_days")
    suspend fun allDays(): List<WordReviewDayEntity>

    @Query("SELECT * FROM saved_sentences")
    suspend fun allSentences(): List<SavedSentenceEntity>
}
