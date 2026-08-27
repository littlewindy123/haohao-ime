// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.unrolled

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_ACTION_GAP_DP
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_ACTION_HEIGHT_DP
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_ACTION_RAIL_WIDTH_DP
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.util.roundedRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView

@SuppressLint("ViewConstructor")
class UnrolledCandidateLayout(
    context: Context,
    private val theme: Theme,
    onReturn: () -> Unit,
    onDelete: () -> Unit,
) : ConstraintLayout(context) {
    val recyclerView =
        recyclerView {
            id = View.generateViewId()
            isVerticalScrollBarEnabled = false
        }

    private val actionRail =
        LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            val padding = dp(4)
            setPadding(padding, padding, padding, 0)
            addView(
                createActionButton(
                    label = context.getString(R.string.unrolled_candidate_return),
                    contentDescription = context.getString(R.string.unrolled_candidate_return),
                    textSize = 18f,
                    repeatable = false,
                    onClick = onReturn,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(UNROLLED_CANDIDATE_ACTION_HEIGHT_DP),
                ),
            )
            addView(
                createActionButton(
                    label = "\u232B",
                    contentDescription = context.getString(R.string.delete),
                    textSize = 28f,
                    repeatable = true,
                    onClick = onDelete,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(UNROLLED_CANDIDATE_ACTION_HEIGHT_DP),
                ).apply {
                    topMargin = dp(UNROLLED_CANDIDATE_ACTION_GAP_DP)
                },
            )
        }

    private val railDivider =
        View(context).apply {
            id = View.generateViewId()
            setBackgroundColor(ColorManager.getColor("candidate_separator_color"))
        }

    init {
        id = R.id.unrolled_candidate_view
        background =
            ColorManager.getDecorDrawable(
                "candidate_background",
                "candidate_border_color",
                dp(theme.generalStyle.candidateBorder),
                dp(theme.generalStyle.candidateBorderRound),
            )

        addView(
            actionRail,
            LayoutParams(dp(UNROLLED_CANDIDATE_ACTION_RAIL_WIDTH_DP), 0).apply {
                topToTop = LayoutParams.PARENT_ID
                bottomToBottom = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
            },
        )
        addView(
            railDivider,
            LayoutParams(dp(1), 0).apply {
                topToTop = LayoutParams.PARENT_ID
                bottomToBottom = LayoutParams.PARENT_ID
                endToStart = actionRail.id
            },
        )
        addView(
            recyclerView,
            LayoutParams(0, 0).apply {
                topToTop = LayoutParams.PARENT_ID
                bottomToBottom = LayoutParams.PARENT_ID
                startToStart = LayoutParams.PARENT_ID
                endToStart = railDivider.id
            },
        )
    }

    private fun createActionButton(
        label: String,
        contentDescription: String,
        textSize: Float,
        repeatable: Boolean,
        onClick: () -> Unit,
    ): GestureFrame = GestureFrame(context).apply {
        isClickable = true
        isRepeatable = repeatable
        this.onClick = onClick
        this.contentDescription = contentDescription
        background =
            roundedRippleDrawable(
                ColorManager.getColor("hilited_off_key_back_color"),
                dp(theme.generalStyle.roundCorner),
                ColorManager.getColor("off_key_back_color"),
            )
        addView(
            TextView(context).apply {
                text = label
                this.textSize = textSize
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(ColorManager.getColor("off_key_text_color"))
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
    }

    fun resetPosition() {
        recyclerView.scrollToPosition(0)
    }
}
