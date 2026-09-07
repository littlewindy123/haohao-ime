/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.core.InputTabLayout
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent

class ClipboardTitleUi(override val ctx: Context, private val theme: Theme) : Ui {

    val tabLayout = InputTabLayout(ctx)

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24)
        .apply { contentDescription = ctx.getString(R.string.delete_all) }
    val addButton = ToolButton(ctx, R.drawable.ic_baseline_add_24)
        .apply { contentDescription = ctx.getString(R.string.haohao_add_phrase) }

    private val size = theme.generalStyle.run { candidateViewHeight + commentHeight }

    override val root = constraintLayout {
        add(
            tabLayout,
            lParams(matchConstraints, dp(size)) {
                startOfParent()
                before(addButton)
                centerVertically()
            },
        )
        add(
            addButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                before(deleteAllButton)
            },
        )
        add(
            deleteAllButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endOfParent()
            },
        )
    }
}
