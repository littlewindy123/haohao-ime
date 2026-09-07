// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.keyboard

internal const val NINE_KEY_SCHEMA_ID = "haohao_pinyin_9"

internal fun nineKeyDigits(pinyin: String): String = pinyin.map { letter ->
    require(letter in 'a'..'z')
    "22233344455566677778889999"[letter - 'a']
}.joinToString("")

/** Only complete syllables at the start of the active numeric segment can be pinned. */
internal fun nineKeySpellings(input: String, syllables: List<String>): List<String> {
    val digits = input.takeWhile { it in '2'..'9' }
    if (digits.isEmpty()) return emptyList()
    return syllables.filter { digits.startsWith(nineKeyDigits(it)) }
        .sortedWith(compareBy<String> { it in setOf("m", "n", "ng") }.thenByDescending { it.length }.thenBy { it })
}
