/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.translation

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.annotation.Keep
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.footprints.InputFootprintPolicy
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationEntry
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRepository
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationRevealController
import com.osfans.trime.ime.candidates.bilingual.OfflineCandidateTranslationRepository
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.kodein.di.instance
import java.util.concurrent.CopyOnWriteArraySet

internal const val CLOUD_CANDIDATE_CACHE_MAX_ENTRIES = 4_096
internal const val CLOUD_CANDIDATE_POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
internal const val CLOUD_CANDIDATE_NEGATIVE_TTL_MS = 8L * 60 * 1_000
private const val CLOUD_CANDIDATE_DEBOUNCE_MS = 300L

@Serializable
internal data class CloudCandidateCacheEntry(
    val providerFingerprint: String,
    val text: String,
    val translation: String,
    val storedAtMillis: Long,
)

internal interface CloudCandidateCacheStorage {
    fun load(): List<CloudCandidateCacheEntry>

    fun save(entries: List<CloudCandidateCacheEntry>)
}

private object SharedPrefsCloudCandidateCacheStorage : CloudCandidateCacheStorage {
    private const val PREFERENCES_NAME = "haohao_cloud_translation_cache"
    private const val ENTRIES_KEY = "positive_entries_v1"
    private val preferences by lazy {
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun load(): List<CloudCandidateCacheEntry> = runCatching {
        val raw = preferences.getString(ENTRIES_KEY, null) ?: return emptyList()
        CACHE_JSON.decodeFromString(ListSerializer(CloudCandidateCacheEntry.serializer()), raw)
    }.getOrDefault(emptyList())

    override fun save(entries: List<CloudCandidateCacheEntry>) {
        val raw = CACHE_JSON.encodeToString(ListSerializer(CloudCandidateCacheEntry.serializer()), entries)
        preferences.edit().putString(ENTRIES_KEY, raw).apply()
    }
}

internal class CloudCandidateTranslationCache(
    private val storage: CloudCandidateCacheStorage = SharedPrefsCloudCandidateCacheStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maximumEntries: Int = CLOUD_CANDIDATE_CACHE_MAX_ENTRIES,
) {
    private val lock = Any()
    private val positive = LinkedHashMap<String, CloudCandidateCacheEntry>()
    private val negativeUntil = HashMap<String, Long>()

    init {
        val now = nowMillis()
        storage.load()
            .asSequence()
            .filter { now - it.storedAtMillis in 0 until CLOUD_CANDIDATE_POSITIVE_TTL_MS }
            .sortedBy(CloudCandidateCacheEntry::storedAtMillis)
            .toList()
            .takeLast(maximumEntries)
            .forEach { positive[cacheKey(it.providerFingerprint, it.text)] = it }
    }

    fun lookup(
        providerFingerprint: String,
        text: String,
    ): CandidateTranslationEntry? = synchronized(lock) {
        val key = cacheKey(providerFingerprint, text)
        val entry = positive[key] ?: return@synchronized null
        if (nowMillis() - entry.storedAtMillis !in 0 until CLOUD_CANDIDATE_POSITIVE_TTL_MS) {
            positive.remove(key)
            persistLocked()
            return@synchronized null
        }
        CandidateTranslationEntry(entry.translation, null)
    }

    fun shouldRequest(
        providerFingerprint: String,
        text: String,
    ): Boolean = synchronized(lock) {
        if (lookup(providerFingerprint, text) != null) return@synchronized false
        val key = cacheKey(providerFingerprint, text)
        val expiresAt = negativeUntil[key] ?: return@synchronized true
        if (expiresAt <= nowMillis()) {
            negativeUntil.remove(key)
            true
        } else {
            false
        }
    }

    fun put(
        providerFingerprint: String,
        values: Map<String, String>,
    ) = synchronized(lock) {
        val storedAt = nowMillis()
        values.forEach { (text, rawTranslation) ->
            val translation = sanitizeCandidateTranslation(rawTranslation) ?: return@forEach
            val key = cacheKey(providerFingerprint, text)
            positive.remove(key)
            positive[key] = CloudCandidateCacheEntry(providerFingerprint, text, translation, storedAt)
            negativeUntil.remove(key)
        }
        while (positive.size > maximumEntries) {
            positive.remove(positive.keys.first())
        }
        persistLocked()
    }

    fun markNegative(
        providerFingerprint: String,
        texts: Collection<String>,
    ) = synchronized(lock) {
        val expiresAt = nowMillis() + CLOUD_CANDIDATE_NEGATIVE_TTL_MS
        texts.forEach { negativeUntil[cacheKey(providerFingerprint, it)] = expiresAt }
    }

    internal fun entries(): List<CloudCandidateCacheEntry> = synchronized(lock) { positive.values.toList() }

    private fun persistLocked() {
        storage.save(positive.values.toList())
    }

    private fun cacheKey(
        providerFingerprint: String,
        text: String,
    ): String = "$providerFingerprint\u0000$text"
}

private fun sanitizeCandidateTranslation(value: String): String? {
    val translation = value.replace(Regex("\\s+"), " ").trim()
    if (translation.isEmpty() || translation.codePointCount(0, translation.length) > 80) return null
    return translation
}

internal object CloudCandidateTranslationRepository : CandidateTranslationRepository {
    private val cache = CloudCandidateTranslationCache()
    private val config by lazy { CloudTranslationConfigStore() }
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    override fun lookup(text: String): CandidateTranslationEntry? = cache.lookup(config.providerFingerprint(), text)

    fun shouldRequest(
        providerFingerprint: String,
        text: String,
    ): Boolean = cache.shouldRequest(providerFingerprint, text)

    fun currentProviderFingerprint(): String = config.providerFingerprint()

    fun put(
        providerFingerprint: String,
        values: Map<String, String>,
    ) {
        if (values.isEmpty()) return
        cache.put(providerFingerprint, values)
        listeners.forEach { it() }
    }

    fun markNegative(
        providerFingerprint: String,
        texts: Collection<String>,
    ) {
        cache.markNegative(providerFingerprint, texts)
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}

internal object OfflineFirstCandidateTranslationRepository : CandidateTranslationRepository {
    override fun lookup(text: String): CandidateTranslationEntry? = OfflineCandidateTranslationRepository.lookup(text) ?: CloudCandidateTranslationRepository.lookup(text)
}

internal object CloudTranslationRuntime {
    val manager: CloudTranslationManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CloudTranslationManager()
    }
}

internal object CloudTranslationPrivacyPolicy {
    fun allows(info: EditorInfo?): Boolean = info != null && allows(info.inputType, info.imeOptions)

    fun allows(
        inputType: Int,
        imeOptions: Int,
    ): Boolean = InputFootprintPolicy.canRecord(inputType, imeOptions)
}

internal fun selectCloudCandidateMisses(
    texts: List<String>,
    offlineLookup: (String) -> CandidateTranslationEntry? = OfflineCandidateTranslationRepository::lookup,
    shouldRequest: (String) -> Boolean,
): List<String> = texts.asSequence()
    .filter { offlineLookup(it) == null }
    .filter(shouldRequest)
    .distinct()
    .take(5)
    .toList()

internal class CloudCandidateTranslationController : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val service: TrimeInputMethodService by di.instance()
    private val revealController: CandidateTranslationRevealController by di.instance()
    private val prefs = AppPrefs.defaultInstance()
    private var requestJob: Job? = null
    private var generation = 0L
    private var cloudAllowedForEditor = false
    private var lastRequestKey: String? = null

    @Keep
    private val fallbackListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        if (!prefs.cloudTranslation.candidateFallback.getValue()) cancelPending()
    }

    private val repositoryListener: () -> Unit = {
        revealController.notifyContentChanged()
    }

    fun start() {
        prefs.cloudTranslation.candidateFallback.registerOnChangeListener(fallbackListener)
        CloudCandidateTranslationRepository.addListener(repositoryListener)
    }

    fun stop() {
        prefs.cloudTranslation.candidateFallback.unregisterOnChangeListener(fallbackListener)
        CloudCandidateTranslationRepository.removeListener(repositoryListener)
        cancelPending()
    }

    override fun onStartInput(info: EditorInfo) {
        cloudAllowedForEditor = CloudTranslationPrivacyPolicy.allows(info)
        cancelPending()
    }

    fun requestVisible(texts: List<String>) {
        if (!cloudAllowedForEditor || !prefs.cloudTranslation.candidateFallback.getValue()) {
            cancelPending()
            return
        }
        if (!prefs.candidates.bilingualTranslation.getValue() || CloudTranslationRuntime.manager.status() != null) {
            cancelPending()
            return
        }
        val providerFingerprint = CloudCandidateTranslationRepository.currentProviderFingerprint()
        val misses = selectCloudCandidateMisses(texts) {
            CloudCandidateTranslationRepository.shouldRequest(providerFingerprint, it)
        }
        if (misses.isEmpty()) return
        val requestKey = "$providerFingerprint\u0000${misses.joinToString("\u0000")}"
        if (requestKey == lastRequestKey && requestJob?.isActive == true) return
        cancelPending(clearRequestKey = false)
        lastRequestKey = requestKey
        val requestGeneration = ++generation
        requestJob = service.lifecycleScope.launch {
            delay(CLOUD_CANDIDATE_DEBOUNCE_MS)
            val result = CloudTranslationRuntime.manager.translate(
                CloudTranslationRequest(misses, TranslationPurpose.CANDIDATE),
            )
            if (
                requestGeneration != generation ||
                providerFingerprint != CloudCandidateTranslationRepository.currentProviderFingerprint()
            ) {
                return@launch
            }
            when (result) {
                is CloudTranslationResult.Success -> {
                    val values = misses.zip(result.translations).toMap()
                    CloudCandidateTranslationRepository.put(providerFingerprint, values)
                }
                is CloudTranslationResult.Failure -> {
                    CloudCandidateTranslationRepository.markNegative(providerFingerprint, misses)
                }
            }
            requestJob = null
        }
    }

    private fun cancelPending(clearRequestKey: Boolean = true) {
        generation += 1
        requestJob?.cancel()
        requestJob = null
        if (clearRequestKey) lastRequestKey = null
    }
}

private val CACHE_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
