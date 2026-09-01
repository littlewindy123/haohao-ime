/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidateViewHolder

open class PagingCandidateViewAdapter(
    val theme: Theme,
) : PagingDataAdapter<UnrolledCandidateItem, CandidateViewHolder>(diffCallback) {
    companion object {
        private val diffCallback =
            object : DiffUtil.ItemCallback<UnrolledCandidateItem>() {
                override fun areItemsTheSame(
                    oldItem: UnrolledCandidateItem,
                    newItem: UnrolledCandidateItem,
                ): Boolean = oldItem.globalIndex == newItem.globalIndex

                override fun areContentsTheSame(
                    oldItem: UnrolledCandidateItem,
                    newItem: UnrolledCandidateItem,
                ): Boolean = oldItem == newItem
            }
    }

    var offset: Int = 0
        private set

    var highlightedIndex: Int = -1
        private set

    fun refreshWith(offset: Int, highlightedIndex: Int) {
        this.offset = offset
        this.highlightedIndex = highlightedIndex
        refresh()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): CandidateViewHolder = CandidateViewHolder(
        CandidateItemUi(parent.context, theme, CandidateItemUi.LayoutMode.EXPANDED),
    )

    override fun onBindViewHolder(
        holder: CandidateViewHolder,
        position: Int,
    ) {
        val item = getItem(position) ?: return
        val candidate = item.candidate
        val idx = item.globalIndex
        val highlighted = idx == highlightedIndex
        holder.ui.update(candidate, highlighted)
        holder.text = candidate.text
        holder.comment = candidate.comment
        holder.idx = idx
        holder.presentationVersion = item.presentationVersion
    }
}
