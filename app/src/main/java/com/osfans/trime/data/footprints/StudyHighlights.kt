package com.osfans.trime.data.footprints

/** Literal ranges only: lexical forms are not machine-generated bilingual alignment. */
internal fun studyHighlightRanges(text: String, terms: List<String>, english: Boolean = true): List<IntRange> {
    val choices = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    if (choices.isEmpty()) return emptyList()
    val alternatives = choices.joinToString("|") { Regex.escape(it) }
    val expression = if (english) "(?<![\\p{L}\\p{N}_'’])(?:$alternatives)(?![\\p{L}\\p{N}_'’])" else "(?:$alternatives)"
    return Regex(expression, RegexOption.IGNORE_CASE).findAll(text).map { it.range }.toList()
}

internal fun studyEnglishTerms(word: String, entry: StudyWord?): List<String> = buildList {
    add(word)
    entry?.let {
        add(it.word)
        it.forms.forEach { form -> if (':' in form) addAll(form.substringAfter(':').split('/', ',')) }
    }
}.map(String::trim).filter { it.isNotEmpty() && it.all { ch -> ch.isLetter() || ch in " -'’" } }.distinct()

internal fun studyChineseTerm(meaning: String): List<String> = meaning.trim().let {
    // Full saved phrases only. Do not split multi-sense explanations into guessed translations.
    if (it.length in 2..12 && it.all { ch -> ch in '\u3400'..'\u9fff' }) listOf(it) else emptyList()
}
