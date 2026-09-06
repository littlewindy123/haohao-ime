// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/** Transparent overlay with only a bounded bottom panel, including during configuration changes. */
internal class ImeBootstrapLayout(context: Context) : FrameLayout(context) {
    val panel = LinearLayout(context)

    init {
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))
        setOnApplyWindowInsetsListener { _, insets ->
            val navigation = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
                .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            panel.updateLayoutParams<LayoutParams> { bottomMargin = navigation }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.heightPixels
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val params = panel.layoutParams as LayoutParams
        params.bottomMargin = params.bottomMargin.coerceIn(0, availableHeight / 2)
        params.height = minOf(
            (232f * resources.displayMetrics.density).toInt(),
            availableHeight / 2,
        ).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
