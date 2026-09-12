// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

val String.Companion.EMPTY: String
    get() = ""

private const val SECTION_DIVIDER = ",.?!~:，。：～？！…\t\r\n\\/"

fun CharSequence.findSectionFrom(
    start: Int,
    forward: Boolean = false,
): Int {
    if (start !in 0..length) return -1
    return if (forward) {
        (start - 1 downTo 0).firstOrNull { SECTION_DIVIDER.contains(this[it]) } ?: 0
    } else {
        // Skip the current divider so repeated navigation always makes progress.
        (start until length).firstOrNull { it > start && SECTION_DIVIDER.contains(this[it]) } ?: length
    }
}

fun CharSequence.splitWithSurrogates(): List<String> = buildList {
    var sur = Char(0)
    for (ch in this@splitWithSurrogates) {
        if (ch.isHighSurrogate()) {
            sur = ch
        } else if (ch.isLowSurrogate()) {
            add(String(charArrayOf(sur, ch)))
        } else {
            add(ch.toString())
        }
    }
}
