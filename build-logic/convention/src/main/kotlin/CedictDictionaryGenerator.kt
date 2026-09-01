// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.GZIPInputStream

internal object CedictDictionaryGenerator {
    private const val FORMAT_VERSION = 2
    private const val INDEX_RECORD_SIZE = 24
    private const val MAX_DEFINITION_CODE_POINTS = 18
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte(), 0)
    private val ENTRY_PATTERN = Regex("^(\\S+)\\s+(\\S+)\\s+\\[{1,2}([^]]+)]{1,2}\\s+/(.*)/$")
    private val STRUCTURAL_PREFIXES = listOf(
        "CL:",
        "abbr. for",
        "classifier for",
        "old variant of",
        "see ",
        "surname ",
        "used in ",
        "variant of",
    )
    private val REJECTED_USAGE_MARKERS = listOf("dialect", "archaic", "obsolete", "literary")
    private val LATIN_LETTER = Regex("[A-Za-z]")
    private val PARENTHETICAL = Regex("\\s*\\([^)]*\\)\\s*")
    private val BRACKETED_METADATA = Regex("\\s*\\[[^]]*]\\s*")
    private val WHITESPACE = Regex("\\s+")
    private val ACRONYM = Regex("^[A-Z][A-Z0-9.-]*$")
    private val ENGLISH_WORD = Regex("[A-Za-z]+(?:['\u2019-][A-Za-z]+)*")
    private val SIMPLE_DEFINITION_PREFIX = Regex("(?:to|a|an|the)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val ENGLISH_TOKEN = Regex("[a-z]+(?:['-][a-z]+)*")

    private data class DefinitionChoice(
        val text: String,
        val sourceIndex: Int,
    ) {
        val acronymPenalty = if (ACRONYM.matches(text)) 1 else 0
    }

    data class GeneratedDictionary(
        val release: String,
        val entries: List<GeneratedEntry>,
    )

    data class GeneratedEntry(
        val sourceText: String,
        val translation: String,
        val phonetic: String?,
    )

    private data class BinaryEntry(
        val key: ByteArray,
        val translation: ByteArray,
        val phonetic: ByteArray,
    )

    data class GenerationStats(
        val sourceEntries: Int,
        val uniqueSimplifiedHeadwords: Int,
        val generatedTranslations: Int,
    )

    fun generate(
        source: InputStream,
        overrides: Reader,
        pronunciations: InputStream,
        release: String,
        output: OutputStream,
    ): GenerationStats {
        val translations = linkedMapOf<String, String>()
        val translationPriorities = hashMapOf<String, Int>()
        val simplifiedHeadwords = hashSetOf<String>()
        var sourceEntries = 0
        GZIPInputStream(source).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEach
                val match = ENTRY_PATTERN.matchEntire(line) ?: return@forEach
                val simplified = match.groupValues[2]
                sourceEntries++
                simplifiedHeadwords += simplified
                val translation =
                    match.groupValues[4]
                        .split('/')
                        .asSequence()
                        .flatMap { it.split(';').asSequence() }
                        .mapIndexedNotNull { index, definition ->
                            normalizeDefinition(definition)?.let { DefinitionChoice(it, index) }
                        }
                        .minWithOrNull(
                            compareBy<DefinitionChoice>(DefinitionChoice::acronymPenalty)
                                .thenBy(DefinitionChoice::sourceIndex),
                        )
                        ?.text
                        ?: return@forEach
                val reading = match.groupValues[3].trimStart()
                val priority = if (reading.firstOrNull()?.isUpperCase() == true) 1 else 0
                val currentPriority = translationPriorities[simplified]
                if (currentPriority == null || priority < currentPriority) {
                    translations[simplified] = translation
                    translationPriorities[simplified] = priority
                }
            }
        }

        TranslationQuality.parseOverrides(overrides).forEach { (sourceText, translation) ->
            if (translation == null) {
                translations.remove(sourceText)
            } else {
                require(TranslationQuality.translationIssues(translation).isEmpty()) {
                    "Invalid translation override for $sourceText: $translation"
                }
                translations[sourceText] = translation
            }
        }

        val pronunciationIndex = readPronunciations(pronunciations)
        val entries =
            translations.entries
                .map { (key, translation) ->
                    BinaryEntry(
                        key = key.encodeToByteArray(),
                        translation = translation.encodeToByteArray(),
                        phonetic = findPronunciation(translation, pronunciationIndex)?.encodeToByteArray() ?: ByteArray(0),
                    )
                }.sortedWith { left, right -> compareUnsigned(left.key, right.key) }
        writeDictionary(entries, release, output)
        return GenerationStats(sourceEntries, simplifiedHeadwords.size, entries.size)
    }

    internal fun readForTesting(bytes: ByteArray): GeneratedDictionary {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC))
        require(buffer.int == FORMAT_VERSION)
        val releaseLength = buffer.int
        val entryCount = buffer.int
        val indexOffset = buffer.int
        val dataOffset = buffer.int
        val release = ByteArray(releaseLength).also(buffer::get).decodeToString()
        val entries =
            List(entryCount) { index ->
                val recordOffset = indexOffset + index * INDEX_RECORD_SIZE
                val keyOffset = buffer.getInt(recordOffset)
                val keyLength = buffer.getInt(recordOffset + 4)
                val translationOffset = buffer.getInt(recordOffset + 8)
                val translationLength = buffer.getInt(recordOffset + 12)
                val phoneticOffset = buffer.getInt(recordOffset + 16)
                val phoneticLength = buffer.getInt(recordOffset + 20)
                GeneratedEntry(
                    sourceText = readUtf8(buffer, dataOffset + keyOffset, keyLength),
                    translation = readUtf8(buffer, dataOffset + translationOffset, translationLength),
                    phonetic = phoneticLength.takeIf { it > 0 }?.let {
                        readUtf8(buffer, dataOffset + phoneticOffset, it)
                    },
                )
            }
        return GeneratedDictionary(release, entries)
    }

    private fun normalizeDefinition(rawDefinition: String): String? {
        val definition = rawDefinition.trim()
        if (definition.isEmpty()) return null
        if (STRUCTURAL_PREFIXES.any { definition.startsWith(it, ignoreCase = true) }) return null
        if (REJECTED_USAGE_MARKERS.any { definition.contains(it, ignoreCase = true) }) return null

        val normalized = definition
            .replace(PARENTHETICAL, " ")
            .replace(BRACKETED_METADATA, " ")
            .replace(WHITESPACE, " ")
            .trim(' ', ',', ';', ':', '-', '\u2013', '\u2014')
        if (normalized.isEmpty() || !LATIN_LETTER.containsMatchIn(normalized)) return null
        if (normalized.any { it == '(' || it == ')' || it == '[' || it == ']' }) return null
        val word = when {
            ENGLISH_WORD.matches(normalized) -> normalized
            else -> SIMPLE_DEFINITION_PREFIX.matchEntire(normalized)
                ?.groupValues
                ?.get(1)
                ?.takeIf(ENGLISH_WORD::matches)
        } ?: return null
        if (word.codePointCount(0, word.length) > MAX_DEFINITION_CODE_POINTS) return null
        return word
    }

    internal fun readPronunciations(source: InputStream): Map<String, String> = buildMap {
        GZIPInputStream(source).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { rawLine ->
                val columns = rawLine.split('\t', limit = 2)
                if (columns.size != 2) return@forEach
                val word = columns[0].trim().lowercase(Locale.ROOT)
                val pronunciation = normalizePronunciation(columns[1].substringBefore(',')) ?: return@forEach
                if (word.isNotEmpty()) putIfAbsent(word, pronunciation)
            }
        }
    }

    internal fun findPronunciation(
        translation: String,
        pronunciations: Map<String, String>,
    ): String? {
        val normalized = translation.lowercase(Locale.ROOT)
        pronunciations[normalized]?.let { return it }
        val tokens = ENGLISH_TOKEN.findAll(normalized).map(MatchResult::value).toList()
        if (tokens.isEmpty()) return null
        val parts = ArrayList<String>(tokens.size)
        tokens.forEach { token ->
            var pronunciation = pronunciations[token]
            if (pronunciation == null && '-' in token) {
                val splitParts = ArrayList<String>()
                token.split('-').forEach { part ->
                    val splitPronunciation = pronunciations[part] ?: return null
                    splitParts += splitPronunciation.removeSurrounding("/")
                }
                pronunciation = splitParts.joinToString(prefix = "/", separator = " ", postfix = "/")
            }
            pronunciation ?: return null
            parts += pronunciation.removeSurrounding("/")
        }
        return parts.joinToString(prefix = "/", separator = " ", postfix = "/")
    }

    private fun normalizePronunciation(raw: String): String? {
        val body = raw.trim().removeSurrounding("/").trim()
        return body.takeIf(String::isNotEmpty)?.let { "/$it/" }
    }

    private fun writeDictionary(
        entries: List<BinaryEntry>,
        release: String,
        output: OutputStream,
    ) {
        val releaseBytes = release.encodeToByteArray()
        val indexOffset = MAGIC.size + Int.SIZE_BYTES * 5 + releaseBytes.size
        val dataOffset = indexOffset + entries.size * INDEX_RECORD_SIZE
        val offsets = ArrayList<Int>(entries.size * 3)
        var payloadSize = 0
        entries.forEach { (key, translation, phonetic) ->
            offsets += payloadSize
            payloadSize += key.size
            offsets += payloadSize
            payloadSize += translation.size
            offsets += payloadSize
            payloadSize += phonetic.size
        }

        output.write(MAGIC)
        output.writeIntLe(FORMAT_VERSION)
        output.writeIntLe(releaseBytes.size)
        output.writeIntLe(entries.size)
        output.writeIntLe(indexOffset)
        output.writeIntLe(dataOffset)
        output.write(releaseBytes)
        entries.forEachIndexed { index, (key, translation, phonetic) ->
            output.writeIntLe(offsets[index * 3])
            output.writeIntLe(key.size)
            output.writeIntLe(offsets[index * 3 + 1])
            output.writeIntLe(translation.size)
            output.writeIntLe(offsets[index * 3 + 2])
            output.writeIntLe(phonetic.size)
        }
        entries.forEach { (key, translation, phonetic) ->
            output.write(key)
            output.write(translation)
            output.write(phonetic)
        }
    }

    private fun compareUnsigned(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        val commonLength = minOf(left.size, right.size)
        for (index in 0 until commonLength) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun readUtf8(
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
    ): String {
        val bytes = ByteArray(length)
        buffer.duplicate().position(offset).get(bytes)
        return bytes.decodeToString()
    }

    private fun OutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }
}
