// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.footprints

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

@Serializable
internal data class StudyExample(val english: String, val chinese: String, val englishId: Long, val chineseId: Long, val englishAuthor: String, val chineseAuthor: String, val license: String)
@Serializable
internal data class StudyWord(val word: String, val phonetic: String, val meanings: List<String>, val definition: String, val forms: List<String>, val examples: List<StudyExample>)

/** A separate read-only lexical reference, never a source of changes to saved review answers. */
internal object StudyLexicon {
    private var database: SQLiteDatabase? = null
    private val cache = object : LinkedHashMap<String, StudyWord?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StudyWord?>?) = size > 128
    }
    suspend fun lookup(context: Context, english: String): StudyWord? = withContext(Dispatchers.IO) {
        synchronized(this@StudyLexicon) {
            val key = english.trim().lowercase(Locale.ROOT)
            if (cache.containsKey(key)) return@synchronized cache[key]
            try {
                val db = database ?: open(context.applicationContext).also { database = it }
                val query = "SELECT payload FROM entries WHERE word = ? UNION ALL SELECT payload FROM entries WHERE word IN (SELECT word FROM forms WHERE form = ? ORDER BY word LIMIT 1) LIMIT 1"
                val result = db.rawQuery(query, arrayOf(key, key)).use { cursor ->
                    if (cursor.moveToFirst()) Json.decodeFromString<StudyWord>(cursor.getString(0)) else null
                }
                cache[key] = result
                result
            } catch (_: Exception) { null }
        }
    }
    private fun open(context: Context): SQLiteDatabase {
        val manifest = context.assets.open("learning/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val digest = manifest.getString("sha256")
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val file = context.noBackupFilesDir.resolve("study-lexicon-$digest.db")
        if (!file.exists() || file.length() != manifest.getLong("bytes") || sha(file) != digest) {
            val temporary = context.noBackupFilesDir.resolve("study-lexicon.pending")
            context.assets.open("learning/lexicon.sqlite3").use { input -> temporary.outputStream().use(input::copyTo) }
            check(sha(temporary) == digest)
            check(temporary.renameTo(file))
        }
        return SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }
    private fun sha(file: java.io.File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
