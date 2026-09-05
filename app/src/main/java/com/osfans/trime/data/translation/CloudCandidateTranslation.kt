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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

internal const val CLOUD_CANDIDATE_CACHE_MAX_ENTRIES = 4_096
internal const val CLOUD_CANDIDATE_POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
internal const val CLOUD_CANDIDATE_NEGATIVE_TTL_MS = 8L * 60 * 1_000
internal const val CLOUD_CANDIDATE_SERVICE_COOLDOWN_MS = 30_000L
internal const val CLOUD_CANDIDATE_DEBOUNCE_MS = 800L
private const val CLOUD_CANDIDATE_MAX_TRANSLATION_CODE_POINTS = 32
private const val CLOUD_CANDIDATE_MAX_TRANSLATION_WORDS = 4
private val CLOUD_CANDIDATE_WORD = Regex("[A-Za-z]+(?:['\u2019-][A-Za-z]+)*")

internal enum class CandidateTranslationSourceMode {
    LOCAL_ONLY,
    CLOUD_ONLY,
    LOCAL_THEN_CLOUD,
    ;

    val requiresCloudConsent: Boolean
        get() = this != LOCAL_ONLY
}

internal fun resolveCandidateTranslationSourceMode(
    persistedMode: CandidateTranslationSourceMode?,
    legacyFallbackEnabled: Boolean,
): CandidateTranslationSourceMode = persistedMode ?: if (legacyFallbackEnabled) {
    CandidateTranslationSourceMode.LOCAL_THEN_CLOUD
} else {
    CandidateTranslationSourceMode.LOCAL_ONLY
}

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
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "word-cache-writer").apply { isDaemon = true } }
    private val preferences by lazy {
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun load(): List<CloudCandidateCacheEntry> = runCatching {
        val raw = preferences.getString(ENTRIES_KEY, null) ?: return emptyList()
        CACHE_JSON.decodeFromString(ListSerializer(CloudCandidateCacheEntry.serializer()), raw)
    }.getOrDefault(emptyList())

    override fun save(entries: List<CloudCandidateCacheEntry>) {
        writer.execute {
            val raw = CACHE_JSON.encodeToString(ListSerializer(CloudCandidateCacheEntry.serializer()), entries)
            preferences.edit().putString(ENTRIES_KEY, raw).apply()
        }
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
        val loaded = storage.load()
        val reusable = loaded
            .asSequence()
            .filter { now - it.storedAtMillis in 0 until CLOUD_CANDIDATE_POSITIVE_TTL_MS }
            .mapNotNull { entry ->
                sanitizeCandidateTranslation(entry.translation)?.let { translation ->
                    entry.copy(
                        text = candidateTextFingerprint(entry.text),
                        translation = translation,
                    )
                }
            }
            .sortedBy(CloudCandidateCacheEntry::storedAtMillis)
            .toList()
            .takeLast(maximumEntries)
        reusable.forEach { positive[storedCacheKey(it.providerFingerprint, it.text)] = it }
        if (reusable != loaded) persistLocked()
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
            val key = cacheKey(providerFingerprint, text)
            val translation = sanitizeCandidateTranslation(rawTranslation)
            if (translation == null) {
                positive.remove(key)
                negativeUntil[key] = storedAt + CLOUD_CANDIDATE_NEGATIVE_TTL_MS
                return@forEach
            }
            positive.remove(key)
            positive[key] = CloudCandidateCacheEntry(
                providerFingerprint = providerFingerprint,
                text = candidateTextFingerprint(text),
                translation = translation,
                storedAtMillis = storedAt,
            )
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
    ): String = storedCacheKey(providerFingerprint, candidateTextFingerprint(text))

    private fun storedCacheKey(
        providerFingerprint: String,
        textFingerprint: String,
    ): String = "$providerFingerprint\u0000$textFingerprint"
}

private fun candidateTextFingerprint(text: String): String {
    if (text.startsWith(CANDIDATE_TEXT_FINGERPRINT_PREFIX)) return text
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
    return CANDIDATE_TEXT_FINGERPRINT_PREFIX + digest.joinToString("") { "%02x".format(it) }
}

private const val CANDIDATE_TEXT_FINGERPRINT_PREFIX = "sha256:"

private fun sanitizeCandidateTranslation(value: String): String? {
    val translation = value.replace(Regex("\\s+"), " ").trim()
    if (translation.isEmpty()) return null
    if (translation.codePointCount(0, translation.length) > CLOUD_CANDIDATE_MAX_TRANSLATION_CODE_POINTS) return null
    val words = translation.split(' ')
    if (words.size !in 1..CLOUD_CANDIDATE_MAX_TRANSLATION_WORDS) return null
    if (words.any { !CLOUD_CANDIDATE_WORD.matches(it) }) return null
    return words.joinToString(" ")
}

internal object CloudCandidateTranslationRepository : CandidateTranslationRepository {
    private val cacheDelegate = lazy { CloudCandidateTranslationCache() }
    private val cache by cacheDelegate
    private val warming = java.util.concurrent.atomic.AtomicBoolean()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val config by lazy { CloudTranslationConfigStore() }
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    private fun warmUp() {
        if (!warming.compareAndSet(false, true)) return
        scope.launch {
            cache
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { listeners.forEach { it() } }
        }
    }

    override fun lookup(text: String): CandidateTranslationEntry? {
        if (!cacheDelegate.isInitialized()) {
            warmUp()
            return null
        }
        return cache.lookup(config.providerFingerprint(), text)
    }

    fun shouldRequest(
        providerFingerprint: String,
        text: String,
    ): Boolean {
        if (!cacheDelegate.isInitialized()) {
            warmUp()
            return false
        }
        return cache.shouldRequest(providerFingerprint, text)
    }

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
        warmUp()
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}

internal fun lookupCandidateTranslation(
    text: String,
    mode: CandidateTranslationSourceMode,
    offlineLookup: (String) -> CandidateTranslationEntry? = OfflineCandidateTranslationRepository::lookup,
    cloudLookup: (String) -> CandidateTranslationEntry? = CloudCandidateTranslationRepository::lookup,
): CandidateTranslationEntry? = when (mode) {
    CandidateTranslationSourceMode.LOCAL_ONLY -> offlineLookup(text)
    CandidateTranslationSourceMode.CLOUD_ONLY -> cloudLookup(text)
    CandidateTranslationSourceMode.LOCAL_THEN_CLOUD -> offlineLookup(text) ?: cloudLookup(text)
}

internal object ConfiguredCandidateTranslationRepository : CandidateTranslationRepository {
    override fun lookup(text: String): CandidateTranslationEntry? = lookupCandidateTranslation(
        text = text,
        mode = AppPrefs.defaultInstance().cloudTranslation.candidateSource.getValue(),
    )
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

internal class CloudCandidateServiceCooldown(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var unavailableUntilMillis = 0L

    fun isActive(): Boolean = nowMillis() < unavailableUntilMillis

    fun record(failure: CloudTranslationResult.Failure) {
        if (failure.kind in COOLDOWN_FAILURES) {
            unavailableUntilMillis = nowMillis() + CLOUD_CANDIDATE_SERVICE_COOLDOWN_MS
        }
    }

    private companion object {
        val COOLDOWN_FAILURES = setOf(
            CloudTranslationResult.Failure.Kind.NETWORK,
            CloudTranslationResult.Failure.Kind.AUTHENTICATION,
            CloudTranslationResult.Failure.Kind.RATE_LIMITED,
            CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED,
            CloudTranslationResult.Failure.Kind.UPSTREAM,
            CloudTranslationResult.Failure.Kind.INVALID_RESPONSE,
        )
    }
}

internal fun selectCloudCandidateMisses(
    texts: List<String>,
    offlineLookup: (String) -> CandidateTranslationEntry? = OfflineCandidateTranslationRepository::lookup,
    shouldRequest: (String) -> Boolean,
): List<String> = texts.asSequence()
    .distinct()
    .filter { offlineLookup(it) == null }
    .filter(shouldRequest)
    .take(5)
    .toList()

internal fun selectCloudCandidates(
    mode: CandidateTranslationSourceMode,
    texts: List<String>,
    offlineLookup: (String) -> CandidateTranslationEntry? = OfflineCandidateTranslationRepository::lookup,
    shouldRequest: (String) -> Boolean,
): List<String> = when (mode) {
    CandidateTranslationSourceMode.LOCAL_ONLY -> emptyList()
    CandidateTranslationSourceMode.CLOUD_ONLY -> texts.asSequence()
        .distinct()
        .filter(shouldRequest)
        .take(5)
        .toList()
    CandidateTranslationSourceMode.LOCAL_THEN_CLOUD -> selectCloudCandidateMisses(
        texts = texts,
        offlineLookup = offlineLookup,
        shouldRequest = shouldRequest,
    )
}

internal class CloudCandidateTranslationController : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val service: TrimeInputMethodService by di.instance()
    private val revealController: CandidateTranslationRevealController by di.instance()
    private val prefs = AppPrefs.defaultInstance()
    private var requestJob: Job? = null
    private var generation = 0L
    private var cloudAllowedForEditor = false
    private var lastRequestKey: String? = null
    private val serviceCooldown = CloudCandidateServiceCooldown()

    @Keep
    private val sourceListener = PreferenceDelegate.OnChangeListener<CandidateTranslationSourceMode> { _, mode ->
        cancelPending()
        if (mode == CandidateTranslationSourceMode.LOCAL_ONLY) {
            CloudCandidateTranslationRepository.removeListener(repositoryListener)
        } else {
            CloudCandidateTranslationRepository.addListener(repositoryListener)
        }
        revealController.notifyContentChanged()
    }

    private val repositoryListener: () -> Unit = {
        revealController.notifyContentChanged()
    }

    fun start() {
        prefs.cloudTranslation.candidateSource.registerOnChangeListener(sourceListener)
        if (prefs.cloudTranslation.candidateSource.getValue() != CandidateTranslationSourceMode.LOCAL_ONLY) {
            CloudCandidateTranslationRepository.addListener(repositoryListener)
        }
    }

    fun stop() {
        prefs.cloudTranslation.candidateSource.unregisterOnChangeListener(sourceListener)
        CloudCandidateTranslationRepository.removeListener(repositoryListener)
        cancelPending()
    }

    fun deactivate() {
        cloudAllowedForEditor = false
        cancelPending()
    }

    override fun onStartInput(info: EditorInfo) {
        cloudAllowedForEditor = CloudTranslationPrivacyPolicy.allows(info)
        cancelPending()
    }

    override fun onRimeKeyInput() {
        cancelPending()
    }

    fun requestVisible(texts: List<String>) {
        val sourceMode = prefs.cloudTranslation.candidateSource.getValue()
        if (
            !cloudAllowedForEditor ||
            sourceMode == CandidateTranslationSourceMode.LOCAL_ONLY ||
            !prefs.cloudTranslation.consentGranted.getValue()
        ) {
            cancelPending()
            return
        }
        if (
            !prefs.candidates.bilingualTranslation.getValue() ||
            serviceCooldown.isActive() ||
            CloudTranslationRuntime.manager.status() != null
        ) {
            cancelPending()
            return
        }
        val providerFingerprint = CloudCandidateTranslationRepository.currentProviderFingerprint()
        val misses = selectCloudCandidates(sourceMode, texts) {
            CloudCandidateTranslationRepository.shouldRequest(providerFingerprint, it)
        }
        if (misses.isEmpty()) return
        val requestKey = "$sourceMode\u0000$providerFingerprint\u0000" +
            misses.joinToString("\u0000", transform = ::candidateTextFingerprint)
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
                    serviceCooldown.record(result)
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
