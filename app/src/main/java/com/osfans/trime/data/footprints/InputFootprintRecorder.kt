/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import android.view.inputmethod.EditorInfo
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.bilingual.OfflineCandidateTranslationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal object InputFootprintRecorder : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {
    private val lastTimestamp = AtomicLong(0)

    fun record(
        text: String,
        editorInfo: EditorInfo?,
    ) {
        if (editorInfo == null) return
        val enabled = AppPrefs.defaultInstance().candidates.learningHistoryEnabled.getValue()
        val inputType = editorInfo.inputType
        val imeOptions = editorInfo.imeOptions
        if (!enabled || !InputFootprintPolicy.canRecord(inputType, imeOptions)) return
        val timestamp = nextTimestamp()
        launch {
            val hasTranslation = OfflineCandidateTranslationRepository.lookup(text) != null
            if (
                !InputFootprintPolicy.shouldRecord(
                    enabled = enabled,
                    inputType = inputType,
                    imeOptions = imeOptions,
                    hasTranslation = hasTranslation,
                )
            ) {
                return@launch
            }
            InputFootprints.store.record(text, timestamp)
        }
    }

    private fun nextTimestamp(): Long {
        while (true) {
            val previous = lastTimestamp.get()
            val next = maxOf(System.currentTimeMillis(), previous + 1)
            if (lastTimestamp.compareAndSet(previous, next)) return next
        }
    }
}
