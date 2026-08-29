/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.text.InputType
import com.osfans.trime.data.prefs.AppPrefs
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val HAOHAO_ONE_HAND_RAIL_WIDTH_DP = 52

internal data class HaoHaoKeyboardViewport(
    val leftInset: Int,
    val rightInset: Int,
    val contentWidth: Int,
)

internal fun calculateHaoHaoKeyboardViewport(
    availableWidth: Int,
    themePadding: Int,
    railWidth: Int,
    mode: AppPrefs.Keyboard.OneHandMode,
    landscape: Boolean,
): HaoHaoKeyboardViewport {
    val effectiveMode = if (landscape) AppPrefs.Keyboard.OneHandMode.OFF else mode
    val leftInset = if (effectiveMode == AppPrefs.Keyboard.OneHandMode.RIGHT) railWidth else themePadding
    val rightInset = if (effectiveMode == AppPrefs.Keyboard.OneHandMode.LEFT) railWidth else themePadding
    return HaoHaoKeyboardViewport(
        leftInset = leftInset,
        rightInset = rightInset,
        contentWidth = (availableWidth - leftInset - rightInset).coerceAtLeast(0),
    )
}

internal fun scaleHaoHaoKeyboardHeight(
    baseHeight: Int,
    mode: AppPrefs.Keyboard.KeyboardHeightMode,
    haoHaoTheme: Boolean,
): Int = if (haoHaoTheme) {
    (baseHeight * mode.percent / 100f).roundToInt()
} else {
    baseHeight
}

internal object HaoHaoGesturePolicy {
    fun canSlideCursor(composing: Boolean): Boolean = !composing

    fun canSlideDelete(
        composing: Boolean,
        password: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean = !composing &&
        !password &&
        selectionStart >= 0 &&
        selectionStart == selectionEnd
}

internal object HaoHaoShiftPolicy {
    fun shouldCommitSingleUppercase(
        asciiMode: Boolean,
        composing: Boolean,
        shifted: Boolean,
        keyCode: Int,
    ): Boolean = !asciiMode &&
        !composing &&
        shifted &&
        keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z

    fun uppercaseFor(keyCode: Int): String = ('A'.code + (keyCode - android.view.KeyEvent.KEYCODE_A)).toChar().toString()
}

internal class HaoHaoSlideDeleteController(
    private val readPreviousCodePoint: () -> String?,
    private val deletePreviousCodePoint: () -> Boolean,
    private val restoreText: (String) -> Boolean,
) {
    private val deletedText = ArrayDeque<String>()

    fun slide(delta: Int): Int {
        var completed = 0
        repeat(abs(delta)) {
            val success = if (delta < 0) deleteOne() else restoreOne()
            if (!success) return completed
            completed += 1
        }
        return completed
    }

    fun clear() {
        deletedText.clear()
    }

    private fun deleteOne(): Boolean {
        val text = readPreviousCodePoint() ?: return false
        if (!deletePreviousCodePoint()) return false
        deletedText.addFirst(text)
        return true
    }

    private fun restoreOne(): Boolean {
        val text = deletedText.firstOrNull() ?: return false
        if (!restoreText(text)) return false
        deletedText.removeFirst()
        return true
    }
}

internal fun isPasswordInputType(inputType: Int): Boolean {
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputClass) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}
