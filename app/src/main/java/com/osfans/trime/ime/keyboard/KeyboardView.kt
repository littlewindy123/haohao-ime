/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.children
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate

internal fun calculateKeyVerticalPadding(
    cellHeight: Int,
    minimumVerticalGap: Int,
    capHeight: Int,
): Pair<Int, Int> {
    val safeCellHeight = cellHeight.coerceAtLeast(0)
    val safeMinimumGap = minimumVerticalGap.coerceAtLeast(0)
    if (capHeight <= 0) {
        val halfGap = safeMinimumGap / 2
        return halfGap to halfGap
    }

    val totalPadding =
        maxOf(
            safeMinimumGap,
            safeCellHeight - capHeight.coerceAtLeast(0),
        ).coerceIn(0, safeCellHeight)
    val paddingTop = totalPadding / 2
    return paddingTop to (totalPadding - paddingTop)
}

// TODO: move layout calculation responsibilities from Keyboard to KeyboardView using ConstraintLayout
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    val popup: PopupDelegate,
    val service: TrimeInputMethodService,
    private val keyboardActionListener: KeyboardActionListener,
    private val enterKeyDisplay: EnterKeyDisplayDelegate,
) : FrameLayout(context) {

    private val keys get() = keyboard.keys
    private var spellingStrip: ScrollView? = null
    private var spellingInput = ""

    fun showNineKeySpellings(input: String, spellings: List<String>, onSelect: (String, String) -> Unit) {
        if (input == spellingInput) return
        spellingInput = input
        spellingStrip?.let(::removeView)
        spellingStrip = null
        keys.take(4).forEachIndexed { index, _ -> getChildAt(index).visibility = VISIBLE }
        if (input.isEmpty() || spellings.isEmpty()) return
        keys.take(4).forEachIndexed { index, _ -> getChildAt(index).visibility = INVISIBLE }
        val stripHeight = keys[3].y + keys[3].height
        val gap = keyboard.horizontalGap / 2
        spellingStrip = createNineKeySpellingStrip(context, input, spellings, maxOf((stripHeight - keyboard.verticalGap) / 4, (40 * resources.displayMetrics.density).toInt()), onSelect).apply {
            layoutParams = LayoutParams(keys[0].width - gap * 2, stripHeight - keyboard.verticalGap).apply {
                leftMargin = gap
                topMargin = keyboard.verticalGap / 2
            }
        }.also(::addView)
    }

    internal val labelEnter: String
        get() = enterKeyDisplay.keyLabel
    internal val keyTextSize = theme.generalStyle.keyTextSize
    internal val keyLongTextSize = theme.generalStyle.keyLongTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val symbolTextSize = theme.generalStyle.symbolTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val popupOnKeyPress by AppPrefs.defaultInstance().keyboard.popupOnKeyPress
    internal val hookShiftArrow: Boolean by AppPrefs.defaultInstance().keyboard.hookShiftArrow
    internal val hideKeySymbol: Boolean by AppPrefs.defaultInstance().keyboard.hideKeySymbol
    internal val hideKeyHint: Boolean by AppPrefs.defaultInstance().keyboard.hideKeyHint

    init {
        setWillNotDraw(false)
        buildKeyViews()
    }

    private fun buildKeyViews() {
        removeAllViews()

        keys.forEachIndexed { index, key ->
            val keyView = createKeyView(index, key)
            addView(keyView)
        }
    }

    private fun createKeyView(index: Int, key: Key): KeyView = KeyView(context, key = key, keyboard = keyboard, keyboardView = this, keyboardActionListener = keyboardActionListener).apply {
        id = index

        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        layoutParams = LayoutParams(totalWidth, key.height)

        translationX = (key.x - key.extraWidthLeft).toFloat()
        translationY = key.y.toFloat()

        val (paddingTop, paddingBottom) =
            calculateKeyVerticalPadding(
                cellHeight = key.height,
                minimumVerticalGap = keyboard.verticalGap,
                capHeight = keyboard.keyCapHeight,
            )
        setPadding(
            keyboard.horizontalGap / 2 + key.extraWidthLeft,
            if (key.verticalGroupPosition > 0) 0 else paddingTop,
            keyboard.horizontalGap / 2 + key.extraWidthRight,
            if (key.verticalGroupPosition in 0..2) 0 else paddingBottom,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = keyboard.minWidth + paddingLeft + paddingRight
        val fullHeight = keyboard.height + paddingTop + paddingBottom

        val measuredWidth = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            fullWidth,
        )

        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, fullHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    fun invalidateAllKeys() {
        children.forEach { it.invalidate() }
    }

    fun invalidateKeyByIndex(index: Int) {
        getChildAt(index)?.invalidate()
    }

    val isCapsOn: Boolean
        get() = keyboard.mShiftKey?.isOn == true

    fun onDetach() {
        popup.dismissAll()
    }
}
