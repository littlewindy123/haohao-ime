/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimePresentationSnapshot
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.haohao.HAOHAO_ONE_HAND_RAIL_WIDTH_DP
import com.osfans.trime.ime.haohao.HaoHaoTranslationController
import com.osfans.trime.ime.haohao.calculateHaoHaoKeyboardViewport
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    theme: Theme,
) : BaseInputView(service, rime, theme) {
    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val leftPaddingSpace =
        LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

    private val rightPaddingSpace =
        LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

    private val placeholderListener = OnClickListener { }

    private val bottomPaddingSpace =
        view(::View) {
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job

    private val inputDepMgr = InputDependencyManager.initialize(this, themedContext, theme, service, rime)
    private val di = inputDepMgr.di
    private val broadcaster: InputBroadcaster by di.instance()
    private val popup: PopupDelegate by di.instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by di.instance()
    private val preedit: PreeditDelegate by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val inputBar: InputBarDelegate by di.instance()
    private val keyboardWindow: KeyboardWindow by di.instance()
    private val liquidWindow: LiquidWindow by di.instance()
    private val translationController: HaoHaoTranslationController by di.instance()

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.generalStyle.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.generalStyle.keyboardPaddingLand
    private val keyboardBottomPadding = theme.generalStyle.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.generalStyle.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    val keyboardView: View

    init {
        // MUST call before any operation
        inputDepMgr.start()

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        keyboardBackground.imageDrawable = ColorManager.getDrawable("keyboard_background")

        keyboardView =
            constraintLayout {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
            }

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    windowManager.view.updateLayoutParams {
                        height = it
                    }
                }
            }

        updateKeyboardSize()

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )

        add(
            keyboardView,
            lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            },
        )

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )
    }

    private fun updateKeyboardSize() {
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val isHaoHaoTheme = ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val viewport = if (isHaoHaoTheme) {
            calculateHaoHaoKeyboardViewport(
                availableWidth = resources.displayMetrics.widthPixels,
                themePadding = keyboardSidePaddingPx,
                railWidth = dp(HAOHAO_ONE_HAND_RAIL_WIDTH_DP),
                mode = AppPrefs.defaultInstance().keyboard.oneHandMode.getValue(),
                landscape = landscape,
            )
        } else {
            calculateHaoHaoKeyboardViewport(
                availableWidth = resources.displayMetrics.widthPixels,
                themePadding = keyboardSidePaddingPx,
                railWidth = 0,
                mode = AppPrefs.Keyboard.OneHandMode.OFF,
                landscape = landscape,
            )
        }
        updateSideSpace(leftPaddingSpace, viewport.leftInset)
        updateSideSpace(rightPaddingSpace, viewport.rightInset)
        updateOneHandRail(isHaoHaoTheme, landscape)

        val unset = LayoutParams.UNSET
        windowManager.view.updateLayoutParams<LayoutParams> {
            if (viewport.leftInset == 0) {
                startToEnd = unset
                startOfParent()
            } else {
                startToStart = unset
                startToEndOf(leftPaddingSpace)
            }
            if (viewport.rightInset == 0) {
                endToStart = unset
                endOfParent()
            } else {
                endToEnd = unset
                endToStartOf(rightPaddingSpace)
            }
        }
        inputBar.view.setPadding(viewport.leftInset, 0, viewport.rightInset, 0)
    }

    private fun updateSideSpace(space: View, width: Int) {
        space.visibility = if (width == 0) View.GONE else View.VISIBLE
        space.updateLayoutParams { this.width = width }
    }

    private fun updateOneHandRail(isHaoHaoTheme: Boolean, landscape: Boolean) {
        leftPaddingSpace.removeAllViews()
        rightPaddingSpace.removeAllViews()
        if (!isHaoHaoTheme || landscape) return

        val prefs = AppPrefs.defaultInstance().keyboard
        val mode = prefs.oneHandMode.getValue()
        if (mode == AppPrefs.Keyboard.OneHandMode.OFF) return

        val rail = if (mode == AppPrefs.Keyboard.OneHandMode.LEFT) rightPaddingSpace else leftPaddingSpace
        val oppositeMode = if (mode == AppPrefs.Keyboard.OneHandMode.LEFT) {
            AppPrefs.Keyboard.OneHandMode.RIGHT
        } else {
            AppPrefs.Keyboard.OneHandMode.LEFT
        }
        val switchIcon = if (mode == AppPrefs.Keyboard.OneHandMode.LEFT) {
            R.drawable.ic_baseline_arrow_right_24
        } else {
            R.drawable.ic_baseline_arrow_left_24
        }
        rail.addView(
            createOneHandButton(switchIcon, R.string.one_hand_switch_side) {
                prefs.oneHandMode.setValue(oppositeMode)
            },
        )
        rail.addView(
            createOneHandButton(R.drawable.ic_baseline_keyboard_24, R.string.one_hand_exit) {
                prefs.oneHandMode.setValue(AppPrefs.Keyboard.OneHandMode.OFF)
            },
        )
    }

    private fun createOneHandButton(
        iconRes: Int,
        descriptionRes: Int,
        action: () -> Unit,
    ): ImageView = ImageView(themedContext).apply {
        val size = dp(48)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            val margin = dp(2)
            setMargins(margin, margin, margin, margin)
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageDrawable = ContextCompat.getDrawable(context, iconRes)
        imageTintList = ColorStateList.valueOf(ColorManager.getColor("off_key_text_color"))
        background = RippleDrawable(
            ColorStateList.valueOf(ColorManager.getColor("hilited_off_key_back_color")),
            ColorManager.getDecorDrawable(
                "off_key_back_color",
                "key_border_color",
                dp(theme.generalStyle.keyBorder),
                dp(theme.generalStyle.roundCorner),
            ),
            null,
        )
        contentDescription = context.getString(descriptionRes)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            InputFeedbackManager.keyPressSound()
            InputFeedbackManager.keyPressVibrate(this)
            action()
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    fun captureCloudTranslationText(text: String): Boolean = translationController.captureCommittedText(text)

    fun deactivateCloudTranslation() {
        translationController.deactivate()
    }

    fun onRimeKeyQueued() {
        broadcaster.onRimeKeyInput()
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }
            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
            }
            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }
            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    override fun handleRimePresentation(snapshot: RimePresentationSnapshot) {
        val composition =
            if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                CompositionProto()
            } else {
                snapshot.composition
            }
        broadcaster.onCompositionUpdate(composition)
        when (val candidates = snapshot.candidates) {
            is Candidates.Bulk -> broadcaster.onCandidateListUpdate(candidates)
            is Candidates.Paged -> Unit
        }
        broadcaster.onInputStatusUpdate(snapshot.status)
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    override fun onDetachedFromWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        inputDepMgr.stop()
        super.onDetachedFromWindow()
    }
}
