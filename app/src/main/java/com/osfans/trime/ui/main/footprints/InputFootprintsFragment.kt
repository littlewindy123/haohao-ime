/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.footprints

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
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
import com.osfans.trime.databinding.FragmentInputFootprintsBinding
import com.osfans.trime.ime.candidates.bilingual.OfflineCandidateTranslationRepository
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class InputFootprintsFragment : Fragment(R.layout.fragment_input_footprints) {
    private var viewBinding: FragmentInputFootprintsBinding? = null
    private val binding: FragmentInputFootprintsBinding
        get() = requireNotNull(viewBinding)

    private val selectedTab = MutableStateFlow(Tab.RECENT)
    private val searchQuery = MutableStateFlow("")
    private val store
        get() = InputFootprints.store
    private val adapter = InputFootprintAdapter(::toggleFavorite, ::speak)

    private var textToSpeech: TextToSpeech? = null
    private var speechAvailable = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding = FragmentInputFootprintsBinding.bind(view)
        binding.footprintList.layoutManager = LinearLayoutManager(requireContext())
        binding.footprintList.adapter = adapter
        binding.recentTab.setOnClickListener { selectedTab.value = Tab.RECENT }
        binding.favoritesTab.setOnClickListener { selectedTab.value = Tab.FAVORITES }
        binding.searchInput.doAfterTextChanged { searchQuery.value = it?.toString().orEmpty() }
        binding.clearMenu.setOnClickListener(::showClearMenu)
        collectContent()
    }

    override fun onStart() {
        super.onStart()
        initializeSpeech()
    }

    override fun onStop() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        speechAvailable = false
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
                    store.counts.collect { counts ->
                        binding.footprintSummary.text = getString(
                            R.string.input_footprints_summary,
                            counts.recent,
                            counts.favorites,
                        )
                    }
                }
                launch {
                    combine(store.recent, store.favorites, selectedTab, searchQuery) { recent, favorites, tab, query ->
                        ContentRequest(if (tab == Tab.RECENT) recent else favorites, tab, query.trim())
                    }.map { request ->
                        RenderedContent(
                            tab = request.tab,
                            query = request.query,
                            items = filterInputFootprints(
                                request.items,
                                request.query,
                                OfflineCandidateTranslationRepository::lookup,
                            ),
                        )
                    }.flowOn(Dispatchers.Default).collect(::renderContent)
                }
            }
        }
    }

    private fun renderContent(content: RenderedContent) {
        binding.recentTab.isSelected = content.tab == Tab.RECENT
        binding.favoritesTab.isSelected = content.tab == Tab.FAVORITES
        adapter.submitList(content.items)
        binding.emptyState.isVisible = content.items.isEmpty()
        binding.footprintList.isVisible = content.items.isNotEmpty()
        binding.emptyTitle.setText(
            if (content.query.isNotEmpty()) {
                R.string.input_footprints_empty_search
            } else if (content.tab == Tab.FAVORITES) {
                R.string.input_footprints_empty_favorites
            } else {
                R.string.input_footprints_empty_recent
            },
        )
        binding.emptyMessage.setText(
            if (content.query.isNotEmpty()) {
                R.string.input_footprints_empty_search_message
            } else if (content.tab == Tab.FAVORITES) {
                R.string.input_footprints_empty_favorites_message
            } else {
                R.string.input_footprints_empty_recent_message
            },
        )
    }

    private fun toggleFavorite(item: InputFootprintListItem) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            store.setFavorite(
                text = item.footprint.text,
                favorite = !item.footprint.favorite,
                timestamp = System.currentTimeMillis(),
            )
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

    private fun initializeSpeech() {
        if (textToSpeech != null) return
        textToSpeech = TextToSpeech(requireContext()) { status ->
            val engine = textToSpeech ?: return@TextToSpeech
            speechAvailable = status == TextToSpeech.SUCCESS &&
                engine.setLanguage(Locale.US) !in setOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)
        }
    }

    private fun speak(text: String) {
        val engine = textToSpeech
        if (!speechAvailable || engine == null) {
            requireContext().toast(R.string.input_footprints_tts_unavailable)
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "haohao-footprint")
    }

    private enum class Tab {
        RECENT,
        FAVORITES,
    }

    private data class ContentRequest(
        val items: List<InputFootprintEntity>,
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
