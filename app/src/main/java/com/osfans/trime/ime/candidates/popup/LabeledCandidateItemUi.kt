/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.Keep
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealController
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealListener
import com.osfans.trime.ime.candidates.bilingual.bilingualPhoneticTextSize
import com.osfans.trime.ime.candidates.bilingual.bilingualTranslationTextSize
import com.osfans.trime.ime.candidates.bilingual.defaultBilingualCandidatePresenter
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.util.sp
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
) : Ui {
    private val labelSize = theme.window.foreground.labelFontSize
    private val textSize = theme.window.foreground.textFontSize
    private val commentSize = theme.window.foreground.commentFontSize
    private val translationSize = bilingualTranslationTextSize(textSize, commentSize)
    private val phoneticSize = bilingualPhoneticTextSize(textSize)
    private val labelFont = FontManager.getTypeface("label_font")
    private val textFont = FontManager.getTypeface("candidate_font")
    private val commentFont = FontManager.getTypeface("comment_font")
    private val labelColor = ColorManager.getColor("label_color")
    private val textColor = ColorManager.getColor("candidate_text_color")
    private val commentColor = ColorManager.getColor("comment_text_color")
    private val highlightLabelColor = ColorManager.getColor("hilited_label_color")
    private val highlightCommentTextColor = ColorManager.getColor("hilited_comment_text_color")
    private val highlightCandidateTextColor = ColorManager.getColor("hilited_candidate_text_color")
    private val highlightCandidateBackColor = ColorManager.getColor("hilited_candidate_back_color")

    private val di = InputDependencyManager.getInstance().di
    private val revealController: CandidateTranslationRevealController by di.instance()
    private var boundCandidate: CandidateProto? = null
    private var boundHighlighted = false

    @Keep
    private val revealListener = CandidateTranslationRevealListener {
        boundCandidate?.let { candidate -> update(candidate, boundHighlighted) }
    }

    private val attachStateListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                revealController.addListener(revealListener)
            }

            override fun onViewDetachedFromWindow(view: View) {
                revealController.removeListener(revealListener)
            }
        }

    override val root =
        textView {
            val v = dp(theme.window.itemPadding.vertical)
            val h = dp(theme.window.itemPadding.horizontal)
            setPadding(h, v, h, v)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            addOnAttachStateChangeListener(attachStateListener)
        }

    private inline fun SpannableStringBuilder.inSpanWith(
        @ColorInt color: Int,
        textSize: Float,
        typeface: Typeface,
        builderAction: SpannableStringBuilder.() -> Unit,
    ) = inSpans(CandidateItemSpan(color, textSize, typeface), builderAction)

    fun update(
        candidate: CandidateProto,
        highlighted: Boolean,
    ) {
        boundCandidate = candidate
        boundHighlighted = highlighted
        val presentation = defaultBilingualCandidatePresenter.present(candidate)
        val labelFg = if (highlighted) highlightLabelColor else labelColor
        val textFg = if (highlighted) highlightCandidateTextColor else textColor
        val commentFg = if (highlighted) highlightCommentTextColor else commentColor
        root.text =
            buildSpannedString {
                inSpanWith(labelFg, ctx.sp(labelSize), labelFont) { append(candidate.label) }
                append(" ")
                inSpanWith(textFg, ctx.sp(textSize), textFont) { append(candidate.text) }
                if (candidate.comment.isNotBlank()) {
                    append(" ")
                    inSpanWith(commentFg, ctx.sp(commentSize), commentFont) { append(candidate.comment) }
                }
                val translation = presentation.translation
                if (translation != null) {
                    append("\n")
                    inSpanWith(commentFg, ctx.sp(translationSize), commentFont) { append(translation) }
                } else if (presentation.reserveTranslationLine) {
                    append("\n")
                    inSpanWith(Color.TRANSPARENT, ctx.sp(translationSize), commentFont) {
                        append('\u00A0')
                    }
                }
                val phonetic = presentation.phonetic
                if (phonetic != null) {
                    append("\n")
                    inSpanWith(commentFg, ctx.sp(phoneticSize), commentFont) { append(phonetic) }
                } else if (presentation.reservePhoneticLine) {
                    append("\n")
                    inSpanWith(Color.TRANSPARENT, ctx.sp(phoneticSize), commentFont) {
                        append('\u00A0')
                    }
                }
            }
        val bg =
            GradientDrawable().apply {
                if (highlighted) {
                    setColor(highlightCandidateBackColor)
                    cornerRadius = ctx.dp(theme.generalStyle.candidateCornerRadius)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
        root.background = bg
    }
}
