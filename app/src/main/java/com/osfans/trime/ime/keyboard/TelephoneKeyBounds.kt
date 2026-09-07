// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import kotlin.math.roundToInt

/** Four independent symbol targets beside three digit rows, followed by a shared bottom row. */
internal data class TelephoneKeyBounds(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun telephoneKeyBounds(width: Int, height: Int, nineKey: Boolean = false): List<TelephoneKeyBounds> {
    require(width >= 0 && height >= 0)
    val xs = listOf(0f, 16.5f, 16.5f + 67f / 3, 16.5f + 134f / 3, 83.5f, 100f)
        .map { (width * it / 100).roundToInt() }
    fun rect(left: Int, top: Int, right: Int, bottom: Int) = TelephoneKeyBounds(left, top, right - left, bottom - top)
    val rows = (0..4).map { height * it / 4 }
    val stripRows = (0..4).map { rows[3] * it / 4 }
    return buildList {
        repeat(4) { add(rect(0, stripRows[it], xs[1], stripRows[it + 1])) }
        repeat(3) { row ->
            for (column in 1..4) add(rect(xs[column], rows[row], xs[column + 1], rows[row + 1]))
        }
        val bottom = if (nineKey) listOf(0, xs[1], (width * .325f).roundToInt(), (width * .675f).roundToInt(), xs[4], width) else xs
        repeat(5) { add(rect(bottom[it], rows[3], bottom[it + 1], rows[4])) }
    }
}
