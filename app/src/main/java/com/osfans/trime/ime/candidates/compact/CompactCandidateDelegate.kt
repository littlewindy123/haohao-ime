/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.bilingual.bilingualTranslationTextSize
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateItem
import com.osfans.trime.ime.candidates.unrolled.toDisplayableUnrolledCandidates
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.max
import kotlin.math.roundToInt

internal const val COMPACT_CANDIDATE_MIN_WIDTH_DP = 48
internal const val COMPACT_CANDIDATE_MAX_WIDTH_DP = 112
internal const val COMPACT_CANDIDATE_HORIZONTAL_PADDING_DP = 10
internal const val COMPACT_CANDIDATE_PORTRAIT_MIN = 3
internal const val COMPACT_CANDIDATE_PORTRAIT_MAX = 5
internal const val COMPACT_CANDIDATE_PORTRAIT_DEFAULT = 4
internal const val COMPACT_CANDIDATE_LANDSCAPE_MIN = 5
internal const val COMPACT_CANDIDATE_LANDSCAPE_MAX = 8
internal const val COMPACT_CANDIDATE_LANDSCAPE_DEFAULT = 6
private const val COMPACT_PRIMARY_TRANSLATION_MIN_LETTERS = 8
private const val COMPACT_PRIMARY_TRANSLATION_MAX_LETTERS = 10
private const val COMPACT_SECONDARY_TRANSLATION_MAX_LETTERS = 8

private val PINYIN_SYLLABLE = Regex("[a-züv]+", RegexOption.IGNORE_CASE)
private val PINYIN_SEPARATOR = Regex("[\\s']+")
private const val PREEDIT_CARET = '\u2038'
private const val TRANSLATION_WIDTH_SAMPLE = "abcdefghij"

internal fun resolveCompactCandidateCount(
    isLandscape: Boolean,
    portraitValue: Int,
    landscapeValue: Int,
): Int = if (isLandscape) {
    landscapeValue.coerceIn(COMPACT_CANDIDATE_LANDSCAPE_MIN, COMPACT_CANDIDATE_LANDSCAPE_MAX)
} else {
    portraitValue.coerceIn(COMPACT_CANDIDATE_PORTRAIT_MIN, COMPACT_CANDIDATE_PORTRAIT_MAX)
}

private fun explicitPinyinSyllableCount(preedit: String?): Int {
    val value = preedit?.replace(PREEDIT_CARET.toString(), "")?.trim().orEmpty()
    if (value.none { it.isWhitespace() || it == '\'' }) return 0
    val syllables = value.split(PINYIN_SEPARATOR).filter(String::isNotEmpty)
    return syllables.size.takeIf { it >= 2 && syllables.all(PINYIN_SYLLABLE::matches) } ?: 0
}

private fun String.commonHanCharacterCount(): Int {
    var offset = 0
    var count = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        if (codePoint !in 0x4E00..0x9FFF) return 0
        count += 1
        offset += Character.charCount(codePoint)
    }
    return count
}

internal fun Array<CandidateProto>.toCompactCandidateItems(
    maxCount: Int,
    preedit: String? = null,
): List<UnrolledCandidateItem> {
    val candidates = toDisplayableUnrolledCandidates(startIndex = 0)
    val syllableCount = explicitPinyinSyllableCount(preedit)
    if (syllableCount < 2) return candidates.take(maxCount)

    val (phrases, fallback) = candidates.partition {
        it.candidate.text.commonHanCharacterCount() >= syllableCount
    }
    return (phrases + fallback).take(maxCount)
}

internal fun compactCandidateCellWidth(
    contentWidth: Int,
    minWidth: Int,
    horizontalPadding: Int,
    maxWidth: Int,
    reservedWidth: Int = 0,
): Int = max(contentWidth + horizontalPadding * 2, reservedWidth).coerceIn(minWidth, maxWidth)

internal data class CompactCandidateCell(
    val item: UnrolledCandidateItem,
    val width: Int,
)

internal data class CompactCandidateWidthBounds(
    val minimum: Int,
    val preferred: Int,
)

internal data class CompactTranslationWidthLimits(
    val primaryMinimum: Int,
    val primaryMaximum: Int,
    val secondaryMaximum: Int,
)

private fun distributeWidth(
    widths: MutableList<Int>,
    indexes: IntRange,
    remaining: Int,
    limitAt: (Int) -> Int,
): Int {
    var left = remaining
    while (left > 0) {
        val growable = indexes.filter { widths[it] < limitAt(it) }
        if (growable.isEmpty()) break
        val share = max(1, left / growable.size)
        growable.forEach { index ->
            val delta = minOf(share, limitAt(index) - widths[index], left)
            widths[index] += delta
            left -= delta
        }
    }
    return left
}

private fun shrinkWidth(
    widths: MutableList<Int>,
    minimums: List<Int>,
    indexes: IntRange,
    overflow: Int,
): Int {
    var left = overflow
    while (left > 0) {
        val shrinkable = indexes.filter { widths[it] > minimums[it] }
        if (shrinkable.isEmpty()) break
        val share = max(1, left / shrinkable.size)
        shrinkable.forEach { index ->
            val delta = minOf(share, widths[index] - minimums[index], left)
            widths[index] -= delta
            left -= delta
        }
    }
    return left
}

internal fun fitCompactCandidateRow(
    candidates: List<UnrolledCandidateItem>,
    targetCount: Int,
    availableWidth: Int,
    translationLimits: CompactTranslationWidthLimits? = null,
    widthOf: (UnrolledCandidateItem) -> CompactCandidateWidthBounds,
): List<CompactCandidateCell> {
    if (targetCount <= 0 || availableWidth <= 0) return emptyList()
    val selected = candidates.take(targetCount).map { it to widthOf(it) }.toMutableList()
    while (selected.sumOf { it.second.minimum } > availableWidth) {
        if (selected.isEmpty()) return emptyList()
        selected.removeAt(selected.lastIndex)
    }
    if (selected.isEmpty()) return emptyList()

    val minimums = selected.map { it.second.minimum.coerceAtLeast(1) }
    val widths = selected.mapIndexed { index, (_, bounds) ->
        bounds.preferred.coerceAtLeast(minimums[index])
    }.toMutableList()
    var overflow = (widths.sum() - availableWidth).coerceAtLeast(0)
    if (widths.size > 1) {
        overflow = shrinkWidth(widths, minimums, 1..widths.lastIndex, overflow)
    }
    if (overflow > 0) {
        shrinkWidth(widths, minimums, 0..0, overflow)
    }

    translationLimits?.let { limits ->
        var remaining = availableWidth - widths.sum()
        remaining = distributeWidth(widths, 0..0, remaining) { limits.primaryMinimum }
        if (widths.size > 1) {
            remaining = distributeWidth(widths, 1..widths.lastIndex, remaining) {
                limits.secondaryMaximum
            }
        }
        distributeWidth(widths, 0..0, remaining) { limits.primaryMaximum }
    }

    return selected.mapIndexed { index, (item, _) -> CompactCandidateCell(item, widths[index]) }
}

class CompactCandidateDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    val service: TrimeInputMethodService by di.instance()
    val rime: RimeSession by di.instance()
    val theme: Theme by di.instance()
    private val inputView: InputView by di.instance()
    val bar: InputBarDelegate by di.instance()
    private val candidatePreferences = AppPrefs.defaultInstance().candidates

    private var latestCandidates: Candidates.Bulk? = null
    private var currentPreedit: String? = null

    private val isLandscape =
        context.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT

    private fun targetCandidateCount(): Int = candidatePreferences.run {
        resolveCompactCandidateCount(
            isLandscape = isLandscape,
            portraitValue = compactCandidateCount.getValue(),
            landscapeValue = compactCandidateCountLandscape.getValue(),
        )
    }

    private fun scaledPixels(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        context.resources.displayMetrics,
    )

    private val candidateTextPaint by lazy {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledPixels(theme.generalStyle.candidateTextSize)
            typeface = FontManager.getTypeface("candidate_font")
        }
    }

    private val commentTextPaint by lazy {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledPixels(theme.generalStyle.commentTextSize)
            typeface = FontManager.getTypeface("comment_font")
        }
    }

    private val translationTextPaint by lazy {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledPixels(
                bilingualTranslationTextSize(
                    theme.generalStyle.candidateTextSize,
                    theme.generalStyle.commentTextSize,
                ),
            )
            typeface = FontManager.getTypeface("comment_font")
        }
    }

    private fun translationReservedWidth(letterBudget: Int): Int {
        val sample = TRANSLATION_WIDTH_SAMPLE.take(letterBudget)
        return translationTextPaint.measureText(sample).roundToInt() +
            context.dp(theme.generalStyle.candidatePadding * 2)
    }

    private fun measureCandidateWidth(item: UnrolledCandidateItem): CompactCandidateWidthBounds {
        val candidate = item.candidate
        val textWidth = candidateTextPaint.measureText(candidate.text).roundToInt()
        val commentWidth = commentTextPaint.measureText(candidate.comment).roundToInt()
        val contentWidth = when (theme.generalStyle.commentPosition) {
            GeneralStyle.CommentPosition.RIGHT ->
                textWidth + if (candidate.comment.isEmpty()) 0 else commentWidth + context.dp(1)
            GeneralStyle.CommentPosition.TOP,
            GeneralStyle.CommentPosition.OVERLAY,
            -> max(textWidth, commentWidth)
        }
        val minimum = compactCandidateCellWidth(
            contentWidth = contentWidth,
            minWidth = context.dp(COMPACT_CANDIDATE_MIN_WIDTH_DP),
            horizontalPadding = context.dp(theme.generalStyle.candidatePadding),
            maxWidth = context.dp(COMPACT_CANDIDATE_MAX_WIDTH_DP),
        )
        val preferred = compactCandidateCellWidth(
            contentWidth = contentWidth,
            minWidth = minimum,
            horizontalPadding = context.dp(COMPACT_CANDIDATE_HORIZONTAL_PADDING_DP),
            maxWidth = context.dp(COMPACT_CANDIDATE_MAX_WIDTH_DP),
        )
        return CompactCandidateWidthBounds(minimum, preferred)
    }

    private fun translationWidthLimits(): CompactTranslationWidthLimits? {
        if (!candidatePreferences.bilingualTranslation.getValue()) return null
        val maximumWidth = context.dp(COMPACT_CANDIDATE_MAX_WIDTH_DP)
        return CompactTranslationWidthLimits(
            primaryMinimum = translationReservedWidth(COMPACT_PRIMARY_TRANSLATION_MIN_LETTERS)
                .coerceAtMost(maximumWidth),
            primaryMaximum = translationReservedWidth(COMPACT_PRIMARY_TRANSLATION_MAX_LETTERS)
                .coerceAtMost(maximumWidth),
            secondaryMaximum = translationReservedWidth(COMPACT_SECONDARY_TRANSLATION_MAX_LETTERS)
                .coerceAtMost(maximumWidth),
        )
    }

    private fun prioritizedPreedit(): String? {
        val status = rime.run { statusCached }
        return currentPreedit.takeIf {
            status.schemaId == SIMPLIFIED_PINYIN_SCHEMA && !status.isAsciiMode
        }
    }

    private fun renderCandidates(availableWidth: Int) {
        val data = latestCandidates ?: return
        val targetCount = targetCandidateCount()
        val candidates = data.candidates.toCompactCandidateItems(targetCount, prioritizedPreedit())
        val cells = fitCompactCandidateRow(
            candidates = candidates,
            targetCount = targetCount,
            availableWidth = availableWidth,
            translationLimits = translationWidthLimits(),
            widthOf = ::measureCandidateWidth,
        )

        adapter.updateCandidates(cells, data.total, data.highlighted)
        if (cells.isEmpty()) refreshUnrolled(0)
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

    internal val adapter by lazy {
        CompactCandidateViewAdapter(theme).apply {
            setOnItemClickListener { _, _, position ->
                val globalIndex = items.getOrNull(position)?.item?.globalIndex ?: return@setOnItemClickListener
                rime.launchOnReady { it.selectCandidate(globalIndex, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                val item = items.getOrNull(position)?.item ?: return@setOnItemLongClickListener false
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
            init {
                flexWrap = FlexWrap.NOWRAP
                justifyContent = JustifyContent.FLEX_START
            }

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
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                val width = right - left
                if (width > 0 && width != oldRight - oldLeft) renderCandidates(width)
            }
        }
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        currentPreedit = data.preedit
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        latestCandidates = data
        val measuredWidth = view.width
        val estimatedWidth = context.resources.displayMetrics.widthPixels - context.dp(40)
        renderCandidates(measuredWidth.takeIf { it > 0 } ?: estimatedWidth)
    }

    private companion object {
        const val SIMPLIFIED_PINYIN_SCHEMA = "luna_pinyin_simp"
    }
}
