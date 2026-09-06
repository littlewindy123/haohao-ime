// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import com.osfans.trime.R
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.candidates.bilingual.candidateSourceRowHeight
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
    private val theme: Theme,
    private val compatView: View,
    onPrimaryButtonClick: (String) -> Unit = {},
) : Ui {
    private var sentencePriority: Boolean? = null
    private val primaryButton = theme.toolBar.primaryButton
        ?.takeIf { it.action == HAOHAO_TOOLBOX_KEY }
        ?.let { config ->
            ToolButton(ctx, config).apply {
                setOnClickListener { onPrimaryButtonClick(config.action) }
            }
        }

    internal val leadingControlWidth: Int
        get() = if (primaryButton == null || primaryButton.visibility == View.GONE) 0 else ctx.dp(HAOHAO_TOOLBOX_BUTTON_WIDTH_DP)

    internal fun setSentencePriority(enabled: Boolean) {
        if (sentencePriority == enabled) return
        sentencePriority = enabled
        // Like the reference IME, composing text owns the row; tools remain on the idle toolbar.
        primaryButton?.visibility = if (enabled) View.GONE else View.VISIBLE
        compatView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // English may use the space below the expand control; Chinese keeps its own inset.
            endToStart = if (enabled) ConstraintLayout.LayoutParams.UNSET else unrollButton.id
            endToEnd = if (enabled) ConstraintLayout.LayoutParams.PARENT_ID else ConstraintLayout.LayoutParams.UNSET
        }
        unrollButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = if (enabled) ConstraintLayout.LayoutParams.UNSET else ConstraintLayout.LayoutParams.PARENT_ID
            val style = theme.generalStyle
            val size = style.compactCandidateTextSize ?: style.candidateTextSize
            topMargin = if (enabled) ctx.dp(((candidateSourceRowHeight(style.candidateViewHeight, size, ctx.resources.configuration.fontScale) - 48) / 2).coerceAtLeast(0)) else 0
        }
    }

    val unrollButton =
        ToolButton(
            ctx,
            R.drawable.ic_baseline_expand_more_24,
            if (ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID) 32 else theme.toolBar.builtinIconSize,
            theme.toolBar.builtinIconColor,
            theme.toolBar.builtinIconHighlightColor,
            scaleBuiltinIcon = ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID,
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
                lParams(dp(48), dp(48)) {
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
