/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.content.ClipData
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
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
internal const val HAOHAO_TRANSLATION_BAR_HEIGHT_DP = 116

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
        state.draft.takeIf(String::isNotEmpty)?.let(service::commitTextDirect)
        deactivate()
    }

    fun commitTranslation() {
        state.translation?.takeIf(String::isNotEmpty)?.let(service::commitTextDirect)
        deactivate()
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

    fun stop() {
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
    private val draft = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        gravity = Gravity.CENTER_VERTICAL
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ColorManager.getColor("candidate_text_color"))
        setPadding(context.dp(12), 0, context.dp(12), 0)
    }
    private val result = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        textSize = 14f
        setTextColor(ColorManager.getColor("comment_text_color"))
        setPadding(context.dp(12), 0, context.dp(12), 0)
    }
    private val commitResult = actionButton(R.string.haohao_translation_commit_result, controller::commitTranslation)
    private val commitSource = actionButton(R.string.haohao_translation_commit_source, controller::commitSource)
    private val copyResult = actionButton(R.string.haohao_translation_copy_result, controller::copyTranslation)
    private val clear = actionButton(R.string.haohao_translation_clear, controller::clear)
    private val back = actionButton(R.string.haohao_translation_back, controller::deactivate)

    private val listener = HaoHaoTranslationStateListener(::render)

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(draft, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(34)))
        addView(result, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(30)))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(commitResult, commitSource, copyResult, clear, back).forEach { button ->
                    addView(button, LinearLayout.LayoutParams(0, context.dp(48), 1f))
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48)),
        )
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    controller.addListener(listener)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    controller.removeListener(listener)
                }
            },
        )
    }

    private fun actionButton(
        label: Int,
        action: () -> Unit,
    ): Button = Button(context).apply {
        setText(label)
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minHeight = context.dp(48)
        setPadding(context.dp(2), 0, context.dp(2), 0)
        background = ContextCompat.getDrawable(context, R.drawable.haohao_segment_background)
        setTextColor(ColorManager.getColor("candidate_text_color"))
        setOnClickListener { action() }
    }

    private fun render(state: HaoHaoTranslationState) {
        draft.text = state.draft.ifEmpty { context.getString(R.string.haohao_translation_draft_hint) }
        result.text = when (state.status) {
            HaoHaoTranslationStatus.IDLE -> context.getString(R.string.haohao_translation_waiting)
            HaoHaoTranslationStatus.WAITING -> context.getString(R.string.haohao_translation_waiting)
            HaoHaoTranslationStatus.TRANSLATING -> context.getString(R.string.haohao_translation_translating)
            HaoHaoTranslationStatus.READY -> state.translation.orEmpty()
            HaoHaoTranslationStatus.FAILED -> failureMessage(state.failure)
        }
        val hasDraft = state.draft.isNotEmpty()
        val hasTranslation = !state.translation.isNullOrEmpty()
        commitResult.isEnabled = hasTranslation
        copyResult.isEnabled = hasTranslation
        commitSource.isEnabled = hasDraft
        clear.isEnabled = hasDraft
    }

    private fun failureMessage(kind: CloudTranslationResult.Failure.Kind?): String = context.getString(
        when (kind) {
            CloudTranslationResult.Failure.Kind.AUTHENTICATION -> R.string.cloud_translation_error_auth
            CloudTranslationResult.Failure.Kind.RATE_LIMITED -> R.string.cloud_translation_error_rate
            CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED -> R.string.cloud_translation_error_quota
            CloudTranslationResult.Failure.Kind.INVALID_RESPONSE -> R.string.cloud_translation_error_response
            CloudTranslationResult.Failure.Kind.NOT_CONFIGURED,
            CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED,
            -> R.string.haohao_translation_not_configured
            else -> R.string.cloud_translation_error_network
        },
    )
}
