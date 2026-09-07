// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.keyboard

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.util.roundedRippleDrawable
import splitties.dimensions.dp

internal fun createNineKeySpellingStrip(
    context: Context,
    input: String,
    spellings: List<String>,
    rowHeight: Int,
    onSelect: (String, String) -> Unit,
): ScrollView = ScrollView(context).apply {
    isFillViewport = true
    background = roundedRippleDrawable(
        ColorManager.getColor("hilited_off_key_back_color"),
        context.dp(7f),
        ColorManager.getColor("off_key_back_color"),
    )
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            spellings.forEach { spelling ->
                addView(
                    TextView(context).apply {
                        text = spelling
                        contentDescription = "筛选拼音 $spelling"
                        gravity = Gravity.CENTER
                        textSize = 17f
                        maxLines = 2
                        setTextColor(ColorManager.getColor("off_key_text_color"))
                        background = roundedRippleDrawable(
                            ColorManager.getColor("hilited_off_key_back_color"),
                            0f,
                            ColorManager.getColor("off_key_back_color"),
                        )
                        setOnClickListener { onSelect(spelling, input) }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight),
                )
            }
        },
    )
}
