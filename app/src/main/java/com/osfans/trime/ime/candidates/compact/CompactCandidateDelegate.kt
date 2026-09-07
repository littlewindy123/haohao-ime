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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.annotation.Keep
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.translation.CloudCandidateTranslationController
import com.osfans.trime.data.translation.CloudCandidateTranslationRepository
import com.osfans.trime.data.translation.ConfiguredCandidateTranslationRepository
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealController
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealListener
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealState
import com.osfans.trime.ime.candidates.bilingual.bilingualTranslationLineHeight
import com.osfans.trime.ime.candidates.bilingual.candidateSourceRowHeight
import com.osfans.trime.ime.candidates.bilingual.resolveCandidateTypography
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateItem
import com.osfans.trime.ime.candidates.unrolled.toDisplayableUnrolledCandidates
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.ceil
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
internal const val COMPACT_CANDIDATE_TRAILING_CONTROL_WIDTH_DP = 48
private const val COMPACT_PRIMARY_TRANSLATION_MIN_LETTERS = 8
private const val COMPACT_PRIMARY_TRANSLATION_MAX_LETTERS = 10
private const val COMPACT_SECONDARY_TRANSLATION_MAX_LETTERS = 8
private const val COMPACT_TRANSLATION_WIDTH_SAFETY_DP = 2

private val PINYIN_SYLLABLE = Regex("[a-züv]+", RegexOption.IGNORE_CASE)
private val PINYIN_SEPARATOR = Regex("[\\s']+")
private const val PREEDIT_CARET = '\u2038'
private const val TRANSLATION_WIDTH_SAMPLE = "abcdefghij"

internal fun compactCandidateAvailableWidth(
    totalWidth: Int,
    leadingWidth: Int,
    trailingWidth: Int,
): Int = (totalWidth - leadingWidth - trailingWidth).coerceAtLeast(0)

/** Keep enough source candidates to fill the viewport, independent of translation count. */
internal fun sentenceCandidateBudget(availableWidth: Int, minimumCellWidth: Int): Int = if (availableWidth <= 0) 0 else ceil(availableWidth.toDouble() / minimumCellWidth.coerceAtLeast(1)).toInt() + 1

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
    val compactTranslation: String? = null,
    val separateTranslationLane: Boolean = false,
    val naturalSourceWidth: Int = 0,
)

internal data class CompactCandidateWidthBounds(
    val minimum: Int,
    val preferred: Int,
)

internal fun CompactCandidateWidthBounds.withCompactTranslationWidth(
    mode: CompactTranslationMode,
    hint: CompactTranslationHint?,
): CompactCandidateWidthBounds {
    if (mode != CompactTranslationMode.ADAPTIVE || hint == null) return this
    val required = hint.requiredWidth
    return CompactCandidateWidthBounds(
        minimum = max(minimum, required),
        preferred = max(preferred, required),
    )
}

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

/** The first sentence owns its natural width before any trailing candidate is allocated space. */
internal fun fitSentenceFirstCandidateRow(
    candidates: List<UnrolledCandidateItem>,
    targetCount: Int,
    availableWidth: Int,
    minimumTrailingWidth: Int = 48,
    widthOf: (UnrolledCandidateItem) -> CompactCandidateWidthBounds,
): List<CompactCandidateCell> {
    if (targetCount <= 0 || availableWidth <= 0 || candidates.isEmpty()) return emptyList()
    val first = candidates.first()
    val bounds = widthOf(first)
    val firstWidth = maxOf(1, bounds.minimum, bounds.preferred).coerceAtMost(availableWidth)
    var remaining = availableWidth - firstWidth
    val cells = mutableListOf(CompactCandidateCell(first, firstWidth, naturalSourceWidth = maxOf(bounds.minimum, bounds.preferred)))
    for (candidate in candidates.drop(1).take(targetCount - 1)) {
        if (remaining < minimumTrailingWidth.coerceAtLeast(1)) break
        val next = widthOf(candidate)
        val width = maxOf(1, next.minimum, next.preferred).coerceAtMost(remaining)
        cells += CompactCandidateCell(candidate, width, naturalSourceWidth = maxOf(next.minimum, next.preferred))
        remaining -= width
    }
    return cells
}

/** Text owns its natural width. Longer sentences move through the viewport, never shrink. */
internal fun sentenceCandidateContentWidth(naturalWidth: Int, viewportWidth: Int, horizontalPadding: Int): Int = maxOf(1, viewportWidth, naturalWidth, horizontalPadding.coerceAtLeast(0) * 2)

class CompactCandidateDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    val service: TrimeInputMethodService by di.instance()
    val rime: RimeSession by di.instance()
    val theme: Theme by di.instance()
    private val inputView: InputView by di.instance()
    val bar: InputBarDelegate by di.instance()
    private val candidatePreferences: AppPrefs.Candidates = AppPrefs.defaultInstance().candidates
    private val cloudCandidateController: CloudCandidateTranslationController by di.instance()
    private val revealController: CandidateTranslationRevealController by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private var renderedCells = emptyList<CompactCandidateCell>()
    private val sentenceListener: () -> Unit = { renderSentenceStrip() }
    private val revealListener = CandidateTranslationRevealListener {
        if (it == CandidateTranslationRevealState.READY) requestSentence()
        renderSentenceStrip()
    }

    private val sentenceStrip by lazy {
        SentenceTranslationStrip(
            context,
            typography.translationTextSize,
            ColorManager.getColor("comment_text_color"),
            FontManager.getTypeface("comment_font"),
            context.dp(theme.generalStyle.candidatePadding),
            onExpand = { state ->
                if (state == cloudCandidateController.sentenceState) {
                    windowManager.attachWindow(SentenceTranslationPreviewWindow(state))
                }
            },
            onRetry = cloudCandidateController::retrySentence,
        )
    }

    private fun isSentenceMode() = candidatePreferences.compactTranslationMode.getValue() == CompactTranslationMode.SENTENCE_FIRST &&
        candidatePreferences.bilingualTranslation.getValue()

    private fun requestSentence() {
        if (!isSentenceMode()) return
        cloudCandidateController.requestFirst(
            renderedCells.firstOrNull()?.item?.candidate?.text?.takeIf { !currentPreedit.isNullOrBlank() },
        )
    }

    private fun renderSentenceStrip() {
        if (!isSentenceMode()) return
        val state = cloudCandidateController.sentenceState
        val matching = state.source == renderedCells.firstOrNull()?.item?.candidate?.text
        sentenceStrip.bind(
            state,
            renderedCells.map { cell ->
                val entry = ConfiguredCandidateTranslationRepository.lookup(cell.item.candidate.text)
                SentenceTranslationCell(cell.width, entry?.translation, entry?.phonetic?.takeIf { candidatePreferences.bilingualPhonetic.getValue() })
            },
            revealed = matching && cloudCandidateController.allowsSentencePreview &&
                revealController.state == CandidateTranslationRevealState.READY,
        )
    }

    private var latestCandidates: Candidates.Bulk? = null
    private var latestCandidatePresentationVersion = 0L
    private var renderedCandidatePresentationVersion = 0L
    private var currentPreedit: String? = null
    private var renderJob: Job? = null
    private var renderGeneration = 0L

    @Volatile private var rowGeometry: RowGeometry? = null

    private data class RowGeometry(
        val candidates: List<UnrolledCandidateItem>,
        val width: Int,
        val mode: CompactTranslationMode,
        val enabled: Boolean,
        val cells: List<CompactCandidateCell>,
    )
    private val candidateWidthCache =
        object : LinkedHashMap<CandidateWidthCacheKey, CompactCandidateWidthBounds>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<CandidateWidthCacheKey, CompactCandidateWidthBounds>,
            ): Boolean = size > WIDTH_CACHE_LIMIT
        }

    @Keep
    private val translationModeListener =
        PreferenceDelegate.OnChangeListener<CompactTranslationMode> { _, _ ->
            clearWidthCache()
            view.width.takeIf { it > 0 }?.let(::renderCandidates)
        }

    @Keep
    private val translationEnabledListener =
        PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
            clearWidthCache()
            view.width.takeIf { it > 0 }?.let(::renderCandidates)
        }

    private val cloudTranslationListener: () -> Unit = {
        clearWidthCache()
        view.width.takeIf { it > 0 }?.let(::renderCandidates)
    }

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

    private val typography by lazy {
        theme.generalStyle.run {
            resolveCandidateTypography(
                candidateTextSize = candidateTextSize,
                commentTextSize = commentTextSize,
                compactCandidateTextSize = compactCandidateTextSize,
                compactTranslationTextSize = compactTranslationTextSize,
                compactPhoneticTextSize = compactPhoneticTextSize,
            )
        }
    }

    private val candidateTextPaint by lazy {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledPixels(typography.candidateTextSize)
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
            textSize = scaledPixels(typography.translationTextSize)
            typeface = FontManager.getTypeface("comment_font")
        }
    }

    private fun translationReservedWidth(letterBudget: Int): Int {
        val sample = TRANSLATION_WIDTH_SAMPLE.take(letterBudget)
        return translationTextPaint.measureText(sample).roundToInt() +
            context.dp(theme.generalStyle.candidatePadding * 2)
    }

    private fun measureCandidateWidth(
        item: UnrolledCandidateItem,
        mode: CompactTranslationMode,
        translationHint: CompactTranslationHint?,
    ): CompactCandidateWidthBounds {
        val candidate = item.candidate
        val cacheKey =
            CandidateWidthCacheKey(
                text = candidate.text,
                comment = candidate.comment,
                mode = mode,
                translation = translationHint?.text,
                translationWidth = translationHint?.requiredWidth ?: 0,
            )
        synchronized(candidateWidthCache) {
            candidateWidthCache[cacheKey]?.let { return it }
        }
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
            maxWidth = if (mode == CompactTranslationMode.SENTENCE_FIRST && candidatePreferences.bilingualTranslation.getValue()) Int.MAX_VALUE else context.dp(COMPACT_CANDIDATE_MAX_WIDTH_DP),
        )
        val preferred = compactCandidateCellWidth(
            contentWidth = contentWidth,
            minWidth = minimum,
            horizontalPadding = context.dp(COMPACT_CANDIDATE_HORIZONTAL_PADDING_DP),
            maxWidth = if (mode == CompactTranslationMode.SENTENCE_FIRST && candidatePreferences.bilingualTranslation.getValue()) Int.MAX_VALUE else context.dp(COMPACT_CANDIDATE_MAX_WIDTH_DP),
        )
        return CompactCandidateWidthBounds(minimum, preferred)
            .withCompactTranslationWidth(mode, translationHint)
            .also { measured ->
                synchronized(candidateWidthCache) {
                    candidateWidthCache[cacheKey] = measured
                }
            }
    }

    private fun translationWidthLimits(mode: CompactTranslationMode): CompactTranslationWidthLimits? {
        if (
            !candidatePreferences.bilingualTranslation.getValue() ||
            mode != CompactTranslationMode.WORD
        ) {
            return null
        }
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

    private fun translationHintFor(
        item: UnrolledCandidateItem,
        mode: CompactTranslationMode,
    ): CompactTranslationHint? {
        if (!candidatePreferences.bilingualTranslation.getValue()) return null
        val translation = ConfiguredCandidateTranslationRepository.lookup(item.candidate.text)?.translation
        val requiredWidth = translation?.let {
            translationTextPaint.measureText(it).roundToInt() +
                context.dp(theme.generalStyle.candidatePadding * 2) +
                context.dp(COMPACT_TRANSLATION_WIDTH_SAFETY_DP)
        } ?: 0
        return compactTranslationHint(
            mode = mode,
            translation = translation,
            requiredWidth = requiredWidth,
            adaptiveMaximumWidth = context.dp(COMPACT_ADAPTIVE_TRANSLATION_MAX_WIDTH_DP),
        )
    }

    private fun prioritizedPreedit(): String? {
        val status = rime.run { statusCached }
        return currentPreedit.takeIf {
            !status.isAsciiMode && when (status.schemaId) {
                SIMPLIFIED_PINYIN_SCHEMA -> true
                // Nine-key preedit shows a possible reading; preserve the engine's ambiguity ranking.
                com.osfans.trime.ime.keyboard.NINE_KEY_SCHEMA_ID -> false
                else -> false
            }
        }
    }

    private fun renderCandidates(availableWidth: Int) {
        val data = latestCandidates ?: return
        val preedit = prioritizedPreedit()
        val generation = ++renderGeneration
        renderJob?.cancel()
        renderJob = service.lifecycleScope.launch(Dispatchers.Default) {
            val startedAt = System.nanoTime()
            val translationMode = candidatePreferences.compactTranslationMode.getValue()
            val translationEnabled = candidatePreferences.bilingualTranslation.getValue()
            val separateTranslationLane = translationEnabled && translationMode == CompactTranslationMode.SENTENCE_FIRST
            val targetCount = if (separateTranslationLane) {
                sentenceCandidateBudget(availableWidth, context.dp(COMPACT_CANDIDATE_MIN_WIDTH_DP))
            } else {
                targetCandidateCount()
            }
            val candidates = data.candidates.toCompactCandidateItems(targetCount, preedit)
            val translationHints = candidates.associateWith { translationHintFor(it, translationMode) }
            // Translation arrivals may reveal text, but must not move the Chinese touch targets.
            val reusableGeometry = rowGeometry.takeIf {
                it?.candidates == candidates && it.width == availableWidth && it.mode == translationMode && it.enabled == translationEnabled
            }?.cells
            val geometry = reusableGeometry ?: if (separateTranslationLane) {
                fitSentenceFirstCandidateRow(candidates, targetCount, availableWidth, minimumTrailingWidth = 1) { item ->
                    measureCandidateWidth(item, translationMode, null)
                }
            } else {
                fitCompactCandidateRow(
                    candidates = candidates,
                    targetCount = targetCount,
                    availableWidth = availableWidth,
                    translationLimits = translationWidthLimits(translationMode),
                    widthOf = { item ->
                        measureCandidateWidth(item, translationMode, translationHints[item])
                    },
                )
            }
            val cells = geometry.map { cell ->
                cell.copy(
                    separateTranslationLane = separateTranslationLane,
                    compactTranslation = compactTranslationTextForCell(
                        translationHints[cell.item],
                        cell.width,
                    ),
                )
            }
            val elapsedNanos = System.nanoTime() - startedAt
            withContext(Dispatchers.Main) {
                if (
                    generation != renderGeneration ||
                    latestCandidates != data
                ) {
                    return@withContext
                }
                service.recordCandidateModelBuild(elapsedNanos)
                val changedCandidates = rowGeometry?.candidates != candidates
                rowGeometry = RowGeometry(candidates, availableWidth, translationMode, translationEnabled, geometry)
                adapter.updateCandidates(cells, data.total, data.highlighted)
                if (changedCandidates) {
                    view.stopScroll()
                    layoutManager.scrollToPositionWithOffset(0, 0)
                }
                renderedCells = cells
                view.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = if (separateTranslationLane) context.dp(candidateSourceRowHeight(theme.generalStyle.candidateViewHeight, typography.candidateTextSize, context.resources.configuration.fontScale)) else LinearLayout.LayoutParams.WRAP_CONTENT
                    marginEnd = if (separateTranslationLane) context.dp(COMPACT_CANDIDATE_TRAILING_CONTROL_WIDTH_DP) else 0
                }
                sentenceStrip.root.visibility = if (separateTranslationLane) View.VISIBLE else View.GONE
                bar.refreshCandidateHeight()
                renderedCandidatePresentationVersion = latestCandidatePresentationVersion
                if (separateTranslationLane) {
                    requestSentence()
                    renderSentenceStrip()
                } else {
                    cloudCandidateController.requestVisible(cells.map { it.item.candidate.text })
                }
                if (cells.isEmpty()) refreshUnrolled(0)
            }
        }
    }

    private fun clearWidthCache() {
        synchronized(candidateWidthCache) { candidateWidthCache.clear() }
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
                (adapter.total == childCount && adapter.items.none { it.naturalSourceWidth > it.width }),
        )
    }

    internal val adapter by lazy {
        CompactCandidateViewAdapter(theme).apply {
            setOnItemClickListener { _, _, position ->
                val globalIndex = items.getOrNull(position)?.item?.globalIndex ?: return@setOnItemClickListener
                service.selectCandidateFromPresentation(
                    renderedCandidatePresentationVersion,
                    globalIndex,
                    global = true,
                )
            }
            setOnItemLongClickListener { _, view, position ->
                val item = items.getOrNull(position)?.item ?: return@setOnItemLongClickListener false
                inputView.showCandidateActionMenu(
                    item.globalIndex,
                    item.candidate.text,
                    view,
                    global = true,
                    presentationVersion = renderedCandidatePresentationVersion,
                )
                true
            }
        }
    }

    val layoutManager by lazy {
        object : LinearLayoutManager(context, HORIZONTAL, false) {
            override fun canScrollHorizontally(): Boolean = false

            override fun canScrollVertically(): Boolean = false

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                refreshUnrolled(childCount)
            }
        }
    }

    val view: RecyclerView by lazy {
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                val width = right - left
                if (width > 0 && width != oldRight - oldLeft) renderCandidates(width)
            }
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        cloudCandidateController.addSentenceListener(sentenceListener)
                        revealController.addListener(revealListener)
                        candidatePreferences.bilingualTranslation
                            .registerOnChangeListener(translationEnabledListener)
                        candidatePreferences.compactTranslationMode
                            .registerOnChangeListener(translationModeListener)
                        CloudCandidateTranslationRepository.addListener(cloudTranslationListener)
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        cloudCandidateController.removeSentenceListener(sentenceListener)
                        revealController.removeListener(revealListener)
                        candidatePreferences.bilingualTranslation
                            .unregisterOnChangeListener(translationEnabledListener)
                        candidatePreferences.compactTranslationMode
                            .unregisterOnChangeListener(translationModeListener)
                        CloudCandidateTranslationRepository.removeListener(cloudTranslationListener)
                        renderGeneration += 1
                        renderJob?.cancel()
                        renderJob = null
                    }
                },
            )
        }
    }

    val candidateView by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(view, LinearLayout.LayoutParams(-1, -2))
            val lineHeight = bilingualTranslationLineHeight(
                typography.translationTextSize * context.resources.configuration.fontScale,
                theme.generalStyle.commentHeight,
            )
            addView(sentenceStrip.root, LinearLayout.LayoutParams(-1, context.dp(lineHeight * 2)))
            sentenceStrip.root.visibility = View.GONE
        }
    }

    override fun onRimeKeyInput() {
        renderGeneration++
        renderJob?.cancel()
        renderJob = null
        renderedCells = emptyList()
        renderSentenceStrip()
    }

    override fun onStartInput(info: EditorInfo) {
        onRimeKeyInput()
        latestCandidates = null
        currentPreedit = null
        rowGeometry = null
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        currentPreedit = data.preedit
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val presentationVersion = inputView.currentPresentationVersion
        if (latestCandidates == data && renderedCells.isNotEmpty()) {
            latestCandidates = data
            latestCandidatePresentationVersion = presentationVersion
            if (renderJob?.isActive != true) {
                renderedCandidatePresentationVersion = presentationVersion
                requestSentence()
                renderSentenceStrip()
            }
            return
        }
        latestCandidates = data
        latestCandidatePresentationVersion = presentationVersion
        val measuredWidth = view.width
        val estimatedWidth = compactCandidateAvailableWidth(
            totalWidth = context.resources.displayMetrics.widthPixels,
            leadingWidth = bar.compactCandidateLeadingControlWidth,
            trailingWidth = context.dp(COMPACT_CANDIDATE_TRAILING_CONTROL_WIDTH_DP),
        )
        renderCandidates(measuredWidth.takeIf { it > 0 } ?: estimatedWidth)
    }

    private companion object {
        const val SIMPLIFIED_PINYIN_SCHEMA = "luna_pinyin_simp"
        const val WIDTH_CACHE_LIMIT = 256
    }
}

private data class CandidateWidthCacheKey(
    val text: String,
    val comment: String,
    val mode: CompactTranslationMode,
    val translation: String?,
    val translationWidth: Int,
)
