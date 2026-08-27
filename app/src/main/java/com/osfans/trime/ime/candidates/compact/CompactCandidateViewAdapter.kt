/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateItem
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

open class CompactCandidateViewAdapter(
    val theme: Theme,
) : BaseQuickAdapter<UnrolledCandidateItem, CandidateViewHolder>() {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items.getOrNull(position).hashCode().toLong()

    var total: Int = -1
        private set

    var highlightedIdx: Int = -1
        private set

    var cellBasis: Float = 1f
        private set

    fun updateCellBasis(value: Float) {
        cellBasis = value
    }

    fun updateCandidates(
        data: List<UnrolledCandidateItem>,
        total: Int,
        highlightedIndex: Int,
    ) {
        super.submitList(data, null)
        this.total = total
        this.highlightedIdx = highlightedIndex
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): CandidateViewHolder {
        val ui = CandidateItemUi(context, theme)
        ui.root.apply {
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(
        holder: CandidateViewHolder,
        position: Int,
        item: UnrolledCandidateItem?,
    ) {
        item ?: return
        val candidate = item.candidate
        val globalIndex = item.globalIndex
        val isHighlighted = globalIndex == highlightedIdx
        holder.ui.update(candidate, isHighlighted)
        holder.text = candidate.text
        holder.comment = candidate.comment
        holder.idx = globalIndex
        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
            flexBasisPercent = this@CompactCandidateViewAdapter.cellBasis
            flexGrow = 0f
            flexShrink = 0f
        }
    }
}
