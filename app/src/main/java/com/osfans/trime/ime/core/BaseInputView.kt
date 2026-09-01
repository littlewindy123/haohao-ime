/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.res.Resources
import android.view.View
import android.view.WindowInsets
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimePresentationSnapshot
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.footprints.InputFootprintPolicy
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.ime.candidates.bilingual.OfflineCandidateTranslationRepository
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: TrimeInputMethodService,
    val rime: RimeSession,
    val theme: Theme,
) : ConstraintLayout(service) {
    protected abstract fun handleRimeMessage(it: RimeMessage<*>)

    internal var currentPresentationVersion = 0L
        private set

    protected open fun handleRimePresentation(snapshot: RimePresentationSnapshot) {
        handleRimeMessage(RimeMessage.InlinePreeditMessage(snapshot.inlinePreedit))
        handleRimeMessage(RimeMessage.CompositionMessage(snapshot.composition))
        when (val candidates = snapshot.candidates) {
            is Candidates.Paged -> handleRimeMessage(RimeMessage.PagedCandidatesMessage(candidates))
            is Candidates.Bulk -> handleRimeMessage(RimeMessage.BulkCandidatesMessage(candidates))
        }
        handleRimeMessage(RimeMessage.StatusMessage(snapshot.status))
    }

    private var messageHandlerJob: Job? = null
    private var presentationHandlerJob: Job? = null

    private fun setupRimeMessageHandler() {
        messageHandlerJob =
            service.lifecycleScope.launch {
                rime.run { messageFlow }.collect {
                    handleRimeMessage(it)
                }
            }
        presentationHandlerJob =
            service.lifecycleScope.launch {
                rime.run { presentationFlow }.collect {
                    currentPresentationVersion = it.version
                    handleRimePresentation(it)
                }
            }
    }

    var handleMessages = false
        set(value) {
            field = value
            if (field) {
                if (messageHandlerJob == null) {
                    setupRimeMessageHandler()
                }
            } else {
                messageHandlerJob?.cancel()
                messageHandlerJob = null
                presentationHandlerJob?.cancel()
                presentationHandlerJob = null
            }
        }

    val themedContext = context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private var candidateActionMenu: PopupMenu? = null

    fun showCandidateActionMenu(
        idx: Int,
        text: String,
        view: View,
        global: Boolean,
        presentationVersion: Long = currentPresentationVersion,
    ) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        val highlightColor = ColorManager.getColor("hilited_candidate_text_color")
        val title = buildSpannedString {
            bold {
                color(highlightColor) { append(text) }
            }
        }
        service.lifecycleScope.launch {
            InputFeedbackManager.keyPressVibrate(view, longPress = true)
            val editorInfo = service.currentInputEditorInfo
            val footprintStore = InputFootprints.storeOrNull
            val canFavorite = editorInfo != null &&
                footprintStore != null &&
                InputFootprintPolicy.canRecord(editorInfo.inputType, editorInfo.imeOptions) &&
                withContext(Dispatchers.IO) {
                    OfflineCandidateTranslationRepository.lookup(text) != null
                }
            val isFavorite = canFavorite && withContext(Dispatchers.IO) {
                requireNotNull(footprintStore).isFavorite(text)
            }
            candidateActionMenu =
                PopupMenu(themedContext, view).apply {
                    menu.add(title).apply {
                        isEnabled = false
                    }
                    if (canFavorite) {
                        menu.add(
                            if (isFavorite) {
                                R.string.input_footprints_remove_favorite
                            } else {
                                R.string.input_footprints_add_favorite
                            },
                        ).setOnMenuItemClickListener {
                            service.lifecycleScope.launch(Dispatchers.IO) {
                                requireNotNull(footprintStore).setFavorite(text, !isFavorite, System.currentTimeMillis())
                                withContext(Dispatchers.Main) {
                                    service.toast(
                                        if (isFavorite) {
                                            R.string.input_footprints_removed_favorite
                                        } else {
                                            R.string.input_footprints_added_favorite
                                        },
                                    )
                                }
                            }
                            true
                        }
                    }
                    menu.add(R.string.forget_this_word).setOnMenuItemClickListener {
                        service.deleteCandidateFromPresentation(
                            presentationVersion,
                            idx,
                            global,
                        )
                        true
                    }
                    setOnDismissListener {
                        candidateActionMenu = null
                    }
                    show()
                }
        }
    }

    fun selectCandidateFromCurrentPresentation(
        index: Int,
        global: Boolean,
    ) {
        service.selectCandidateFromPresentation(currentPresentationVersion, index, global)
    }

    private val navBarBackground by ThemeManager.prefs.navbarBackground

    private val navBarFrameHeight: Int
        get() {
            @SuppressLint("DiscouragedApi")
            val resId = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
            return try {
                resources.getDimensionPixelSize(resId)
            } catch (_: Resources.NotFoundException) {
                dp(FALLBACK_NAVBAR_HEIGHT)
            }
        }

    private val ignoreSystemGestureInsets by AppPrefs.defaultInstance().advanced.ignoreSystemGestureInsets

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navBarBackground != ThemePrefs.NavbarBackground.FULL) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val navBars = WindowInsetsCompat.Type.navigationBars()
        val gestures = WindowInsetsCompat.Type.systemGestures() or
            WindowInsetsCompat.Type.mandatorySystemGestures()
        return if (ignoreSystemGestureInsets) {
            // check gestures insets and ignore them when it greater than zero
            val gesturesBottom = insets.getInsets(gestures).bottom
            val navBarsBottom = insets.getInsets(navBars).bottom
            if (gesturesBottom > 0) 0 else navBarsBottom
        } else {
            val insetsBottom = insets.getInsets(navBars or gestures).bottom
            if (insetsBottom > 0) max(insetsBottom, navBarFrameHeight) else insetsBottom
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // on API 35+, we must call requestApplyInsets() manually after replacing views,
        // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        handleMessages = false
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FALLBACK_NAVBAR_HEIGHT = 16
    }
}
