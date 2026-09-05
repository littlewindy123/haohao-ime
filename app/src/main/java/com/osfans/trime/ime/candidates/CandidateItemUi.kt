/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.Keep
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.ime.candidates.bilingual.CANDIDATE_TRANSLATION_MAX_WIDTH_DP
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealController
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealListener
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_MIN_HEIGHT_DP
import com.osfans.trime.ime.candidates.bilingual.UNROLLED_CANDIDATE_PHONETIC_HEIGHT_DP
import com.osfans.trime.ime.candidates.bilingual.bilingualPhoneticLineHeight
import com.osfans.trime.ime.candidates.bilingual.bilingualTranslationLineHeight
import com.osfans.trime.ime.candidates.bilingual.defaultBilingualCandidatePresenter
import com.osfans.trime.ime.candidates.bilingual.resolveCandidateTypography
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.util.roundedRippleDrawable
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.baselineToBaselineOf
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.bottomToTopOf
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToBottomOf
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding

class CandidateItemUi(
    override val ctx: Context,
    private val theme: Theme,
    private val layoutMode: LayoutMode = LayoutMode.COMPACT,
) : Ui {

    enum class LayoutMode {
        COMPACT,
        EXPANDED,
    }

    private val isExpanded = layoutMode == LayoutMode.EXPANDED
    private val itemGravity = Gravity.CENTER
    private val itemWidth = ViewGroup.LayoutParams.MATCH_PARENT

    private val commentSize = theme.generalStyle.commentTextSize
    private val typography = theme.generalStyle.run {
        resolveCandidateTypography(
            candidateTextSize = candidateTextSize,
            commentTextSize = commentTextSize,
            compactCandidateTextSize = compactCandidateTextSize,
            compactTranslationTextSize = compactTranslationTextSize,
            compactPhoneticTextSize = compactPhoneticTextSize,
        )
    }
    private val textSize = typography.candidateTextSize
    private val translationSize = typography.translationTextSize
    private val translationLineHeight =
        bilingualTranslationLineHeight(translationSize * ctx.resources.configuration.fontScale, theme.generalStyle.commentHeight)
    private val phoneticSize = typography.phoneticTextSize
    private val phoneticLineHeight = bilingualPhoneticLineHeight(phoneticSize * ctx.resources.configuration.fontScale)

    private val textFont = FontManager.getTypeface("candidate_font")
    private val commentFont = FontManager.getTypeface("comment_font")

    private val textColor = ColorManager.getColor("candidate_text_color")
    private val commentColor = ColorManager.getColor("comment_text_color")

    private val hlCommentColor = ColorManager.getColor("hilited_comment_text_color")
    private val hlTextColor = ColorManager.getColor("hilited_candidate_text_color")
    private val hlBackColor = ColorManager.getColor("hilited_candidate_back_color")

    private val commentPosition = theme.generalStyle.commentPosition
    private val commentVerticalBias = theme.generalStyle.commentVerticalBias
    private val candidateTextVerticalBias = theme.generalStyle.candidateTextVerticalBias

    private val di = InputDependencyManager.getInstance().di
    private val revealController: CandidateTranslationRevealController by di.instance()
    private var boundItem: CandidateProto? = null
    private var boundHighlighted = false
    private var restrictCompactTranslation = false
    private var boundCompactTranslation: String? = null

    @Keep
    private val revealListener = CandidateTranslationRevealListener {
        boundItem?.let { item -> render(item, boundHighlighted) }
    }

    private val attachStateListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                revealController.addListener(revealListener)
            }

            override fun onViewDetachedFromWindow(view: View) {
                revealController.removeListener(revealListener)
                translation.animate().cancel()
                phonetic.animate().cancel()
            }
        }

    private val text =
        view(::AutoScaleTextView) {
            id = View.generateViewId()
            this.textSize = this@CandidateItemUi.textSize
            typeface = textFont
            isSingleLine = true
            gravity = itemGravity
            scaleMode = AutoScaleTextView.Mode.Proportional
        }

    private val comment =
        view(::AutoScaleTextView) {
            id = View.generateViewId()
            this.textSize = commentSize
            typeface = commentFont
            isSingleLine = true
            gravity = itemGravity
            scaleMode = AutoScaleTextView.Mode.Proportional
        }

    private val translation =
        textView {
            id = View.generateViewId()
            this.textSize = translationSize
            typeface = commentFont
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END.takeIf { isExpanded }
            if (!isExpanded) maxWidth = dp(CANDIDATE_TRANSLATION_MAX_WIDTH_DP)
            gravity = itemGravity
            horizontalPadding = dp(theme.generalStyle.candidatePadding)
            isVisible = false
        }

    private val phonetic =
        textView {
            id = View.generateViewId()
            this.textSize = phoneticSize
            typeface = commentFont
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            if (!isExpanded) maxWidth = dp(CANDIDATE_TRANSLATION_MAX_WIDTH_DP)
            gravity = itemGravity
            horizontalPadding = dp(theme.generalStyle.candidatePadding)
            isVisible = false
        }

    private val content = constraintLayout {
        horizontalPadding = dp(theme.generalStyle.candidatePadding)
        when (commentPosition) {
            GeneralStyle.CommentPosition.RIGHT -> {
                add(
                    text,
                    lParams(wrapContent, wrapContent) {
                        centerVertically()
                        startOfParent()
                        endToStartOf(comment)
                        horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
                        horizontalBias = 0.5f
                    },
                )
                add(
                    comment,
                    lParams(wrapContent, wrapContent) {
                        startToEndOf(text, ctx.dp(1))
                        endOfParent()
                        baselineToBaselineOf(text)
                        horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
                    },
                )
            }
            GeneralStyle.CommentPosition.TOP -> {
                add(
                    text,
                    lParams(wrapContent, matchConstraints) {
                        centerHorizontally()
                        bottomOfParent()
                        topToBottomOf(comment)
                    },
                )
                add(
                    comment,
                    lParams(wrapContent, matchConstraints) {
                        matchConstraintPercentHeight = 0.4f
                        topOfParent()
                        centerHorizontally()
                        bottomToTopOf(text)
                    },
                )
            }
            GeneralStyle.CommentPosition.OVERLAY -> {
                add(
                    text,
                    lParams(wrapContent, wrapContent) {
                        centerInParent()
                        verticalBias = candidateTextVerticalBias
                    },
                )
                add(
                    comment,
                    lParams(wrapContent, wrapContent) {
                        centerInParent()
                        verticalBias = commentVerticalBias
                    },
                )
            }
        }
    }

    private val stackedContent = verticalLayout {
        gravity = itemGravity
        add(
            content,
            lParams(itemWidth, dp(theme.generalStyle.candidateViewHeight)) {
                gravity = itemGravity
            },
        )
        add(
            translation,
            lParams(itemWidth, dp(translationLineHeight)) {
                gravity = itemGravity
            },
        )
        add(
            phonetic,
            lParams(itemWidth, dp(phoneticLineHeight)) {
                gravity = itemGravity
            },
        )
    }

    override val root = view(::GestureFrame) {
        if (isExpanded) minimumHeight = dp(UNROLLED_CANDIDATE_MIN_HEIGHT_DP)
        addOnAttachStateChangeListener(attachStateListener)
        /**
         * candidate long press feedback is handled by `showCandidateActionMenu`
         */
        add(
            stackedContent,
            lParams(itemWidth, wrapContent) {
                gravity = itemGravity
            },
        )
    }

    @SuppressLint("UseKtx")
    fun update(
        item: CandidateProto,
        highlighted: Boolean,
    ) {
        boundItem = item
        boundHighlighted = highlighted
        restrictCompactTranslation = false
        boundCompactTranslation = null
        render(item, highlighted)
    }

    fun updateCompact(
        item: CandidateProto,
        highlighted: Boolean,
        compactTranslation: String?,
    ) {
        boundItem = item
        boundHighlighted = highlighted
        restrictCompactTranslation = true
        boundCompactTranslation = compactTranslation
        render(item, highlighted)
    }

    @SuppressLint("UseKtx")
    private fun render(
        item: CandidateProto,
        highlighted: Boolean,
    ) {
        val presentation = defaultBilingualCandidatePresenter.present(item)
        val tColor = if (highlighted) hlTextColor else textColor
        val cColor = if (highlighted) hlCommentColor else commentColor
        val cornerRadius = if (isExpanded) 0f else ctx.dp(theme.generalStyle.candidateCornerRadius)
        val contentColor = if (highlighted) hlBackColor else Color.TRANSPARENT

        root.background = roundedRippleDrawable(hlBackColor, cornerRadius, contentColor)
        text.text = item.text
        text.setTextColor(tColor)

        val commentText = item.comment
        comment.text = commentText
        comment.setTextColor(cColor)
        comment.isVisible = commentText.isNotEmpty()

        val translationText = presentation.translation.takeIf {
            !restrictCompactTranslation || it == boundCompactTranslation
        }
        val revealTranslation = translation.text.isNullOrEmpty() && !translationText.isNullOrEmpty()
        translation.animate().cancel()
        phonetic.animate().cancel()
        translation.alpha = 1f
        phonetic.alpha = 1f
        translation.text = translationText.orEmpty()
        translation.setTextColor(cColor)
        translation.visibility =
            when {
                !translationText.isNullOrEmpty() -> View.VISIBLE
                presentation.reserveTranslationLine -> View.INVISIBLE
                else -> View.GONE
            }

        val phoneticText = presentation.phonetic.takeIf { !translationText.isNullOrEmpty() }
        phonetic.text = phoneticText.orEmpty()
        phonetic.setTextColor(cColor)
        phonetic.visibility =
            when {
                !phoneticText.isNullOrEmpty() -> View.VISIBLE
                presentation.reservePhoneticLine -> View.INVISIBLE
                else -> View.GONE
            }
        if (revealTranslation && Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
            translation.alpha = 0f
            translation.animate().alpha(1f).setDuration(120).start()
            if (!phoneticText.isNullOrEmpty()) {
                phonetic.alpha = 0f
                phonetic.animate().alpha(1f).setDuration(120).start()
            }
        }
        if (isExpanded) {
            root.minimumHeight = ctx.dp(
                if (presentation.reservePhoneticLine) {
                    UNROLLED_CANDIDATE_PHONETIC_HEIGHT_DP
                } else {
                    UNROLLED_CANDIDATE_MIN_HEIGHT_DP
                },
            )
        }
    }
}
