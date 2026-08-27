/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.Configuration
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateItem
import com.osfans.trime.ime.candidates.unrolled.toDisplayableUnrolledCandidates
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.instance
import splitties.views.dsl.recyclerview.recyclerView

internal fun resolveCompactCandidateCount(
    isLandscape: Boolean,
    portraitValue: Int,
    landscapeValue: Int,
): Int = if (isLandscape) landscapeValue.coerceIn(4, 12) else portraitValue.coerceIn(3, 8)

internal fun compactCandidateCellBasis(candidateCount: Int): Float = 1f / candidateCount.coerceAtLeast(1)

internal fun Array<CandidateProto>.toCompactCandidateItems(maxCount: Int): List<UnrolledCandidateItem> = toDisplayableUnrolledCandidates(startIndex = 0).take(maxCount)

class CompactCandidateDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    val service: TrimeInputMethodService by di.instance()
    val rime: RimeSession by di.instance()
    val theme: Theme by di.instance()
    private val inputView: InputView by di.instance()
    val bar: InputBarDelegate by di.instance()

    private val isLandscape =
        context.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT

    private fun maxCandidateCount(): Int = AppPrefs.defaultInstance().candidates.run {
        resolveCompactCandidateCount(
            isLandscape = isLandscape,
            portraitValue = compactCandidateCount.getValue(),
            landscapeValue = compactCandidateCountLandscape.getValue(),
        )
    }

    private val _unrolledCandidateOffset =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(childCount: Int) {
        _unrolledCandidateOffset.tryEmit(childCount)
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (adapter.total == childCount),
        )
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(theme).apply {
            setOnItemClickListener { _, _, position ->
                val globalIndex = items.getOrNull(position)?.globalIndex ?: return@setOnItemClickListener
                rime.launchOnReady { it.selectCandidate(globalIndex, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                val item = items.getOrNull(position) ?: return@setOnItemLongClickListener false
                inputView.showCandidateActionMenu(
                    item.globalIndex,
                    item.candidate.text,
                    view,
                    global = true,
                )
                true
            }
        }
    }

    val layoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollHorizontally(): Boolean = false

            override fun canScrollVertically(): Boolean = false

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                refreshUnrolled(childCount)
            }
        }
    }

    val view by lazy {
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
        }
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val (total, highlighted, candidates) = data

        val maxSpanCount = maxCandidateCount()
        val visibleCandidates = candidates.toCompactCandidateItems(maxSpanCount)

        adapter.updateCellBasis(compactCandidateCellBasis(visibleCandidates.size))
        adapter.updateCandidates(visibleCandidates, total, highlighted)

        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (visibleCandidates.isEmpty()) {
            refreshUnrolled(0)
        }
    }
}
