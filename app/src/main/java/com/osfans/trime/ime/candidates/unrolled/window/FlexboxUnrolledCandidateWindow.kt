/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled.window

import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Slide
import androidx.transition.Transition
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_COLUMNS
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_MIN_HEIGHT_DP
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_PHONETIC_HEIGHT_DP
import com.osfans.trime.ime.candidates.unrolled.PagingCandidateViewAdapter
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateLayout
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import org.kodein.di.instance
import splitties.dimensions.dp

class FlexboxUnrolledCandidateWindow : BaseUnrolledCandidateWindow() {
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val windowManager: BoardWindowManager by di.instance()

    override fun exitAnimation(nextWindow: BoardWindow): Transition = Slide().apply {
        slideEdge = Gravity.TOP
    }

    override val adapter by lazy {
        object : PagingCandidateViewAdapter(theme) {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): CandidateViewHolder = super.onCreateViewHolder(parent, viewType).apply {
                itemView.layoutParams =
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        context.dp(currentCandidateHeightDp()),
                    )
            }

            override fun onBindViewHolder(
                holder: CandidateViewHolder,
                position: Int,
            ) {
                super.onBindViewHolder(holder, position)
                holder.itemView.layoutParams.height = context.dp(currentCandidateHeightDp())
                bindCandidateUiViewHolder(holder)
            }
        }
    }

    override val layoutManager by lazy { GridLayoutManager(context, UNROLLED_CANDIDATE_COLUMNS) }

    private fun currentCandidateHeightDp(): Int = AppPrefs.defaultInstance().candidates.run {
        if (bilingualTranslation.getValue() && bilingualPhonetic.getValue()) {
            UNROLLED_CANDIDATE_PHONETIC_HEIGHT_DP
        } else {
            UNROLLED_CANDIDATE_MIN_HEIGHT_DP
        }
    }

    override fun onCreateCandidateLayout(): UnrolledCandidateLayout = UnrolledCandidateLayout(
        context = context,
        theme = theme,
        onReturn = { windowManager.attachWindow(KeyboardWindow) },
        onDelete = {
            commonKeyboardActionListener.listener.onAction(
                KeyActionManager.getAction(BACKSPACE_ACTION),
            )
        },
    ).apply {
        recyclerView.apply {
            adapter = this@FlexboxUnrolledCandidateWindow.adapter
            layoutManager = this@FlexboxUnrolledCandidateWindow.layoutManager
            addItemDecoration(
                GridDividerDecoration(
                    spanCount = UNROLLED_CANDIDATE_COLUMNS,
                    color = ColorManager.getColor("candidate_separator_color"),
                    thickness = context.dp(1),
                ),
            )
        }
    }

    private class GridDividerDecoration(
        color: Int,
        private val spanCount: Int,
        private val thickness: Int,
    ) : RecyclerView.ItemDecoration() {
        private val paint = Paint().apply { this.color = color }

        override fun onDrawOver(
            canvas: Canvas,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            repeat(parent.childCount) { childIndex ->
                val child = parent.getChildAt(childIndex)
                val position = parent.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION) return@repeat
                if ((position + 1) % spanCount != 0) {
                    canvas.drawRect(
                        (child.right - thickness).toFloat(),
                        child.top.toFloat(),
                        child.right.toFloat(),
                        child.bottom.toFloat(),
                        paint,
                    )
                }
                canvas.drawRect(
                    child.left.toFloat(),
                    (child.bottom - thickness).toFloat(),
                    child.right.toFloat(),
                    child.bottom.toFloat(),
                    paint,
                )
            }
        }
    }

    private companion object {
        const val BACKSPACE_ACTION = "BackSpace"
    }
}
