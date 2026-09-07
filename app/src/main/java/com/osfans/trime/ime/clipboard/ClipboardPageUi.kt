/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.symbol.SpacesItemDecoration
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.recyclerview.recyclerView

class ClipboardPageUi(override val ctx: Context) : Ui {
    val emptyView = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setTextColor(ColorManager.getColor("key_text_color"))
        visibility = View.GONE
    }
    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    override val root = view(::FrameLayout) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyView, lParams(matchParent, matchParent))
    }
}
