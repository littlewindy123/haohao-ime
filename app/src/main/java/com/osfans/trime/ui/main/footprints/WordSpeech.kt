/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ui.main.footprints

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AlertDialog
import com.osfans.trime.R
import com.osfans.trime.util.toast
import java.util.Locale
import java.util.UUID

/** No network voice is selected implicitly, including when the device default requires one. */
internal class WordSpeech(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var state = R.string.words_speech_initializing
    private var closed = false
    private var generation = 0
    private var pending: String? = null
    private var utterance: String? = null
    private var dialog: AlertDialog? = null

    init {
        initialize()
    }

    private fun initialize() {
        val request = ++generation
        state = R.string.words_speech_initializing
        engine?.shutdown()
        engine = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                if (closed || request != generation) return@post
                val current = engine ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    state = R.string.words_speech_init_failed
                } else {
                    val voice = current.voices.orEmpty().filter {
                        it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired &&
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                    }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == Locale.US }.thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()
                    state = if (voice == null) {
                        R.string.words_speech_missing
                    } else if (current.setVoice(voice) == TextToSpeech.SUCCESS) {
                        0
                    } else {
                        R.string.words_speech_failed
                    }
                    current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = Unit

                        @Deprecated("Required by the platform listener")
                        override fun onError(utteranceId: String?) {
                            handler.post {
                                if (!closed && request == generation && utteranceId == utterance) {
                                    state = R.string.words_speech_failed
                                    showRecovery()
                                }
                            }
                        }
                    })
                }
                pending?.let { text ->
                    pending = null
                    speak(text)
                }
            }
        }
    }

    fun speak(text: String) {
        if (closed) return
        pending = text
        if (state == R.string.words_speech_initializing) {
            context.toast(state)
            return
        }
        if (state != 0) {
            showRecovery()
            return
        }
        val id = UUID.randomUUID().toString()
        utterance = id
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            state = R.string.words_speech_failed
            showRecovery()
        }
    }

    private fun showRecovery() {
        if (closed || dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(context).setTitle(R.string.input_footprints_speak)
            .setMessage(state).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.words_speech_retry) { _, _ -> initialize() }
            .setNeutralButton(R.string.words_speech_settings) { _, _ ->
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
            }.show()
    }

    fun close() {
        closed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
