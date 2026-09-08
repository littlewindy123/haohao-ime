// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.osfans.trime.R
import com.osfans.trime.data.translation.CloudTranslationResult
import com.osfans.trime.data.translation.SentenceCandidateState
import com.osfans.trime.data.translation.SentenceCandidateStatus
import splitties.dimensions.dp

internal data class SentenceTranslationCell(val width: Int, val translation: String?, val phonetic: String? = null)

/** A fixed-height sibling of the Chinese row, never a replacement for its touch targets. */
internal class SentenceTranslationStrip(
    private val context: Context,
    private val textSizeSp: Float,
    private val textColor: Int,
    private val font: Typeface,
    private val horizontalPadding: Int,
    private val onExpand: (SentenceCandidateState) -> Unit,
    private val onRetry: () -> Unit,
) {
    private var generation = 0
    private var lastBinding: Triple<SentenceCandidateState, List<SentenceTranslationCell>, Boolean>? = null
    private fun label() = TextView(context).apply {
        textSize = textSizeSp
        typeface = font
        setTextColor(textColor)
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.START
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val words = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    internal val sentence = label().apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    internal val action = ImageButton(context).apply {
        background = null
        imageTintList = ColorStateList.valueOf(textColor)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        isFocusable = true
        isFocusableInTouchMode = false
    }
    private val whole = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(sentence, LinearLayout.LayoutParams(0, -1, 1f))
        addView(action, LinearLayout.LayoutParams(context.dp(48), -1))
    }
    val root = FrameLayout(context).apply {
        addView(words, FrameLayout.LayoutParams(-1, -1))
        addView(whole, FrameLayout.LayoutParams(-1, -1))
    }

    fun bind(state: SentenceCandidateState, cells: List<SentenceTranslationCell>, revealed: Boolean) {
        val binding = Triple(state, cells, revealed)
        if (lastBinding == binding) return
        lastBinding = binding
        val current = ++generation
        sentence.setOnClickListener(null)
        action.setOnClickListener(null)
        action.contentDescription = null
        action.visibility = View.INVISIBLE
        if (!revealed || cells.isEmpty()) {
            whole.visibility = View.INVISIBLE
            words.visibility = View.INVISIBLE
            sentence.text = ""
            return
        }
        val text = state.translation.orEmpty()
        val width = sentence.paint.measureText(text).toInt() + horizontalPadding * 2
        val wide = text.isNotEmpty() && needsSentenceTranslationLane(text, width, cells.first().width)
        val status = state.status.isTranslationIssue()
        whole.visibility = if (wide || status) View.VISIBLE else View.INVISIBLE
        words.visibility = if (wide || status) View.INVISIBLE else View.VISIBLE
        if (wide || status) {
            sentence.text = if (wide) text else ""
            if (wide) {
                sentence.setOnClickListener { onExpand(state) }
                action.setImageResource(R.drawable.ic_baseline_more_horiz_24)
                action.contentDescription = context.getString(R.string.sentence_translation_expand)
                action.setOnClickListener { onExpand(state) }
                sentence.post {
                    if (current == generation) {
                        val layout = sentence.layout
                        val overflow = layout != null && (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                        action.visibility = if (overflow) View.VISIBLE else View.INVISIBLE
                    }
                }
            } else {
                val retryable = state.status == SentenceCandidateStatus.FAILED && state.failure in RETRYABLE
                action.setImageResource(if (retryable) R.drawable.ic_baseline_refresh_reversed_24 else R.drawable.ic_baseline_warning_24)
                action.contentDescription = context.getString(statusMessage(state)) +
                    if (retryable) "; " + context.getString(R.string.haohao_translation_retry) else ""
                action.visibility = View.VISIBLE
                action.setOnClickListener {
                    // Explanations are opt-in, never mixed into the live translation text.
                    Toast.makeText(context, statusMessage(state), Toast.LENGTH_LONG).show()
                    if (retryable) onRetry()
                }
            }
            return
        }
        sentence.text = ""
        sentence.setOnClickListener(null)
        val rowWidth = cells.sumOf { it.width }.coerceAtLeast(1)
        if (words.layoutParams.width != rowWidth) {
            words.layoutParams = words.layoutParams.apply { this.width = rowWidth }
        }
        while (words.childCount > cells.size) words.removeViewAt(words.childCount - 1)
        while (words.childCount < cells.size) {
            words.addView(label().apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(1, -1))
        }
        cells.forEachIndexed { index, cell ->
            val value = if (index == 0) state.translation ?: cell.translation else cell.translation
            val measured = value?.let { sentence.paint.measureText(it).toInt() + horizontalPadding * 2 } ?: 0
            val fits = value != null && !needsSentenceTranslationLane(value, measured, cell.width)
            (words.getChildAt(index) as TextView).apply {
                val nextText = if (fits) listOfNotNull(value, cell.phonetic).joinToString("\n") else ""
                if (this.text.toString() != nextText) this.text = nextText
                if (layoutParams.width != cell.width) {
                    layoutParams = layoutParams.apply { this.width = cell.width }
                }
                setOnLongClickListener(null)
                if (index == 0 && state.translation != null) {
                    setOnLongClickListener {
                        onExpand(state)
                        true
                    }
                }
            }
        }
    }

    private fun statusMessage(state: SentenceCandidateState): Int = when (state.status) {
        SentenceCandidateStatus.TOO_LONG -> R.string.sentence_translation_limit
        SentenceCandidateStatus.UNAVAILABLE -> R.string.sentence_translation_local_missing
        else -> when (state.failure) {
            CloudTranslationResult.Failure.Kind.TIMEOUT -> R.string.cloud_translation_error_timeout
            CloudTranslationResult.Failure.Kind.AUTHENTICATION -> R.string.cloud_translation_error_auth
            CloudTranslationResult.Failure.Kind.RATE_LIMITED -> R.string.cloud_translation_error_rate
            CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED -> R.string.cloud_translation_error_quota
            CloudTranslationResult.Failure.Kind.CONFIGURATION_EXPIRED -> R.string.cloud_translation_error_expired
            CloudTranslationResult.Failure.Kind.INVALID_RESPONSE -> R.string.cloud_translation_error_response
            CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED -> R.string.sentence_translation_consent
            CloudTranslationResult.Failure.Kind.NOT_CONFIGURED -> R.string.haohao_translation_not_configured
            CloudTranslationResult.Failure.Kind.INVALID_REQUEST -> R.string.sentence_translation_invalid
            CloudTranslationResult.Failure.Kind.UNSUPPORTED_DEVICE -> R.string.haohao_tool_unavailable_unsupported
            else -> R.string.cloud_translation_error_network
        }
    }

    private companion object {
        val RETRYABLE = setOf(
            CloudTranslationResult.Failure.Kind.NETWORK,
            CloudTranslationResult.Failure.Kind.TIMEOUT,
            CloudTranslationResult.Failure.Kind.UPSTREAM,
            CloudTranslationResult.Failure.Kind.INVALID_RESPONSE,
            CloudTranslationResult.Failure.Kind.RATE_LIMITED,
        )
    }
}
