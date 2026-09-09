/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.footprints

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Dao
internal interface WordLearningDao {
    @Query("SELECT * FROM saved_words ORDER BY createdAt DESC")
    fun observeWords(): Flow<List<SavedWordEntity>>

    @Query("SELECT * FROM saved_words ORDER BY createdAt DESC")
    suspend fun words(): List<SavedWordEntity>

    @Query("SELECT * FROM saved_words WHERE chinese = :chinese AND english = :english")
    suspend fun find(chinese: String, english: String): SavedWordEntity?

    @Upsert
    suspend fun save(word: SavedWordEntity)

    @Query("DELETE FROM saved_words WHERE chinese = :chinese AND english = :english AND favorite = 0 AND learning = 0 AND NOT EXISTS (SELECT 1 FROM wordbook_members m WHERE m.chinese=saved_words.chinese AND m.english=saved_words.english)")
    suspend fun prune(chinese: String, english: String)

    @Query("SELECT * FROM word_learning_state WHERE id = 1")
    suspend fun state(): WordLearningStateEntity?

    @Query("SELECT * FROM word_learning_state WHERE id = 1")
    fun observeState(): Flow<WordLearningStateEntity?>

    @Query("SELECT * FROM word_review_days")
    fun observeDays(): Flow<List<WordReviewDayEntity>>

    @Query("DELETE FROM word_review_days WHERE day = :day AND chinese = :chinese AND english = :english")
    suspend fun deleteDay(day: String, chinese: String, english: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: WordLearningStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordDay(event: WordReviewDayEntity)

    @Query("SELECT * FROM word_review_days WHERE day = :day")
    suspend fun dailyAnswers(day: String): List<WordReviewDayEntity>

    @Query("DELETE FROM word_review_days WHERE day < :beforeDay")
    suspend fun pruneDays(beforeDay: String)

    @Query("DELETE FROM saved_words")
    suspend fun clearWords()

    @Query("DELETE FROM word_review_days")
    suspend fun clearDays()
}

internal class WordLearningStore(private val database: InputFootprintDatabase, private val isPractice: Boolean = false) {
    private val extra = database.learningExtraDao()
    private val base = database.wordLearningDao()
    private val dao = if (!isPractice) {
        base
    } else {
        object : WordLearningDao by base {
            override suspend fun state(): WordLearningStateEntity {
                val saved = extra.state()
                return (base.state() ?: WordLearningStateEntity()).copy(sessionJson = saved?.sessionJson, undoJson = saved?.undoJson)
            }
            override suspend fun saveState(state: WordLearningStateEntity) = extra.state(LearningPracticeState(sessionJson = state.sessionJson, undoJson = state.undoJson))
        }
    }
    val practice: WordLearningStore by lazy { WordLearningStore(database, true) }
    val progress = LearningProgress(database)
    val words = dao.observeWords()
    val generation get() = database.learningGeneration.get()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }
    val taskSummary = combine(words, dao.observeState(), dao.observeDays(), clock) { words, state, days, now ->
        summarize(words, state ?: WordLearningStateEntity(), days, now)
    }

    suspend fun summary(now: Long = System.currentTimeMillis()): WordTaskSummary = database.withTransaction {
        summarize(dao.words(), settings(), dao.dailyAnswers(dayKey(now)), now)
    }

    private fun summarize(words: List<SavedWordEntity>, state: WordLearningStateEntity, days: List<WordReviewDayEntity>, now: Long): WordTaskSummary {
        val today = days.filter { it.day == dayKey(now) }
        val answered = today.map { it.chinese to it.english }.toSet()
        val active = words.filter { it.learning }
        val available = active.filter { (it.chinese to it.english) !in answered }
        val fresh = available.count { it.reviewCount == 0 }
        val due = available.count { it.reviewCount > 0 && (it.nextReviewAt ?: Long.MAX_VALUE) <= now }
        val session = decodeSession(state.sessionJson)?.let { session ->
            session.copy(cards = session.cards.filter { card -> active.any { it.chinese == card.chinese && it.english == card.english } })
        }?.takeIf { it.cards.isNotEmpty() }
        return WordTaskSummary(
            session,
            active.size,
            fresh,
            due,
            minOf(fresh, (state.newLimit - today.count { it.wasNew }).coerceAtLeast(0)),
            minOf(due, (state.reviewLimit - today.count { !it.wasNew }).coerceAtLeast(0)),
            active.mapNotNull { it.nextReviewAt }.filter { it > now }.minOrNull(),
        )
    }

    suspend fun find(chinese: String, english: String): SavedWordEntity? = normalizeSavedEnglish(english)?.let { dao.find(chinese.trim(), it) }

    suspend fun saveMeaning(
        chinese: String,
        english: String,
        phonetic: String?,
        source: String,
        favorite: Boolean? = null,
        learning: Boolean? = null,
        now: Long = System.currentTimeMillis(),
        generation: Long = this.generation,
    ): SavedWordEntity = database.withTransaction {
        check(generation == this.generation) { "stale_learning_write" }
        val normalized = requireNotNull(normalizeSavedEnglish(english))
        val headword = chinese.trim()
        require(validLearningMeaning(headword))
        require(source in setOf("offline", "cloud", "import"))
        // Existing meanings are snapshots: a refreshed provider must never rewrite an answer.
        val current = dao.find(headword, normalized) ?: SavedWordEntity(headword, normalized, phonetic, source, now, displayEnglish = requireNotNull(displaySavedEnglish(english)))
        val saved = current.copy(favorite = favorite ?: current.favorite, learning = learning ?: current.learning)
        dao.save(saved)
        dao.prune(headword, normalized)
        if (!saved.learning) {
            progress.exclude(headword, normalized, now)
            invalidateUndoFor(headword, normalized)
        }
        saved
    }

    internal suspend fun invalidateUndoFor(headword: String, normalized: String) {
        val state = settings()
        val undo = decodeUndo(state.undoJson)
        if (undo?.session?.cards?.any { it.chinese == headword && it.english == normalized } == true) {
            dao.saveState(state.copy(undoJson = null))
        }
        val practiceState = extra.state()
        if (decodeUndo(practiceState?.undoJson)?.word?.let { it.chinese == headword && it.english == normalized } == true) {
            extra.state(requireNotNull(practiceState).copy(undoJson = null))
        }
    }

    suspend fun correctCase(chinese: String, english: String, display: String, generation: Long = this.generation) = database.withTransaction {
        if (generation != this.generation) return@withTransaction
        val word = find(chinese, english) ?: return@withTransaction
        require(normalizeSavedEnglish(display) == word.english)
        dao.save(word.copy(displayEnglish = requireNotNull(displaySavedEnglish(display))))
    }

    suspend fun settings(): WordLearningStateEntity = dao.state() ?: WordLearningStateEntity()

    suspend fun saveSettings(enabled: Boolean, newLimit: Int, reviewLimit: Int, reverse: Boolean, mode: ReviewMode? = null, generation: Long = this.generation) = database.withTransaction {
        if (generation != this.generation) return@withTransaction
        dao.saveState(settings().copy(planEnabled = enabled, newLimit = newLimit.coerceIn(1, 50), reviewLimit = reviewLimit.coerceIn(1, 100), reverse = reverse, reviewMode = mode?.name))
    }

    suspend fun session(): WordReviewSession? = decodeSession(settings().sessionJson)

    suspend fun setReviewMode(mode: ReviewMode, generation: Long = this.generation) = database.withTransaction {
        if (generation != this.generation) return@withTransaction
        dao.saveState(settings().copy(reviewMode = mode.name, reverse = mode == ReviewMode.CHINESE))
    }

    suspend fun saveSpellingDraft(token: String, draft: String) = database.withTransaction {
        val state = settings()
        val session = decodeSession(state.sessionJson) ?: return@withTransaction
        if (session.cards.firstOrNull()?.token != token || session.answerVisible || session.currentMode != ReviewMode.SPELLING) return@withTransaction
        dao.saveState(state.copy(sessionJson = json.encodeToString(session.copy(spellingDraft = draft.take(128)))))
    }

    suspend fun checkSpelling(token: String, draft: String): WordReviewSession? = database.withTransaction {
        val state = settings()
        val session = decodeSession(state.sessionJson) ?: return@withTransaction null
        val card = session.cards.firstOrNull() ?: return@withTransaction session
        if (card.token != token || session.answerVisible || session.currentMode != ReviewMode.SPELLING) return@withTransaction session
        val correct = spellingMatches(draft, card.english)
        val result = session.copy(answerVisible = true, spellingDraft = draft.take(128), spellingCorrect = correct, spellingOutcome = if (correct) SpellingOutcome.CORRECT else SpellingOutcome.WRONG)
        dao.saveState(state.copy(sessionJson = json.encodeToString(result)))
        result
    }

    suspend fun savedWords(): List<SavedWordEntity> = dao.words()

    suspend fun weaknesses(kind: WeaknessKind, now: Long = System.currentTimeMillis()): List<WeakWord> = database.withTransaction {
        weakWords(dao.words(), progress.events() + extra.practiceEvents().map { it.event() }, kind, now)
    }

    suspend fun startPractice(kind: WeaknessKind, now: Long = System.currentTimeMillis()): WordReviewSession = database.withTransaction {
        check(isPractice)
        val previous = session()
        if (previous != null && previous.cards.any { dao.find(it.chinese, it.english)?.learning == true }) return@withTransaction previous
        val selected = weaknesses(kind, now).take(10)
        val mode = if (kind == WeaknessKind.SPELLING) ReviewMode.SPELLING else ReviewMode.MIXED
        val attempts = extra.practiceEvents().map { it.event() }.filter { !it.undone && it.kind != "repeat" }.groupingBy { it.chinese to it.english }.eachCount()
        val result = WordReviewSession(cards = selected.mapIndexed { index, entry -> ReviewCard(entry.word.chinese, entry.word.english, mode = if (mode == ReviewMode.MIXED) mixedReviewMode(index + (attempts[entry.word.chinese to entry.word.english] ?: 0)) else null) }, mode = mode, practiceKind = kind)
        dao.saveState(settings().copy(sessionJson = json.encodeToString(result), undoJson = null))
        result
    }

    suspend fun startSession(daily: Boolean, extra: Boolean = false, now: Long = System.currentTimeMillis()): WordReviewSession = database.withTransaction {
        if (isPractice) return@withTransaction startPractice(session()?.practiceKind ?: WeaknessKind.FORGOTTEN, now)
        val state = settings()
        val savedWords = dao.words()
        val learningKeys = savedWords.filter { it.learning }.map { it.chinese to it.english }.toSet()
        val previous = decodeSession(state.sessionJson)?.takeIf { session -> session.cards.any { (it.chinese to it.english) in learningKeys } }
        val task = progress.ensure(now, state, previous)
        previous?.let { return@withTransaction it }
        val day = dayKey(now)
        val answers = dao.dailyAnswers(day)
        val answeredKeys = answers.map { it.chinese to it.english }.toSet()
        val available = savedWords.filter { (it.chinese to it.english) !in answeredKeys }
        val newLimit = if (daily) (state.newLimit - if (extra) 0 else answers.count { it.wasNew }).coerceAtLeast(0) else 5
        val reviewLimit = if (daily) (state.reviewLimit - if (extra) 0 else answers.count { !it.wasNew }).coerceAtLeast(0) else 5
        val selected = if (daily && !extra && task != null) {
            val keys = progress.remaining(day).map { it.key }.toSet()
            savedWords.filter { it.learning && (it.chinese to it.english) in keys }.sortedWith(compareBy({ it.reviewCount == 0 }, { it.nextReviewAt ?: Long.MAX_VALUE }, { it.createdAt }))
        } else {
            selectReviewWords(available, now, newLimit, reviewLimit, if (daily) newLimit + reviewLimit else 5)
        }
        val pending = progress.remaining(day).filter { it.pendingRepeat }.map { it.key }.toSet()
        val result = WordReviewSession(
            cards = selected.mapIndexed { index, word ->
                ReviewCard(
                    word.chinese,
                    word.english,
                    repeat = (word.chinese to word.english) in pending,
                    mode = if (state.selectedMode() == ReviewMode.MIXED) mixedReviewMode(index + word.reviewCount) else null,
                )
            },
            reverse = state.selectedMode() == ReviewMode.CHINESE,
            daily = daily,
            mode = state.selectedMode(),
        )
        dao.saveState(state.copy(sessionJson = json.encodeToString(result), undoJson = null))
        result
    }

    suspend fun remainingCount(now: Long = System.currentTimeMillis()): Int {
        val answered = dao.dailyAnswers(dayKey(now)).map { it.chinese to it.english }.toSet()
        return dao.words().count {
            it.learning && (it.chinese to it.english) !in answered &&
                (it.reviewCount == 0 || (it.nextReviewAt ?: Long.MAX_VALUE) <= now)
        }
    }

    suspend fun reveal(token: String): WordReviewSession? = database.withTransaction {
        val state = settings()
        val session = decodeSession(state.sessionJson) ?: return@withTransaction null
        if (session.cards.firstOrNull()?.token != token || session.answerVisible) return@withTransaction session
        val result = session.copy(answerVisible = true, spellingCorrect = if (session.currentMode == ReviewMode.SPELLING) false else null, spellingOutcome = if (session.currentMode == ReviewMode.SPELLING) SpellingOutcome.REVEALED else null)
        dao.saveState(state.copy(sessionJson = json.encodeToString(result)))
        result
    }

    suspend fun answer(token: String, rating: RecallRating, now: Long = System.currentTimeMillis()): WordReviewSession? = database.withTransaction {
        val state = settings()
        val session = decodeSession(state.sessionJson) ?: return@withTransaction null
        val card = session.cards.firstOrNull() ?: return@withTransaction session
        // The persisted card token makes double taps and retries idempotent across recreation.
        if (card.token != token || !session.answerVisible) return@withTransaction session
        // Looking at the solution or a failed spelling attempt cannot advance the interval as mastered.
        if (session.currentMode == ReviewMode.SPELLING && session.spellingCorrect != true && rating == RecallRating.REMEMBERED) return@withTransaction session
        val word = dao.find(card.chinese, card.english)?.takeIf { it.learning }
        val day = dayKey(now)
        if (isPractice) {
            if (word != null) extra.record(LearningPracticeEvent(card.token, json.encodeToString(LearningReviewEvent(card.token, word.chinese, word.english, now, day, rating.name, if (card.repeat) "repeat" else "practice", mode = session.currentMode.name, spellingOutcome = session.spellingOutcome?.name))))
            val result = advanceReview(session, rating.takeIf { word != null })
            dao.saveState(state.copy(sessionJson = json.encodeToString(result), undoJson = word?.let { json.encodeToString(ReviewUndo(card.token, it, session, day, false)) }))
            return@withTransaction result
        }
        progress.ensure(now, state, session)
        val insertedDay = word != null && dao.dailyAnswers(day).none { it.chinese == word.chinese && it.english == word.english }
        if (word != null) {
            if (!card.repeat) {
                dao.save(scheduleWord(word, rating, now))
            } else if (rating == RecallRating.FORGOTTEN) {
                // A same-session retry is practice, not a second daily completion or interval jump.
                dao.save(word.copy(stage = 0, nextReviewAt = maxOf(now, word.lastReviewedAt ?: now) + LEARNING_DAY_MS))
            }
            dao.recordDay(WordReviewDayEntity(day, word.chinese, word.english, !card.repeat && word.reviewCount == 0))
            progress.record(card, word, rating, now, session.currentMode, session.spellingOutcome)
        }
        val result = advanceReview(session, rating.takeIf { word != null })
        val undo = word?.let { ReviewUndo(card.token, it, session, day, insertedDay) }
        dao.saveState(state.copy(sessionJson = json.encodeToString(result), undoJson = undo?.let { json.encodeToString(it) }))
        result
    }

    suspend fun undoToken(): String? = decodeUndo(settings().undoJson)?.token

    suspend fun undoAnswer(token: String): WordReviewSession? = database.withTransaction {
        val state = settings()
        val undo = decodeUndo(state.undoJson)?.takeIf { it.token == token } ?: return@withTransaction decodeSession(state.sessionJson)
        val current = dao.find(undo.word.chinese, undo.word.english)?.takeIf { it.learning }
        if (current == null) {
            dao.saveState(state.copy(undoJson = null))
            return@withTransaction decodeSession(state.sessionJson)
        }
        if (isPractice) {
            extra.undo(token)
            val restored = undo.session.copy(cards = undo.session.cards.mapIndexed { index, card -> if (index == 0) card.copy(token = java.util.UUID.randomUUID().toString()) else card })
            dao.saveState(state.copy(sessionJson = json.encodeToString(restored), undoJson = null))
            return@withTransaction restored
        }
        // Restore only review fields; a later favorite or case correction is not a rating.
        dao.save(
            current.copy(
                stage = undo.word.stage,
                reviewCount = undo.word.reviewCount,
                lastReviewedAt = undo.word.lastReviewedAt,
                nextReviewAt = undo.word.nextReviewAt,
            ),
        )
        if (undo.insertedDay) dao.deleteDay(undo.day, current.chinese, current.english)
        progress.undo(token)
        // Rotate the restored token so callbacks from the old card cannot rate it again.
        val restored = undo.session.copy(
            cards = undo.session.cards.mapIndexed { index, card ->
                if (index == 0) card.copy(token = java.util.UUID.randomUUID().toString()) else card
            },
        )
        dao.saveState(state.copy(sessionJson = json.encodeToString(restored), undoJson = null))
        restored
    }

    suspend fun skipRemoved(token: String): WordReviewSession? = database.withTransaction {
        val state = settings()
        val session = decodeSession(state.sessionJson) ?: return@withTransaction null
        val card = session.cards.firstOrNull() ?: return@withTransaction session
        if (card.token != token) return@withTransaction session
        if (dao.find(card.chinese, card.english)?.learning == true) return@withTransaction session
        val result = advanceReview(session, null)
        dao.saveState(state.copy(sessionJson = json.encodeToString(result), undoJson = null))
        result
    }

    suspend fun clearAll() = database.withTransaction {
        extra.clearEvents()
        extra.clearState()
        if (isPractice) return@withTransaction
        database.wordbookDao().clear()
        dao.clearWords()
        dao.clearDays()
        progress.clear()
        dao.saveState(settings().copy(sessionJson = null, undoJson = null))
    }

    private fun decodeUndo(value: String?): ReviewUndo? = value?.let {
        runCatching { json.decodeFromString<ReviewUndo>(it) }.getOrNull()
    }

    private fun decodeSession(value: String?): WordReviewSession? = value?.let {
        runCatching { json.decodeFromString<WordReviewSession>(it) }.getOrNull()
    }

    private fun dayKey(now: Long): String = learningDay(now)
}
