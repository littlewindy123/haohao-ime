/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.bilingual

import android.view.inputmethod.EditorInfo
import androidx.annotation.Keep
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import java.util.concurrent.CopyOnWriteArraySet

internal const val BILINGUAL_TRANSLATION_DELAY_MIN_MS = 0
internal const val BILINGUAL_TRANSLATION_DELAY_DEFAULT_MS = 300
internal const val BILINGUAL_TRANSLATION_DELAY_MAX_MS = 1_000
internal const val BILINGUAL_TRANSLATION_DELAY_STEP_MS = 100

internal enum class CandidateTranslationRevealState {
    HIDDEN,
    PENDING,
    READY,
}

internal fun interface CandidateTranslationRevealListener {
    fun onRevealStateChanged(state: CandidateTranslationRevealState)
}

internal fun interface CandidateTranslationDelayTask {
    fun cancel()
}

internal fun interface CandidateTranslationDelayScheduler {
    fun schedule(
        delayMillis: Long,
        block: () -> Unit,
    ): CandidateTranslationDelayTask
}

internal class CandidateTranslationRevealController(
    private val isTranslationEnabled: () -> Boolean = {
        AppPrefs.defaultInstance().candidates.bilingualTranslation.getValue()
    },
    private val delayMillis: () -> Int = {
        AppPrefs.defaultInstance().candidates.bilingualTranslationDelay.getValue()
    },
    private val scheduler: CandidateTranslationDelayScheduler,
) : InputBroadcastReceiver {
    private val listeners = CopyOnWriteArraySet<CandidateTranslationRevealListener>()
    private var pendingTask: CandidateTranslationDelayTask? = null
    private var generation = 0L
    private var hasCandidates = false
    private var observingPreferences = false

    var state = CandidateTranslationRevealState.HIDDEN
        private set

    @Keep
    private val translationPreferenceListener =
        PreferenceDelegate.OnChangeListener<Boolean> { _, _ -> onPreferencesChanged() }

    @Keep
    private val delayPreferenceListener =
        PreferenceDelegate.OnChangeListener<Int> { _, _ -> onPreferencesChanged() }

    fun start() {
        if (observingPreferences) return
        observingPreferences = true
        val preferences = AppPrefs.defaultInstance().candidates
        preferences.bilingualTranslation.registerOnChangeListener(translationPreferenceListener)
        preferences.bilingualTranslationDelay.registerOnChangeListener(delayPreferenceListener)
    }

    fun stop() {
        if (observingPreferences) {
            val preferences = AppPrefs.defaultInstance().candidates
            preferences.bilingualTranslation.unregisterOnChangeListener(translationPreferenceListener)
            preferences.bilingualTranslationDelay.unregisterOnChangeListener(delayPreferenceListener)
            observingPreferences = false
        }
        hasCandidates = false
        cancelPendingReveal()
        publish(CandidateTranslationRevealState.HIDDEN, force = true)
        listeners.clear()
    }

    fun addListener(listener: CandidateTranslationRevealListener) {
        listeners += listener
    }

    fun removeListener(listener: CandidateTranslationRevealListener) {
        listeners -= listener
    }

    fun notifyContentChanged() {
        listeners.forEach { it.onRevealStateChanged(state) }
    }

    override fun onStartInput(info: EditorInfo) {
        hasCandidates = false
        restartReveal()
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        hasCandidates = data.candidates.isNotEmpty()
        restartReveal()
    }

    internal fun onPreferencesChanged() {
        restartReveal()
    }

    private fun restartReveal() {
        cancelPendingReveal()
        val currentGeneration = generation
        if (!hasCandidates || !isTranslationEnabled()) {
            publish(CandidateTranslationRevealState.HIDDEN, force = true)
            return
        }

        val configuredDelay = delayMillis().coerceIn(
            BILINGUAL_TRANSLATION_DELAY_MIN_MS,
            BILINGUAL_TRANSLATION_DELAY_MAX_MS,
        )
        if (configuredDelay == 0) {
            publish(CandidateTranslationRevealState.READY, force = true)
            return
        }

        publish(CandidateTranslationRevealState.PENDING, force = true)
        pendingTask = scheduler.schedule(configuredDelay.toLong()) {
            if (
                currentGeneration == generation &&
                hasCandidates &&
                isTranslationEnabled()
            ) {
                pendingTask = null
                publish(CandidateTranslationRevealState.READY)
            }
        }
    }

    private fun cancelPendingReveal() {
        generation += 1
        pendingTask?.cancel()
        pendingTask = null
    }

    private fun publish(
        newState: CandidateTranslationRevealState,
        force: Boolean = false,
    ) {
        if (!force && state == newState) return
        state = newState
        listeners.forEach { it.onRevealStateChanged(newState) }
    }
}
