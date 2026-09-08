/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.footprints

import androidx.room.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Entity(tableName = "learning_tasks")
internal data class LearningDayTask(
    @PrimaryKey val day: String,
    val targets: String,
    val completed: Boolean = false,
)

@Entity(tableName = "learning_events", indices = [Index("day")])
internal data class LearningReviewEvent(
    @PrimaryKey val token: String,
    val chinese: String,
    val english: String,
    val occurredAt: Long,
    val day: String,
    val rating: String,
    val kind: String,
    val undone: Boolean = false,
)

@Serializable
internal data class DailyLearningTarget(
    val chinese: String,
    val english: String,
    val wasNew: Boolean,
    val excluded: Boolean = false,
    val pendingRepeat: Boolean = false,
) {
    val key get() = chinese to english
}

internal data class LearningDayStats(val day: String, val fresh: Int, val reviewed: Int, val completed: Boolean) {
    val total get() = fresh + reviewed
}

internal data class LearningRatingCount(val rating: String, val count: Int)

internal data class LearningDashboard(
    val day: String,
    val task: LearningDayTask?,
    val targets: List<DailyLearningTarget>,
    val done: Int,
    val targetAnswered: Int,
    val days: List<LearningDayStats>,
    val ratings: Map<String, Int>,
    val streak: Int,
) {
    val today get() = days.firstOrNull { it.day == day } ?: LearningDayStats(day, 0, 0, false)
    val total get() = targets.count { !it.excluded }
    val checkins get() = days.count { it.completed }
    val studyDays get() = days.count { it.total > 0 }
}

internal fun learningDay(now: Long, zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date(now))

internal fun learningStreak(completed: Set<String>, today: String): Int {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val calendar = Calendar.getInstance(format.timeZone).apply { time = format.parse(today)!! }
    if (today !in completed) calendar.add(Calendar.DAY_OF_MONTH, -1)
    var count = 0
    while (format.format(calendar.time) in completed) { count++; calendar.add(Calendar.DAY_OF_MONTH, -1) }
    return count
}

internal fun taskIsComplete(targets: List<DailyLearningTarget>, answered: Set<Pair<String, String>>): Boolean {
    val active = targets.filterNot { it.excluded }
    return active.isNotEmpty() && active.all { it.key in answered && !it.pendingRepeat }
}

@Dao
internal interface LearningProgressDao {
    @Query("SELECT * FROM learning_tasks WHERE day = :day") suspend fun task(day: String): LearningDayTask?
    @Query("SELECT * FROM learning_tasks ORDER BY day DESC") suspend fun tasks(): List<LearningDayTask>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(task: LearningDayTask)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun record(event: LearningReviewEvent)
    @Query("SELECT * FROM learning_events WHERE token = :token") suspend fun event(token: String): LearningReviewEvent?
    @Query("SELECT * FROM learning_events WHERE undone = 0") suspend fun events(): List<LearningReviewEvent>
    @Query("SELECT EXISTS(SELECT 1 FROM learning_events WHERE day = :day AND undone = 0)") suspend fun hasFeedback(day: String): Boolean
    @Query("SELECT rating, COUNT(*) AS count FROM learning_events WHERE undone = 0 GROUP BY rating") suspend fun ratingCounts(): List<LearningRatingCount>
    @Query("UPDATE learning_events SET undone = 1 WHERE token = :token") suspend fun undo(token: String)
    @Query("SELECT * FROM word_review_days") suspend fun days(): List<WordReviewDayEntity>
    @Query("DELETE FROM learning_tasks") suspend fun clearTasks()
    @Query("DELETE FROM learning_events") suspend fun clearEvents()
}

/** Called within the learning store's Room transaction; never on an input callback. */
internal class LearningProgress(private val database: InputFootprintDatabase) {
    private val dao = database.learningProgressDao()
    private val words = database.wordLearningDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun targets(task: LearningDayTask?): List<DailyLearningTarget> = task?.let {
        // Corrupted task data must not turn into a successful empty goal.
        runCatching { json.decodeFromString<List<DailyLearningTarget>>(it.targets) }.getOrDefault(emptyList())
    }.orEmpty()

    suspend fun ensure(now: Long, state: WordLearningStateEntity, session: WordReviewSession?): LearningDayTask? {
        val day = learningDay(now)
        dao.task(day)?.let { return it }
        if (!state.planEnabled) return null
        val active = words.words().filter { it.learning }
        val byKey = active.associateBy { it.chinese to it.english }
        val answers = words.dailyAnswers(day)
        val completed = answers.mapNotNull { answer -> byKey[answer.chinese to answer.english]?.let {
            DailyLearningTarget(it.chinese, it.english, answer.wasNew)
        } }.let { rows -> rows.filter { it.wasNew }.take(state.newLimit) + rows.filterNot { it.wasNew }.take(state.reviewLimit) }
        val answeredKeys = answers.map { it.chinese to it.english }.toSet()
        val carry = session?.cards.orEmpty().mapNotNull { card -> byKey[card.chinese to card.english]?.let {
            DailyLearningTarget(it.chinese, it.english, it.reviewCount == 0, pendingRepeat = card.repeat)
        } }
        val included = (carry + completed).distinctBy { it.key }
        val selected = selectReviewWords(
            active.filter { (it.chinese to it.english) !in answeredKeys && included.none { target -> target.key == (it.chinese to it.english) } },
            now, (state.newLimit - included.count { it.wasNew }).coerceAtLeast(0),
            (state.reviewLimit - included.count { !it.wasNew }).coerceAtLeast(0), Int.MAX_VALUE,
        ).map { DailyLearningTarget(it.chinese, it.english, it.reviewCount == 0) }
        val task = LearningDayTask(day, json.encodeToString(included + selected))
        dao.save(task)
        refresh(day)
        return dao.task(day)
    }

    suspend fun remaining(day: String): List<DailyLearningTarget> {
        val answered = words.dailyAnswers(day).map { it.chinese to it.english }.toSet()
        return targets(dao.task(day)).filter { !it.excluded && (it.key !in answered || it.pendingRepeat) }
    }

    suspend fun record(card: ReviewCard, word: SavedWordEntity, rating: RecallRating, now: Long) {
        val day = learningDay(now)
        dao.record(LearningReviewEvent(card.token, word.chinese, word.english, now, day, rating.name,
            if (card.repeat) "repeat" else if (word.reviewCount == 0) "new" else "review"))
        val task = dao.task(day) ?: return
        val updated = targets(task).map { target ->
            if (target.key == (word.chinese to word.english)) target.copy(pendingRepeat = !card.repeat && rating == RecallRating.FORGOTTEN) else target
        }
        dao.save(task.copy(targets = json.encodeToString(updated)))
        refresh(day)
    }

    suspend fun undo(token: String) {
        val event = dao.event(token) ?: return
        dao.undo(token)
        val task = dao.task(event.day) ?: return
        val targets = targets(task).map {
            if (it.key == (event.chinese to event.english)) it.copy(pendingRepeat = event.kind == "repeat") else it
        }
        dao.save(task.copy(targets = json.encodeToString(targets)))
        refresh(event.day)
    }

    suspend fun exclude(chinese: String, english: String, now: Long) {
        val task = dao.task(learningDay(now)) ?: return
        dao.save(task.copy(targets = json.encodeToString(targets(task).map {
            if (it.key == (chinese to english)) it.copy(excluded = true) else it
        })))
        refresh(task.day)
    }

    private suspend fun refresh(day: String) {
        val task = dao.task(day) ?: return
        val answered = words.dailyAnswers(day).map { it.chinese to it.english }.toSet()
        val hasFeedback = dao.hasFeedback(day)
        dao.save(task.copy(completed = hasFeedback && taskIsComplete(targets(task), answered)))
    }

    suspend fun dashboard(now: Long): LearningDashboard = database.withTransaction {
        val today = learningDay(now)
        val tasks = dao.tasks().associateBy { it.day }
        val days = dao.days().groupBy { it.day }
        val stats = (days.keys + tasks.keys).sortedDescending().map { day ->
            val entries = days[day].orEmpty()
            LearningDayStats(day, entries.count { it.wasNew }, entries.count { !it.wasNew }, tasks[day]?.completed == true)
        }
        val targets = targets(tasks[today])
        val answered = days[today].orEmpty().map { it.chinese to it.english }.toSet()
        LearningDashboard(today, tasks[today], targets, targets.count { !it.excluded && it.key in answered && !it.pendingRepeat }, targets.count { !it.excluded && it.key in answered }, stats,
            dao.ratingCounts().associate { it.rating to it.count }, learningStreak(tasks.values.filter { it.completed }.map { it.day }.toSet(), today))
    }

    suspend fun clear() { dao.clearEvents(); dao.clearTasks() }
}
