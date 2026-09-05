/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.footprints

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

internal const val LEARNING_DAY_MS = 86_400_000L
private val REVIEW_INTERVALS = listOf(1, 3, 7, 14, 30)
private val ENGLISH_MEANING = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*(?: [A-Za-z]+(?:['’\\-][A-Za-z]+)*){0,3}")

@Entity(tableName = "saved_words", primaryKeys = ["chinese", "english"])
@Serializable
data class SavedWordEntity(
    val chinese: String,
    val english: String,
    val phonetic: String? = null,
    val source: String,
    val createdAt: Long,
    val favorite: Boolean = false,
    val learning: Boolean = false,
    val stage: Int = 0,
    val reviewCount: Int = 0,
    val lastReviewedAt: Long? = null,
    val nextReviewAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val displayEnglish: String = english,
)

@Entity(tableName = "word_review_days", primaryKeys = ["day", "chinese", "english"])
data class WordReviewDayEntity(val day: String, val chinese: String, val english: String, val wasNew: Boolean)

@Entity(tableName = "word_learning_state", primaryKeys = ["id"])
data class WordLearningStateEntity(
    val id: Int = 1,
    val planEnabled: Boolean = false,
    val newLimit: Int = 5,
    val reviewLimit: Int = 10,
    val reverse: Boolean = false,
    val sessionJson: String? = null,
    val undoJson: String? = null,
)

@Serializable
internal data class ReviewUndo(
    val token: String,
    val word: SavedWordEntity,
    val session: WordReviewSession,
    val day: String,
    val insertedDay: Boolean,
)

internal data class WordTaskSummary(
    val active: WordReviewSession?,
    val learningCount: Int,
    val newCount: Int,
    val dueCount: Int,
    val plannedNew: Int,
    val plannedDue: Int,
    val nextReviewAt: Long?,
) {
    val available: Int get() = newCount + dueCount
    val quickCount: Int get() = minOf(5, available)
}

@Serializable
internal data class ReviewCard(
    val chinese: String,
    val english: String,
    val token: String = UUID.randomUUID().toString(),
    val repeat: Boolean = false,
)

@Serializable
internal data class WordReviewSession(
    val cards: List<ReviewCard> = emptyList(),
    val total: Int = cards.size,
    val completed: Int = 0,
    val reverse: Boolean = false,
    val daily: Boolean = false,
    val answerVisible: Boolean = false,
)

internal enum class RecallRating { FORGOTTEN, UNCERTAIN, REMEMBERED }

internal fun normalizeSavedEnglish(value: String): String? = value.trim().replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT).takeIf { it.length <= 32 && ENGLISH_MEANING.matches(it) }

internal fun displaySavedEnglish(value: String): String? = value.trim().replace(Regex("\\s+"), " ")
    .takeIf { normalizeSavedEnglish(it) != null }

internal fun scheduleWord(word: SavedWordEntity, rating: RecallRating, now: Long): SavedWordEntity {
    val answeredAt = maxOf(now, word.lastReviewedAt ?: now)
    val currentStage = word.stage.coerceIn(0, REVIEW_INTERVALS.size)
    val days = if (rating == RecallRating.REMEMBERED) REVIEW_INTERVALS[currentStage.coerceAtMost(REVIEW_INTERVALS.lastIndex)] else 1
    return word.copy(
        stage = when (rating) {
            RecallRating.FORGOTTEN -> 0
            RecallRating.UNCERTAIN -> currentStage
            RecallRating.REMEMBERED -> (currentStage + 1).coerceAtMost(REVIEW_INTERVALS.size)
        },
        reviewCount = word.reviewCount + 1,
        lastReviewedAt = answeredAt,
        nextReviewAt = answeredAt + days * LEARNING_DAY_MS,
    )
}

internal fun selectReviewWords(words: List<SavedWordEntity>, now: Long, newLimit: Int, reviewLimit: Int, totalLimit: Int): List<SavedWordEntity> {
    val active = words.filter { it.learning }
    val due = active.filter { it.reviewCount > 0 && (it.nextReviewAt ?: Long.MAX_VALUE) <= now }
        .sortedWith(compareBy({ it.nextReviewAt }, { it.createdAt }, { it.chinese }, { it.english }))
        .take(reviewLimit.coerceAtLeast(0))
    val fresh = active.filter { it.reviewCount == 0 }.sortedWith(compareBy({ it.createdAt }, { it.chinese }, { it.english }))
        .take(newLimit.coerceAtLeast(0))
    return (due + fresh).take(totalLimit.coerceAtLeast(0))
}

internal fun advanceReview(session: WordReviewSession, rating: RecallRating?): WordReviewSession {
    val current = session.cards.firstOrNull() ?: return session
    val remaining = session.cards.drop(1).toMutableList()
    if (rating == RecallRating.FORGOTTEN && !current.repeat) {
        remaining.add(minOf(2, remaining.size), current.copy(token = UUID.randomUUID().toString(), repeat = true))
    }
    return session.copy(cards = remaining, completed = session.completed + if (current.repeat || rating == null) 0 else 1, answerVisible = false)
}
