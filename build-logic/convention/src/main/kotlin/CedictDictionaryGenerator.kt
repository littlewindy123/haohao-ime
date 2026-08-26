// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

internal object CedictDictionaryGenerator {
    private const val FORMAT_VERSION = 1
    private const val INDEX_RECORD_SIZE = 16
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0)
    private val ENTRY_PATTERN = Regex("^(\\S+)\\s+(\\S+)\\s+\\[{1,2}[^]]+]{1,2}\\s+/(.*)/$")
    private val STRUCTURAL_PREFIXES = listOf("CL:", "variant of", "old variant of", "see ", "abbr. for")
    private val LATIN_LETTER = Regex("[A-Za-z]")

    data class GeneratedDictionary(
        val release: String,
        val entries: List<Pair<String, String>>,
    )

    data class GenerationStats(
        val sourceEntries: Int,
        val uniqueSimplifiedHeadwords: Int,
        val generatedTranslations: Int,
    )

    fun generate(
        source: InputStream,
        overrides: Reader,
        release: String,
        output: OutputStream,
    ): GenerationStats {
        val translations = linkedMapOf<String, String>()
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
                    match.groupValues[3]
                        .split('/')
                        .asSequence()
                        .flatMap { it.split(';').asSequence() }
                        .map(String::trim)
                        .firstOrNull(::isEligibleDefinition)
                        ?: return@forEach
                translations.putIfAbsent(simplified, translation)
            }
        }

        overrides.buffered().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEach
                val columns = line.split('\t', limit = 2)
                if (columns.size != 2) return@forEach
                val sourceText = columns[0].trim()
                val translation = columns[1].trim()
                if (sourceText.isNotEmpty() && translation.isNotEmpty()) {
                    translations[sourceText] = translation
                }
            }
        }

        val entries =
            translations.entries
                .map { it.key.encodeToByteArray() to it.value.encodeToByteArray() }
                .sortedWith { left, right -> compareUnsigned(left.first, right.first) }
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
                val valueOffset = buffer.getInt(recordOffset + 8)
                val valueLength = buffer.getInt(recordOffset + 12)
                readUtf8(buffer, dataOffset + keyOffset, keyLength) to
                    readUtf8(buffer, dataOffset + valueOffset, valueLength)
            }
        return GeneratedDictionary(release, entries)
    }

    private fun isEligibleDefinition(definition: String): Boolean {
        if (definition.isEmpty() || !LATIN_LETTER.containsMatchIn(definition)) return false
        if (definition.codePointCount(0, definition.length) > 24) return false
        return STRUCTURAL_PREFIXES.none { definition.startsWith(it, ignoreCase = true) }
    }

    private fun writeDictionary(
        entries: List<Pair<ByteArray, ByteArray>>,
        release: String,
        output: OutputStream,
    ) {
        val releaseBytes = release.encodeToByteArray()
        val indexOffset = MAGIC.size + Int.SIZE_BYTES * 5 + releaseBytes.size
        val dataOffset = indexOffset + entries.size * INDEX_RECORD_SIZE
        val offsets = ArrayList<Int>(entries.size * 2)
        var payloadSize = 0
        entries.forEach { (key, value) ->
            offsets += payloadSize
            payloadSize += key.size
            offsets += payloadSize
            payloadSize += value.size
        }

        output.write(MAGIC)
        output.writeIntLe(FORMAT_VERSION)
        output.writeIntLe(releaseBytes.size)
        output.writeIntLe(entries.size)
        output.writeIntLe(indexOffset)
        output.writeIntLe(dataOffset)
        output.write(releaseBytes)
        entries.forEachIndexed { index, (key, value) ->
            output.writeIntLe(offsets[index * 2])
            output.writeIntLe(key.size)
            output.writeIntLe(offsets[index * 2 + 1])
            output.writeIntLe(value.size)
        }
        entries.forEach { (key, value) ->
            output.write(key)
            output.write(value)
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
