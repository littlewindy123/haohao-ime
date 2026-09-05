/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.footprints

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprintEntity
import com.osfans.trime.data.footprints.SavedWordEntity
import com.osfans.trime.databinding.ItemInputFootprintBinding
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationEntry

internal data class InputFootprintListItem(
    val footprint: InputFootprintEntity,
    val translation: CandidateTranslationEntry,
    val savedWord: SavedWordEntity? = null,
)

internal fun filterInputFootprints(
    footprints: List<InputFootprintEntity>,
    query: String,
    lookup: (String) -> CandidateTranslationEntry?,
): List<InputFootprintListItem> {
    val normalizedQuery = query.trim().lowercase(java.util.Locale.US)
    return footprints.mapNotNull { footprint ->
        val translation = lookup(footprint.text) ?: CandidateTranslationEntry("", null)
        if (
            normalizedQuery.isNotEmpty() &&
            !footprint.text.contains(query.trim(), ignoreCase = true) &&
            !translation.translation.lowercase(java.util.Locale.US).contains(normalizedQuery)
        ) {
            return@mapNotNull null
        }
        InputFootprintListItem(footprint, translation)
    }
}

internal class InputFootprintAdapter(
    private val onFavorite: (InputFootprintListItem) -> Unit,
    private val onSpeak: (String) -> Unit,
    private val onOpen: (InputFootprintListItem) -> Unit,
) : ListAdapter<InputFootprintListItem, InputFootprintAdapter.ViewHolder>(Diff) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(
        ItemInputFootprintBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = holder.bind(getItem(position))

    inner class ViewHolder(
        private val binding: ItemInputFootprintBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: InputFootprintListItem) {
            val context = binding.root.context
            binding.chineseText.text = item.footprint.text
            binding.englishText.text = item.translation.translation.ifBlank { context.getString(R.string.words_no_meaning) }
            binding.ipaText.text = item.translation.phonetic.orEmpty()
            binding.ipaText.isVisible = item.translation.phonetic != null
            binding.useCount.text = item.savedWord?.takeIf { it.learning }?.let { WordLearningActivity.statusText(context, it) }
                ?: context.getString(R.string.input_footprints_use_count, item.footprint.useCount)
            binding.useCount.isVisible = item.footprint.useCount > 0 || item.savedWord?.learning == true
            binding.favoriteButton.setImageResource(
                if (item.footprint.favorite) R.drawable.ic_baseline_star_24 else R.drawable.ic_haohao_star_border_24,
            )
            ImageViewCompat.setImageTintList(
                binding.favoriteButton,
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (item.footprint.favorite) R.color.haohao_honey_pressed else R.color.haohao_cocoa,
                    ),
                ),
            )
            binding.favoriteButton.contentDescription = context.getString(
                if (item.footprint.favorite) {
                    R.string.input_footprints_remove_favorite
                } else {
                    R.string.input_footprints_add_favorite
                },
            )
            binding.favoriteButton.setOnClickListener { onFavorite(item) }
            binding.speakButton.setOnClickListener { onSpeak(item.translation.translation) }
            binding.speakButton.isEnabled = item.translation.translation.isNotBlank()
            binding.root.setOnClickListener { onOpen(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<InputFootprintListItem>() {
        override fun areItemsTheSame(
            oldItem: InputFootprintListItem,
            newItem: InputFootprintListItem,
        ): Boolean = oldItem.footprint.text == newItem.footprint.text && oldItem.translation.translation == newItem.translation.translation

        override fun areContentsTheSame(
            oldItem: InputFootprintListItem,
            newItem: InputFootprintListItem,
        ): Boolean = oldItem == newItem
    }
}
