// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.Reader
import java.io.Writer
import java.text.Normalizer

object WanxiangDictionaryGenerator {
    data class Stats(
        val sourceEntries: Int,
        val generatedEntries: Int,
        val ignoredEntries: Int,
    )

    fun generate(
        source: Reader,
        release: String,
        output: Writer,
    ): Stats {
        var sourceEntries = 0
        var generatedEntries = 0
        var ignoredEntries = 0
        var entriesStarted = false

        output.appendLine("# Rime dictionary")
        output.appendLine("# encoding: utf-8")
        output.appendLine("# Generated from Rime Wanxiang $release.")
        output.appendLine("---")
        output.appendLine("name: haohao_wanxiang_core")
        output.appendLine("version: \"$release\"")
        output.appendLine("sort: by_weight")
        output.appendLine("...")

        source.buffered().forEachLine { rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (!entriesStarted) {
                entriesStarted = line == "..."
                return@forEachLine
            }
            if (line.isBlank() || line.startsWith('#')) return@forEachLine

            sourceEntries++
            val columns = line.split('\t')
            require(columns.size == 2 || columns.size == 3) {
                "Malformed Wanxiang entry at source entry $sourceEntries: expected 2 or 3 columns"
            }
            if (columns.size == 2) {
                ignoredEntries++
                return@forEachLine
            }

            val text = columns[0]
            val pinyin = normalizePinyin(columns[1])
            val weight = columns[2]
            require(text.isNotBlank()) { "Empty Wanxiang headword at source entry $sourceEntries" }
            require(weight.toLongOrNull() != null) {
                "Invalid Wanxiang weight at source entry $sourceEntries: $weight"
            }
            output.append(text).append('\t').append(pinyin).append('\t').append(weight).appendLine()
            generatedEntries++
        }
        require(entriesStarted) { "Wanxiang dictionary header terminator was not found" }
        output.flush()
        return Stats(sourceEntries, generatedEntries, ignoredEntries)
    }

    private fun normalizePinyin(raw: String): String {
        val umlautNormalized = buildString(raw.length) {
            raw.forEach { char ->
                append(
                    when (char) {
                        'ü', 'ǖ', 'ǘ', 'ǚ', 'ǜ', 'Ü', 'Ǖ', 'Ǘ', 'Ǚ', 'Ǜ' -> 'v'
                        else -> char
                    },
                )
            }
        }
        val normalized =
            Normalizer
                .normalize(umlautNormalized, Normalizer.Form.NFD)
                .filterNot { char ->
                    when (Character.getType(char)) {
                        Character.NON_SPACING_MARK.toInt(),
                        Character.COMBINING_SPACING_MARK.toInt(),
                        Character.ENCLOSING_MARK.toInt(),
                        -> true
                        else -> false
                    }
                }.trim()
                .replace(WHITESPACE, " ")
                .lowercase()
        require(PINYIN.matches(normalized)) { "Unsupported Wanxiang pinyin: $raw" }
        return normalized
    }

    private val WHITESPACE = Regex("\\s+")
    private val PINYIN = Regex("[a-z]+(?: [a-z]+)*")
}
