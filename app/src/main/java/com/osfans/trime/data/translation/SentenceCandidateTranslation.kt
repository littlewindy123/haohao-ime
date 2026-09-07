// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class SentenceCandidateStatus { IDLE, WAITING, TRANSLATING, READY, UNAVAILABLE, TOO_LONG, FAILED }

internal data class SentenceCandidateState(
    val source: String = "",
    val translation: String? = null,
    val status: SentenceCandidateStatus = SentenceCandidateStatus.IDLE,
    val failure: CloudTranslationResult.Failure.Kind? = null,
)

internal data class SentenceCandidateContext(
    val sourceMode: CandidateTranslationSourceMode,
    val providerFingerprint: String,
    val enabled: Boolean,
    val editorAllowed: Boolean,
    val cloudFailure: CloudTranslationResult.Failure.Kind? = null,
)

/** Main-thread session: never reads an editor or persists composed sentences to disk. */
internal class SentenceCandidateTranslationSession(
    private val scope: CoroutineScope,
    private val translate: suspend (CloudTranslationRequest) -> CloudTranslationResult,
    private val cachedLookup: (String, CandidateTranslationSourceMode) -> String?,
    private val onState: (SentenceCandidateState) -> Unit,
    private val debounceMillis: Long = CLOUD_CANDIDATE_DEBOUNCE_MS,
    private val configuredDelayMillis: () -> Long = { debounceMillis },
) {
    private data class Input(val source: String, val context: SentenceCandidateContext)
    private var input: Input? = null
    private var generation = 0L
    private var job: Job? = null
    private val cache = object : LinkedHashMap<Input, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Input, String>?) = size > 32
    }
    var state = SentenceCandidateState()
        private set

    fun update(source: String?, context: SentenceCandidateContext) {
        if (source.isNullOrBlank() || !context.enabled || !context.editorAllowed) {
            if (input != null || state != SentenceCandidateState()) invalidate()
            return
        }
        val next = Input(source, context)
        if (input == next && state.status != SentenceCandidateStatus.UNAVAILABLE) return
        invalidate()
        input = next
        if (source.codePointCount(0, source.length) > TRANSLATION_SENTENCE_MAX_CODE_POINTS) {
            publish(SentenceCandidateState(source, status = SentenceCandidateStatus.TOO_LONG))
            return
        }
        val cached = cache[next] ?: cachedLookup(source, context.sourceMode)
        if (!cached.isNullOrBlank()) {
            publish(SentenceCandidateState(source, cached, SentenceCandidateStatus.READY))
            return
        }
        if (context.sourceMode == CandidateTranslationSourceMode.LOCAL_ONLY) {
            publish(SentenceCandidateState(source, status = SentenceCandidateStatus.UNAVAILABLE))
            return
        }
        context.cloudFailure?.let {
            publish(SentenceCandidateState(source, status = SentenceCandidateStatus.FAILED, failure = it))
            return
        }
        val requestGeneration = generation
        publish(SentenceCandidateState(source, status = SentenceCandidateStatus.WAITING))
        job = scope.launch {
            delay(configuredDelayMillis().coerceIn(0L, CLOUD_CANDIDATE_DELAY_MAX_MS.toLong()))
            if (requestGeneration != generation) return@launch
            publish(SentenceCandidateState(source, status = SentenceCandidateStatus.TRANSLATING))
            val result = executeTranslationRequest(
                CloudTranslationProvider(translate),
                CloudTranslationRequest(listOf(source), TranslationPurpose.SENTENCE),
            )
            if (requestGeneration != generation || input != next) return@launch
            job = null
            when (result) {
                is CloudTranslationResult.Success -> {
                    val text = result.translations.single().trim()
                    cache[next] = text
                    publish(SentenceCandidateState(source, text, SentenceCandidateStatus.READY))
                }
                is CloudTranslationResult.Failure -> publish(
                    SentenceCandidateState(source, status = SentenceCandidateStatus.FAILED, failure = result.kind),
                )
            }
        }
    }

    fun retry() {
        val previous = input ?: return
        invalidate()
        update(previous.source, previous.context)
    }

    fun invalidate(clearCache: Boolean = false) {
        generation++
        job?.cancel()
        job = null
        input = null
        if (clearCache) cache.clear()
        publish(SentenceCandidateState())
    }

    private fun publish(value: SentenceCandidateState) {
        if (state == value) return
        state = value
        onState(value)
    }
}
