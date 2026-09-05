/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.footprints

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprintEntity
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.SavedWordEntity
import com.osfans.trime.data.footprints.normalizeSavedEnglish
import com.osfans.trime.databinding.FragmentInputFootprintsBinding
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationEntry
import com.osfans.trime.ime.candidates.bilingual.OfflineCandidateTranslationRepository
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class InputFootprintsFragment : Fragment(R.layout.fragment_input_footprints) {
    private var viewBinding: FragmentInputFootprintsBinding? = null
    private val binding: FragmentInputFootprintsBinding
        get() = requireNotNull(viewBinding)

    private val selectedTab = MutableStateFlow(Tab.RECENT)
    private val searchQuery = MutableStateFlow("")
    private val store
        get() = InputFootprints.store
    private val adapter = InputFootprintAdapter(::toggleFavorite, ::speak, ::openMeaning)

    private var speech: WordSpeech? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding = FragmentInputFootprintsBinding.bind(view)
        binding.footprintList.layoutManager = LinearLayoutManager(requireContext())
        binding.footprintList.itemAnimator = null
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            binding.wordHeader.setExpanded(false, false)
        }
        binding.footprintList.adapter = adapter
        binding.recentTab.setOnClickListener { selectedTab.value = Tab.RECENT }
        binding.favoritesTab.setOnClickListener { selectedTab.value = Tab.FAVORITES }
        binding.learningTab.setOnClickListener { selectedTab.value = Tab.LEARNING }
        binding.quickReview.setOnClickListener { WordLearningActivity.openReview(requireContext()) }
        binding.dailyPlan.setOnClickListener { WordLearningActivity.openReview(requireContext(), daily = true) }
        binding.searchInput.imeOptions = binding.searchInput.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        binding.searchInput.doAfterTextChanged { searchQuery.value = it?.toString().orEmpty() }
        binding.clearMenu.setOnClickListener(::showClearMenu)
        if (!InputFootprints.isAvailable) {
            binding.emptyTitle.setText(R.string.words_unavailable)
            binding.emptyMessage.text = ""
            binding.emptyState.isVisible = true
            binding.quickReview.isEnabled = false
            binding.dailyPlan.isEnabled = false
            binding.clearMenu.isEnabled = false
            return
        }
        collectContent()
    }

    override fun onStart() {
        super.onStart()
        speech = WordSpeech(requireContext())
    }

    override fun onStop() {
        speech?.close()
        speech = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding.footprintList.adapter = null
        viewBinding = null
        super.onDestroyView()
    }

    private fun collectContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    store.learning.taskSummary.collect { summary ->
                        binding.quickReview.text = when {
                            summary.active != null -> getString(R.string.words_resume, summary.active.cards.size)
                            summary.quickCount > 0 -> getString(R.string.words_review_count, summary.quickCount)
                            else -> getString(R.string.words_last_session)
                        }
                        binding.footprintSummary.text = WordLearningActivity.taskText(requireContext(), summary)
                    }
                }
                launch {
                    combine(store.recent, store.favorites, store.learning.words, selectedTab, searchQuery) { recent, favorites, saved, tab, query ->
                        ContentRequest(if (tab == Tab.RECENT) recent else favorites, saved, tab, query.trim())
                    }.map { request ->
                        RenderedContent(
                            tab = request.tab,
                            query = request.query,
                            items = buildWordRows(request),
                        )
                    }.flowOn(Dispatchers.Default).collect(::renderContent)
                }
            }
        }
    }

    private fun renderContent(content: RenderedContent) {
        binding.recentTab.isSelected = content.tab == Tab.RECENT
        binding.favoritesTab.isSelected = content.tab == Tab.FAVORITES
        binding.learningTab.isSelected = content.tab == Tab.LEARNING
        adapter.submitList(content.items)
        binding.emptyState.isVisible = content.items.isEmpty()
        binding.footprintList.isVisible = content.items.isNotEmpty()
        binding.emptyTitle.setText(
            if (content.query.isNotEmpty()) {
                R.string.input_footprints_empty_search
            } else if (content.tab == Tab.LEARNING) {
                R.string.words_empty_learning
            } else if (content.tab == Tab.FAVORITES) {
                R.string.input_footprints_empty_favorites
            } else {
                R.string.input_footprints_empty_recent
            },
        )
        binding.emptyMessage.setText(
            if (content.query.isNotEmpty()) {
                R.string.input_footprints_empty_search_message
            } else if (content.tab == Tab.LEARNING) {
                R.string.words_empty_learning_hint
            } else if (content.tab == Tab.FAVORITES) {
                R.string.input_footprints_empty_favorites_message
            } else {
                R.string.input_footprints_empty_recent_message
            },
        )
    }

    private fun toggleFavorite(item: InputFootprintListItem) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (item.savedWord != null) {
                    val word = item.savedWord
                    store.learning.saveMeaning(word.chinese, word.english, word.phonetic, word.source, favorite = !item.footprint.favorite)
                    if (item.footprint.favorite) store.setFavorite(item.footprint.text, false, System.currentTimeMillis())
                } else {
                    store.setFavorite(item.footprint.text, !item.footprint.favorite, System.currentTimeMillis())
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { context?.toast(R.string.words_error) }
            }
        }
    }

    private fun openMeaning(item: InputFootprintListItem) {
        WordLearningActivity.openMeaning(requireContext(), item.footprint.text, item.translation.translation, item.translation.phonetic, item.savedWord?.source ?: "offline")
    }

    private fun buildWordRows(request: ContentRequest): List<InputFootprintListItem> {
        val savedByKey = request.saved.associateBy { it.chinese to it.english }
        val legacy = if (request.tab == Tab.LEARNING) emptyList() else filterInputFootprints(request.items, "", OfflineCandidateTranslationRepository::lookup)
        val rows = legacy.map { row ->
            val saved = savedByKey[row.footprint.text to normalizeSavedEnglish(row.translation.translation)]
            row.copy(savedWord = saved, translation = saved?.let { CandidateTranslationEntry(it.displayEnglish, it.phonetic) } ?: row.translation, footprint = row.footprint.copy(favorite = row.footprint.favorite || saved?.favorite == true))
        }.toMutableList()
        if (request.tab != Tab.RECENT) {
            request.saved.filter { if (request.tab == Tab.LEARNING) it.learning else it.favorite }.forEach { word ->
                rows.removeAll { it.footprint.text == word.chinese && normalizeSavedEnglish(it.translation.translation) == word.english }
                rows += InputFootprintListItem(InputFootprintEntity(word.chinese, favorite = word.favorite), CandidateTranslationEntry(word.displayEnglish, word.phonetic), word)
            }
        }
        val query = request.query
        val filtered = rows.filter { query.isEmpty() || it.footprint.text.contains(query, true) || it.translation.translation.contains(query, true) }
        return if (request.tab == Tab.LEARNING) {
            filtered.sortedWith(
                compareBy(
                    { row ->
                        row.savedWord?.let {
                            if (it.reviewCount == 0) {
                                1
                            } else if ((it.nextReviewAt ?: Long.MAX_VALUE) <= System.currentTimeMillis()) {
                                0
                            } else {
                                2
                            }
                        } ?: 3
                    },
                    { it.savedWord?.nextReviewAt ?: 0 },
                ),
            )
        } else {
            filtered
        }
    }

    private fun showClearMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(MENU_GROUP, MENU_CLEAR_RECENT, MENU_CLEAR_RECENT, R.string.input_footprints_clear_recent)
            menu.add(MENU_GROUP, MENU_CLEAR_ALL, MENU_CLEAR_ALL, R.string.input_footprints_clear_all)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CLEAR_RECENT -> confirmClear(all = false)
                    MENU_CLEAR_ALL -> confirmClear(all = true)
                }
                true
            }
            show()
        }
    }

    private fun confirmClear(all: Boolean) {
        AlertDialog.Builder(requireContext())
            .setTitle(if (all) R.string.input_footprints_clear_all else R.string.input_footprints_clear_recent)
            .setMessage(
                if (all) {
                    R.string.input_footprints_clear_all_confirmation
                } else {
                    R.string.input_footprints_clear_recent_confirmation
                },
            ).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (all) store.clearAll() else store.clearRecent()
                    viewBinding?.root?.let {
                        Snackbar.make(it, R.string.input_footprints_cleared, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun speak(text: String) {
        speech?.speak(text)
    }

    private enum class Tab {
        RECENT,
        FAVORITES,
        LEARNING,
    }

    private data class ContentRequest(
        val items: List<InputFootprintEntity>,
        val saved: List<SavedWordEntity>,
        val tab: Tab,
        val query: String,
    )

    private data class RenderedContent(
        val tab: Tab,
        val query: String,
        val items: List<InputFootprintListItem>,
    )

    private companion object {
        const val MENU_GROUP = 200
        const val MENU_CLEAR_RECENT = 201
        const val MENU_CLEAR_ALL = 202
    }
}
