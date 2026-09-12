/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

/** Preserve command argument precedence without querying the editor until it is needed. */
internal fun resolveActiveText(
    type: Int,
    preedit: String,
    commitPreview: String?,
    lastCommitted: String,
    readSelection: () -> String?,
    readBeforeCursor: () -> String?,
    readAfterCursor: () -> String?,
): String {
    val selected by lazy(readSelection)
    val beforeCursor by lazy(readBeforeCursor)
    val afterCursor by lazy(readAfterCursor)
    val preferred = when (type) {
        1 -> lastCommitted
        2 -> preedit
        3 -> selected
        4 -> beforeCursor
        else -> null
    }
    return preferred?.takeIf { it.isNotEmpty() }
        ?: commitPreview?.takeIf { it.isNotEmpty() }
        ?: selected?.takeIf { it.isNotEmpty() }
        ?: lastCommitted.takeIf { it.isNotEmpty() }
        ?: beforeCursor?.takeIf { it.isNotEmpty() }
        ?: afterCursor.orEmpty()
}

/** Let Formatter handle indexing and escapes; each referenced argument is read at most once. */
internal fun expandActiveTextArgument(input: String, readText: (Int) -> String): String {
    if (!ACTIVE_TEXT_PLACEHOLDER.containsMatchIn(input)) return input
    val arguments = Array(4) { index ->
        object {
            private val value by lazy { readText(index + 1) }
            override fun toString(): String = value
            override fun hashCode(): Int = value.hashCode()
        }
    }
    return input.format(*arguments)
}

private val ACTIVE_TEXT_PLACEHOLDER = Regex("%([1-4]\\$)?s")
