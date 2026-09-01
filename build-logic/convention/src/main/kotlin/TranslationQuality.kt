// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.Reader
import java.util.TreeSet

internal object TranslationQuality {
    private const val MAX_TRANSLATION_CODE_POINTS = 18
    private val LATIN_LETTER = Regex("[A-Za-z]")
    private val REPEATED_WHITESPACE = Regex("\\s{2,}")
    private val ABNORMAL_PUNCTUATION = Regex("[!?.,]{2,}")
    private val ALLOWED_TRANSLATION = Regex("[A-Za-z0-9][A-Za-z0-9 .,&+'\u2019?!-]*")
    private val SINGLE_ENGLISH_WORD = Regex("[A-Za-z]+(?:['\u2019-][A-Za-z]+)*")
    private val REJECTED_MARKERS =
        listOf(
            "CL:",
            "archaic",
            "classifier for",
            "dialect",
            "obsolete",
            "surname",
            "used in",
            "variant of",
        )

    data class RegressionCase(
        val text: String,
        val translation: String?,
        val category: String,
    )

    data class WeightedHeadword(
        val text: String,
        val weight: Long,
    )

    data class HardIssue(
        val text: String,
        val translation: String,
        val reasons: List<String>,
    )

    data class QualityReport(
        val totalCount: Int,
        val coveredCount: Int,
        val missing: List<WeightedHeadword>,
        val hardIssues: List<HardIssue>,
    )

    fun parseRegression(reader: Reader): List<RegressionCase> = reader.buffered().useLines { lines ->
        lines.mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@mapNotNull null
            val columns = line.split('\t')
            require(columns.size == 3) { "Malformed translation regression row: $rawLine" }
            val text = columns[0].trim()
            val expected = columns[1].trim()
            val category = columns[2].trim()
            require(text.isNotEmpty() && expected.isNotEmpty() && category.isNotEmpty()) {
                "Empty translation regression field: $rawLine"
            }
            RegressionCase(text, expected.takeUnless { it == "-" }, category)
        }.toList()
    }

    fun parseOverrides(reader: Reader): Map<String, String?> = buildMap {
        reader.buffered().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEach
                val columns = line.split('\t', limit = 2)
                require(columns.size == 2) { "Malformed translation override row: $rawLine" }
                val text = columns[0].trim()
                val translation = columns[1].trim()
                require(text.isNotEmpty() && translation.isNotEmpty()) {
                    "Empty translation override field: $rawLine"
                }
                put(text, translation.takeUnless { it == "-" })
            }
        }
    }

    fun selectTopWanxiang(
        source: Reader,
        limit: Int,
    ): List<WeightedHeadword> {
        require(limit > 0) { "Top headword limit must be positive" }
        val selected = TreeSet(BEST_FIRST)
        val selectedByText = HashMap<String, WeightedHeadword>(limit)
        var entriesStarted = false
        source.buffered().forEachLine { rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (!entriesStarted) {
                entriesStarted = line == "..."
                return@forEachLine
            }
            if (line.isBlank() || line.startsWith('#')) return@forEachLine
            val columns = line.split('\t')
            if (columns.size != 3) return@forEachLine
            val text = columns[0].trim()
            val weight = columns[2].trim().toLongOrNull() ?: return@forEachLine
            if (text.isEmpty()) return@forEachLine

            val candidate = WeightedHeadword(text, weight)
            val existing = selectedByText[text]
            if (existing != null) {
                if (weight > existing.weight) {
                    selected.remove(existing)
                    selected.add(candidate)
                    selectedByText[text] = candidate
                }
                return@forEachLine
            }
            if (selected.size < limit) {
                selected.add(candidate)
                selectedByText[text] = candidate
            } else if (BEST_FIRST.compare(candidate, selected.last()) < 0) {
                selectedByText.remove(selected.pollLast().text)
                selected.add(candidate)
                selectedByText[text] = candidate
            }
        }
        require(entriesStarted) { "Wanxiang dictionary header terminator was not found" }
        return selected.toList()
    }

    fun scan(
        topHeadwords: List<WeightedHeadword>,
        dictionary: Map<String, CedictDictionaryGenerator.GeneratedEntry>,
        expectedPronunciation: ((String) -> String?)? = null,
    ): QualityReport {
        val missing = ArrayList<WeightedHeadword>()
        val issues = ArrayList<HardIssue>()
        var coveredCount = 0
        topHeadwords.forEach { headword ->
            val entry = dictionary[headword.text]
            if (entry == null) {
                missing += headword
                return@forEach
            }
            coveredCount++
            val reasons = translationIssues(entry.translation).toMutableList()
            entry.phonetic?.let { phonetic ->
                if (!phonetic.startsWith('/') || !phonetic.endsWith('/') || phonetic.length <= 2) {
                    reasons += "invalid IPA format"
                }
                expectedPronunciation?.let { expected ->
                    if (expected(entry.translation) != phonetic) reasons += "IPA does not match translation"
                }
            }
            if (reasons.isNotEmpty()) {
                issues += HardIssue(headword.text, entry.translation, reasons.distinct())
            }
        }
        return QualityReport(topHeadwords.size, coveredCount, missing, issues)
    }

    internal fun translationIssues(translation: String): List<String> = buildList {
        if (translation.codePointCount(0, translation.length) > MAX_TRANSLATION_CODE_POINTS) add("too long")
        if (!LATIN_LETTER.containsMatchIn(translation)) add("no Latin letters")
        if (translation != translation.trim() || REPEATED_WHITESPACE.containsMatchIn(translation)) add("invalid whitespace")
        if (ABNORMAL_PUNCTUATION.containsMatchIn(translation)) add("abnormal punctuation")
        if (!ALLOWED_TRANSLATION.matches(translation)) add("unexpected characters")
        if (translation.any { it == '(' || it == ')' || it == '[' || it == ']' }) add("metadata brackets")
        if (REJECTED_MARKERS.any { translation.contains(it, ignoreCase = true) }) add("structural metadata")
        if (!SINGLE_ENGLISH_WORD.matches(translation)) add("not a single English word")
    }

    private val BEST_FIRST = Comparator<WeightedHeadword> { left, right ->
        val weightComparison = right.weight.compareTo(left.weight)
        if (weightComparison != 0) weightComparison else compareUtf8(left.text, right.text)
    }

    private fun compareUtf8(
        left: String,
        right: String,
    ): Int {
        val leftBytes = left.encodeToByteArray()
        val rightBytes = right.encodeToByteArray()
        val commonLength = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until commonLength) {
            val comparison =
                (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return leftBytes.size.compareTo(rightBytes.size)
    }
}
