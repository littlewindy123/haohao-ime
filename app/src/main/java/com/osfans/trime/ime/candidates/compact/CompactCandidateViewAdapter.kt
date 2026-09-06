/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidateViewHolder
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

internal class CompactCandidateViewAdapter(
    val theme: Theme,
) : BaseQuickAdapter<CompactCandidateCell, CandidateViewHolder>() {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.item.hashCode().toLong()

    var total: Int = -1
        private set

    var highlightedIdx: Int = -1
        private set

    fun updateCandidates(
        data: List<CompactCandidateCell>,
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
            layoutParams = RecyclerView.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(
        holder: CandidateViewHolder,
        position: Int,
        item: CompactCandidateCell?,
    ) {
        item ?: return
        val candidate = item.item.candidate
        val globalIndex = item.item.globalIndex
        val isHighlighted = globalIndex == highlightedIdx
        holder.ui.updateCompact(candidate, isHighlighted, item.compactTranslation, item.separateTranslationLane, firstCandidate = position == 0)
        holder.text = candidate.text
        holder.comment = candidate.comment
        holder.idx = globalIndex
        holder.ui.root.updateLayoutParams<RecyclerView.LayoutParams> {
            width = item.width
        }
    }
}
