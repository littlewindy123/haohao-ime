// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.View
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.haohao.HAOHAO_TOOLBOX_BUTTON_WIDTH_DP
import com.osfans.trime.ime.haohao.HAOHAO_TOOLBOX_KEY
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add

class CandidateUi(
    override val ctx: Context,
    theme: Theme,
    private val compatView: View,
    onPrimaryButtonClick: (String) -> Unit = {},
) : Ui {
    private val primaryButton = theme.toolBar.primaryButton
        ?.takeIf { it.action == HAOHAO_TOOLBOX_KEY }
        ?.let { config ->
            ToolButton(ctx, config).apply {
                setOnClickListener { onPrimaryButtonClick(config.action) }
            }
        }

    internal val leadingControlWidth: Int
        get() = if (primaryButton == null) 0 else ctx.dp(HAOHAO_TOOLBOX_BUTTON_WIDTH_DP)

    val unrollButton =
        ToolButton(
            ctx,
            R.drawable.ic_baseline_expand_more_24,
            theme.toolBar.builtinIconSize,
            theme.toolBar.builtinIconColor,
            theme.toolBar.builtinIconHighlightColor,
        ).apply {
            visibility = View.INVISIBLE
        }

    override val root =
        ctx.constraintLayout {
            primaryButton?.let { button ->
                add(
                    button,
                    lParams(dp(HAOHAO_TOOLBOX_BUTTON_WIDTH_DP), dp(HAOHAO_TOOLBOX_BUTTON_WIDTH_DP)) {
                        centerVertically()
                        startOfParent()
                    },
                )
            }
            add(
                unrollButton,
                lParams(dp(40)) {
                    centerVertically()
                    endOfParent()
                },
            )
            add(
                compatView,
                lParams {
                    centerVertically()
                    if (primaryButton == null) {
                        startOfParent(dp(theme.generalStyle.candidatePadding / 2))
                    } else {
                        after(primaryButton)
                    }
                    before(unrollButton)
                },
            )
        }
}
