/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.content.ClipData
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.translation.CloudTranslationPrivacyPolicy
import com.osfans.trime.data.translation.CloudTranslationRequest
import com.osfans.trime.data.translation.CloudTranslationResult
import com.osfans.trime.data.translation.CloudTranslationRuntime
import com.osfans.trime.data.translation.TRANSLATION_SENTENCE_MAX_CODE_POINTS
import com.osfans.trime.data.translation.TranslationPurpose
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.util.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.clipboardManager
import java.util.concurrent.CopyOnWriteArraySet

internal const val HAOHAO_TRANSLATION_ACTION = "haohao_translation"
internal const val HAOHAO_TRANSLATION_DEBOUNCE_MS = 800L
internal const val HAOHAO_TRANSLATION_BAR_HEIGHT_DP = 48

internal fun handlesTranslationDraftKey(active: Boolean, composing: Boolean, keyCode: Int): Boolean = active &&
    when (keyCode) {
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER -> !composing
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> true
        else -> false
    }

internal enum class HaoHaoTranslationStatus {
    IDLE,
    WAITING,
    TRANSLATING,
    READY,
    FAILED,
}

internal data class HaoHaoTranslationState(
    val active: Boolean = false,
    val draft: String = "",
    val translation: String? = null,
    val status: HaoHaoTranslationStatus = HaoHaoTranslationStatus.IDLE,
    val failure: CloudTranslationResult.Failure.Kind? = null,
)

internal fun translationPreviewText(state: HaoHaoTranslationState): String = if (state.status == HaoHaoTranslationStatus.READY) {
    state.translation.orEmpty()
} else {
    state.draft.ifEmpty { "Chinese → English" }
}

internal fun interface HaoHaoTranslationStateListener {
    fun onStateChanged(state: HaoHaoTranslationState)
}

internal fun appendTranslationDraft(
    current: String,
    addition: String,
    maximumCodePoints: Int = TRANSLATION_SENTENCE_MAX_CODE_POINTS,
): String? = (current + addition).takeIf {
    it.codePointCount(0, it.length) <= maximumCodePoints
}

internal fun removeLastTranslationCodePoint(value: String): String {
    if (value.isEmpty()) return value
    return value.substring(0, value.offsetByCodePoints(value.length, -1))
}

internal class TranslationRequestGeneration {
    private var value = 0L

    fun next(): Long = ++value

    fun invalidate() {
        value += 1
    }

    fun isCurrent(candidate: Long): Boolean = candidate == value
}

internal class HaoHaoTranslationController : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val service: TrimeInputMethodService by di.instance()
    private val listeners = CopyOnWriteArraySet<HaoHaoTranslationStateListener>()
    private var requestJob: Job? = null
    private val generation = TranslationRequestGeneration()
    private var editorAllowsCloud = false

    @Keep
    private val configurationListener = PreferenceDelegateProvider.OnChangeListener { deactivate() }

    fun start() {
        AppPrefs.defaultInstance().cloudTranslation.registerOnChangeListener(configurationListener)
    }

    var state = HaoHaoTranslationState()
        private set

    val isActive: Boolean
        get() = state.active

    fun addListener(listener: HaoHaoTranslationStateListener) {
        listeners += listener
        listener.onStateChanged(state)
    }

    fun removeListener(listener: HaoHaoTranslationStateListener) {
        listeners -= listener
    }

    override fun onStartInput(info: EditorInfo) {
        editorAllowsCloud = CloudTranslationPrivacyPolicy.allows(info)
        deactivate()
    }

    override fun onRimeKeyInput() {
        if (!state.active) return
        // Invalidate as soon as composition changes, not only after the next Chinese commit.
        cancelRequest()
        publish(state.copy(translation = null, status = HaoHaoTranslationStatus.IDLE, failure = null))
    }

    fun activate(): CloudTranslationResult.Failure? {
        if (!editorAllowsCloud) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
        }
        CloudTranslationRuntime.manager.status()?.let { return it }
        publish(state.copy(active = true, status = HaoHaoTranslationStatus.IDLE, failure = null))
        return null
    }

    fun captureCommittedText(text: String): Boolean {
        if (!state.active || text.isEmpty()) return false
        val next = appendTranslationDraft(state.draft, text)
        if (next == null) {
            service.toast(R.string.haohao_translation_draft_limit)
            return true
        }
        updateDraft(next)
        return true
    }

    fun deleteLastCodePoint(): Boolean {
        if (!state.active) return false
        if (state.draft.isEmpty()) return true
        updateDraft(removeLastTranslationCodePoint(state.draft))
        return true
    }

    fun translateNow(): Boolean {
        if (!state.active || state.draft.isBlank()) return state.active
        scheduleTranslation(delayMillis = 0)
        return true
    }

    fun clear() {
        if (!state.active) return
        cancelRequest()
        publish(HaoHaoTranslationState(active = true))
    }

    fun commitSource() {
        val current = state
        if (current.draft.isNotEmpty() && service.commitTextDirect(current.draft)) {
            current.translation?.let { service.saveSentence(current.draft, it, false) }
        }
        deactivate()
    }

    fun commitTranslation() {
        val current = state
        current.translation?.takeIf { current.status == HaoHaoTranslationStatus.READY && it.isNotEmpty() }?.let {
            if (service.commitTextDirect(it)) service.saveSentence(current.draft, it, false)
        }
        deactivate()
    }

    fun saveSentence() {
        val current = state
        current.translation?.takeIf { current.status == HaoHaoTranslationStatus.READY }?.let {
            service.saveSentence(current.draft, it, true)
        }
    }

    fun copyTranslation() {
        val translation = state.translation?.takeIf(String::isNotEmpty) ?: return
        clipboardManager.setPrimaryClip(ClipData.newPlainText(service.getString(R.string.haohao_translation_title), translation))
        service.toast(R.string.haohao_translation_copied)
    }

    fun deactivate() {
        cancelRequest()
        if (state != HaoHaoTranslationState()) publish(HaoHaoTranslationState())
    }

    fun closeFromUser() {
        deactivate()
        service.postRimeJob { clearComposition() }
    }

    fun stop() {
        AppPrefs.defaultInstance().cloudTranslation.unregisterOnChangeListener(configurationListener)
        deactivate()
        listeners.clear()
    }

    private fun updateDraft(draft: String) {
        cancelRequest()
        if (draft.isBlank()) {
            publish(HaoHaoTranslationState(active = true))
            return
        }
        publish(
            HaoHaoTranslationState(
                active = true,
                draft = draft,
                status = HaoHaoTranslationStatus.WAITING,
            ),
        )
        scheduleTranslation(HAOHAO_TRANSLATION_DEBOUNCE_MS)
    }

    private fun scheduleTranslation(delayMillis: Long) {
        requestJob?.cancel()
        val requestGeneration = generation.next()
        val draft = state.draft
        requestJob = service.lifecycleScope.launch {
            delay(delayMillis)
            if (!generation.isCurrent(requestGeneration) || draft != state.draft) return@launch
            publish(state.copy(status = HaoHaoTranslationStatus.TRANSLATING, translation = null, failure = null))
            val result = CloudTranslationRuntime.manager.translate(
                CloudTranslationRequest(listOf(draft), TranslationPurpose.SENTENCE),
            )
            if (!generation.isCurrent(requestGeneration) || draft != state.draft) return@launch
            when (result) {
                is CloudTranslationResult.Success -> publish(
                    state.copy(
                        translation = result.translations.singleOrNull(),
                        status = HaoHaoTranslationStatus.READY,
                        failure = null,
                    ),
                )
                is CloudTranslationResult.Failure -> publish(
                    state.copy(
                        translation = null,
                        status = HaoHaoTranslationStatus.FAILED,
                        failure = result.kind,
                    ),
                )
            }
            requestJob = null
        }
    }

    private fun cancelRequest() {
        generation.invalidate()
        requestJob?.cancel()
        requestJob = null
    }

    private fun publish(newState: HaoHaoTranslationState) {
        state = newState
        listeners.forEach { it.onStateChanged(newState) }
    }
}

internal class HaoHaoTranslationWindow(
    private val context: Context,
    private val controller: HaoHaoTranslationController,
) {
    private var speech = com.osfans.trime.ui.main.footprints.WordSpeech(context)
    private var speakingText: String? = null
    private var recreateSpeech = false
    private var speechIcon = speech.icon { controller.state.translation.takeIf { controller.state.status == HaoHaoTranslationStatus.READY } }
    private val preview = TextView(context).apply {
        setSingleLine(true)
        gravity = Gravity.CENTER_VERTICAL
        textSize = 16f
        setTextColor(ColorManager.getColor("candidate_text_color"))
        setPadding(context.dp(12), 0, context.dp(12), 0)
        setOnLongClickListener {
            showMoreMenu(it)
            true
        }
    }
    private val previewScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(preview, android.widget.FrameLayout.LayoutParams(-2, -1))
    }
    private val primaryAction = actionButton(R.string.haohao_translation_primary_commit).apply {
        minWidth = context.dp(48)
        setOnClickListener {
            when (controller.state.status) {
                HaoHaoTranslationStatus.READY -> controller.commitTranslation()
                HaoHaoTranslationStatus.FAILED -> {
                    context.toast(failureMessage(controller.state.failure))
                    controller.translateNow()
                }
                else -> Unit
            }
        }
    }
    private val closeAction = actionButton(R.string.haohao_translation_close).apply {
        minWidth = context.dp(48)
        setOnClickListener { controller.closeFromUser() }
    }

    private val listener = HaoHaoTranslationStateListener(::render)

    private val saveAction = android.widget.ImageButton(context).apply {
        setImageResource(android.R.drawable.btn_star_big_off)
        background = null
        contentDescription = context.getString(R.string.sentences_save)
        imageTintList = android.content.res.ColorStateList.valueOf(ColorManager.getColor("candidate_text_color"))
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        setOnClickListener { controller.saveSentence() }
    }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), 0, context.dp(4), 0)
        addView(speechIcon, LinearLayout.LayoutParams(context.dp(48), -1))
        addView(previewScroller, LinearLayout.LayoutParams(0, -1, 1f))
        addView(saveAction, LinearLayout.LayoutParams(context.dp(48), -1))
        addView(primaryAction, LinearLayout.LayoutParams(context.dp(48), -1))
        addView(closeAction, LinearLayout.LayoutParams(context.dp(48), -1))
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    if (recreateSpeech) {
                        speech = com.osfans.trime.ui.main.footprints.WordSpeech(context)
                        (view as LinearLayout).removeView(speechIcon)
                        speechIcon = speech.icon { controller.state.translation.takeIf { controller.state.status == HaoHaoTranslationStatus.READY } }
                        view.addView(speechIcon, 0, LinearLayout.LayoutParams(context.dp(48), -1))
                        recreateSpeech = false
                    }
                    controller.addListener(listener)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    speech.close()
                    recreateSpeech = true
                    controller.removeListener(listener)
                }
            },
        )
    }

    private fun actionButton(label: Int): Button = Button(context).apply {
        setText(label)
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minHeight = context.dp(48)
        setPadding(context.dp(2), 0, context.dp(2), 0)
        background = ContextCompat.getDrawable(context, R.drawable.haohao_segment_background)
        setTextColor(ColorManager.getColor("candidate_text_color"))
    }

    private fun showMoreMenu(anchor: View) {
        val state = controller.state
        PopupMenu(context, anchor).apply {
            menu.add(0, MENU_COMMIT_SOURCE, 0, R.string.haohao_translation_commit_source).isEnabled =
                state.draft.isNotEmpty()
            menu.add(0, MENU_COPY_RESULT, 1, R.string.haohao_translation_copy_result).isEnabled =
                !state.translation.isNullOrEmpty()
            menu.add(0, MENU_CLEAR, 2, R.string.haohao_translation_clear).isEnabled = state.draft.isNotEmpty()
            menu.add(0, MENU_EXIT, 3, R.string.haohao_translation_back)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COMMIT_SOURCE -> controller.commitSource()
                    MENU_COPY_RESULT -> controller.copyTranslation()
                    MENU_CLEAR -> controller.clear()
                    MENU_EXIT -> controller.closeFromUser()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun render(state: HaoHaoTranslationState) {
        saveAction.isEnabled = state.status == HaoHaoTranslationStatus.READY
        saveAction.alpha = if (saveAction.isEnabled) 1f else 0.3f
        val currentSpeech = state.translation.takeIf { state.active && state.status == HaoHaoTranslationStatus.READY }
        if (speakingText != currentSpeech) speech.stop()
        speakingText = currentSpeech
        speechIcon.isEnabled = currentSpeech != null
        speechIcon.alpha = if (currentSpeech != null) 1f else 0.3f
        val nextText = when (state.status) {
            HaoHaoTranslationStatus.FAILED -> ""
            else -> translationPreviewText(state)
        }
        primaryAction.contentDescription = null
        if (preview.text.toString() != nextText) {
            preview.text = nextText
            previewScroller.post {
                previewScroller.fullScroll(if (state.status == HaoHaoTranslationStatus.READY) View.FOCUS_LEFT else View.FOCUS_RIGHT)
            }
        }
        when (state.status) {
            HaoHaoTranslationStatus.READY -> {
                primaryAction.setText(R.string.haohao_translation_primary_commit)
                primaryAction.isEnabled = !state.translation.isNullOrEmpty()
            }
            HaoHaoTranslationStatus.FAILED -> {
                primaryAction.text = "↻"
                primaryAction.contentDescription = failureMessage(state.failure)
                primaryAction.isEnabled = state.draft.isNotEmpty()
            }
            else -> {
                primaryAction.setText(R.string.haohao_translation_primary_commit)
                primaryAction.isEnabled = false
            }
        }
    }

    private fun failureMessage(kind: CloudTranslationResult.Failure.Kind?): String = context.getString(
        when (kind) {
            CloudTranslationResult.Failure.Kind.TIMEOUT -> R.string.cloud_translation_error_timeout
            CloudTranslationResult.Failure.Kind.AUTHENTICATION -> R.string.cloud_translation_error_auth
            CloudTranslationResult.Failure.Kind.RATE_LIMITED -> R.string.cloud_translation_error_rate
            CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED -> R.string.cloud_translation_error_quota
            CloudTranslationResult.Failure.Kind.CONFIGURATION_EXPIRED -> R.string.cloud_translation_error_expired
            CloudTranslationResult.Failure.Kind.INVALID_RESPONSE -> R.string.cloud_translation_error_response
            CloudTranslationResult.Failure.Kind.NOT_CONFIGURED,
            CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED,
            -> R.string.haohao_translation_not_configured
            else -> R.string.cloud_translation_error_network
        },
    )

    private companion object {
        const val MENU_COMMIT_SOURCE = 1
        const val MENU_COPY_RESULT = 2
        const val MENU_CLEAR = 3
        const val MENU_EXIT = 4
    }
}
