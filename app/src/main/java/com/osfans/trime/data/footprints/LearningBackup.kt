package com.osfans.trime.data.footprints

import android.util.AtomicFile
import androidx.room.withTransaction
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

internal const val LEARNING_BACKUP_LIMIT = 16 * 1024 * 1024
internal const val LEARNING_BACKUP_RECORD_LIMIT = 100_000

@Serializable
internal data class LearningBackup(
    @Required val format: String = "haohao-learning",
    @Required val version: Int = 2,
    val exportedAt: Long,
    val words: List<SavedWordEntity>,
    val sentences: List<SavedSentenceEntity>,
    val settings: WordLearningStateEntity,
    val days: List<WordReviewDayEntity>,
    val tasks: List<LearningDayTask>,
    val events: List<LearningReviewEvent>,
    val practiceState: LearningPracticeState = LearningPracticeState(),
    val practiceEvents: List<LearningPracticeEvent> = emptyList(),
    val books: List<WordbookEntity> = emptyList(),
    val memberships: List<WordbookMember> = emptyList(),
)

internal object LearningBackupCodec {
    val json = Json { encodeDefaults = true }

    fun read(input: InputStream): LearningBackup {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= LEARNING_BACKUP_LIMIT) { "backup_too_large" }
            out.write(buffer, 0, count)
        }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        return json.decodeFromString<LearningBackup>(text).also(::validate)
    }

    fun encode(backup: LearningBackup): ByteArray {
        validate(backup)
        return json.encodeToString(backup).toByteArray(Charsets.UTF_8).also { require(it.size <= LEARNING_BACKUP_LIMIT) { "backup_too_large" } }
    }

    fun validate(b: LearningBackup) {
        require(b.format == "haohao-learning" && b.version in 1..2) { "backup_version" }
        require(b.version != 1 || (b.books.isEmpty() && b.memberships.isEmpty()))
        require(b.exportedAt > 0)
        require(listOf(b.words.size, b.sentences.size, b.days.size, b.tasks.size, b.events.size, b.practiceEvents.size, b.books.size, b.memberships.size).sumOf { it.toLong() } <= LEARNING_BACKUP_RECORD_LIMIT) { "backup_too_large" }
        fun text(s: String, max: Int) = s.isNotBlank() && s.length <= max && s.none { it == '\u0000' }
        fun key(zh: String, en: String) {
            require(validLearningMeaning(zh) && normalizeSavedEnglish(en) == en)
        }
        fun day(s: String) {
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(s))
            val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { isLenient = false }
            require(f.format(requireNotNull(f.parse(s))) == s)
        }
        fun session(raw: String?, practice: Boolean) {
            if (raw == null) return
            val s = json.decodeFromString<WordReviewSession>(raw)
            require(s.total in 0..150 && s.completed in 0..s.total && s.cards.size <= 300 && s.practiceMisses.size <= 150 && s.spellingDraft.length <= 128)
            require(!practice || (s.total <= 10 && s.cards.size <= 20 && s.practiceMisses.size <= 10))
            require(s.completed + s.cards.count { !it.repeat } <= s.total)
            require(s.cards.distinctBy { it.token }.size == s.cards.size)
            require((s.practiceKind != null) == practice && (!practice || !s.daily))
            require(practice || s.practiceMisses.isEmpty())
            require(s.spellingOutcome == null || (s.answerVisible && s.currentMode == ReviewMode.SPELLING && s.spellingCorrect == (s.spellingOutcome == SpellingOutcome.CORRECT)))
            (s.cards + s.practiceMisses).forEach {
                key(it.chinese, it.english)
                require(text(it.token, 128))
                require(it.mode != ReviewMode.MIXED)
            }
            require(!s.spellingCorrect.orFalse() || s.answerVisible)
        }
        fun undo(raw: String?, practice: Boolean) {
            if (raw == null) return
            val u = json.decodeFromString<ReviewUndo>(raw)
            require(text(u.token, 128))
            day(u.day)
            validateWord(u.word)
            session(json.encodeToString(u.session), practice)
            require(u.session.cards.firstOrNull()?.token == u.token)
            require(u.session.answerVisible && u.session.cards.first().let { it.chinese == u.word.chinese && it.english == u.word.english })
            require(!practice || !u.insertedDay)
        }
        fun event(e: LearningReviewEvent) {
            key(e.chinese, e.english)
            day(e.day)
            require(text(e.token, 128) && e.occurredAt >= 0 && e.rating in RecallRating.entries.map { it.name })
            require(e.kind in setOf("new", "review", "repeat", "practice"))
            require(e.mode == null || e.mode in ReviewMode.entries.filter { it != ReviewMode.MIXED }.map { it.name })
            require(e.spellingOutcome == null || (e.mode == ReviewMode.SPELLING.name && e.spellingOutcome in SpellingOutcome.entries.map { it.name }))
            require(e.spellingOutcome !in setOf(SpellingOutcome.WRONG.name, SpellingOutcome.REVEALED.name) || e.rating != RecallRating.REMEMBERED.name)
        }
        require(b.words.distinctBy { it.chinese to it.english }.size == b.words.size)
        b.words.forEach(::validateWord)
        require(b.books.distinctBy { it.id }.size == b.books.size)
        b.books.forEach { require(text(it.id, 128) && text(it.name, 40) && it.name.none(Char::isISOControl) && it.createdAt >= 0 && (it.catalogId == null || it.catalogId in setOf("basic", "cet4", "cet6"))) }
        val bookIds = b.books.map { it.id }.toSet()
        val wordKeys = b.words.map { it.chinese to it.english }.toSet()
        require(b.memberships.distinct().size == b.memberships.size)
        b.memberships.forEach { require(it.bookId in bookIds && (it.chinese to it.english) in wordKeys) }
        require(b.sentences.distinctBy { it.chinese to it.english }.size == b.sentences.size)
        b.sentences.forEach { require(validSentencePair(it.chinese, it.english) && text(it.source, 128) && it.createdAt >= 0 && it.lastUsedAt >= it.createdAt) }
        require(b.settings.id == 1 && b.settings.newLimit in 1..50 && b.settings.reviewLimit in 1..100)
        require(b.settings.reviewMode == null || b.settings.reviewMode in ReviewMode.entries.map { it.name })
        session(b.settings.sessionJson, false)
        undo(b.settings.undoJson, false)
        require(b.practiceState.id == 1)
        session(b.practiceState.sessionJson, true)
        undo(b.practiceState.undoJson, true)
        require(b.days.distinctBy { Triple(it.day, it.chinese, it.english) }.size == b.days.size)
        b.days.forEach {
            day(it.day)
            key(it.chinese, it.english)
        }
        require(b.tasks.distinctBy { it.day }.size == b.tasks.size)
        b.tasks.forEach { task ->
            day(task.day)
            val targets = json.decodeFromString<List<DailyLearningTarget>>(task.targets)
            require(targets.size <= 300 && targets.distinctBy { it.key }.size == targets.size)
            targets.forEach { key(it.chinese, it.english) }
        }
        val allEvents = b.events + b.practiceEvents.map {
            require(it.token == it.event().token)
            it.event()
        }
        require(allEvents.distinctBy { it.token }.size == allEvents.size)
        allEvents.forEach(::event)
        require(b.events.none { it.kind == "practice" })
        require(b.practiceEvents.all { it.event().kind in setOf("practice", "repeat") })
        // Removed words can legitimately remain in historical events and excluded day targets.
        b.settings.undoJson?.let { raw ->
            val u = json.decodeFromString<ReviewUndo>(raw)
            require(b.events.any { it.token == u.token && !it.undone && it.chinese == u.word.chinese && it.english == u.word.english && it.day == u.day } || b.events.isEmpty())
        }
        b.practiceState.undoJson?.let { raw ->
            val u = json.decodeFromString<ReviewUndo>(raw)
            require(b.practiceEvents.any { it.event().let { e -> e.token == u.token && !e.undone && e.chinese == u.word.chinese && e.english == u.word.english && e.day == u.day } })
        }
    }

    private fun Boolean?.orFalse() = this == true
    private fun validateWord(w: SavedWordEntity) {
        require(validLearningMeaning(w.chinese) && normalizeSavedEnglish(w.english) == w.english)
        require(w.displayEnglish.isEmpty() || normalizeSavedEnglish(w.displayEnglish) == w.english)
        require(w.source in setOf("offline", "cloud", "import") && (w.phonetic?.length ?: 0) <= 512)
        require(w.stage in 0..5 && w.reviewCount >= 0 && w.createdAt >= 0 && (w.lastReviewedAt ?: 0) >= 0 && (w.nextReviewAt ?: 0) >= 0)
    }
}

/** Explicit learning-only backup. No clipboard, input history, privacy settings or credentials. */
internal class LearningBackupStore(private val store: InputFootprintStore, rollbackFile: File) {
    private val db = store.database
    private val extra = db.learningExtraDao()
    private val rollback = AtomicFile(rollbackFile)
    private val json = LearningBackupCodec.json
    fun hasRollback() = rollback.baseFile.isFile

    suspend fun snapshot(now: Long = System.currentTimeMillis()): LearningBackup = db.withTransaction {
        LearningBackup(exportedAt = now, words = db.wordLearningDao().words(), sentences = extra.allSentences(), settings = db.wordLearningDao().state() ?: WordLearningStateEntity(), days = extra.allDays(), tasks = db.learningProgressDao().tasks(), events = extra.allEvents(), practiceState = extra.state() ?: LearningPracticeState(), practiceEvents = extra.practiceEvents(), books = db.wordbookDao().books(), memberships = db.wordbookDao().members())
    }

    suspend fun restore(backup: LearningBackup, mergeOnly: Boolean) {
        LearningBackupCodec.validate(backup)
        store.sentences.invalidatePending()
        db.withTransaction {
            if (mergeOnly) {
                val existing = db.wordLearningDao().words().map { it.chinese to it.english }.toSet()
                backup.words.filter { (it.chinese to it.english) !in existing }.forEach { db.wordLearningDao().save(it.copy(stage = 0, reviewCount = 0, lastReviewedAt = null, nextReviewAt = null)) }
                val sentences = extra.allSentences().map { it.chinese to it.english }.toSet()
                backup.sentences.filter { (it.chinese to it.english) !in sentences }.forEach { db.sentenceDao().put(it) }
                restoreBooks(backup)
            } else {
                val before = LearningBackupCodec.encode(snapshot())
                rollback.baseFile.parentFile?.mkdirs()
                val output = rollback.startWrite()
                try {
                    output.write(before)
                    rollback.finishWrite(output)
                } catch (e: Exception) {
                    rollback.failWrite(output)
                    throw e
                }
                replace(backup)
            }
        }
    }

    suspend fun rollBack() {
        val backup = rollback.openRead().use(LearningBackupCodec::read)
        store.sentences.invalidatePending()
        db.withTransaction { replace(backup) }
        rollback.delete()
    }

    private suspend fun replace(b: LearningBackup) {
        db.learningGeneration.incrementAndGet()
        // Renew every active/undo token together with its event references. Callbacks from
        // pages opened before a restore must never grade or undo the restored snapshot.
        val tokens = mutableMapOf<String, String>()
        fun session(raw: String?): String? = raw?.let {
            val s = json.decodeFromString<WordReviewSession>(it)
            json.encodeToString(s.copy(cards = s.cards.map { c -> c.copy(token = tokens.getOrPut(c.token) { UUID.randomUUID().toString() }) }))
        }
        fun undo(raw: String?): String? = raw?.let {
            val u = json.decodeFromString<ReviewUndo>(it)
            val token = tokens.getOrPut(u.token) { UUID.randomUUID().toString() }
            json.encodeToString(u.copy(token = token, session = json.decodeFromString(requireNotNull(session(json.encodeToString(u.session))))))
        }
        val settings = b.settings.copy(sessionJson = session(b.settings.sessionJson), undoJson = undo(b.settings.undoJson))
        val practice = b.practiceState.copy(sessionJson = session(b.practiceState.sessionJson), undoJson = undo(b.practiceState.undoJson))
        db.wordbookDao().clear()
        db.wordLearningDao().clearWords()
        db.wordLearningDao().clearDays()
        db.learningProgressDao().clearEvents()
        db.learningProgressDao().clearTasks()
        db.sentenceDao().clear(true)
        extra.clearEvents()
        extra.clearState()
        b.words.forEach { db.wordLearningDao().save(it) }
        restoreBooks(b)
        b.sentences.forEach { db.sentenceDao().put(it) }
        b.days.forEach { db.wordLearningDao().recordDay(it) }
        b.tasks.forEach { db.learningProgressDao().save(it) }
        b.events.forEach { db.learningProgressDao().record(it.copy(token = tokens[it.token] ?: it.token)) }
        b.practiceEvents.forEach {
            val e = it.event().copy(token = tokens[it.token] ?: it.token)
            extra.record(LearningPracticeEvent(e.token, json.encodeToString(e)))
        }
        db.wordLearningDao().saveState(settings)
        extra.state(practice)
    }

    private suspend fun restoreBooks(b: LearningBackup) {
        val books = if (b.version == 1) listOf(WordbookEntity(DEFAULT_WORDBOOK, "我的词本", 0)) else b.books
        val members = if (b.version == 1) b.words.map { WordbookMember(DEFAULT_WORDBOOK, it.chinese, it.english) } else b.memberships
        val store = WordbookStore(db)
        books.forEach { book ->
            if (store.dao.find(book.id) == null) store.dao.insert(book.copy(name = store.uniqueName(book.name)))
        }
        members.forEach { store.dao.attach(it) }
    }
}
