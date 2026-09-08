/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [InputFootprintEntity::class, SavedWordEntity::class, WordReviewDayEntity::class, WordLearningStateEntity::class, SavedSentenceEntity::class, SentenceSettingsEntity::class, LearningDayTask::class, LearningReviewEvent::class],
    version = 5,
    exportSchema = false,
)
abstract class InputFootprintDatabase : RoomDatabase() {
    internal abstract fun inputFootprintDao(): InputFootprintDao
    internal abstract fun wordLearningDao(): WordLearningDao
    internal abstract fun sentenceDao(): SentenceDao
    internal abstract fun learningProgressDao(): LearningProgressDao
}

internal val LEARNING_PROGRESS_MIGRATION = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_tasks (day TEXT NOT NULL, targets TEXT NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(day))")
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_events (token TEXT NOT NULL, chinese TEXT NOT NULL, english TEXT NOT NULL, occurredAt INTEGER NOT NULL, day TEXT NOT NULL, rating TEXT NOT NULL, kind TEXT NOT NULL, undone INTEGER NOT NULL, PRIMARY KEY(token))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_events_day ON learning_events(day)")
    }
}

internal val SENTENCE_MIGRATION = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS saved_sentences (chinese TEXT NOT NULL, english TEXT NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(chinese, english))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sentence_settings (id INTEGER NOT NULL, automatic INTEGER NOT NULL, PRIMARY KEY(id))")
    }
}

internal val WORD_LEARNING_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS saved_words (chinese TEXT NOT NULL, english TEXT NOT NULL, phonetic TEXT, source TEXT NOT NULL, createdAt INTEGER NOT NULL, favorite INTEGER NOT NULL, learning INTEGER NOT NULL, stage INTEGER NOT NULL, reviewCount INTEGER NOT NULL, lastReviewedAt INTEGER, nextReviewAt INTEGER, PRIMARY KEY(chinese, english))")
        db.execSQL("CREATE TABLE IF NOT EXISTS word_review_days (day TEXT NOT NULL, chinese TEXT NOT NULL, english TEXT NOT NULL, wasNew INTEGER NOT NULL, PRIMARY KEY(day, chinese, english))")
        db.execSQL("CREATE TABLE IF NOT EXISTS word_learning_state (id INTEGER NOT NULL, planEnabled INTEGER NOT NULL, newLimit INTEGER NOT NULL, reviewLimit INTEGER NOT NULL, reverse INTEGER NOT NULL, sessionJson TEXT, PRIMARY KEY(id))")
    }
}

internal val WORD_DISPLAY_UNDO_MIGRATION = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE saved_words ADD COLUMN displayEnglish TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE saved_words SET displayEnglish = english")
        db.execSQL("ALTER TABLE word_learning_state ADD COLUMN undoJson TEXT")
    }
}
