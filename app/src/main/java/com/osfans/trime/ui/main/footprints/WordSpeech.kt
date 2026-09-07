// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.footprints

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.data.speech.SpeechFailure
import com.osfans.trime.data.speech.SpeechPlayback
import com.osfans.trime.data.speech.SpeechRate
import com.osfans.trime.data.speech.SpeechStatus
import com.osfans.trime.data.speech.speechCacheKey
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** UI-only owner; also supports the IME attached dialog window. */
internal class WordSpeech(private val context: Context) {
    private val owner = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    private var requestVersion = 0L
    private var dialog: AlertDialog? = null
    private val buttons = mutableListOf<ImageButton>()
    private val labels = mutableListOf<TextView>()
    init {
        scope.launch {
            SpeechPlayback.state.collect { state ->
                val own = state.owner === owner
                val busy = own && state.status in setOf(SpeechStatus.LOADING, SpeechStatus.PLAYING)
                buttons.forEach {
                    it.setImageResource(if (busy) R.drawable.ic_haohao_stop_24 else R.drawable.ic_haohao_volume_24)
                    it.contentDescription = context.getString(if (busy) R.string.speech_stop else R.string.input_footprints_speak)
                    it.alpha = if (own && state.status == SpeechStatus.LOADING) 0.5f else 1f
                }
                labels.forEach {
                    it.setText(
                        if (own && state.status == SpeechStatus.LOADING) {
                            R.string.speech_loading
                        } else if (busy) {
                            R.string.speech_playing
                        } else {
                            R.string.speech_manual
                        },
                    )
                }
                if (own && state.status == SpeechStatus.FAILED) context.toast(message(state.failure))
            }
        }
    }

    fun speak(text: String, rate: SpeechRate = SpeechRate.NORMAL, stillValid: () -> Boolean = { true }) {
        if (closed || text.isBlank() || !stillValid()) return
        if (dialog?.isShowing == true) return
        val active = SpeechPlayback.state.value
        if (active.owner === owner && active.key == speechCacheKey(text, rate) && active.status in setOf(SpeechStatus.LOADING, SpeechStatus.PLAYING)) {
            stop()
            return
        }
        // Stop immediately, before disk-cache checks or a new consent dialog can suspend.
        SpeechPlayback.stop()
        val version = ++requestVersion
        scope.launch {
            try {
                val needsNetwork = SpeechPlayback.needsNetwork(context, text, rate)
                if (closed || version != requestVersion || !stillValid()) return@launch
                if (!needsNetwork || SpeechPlayback.consent(context)) {
                    SpeechPlayback.toggle(context, owner, text, rate)
                } else {
                    requestConsent(text, rate) { version == requestVersion && stillValid() }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && version == requestVersion) context.toast(message((failure as? com.osfans.trime.data.speech.SpeechException)?.reason))
            }
        }
    }

    private fun requestConsent(text: String, rate: SpeechRate, stillValid: () -> Boolean) {
        dialog = AlertDialog.Builder(context).setTitle(R.string.speech_consent_title)
            .setMessage(R.string.speech_consent_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.speech_allow) { _, _ ->
                if (!closed && stillValid()) {
                    SpeechPlayback.setConsent(context, true)
                    SpeechPlayback.toggle(context, owner, text, rate)
                }
            }.create()
        var base: Context = context
        while (base is ContextWrapper && base !is TrimeInputMethodService && base.baseContext !== base) base = base.baseContext
        if (base is TrimeInputMethodService) base.showDialog(requireNotNull(dialog)) else dialog?.show()
    }

    fun icon(text: () -> String?): ImageButton = ImageButton(context).apply {
        background = null
        minimumHeight = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setImageResource(R.drawable.ic_haohao_volume_24)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.haohao_cocoa))
        contentDescription = context.getString(R.string.input_footprints_speak)
        setOnClickListener { text()?.let { value -> speak(value, stillValid = { isAttachedToWindow && text() == value }) } }
        setOnLongClickListener {
            val value = text()
            PopupMenu(context, this).apply {
                menu.add(R.string.speech_slow).setOnMenuItemClickListener {
                    value?.let { speak(it, SpeechRate.SLOW) { isAttachedToWindow && text() == value } }
                    true
                }
                show()
            }
            true
        }
        buttons += this
    }

    fun controls(text: () -> String?): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        addView(icon(text), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(
            AppCompatButton(context).apply {
                setText(R.string.learning_slow_short)
                contentDescription = context.getString(R.string.speech_slow)
                minWidth = 0
                minimumWidth = dp(64)
                setPadding(dp(10), 0, dp(10), 0)
                isAllCaps = false
                minHeight = dp(48)
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.haohao_cocoa))
                background = ContextCompat.getDrawable(context, R.drawable.haohao_segment_background)
                setOnClickListener { text()?.let { value -> speak(value, SpeechRate.SLOW) { isAttachedToWindow && text() == value } } }
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        addView(
            TextView(context).apply {
                setText(R.string.speech_manual)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(context, R.color.haohao_cocoa_secondary))
                setPadding(dp(12), 0, 0, 0)
                labels += this
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
    }

    fun stop() {
        requestVersion++
        dialog?.dismiss()
        SpeechPlayback.stop(owner)
    }
    fun clearBindings() {
        stop()
        buttons.clear()
        labels.clear()
    }
    fun close() {
        closed = true
        stop()
        scope.cancel()
        buttons.clear()
        labels.clear()
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun message(reason: SpeechFailure?): Int = when (reason) {
        SpeechFailure.TOO_LONG -> R.string.speech_too_long
        SpeechFailure.INVALID_TEXT -> R.string.speech_invalid_text
        SpeechFailure.NOT_CONFIGURED -> R.string.speech_not_configured
        SpeechFailure.EXPIRED -> R.string.speech_expired
        SpeechFailure.UNAUTHORIZED -> R.string.speech_unauthorized
        SpeechFailure.QUOTA -> R.string.speech_quota
        SpeechFailure.BUSY -> R.string.speech_busy
        SpeechFailure.TIMEOUT -> R.string.speech_timeout
        SpeechFailure.NETWORK -> R.string.speech_network
        else -> R.string.speech_failed
    }
}
