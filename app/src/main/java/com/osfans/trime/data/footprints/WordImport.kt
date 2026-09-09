package com.osfans.trime.data.footprints

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

@Serializable internal data class ImportWord(val english: String, val chinese: String, val phonetic: String? = null)
internal data class ImportIssue(val line: Int, val reason: String)
internal data class ImportPreview(val words: List<ImportWord>, val duplicates: Int, val issues: List<ImportIssue>)

/** Quoted CSV state machine, shared by file and tab-separated paste. No heuristic splitting. */
internal object WordImport {
    fun read(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= LEARNING_BACKUP_LIMIT) { "import_limit" }
            out.write(buffer, 0, n)
        }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString().removePrefix("\uFEFF")
    }
    fun preview(text: String, delimiter: Char = ',', swapped: Boolean = false): ImportPreview {
        require(text.toByteArray(Charsets.UTF_8).size <= LEARNING_BACKUP_LIMIT) { "import_limit" }
        val records = mutableListOf<Pair<Int, List<String>>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var line = 1
        var start = 1
        val source = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        var i = 0
        fun finishField() {
            fields.add(field.toString())
            field.setLength(0)
            closed = false
        }
        fun finishRow() {
            finishField()
            if (fields.any { it.isNotBlank() }) records.add(start to fields)
            require(records.size <= LEARNING_BACKUP_RECORD_LIMIT + 1) { "import_limit" }
            fields = mutableListOf()
        }
        while (i < source.length) {
            val ch = source[i++]
            when {
                quoted && ch == '"' -> if (i < source.length && source[i] == '"') {
                    field.append('"')
                    i++
                } else {
                    quoted = false
                    closed = true
                }
                quoted -> {
                    field.append(ch)
                    if (ch == '\n') line++
                }
                ch == delimiter -> finishField()
                ch == '\n' -> {
                    finishRow()
                    line++
                    start = line
                }
                ch == '"' -> {
                    require(field.isEmpty() && !closed) { "import_syntax:$line" }
                    quoted = true
                }
                else -> {
                    require(!closed) { "import_syntax:$line" }
                    field.append(ch)
                }
            }
        }
        require(!quoted) { "import_syntax:$start" }
        if (fields.isNotEmpty() || field.isNotEmpty() || closed) finishRow()
        val header = records.firstOrNull()?.second?.map { it.trim().lowercase() }
        if (header != null && header.size >= 2 && header[0] in setOf("english", "英文", "chinese", "中文", "中文释义") && header[1] in setOf("english", "英文", "chinese", "中文", "中文释义")) records.removeAt(0)
        require(records.size <= LEARNING_BACKUP_RECORD_LIMIT) { "import_limit" }
        val words = linkedMapOf<Pair<String, String>, ImportWord>()
        val issues = mutableListOf<ImportIssue>()
        var duplicates = 0
        for ((number, row) in records) {
            val english = row.getOrNull(if (swapped) 1 else 0)?.trim().orEmpty()
            val chinese = row.getOrNull(if (swapped) 0 else 1)?.trim().orEmpty()
            val en = normalizeSavedEnglish(english)
            val phonetic = row.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            val error = when {
                row.size !in 2..3 -> "columns"
                en == null -> "english"
                !validLearningMeaning(chinese) -> "chinese"
                (phonetic?.length ?: 0) > 512 || phonetic?.any { it.isISOControl() } == true -> "phonetic"
                else -> null
            }
            if (error != null) {
                issues.add(ImportIssue(number, error))
                continue
            }
            val key = chinese to requireNotNull(en)
            if (words.containsKey(key)) duplicates++ else words[key] = ImportWord(english, chinese, phonetic)
        }
        return ImportPreview(words.values.toList(), duplicates, issues)
    }
}
