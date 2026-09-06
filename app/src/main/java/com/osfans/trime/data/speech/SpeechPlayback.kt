// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.core.content.ContextCompat
import com.osfans.trime.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class SpeechStatus { IDLE, LOADING, PLAYING, FAILED }
internal data class SpeechState(val owner: Any? = null, val key: String = "", val status: SpeechStatus = SpeechStatus.IDLE, val failure: SpeechFailure? = null)

/** One process-wide player; no cloud credentials or editor contents are passed to the engine. */
internal object SpeechPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SpeechState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    fun isCurrent(value: Long): Boolean = value == revision && mutableState.value.owner != null
    private var cache: SpeechDiskCache? = null
    private val transport = SpeechTransport(BuildConfig.HAOHAO_SPEECH_ENDPOINT, BuildConfig.INTERNAL_SPEECH_CLIENT_TOKEN)
    fun consent(context: Context): Boolean = preferences(context).getBoolean("consent_v1", false)
    fun setConsent(context: Context, allowed: Boolean) {
        preferences(context).edit().putBoolean("consent_v1", allowed).apply()
        if (!allowed) stop()
    }
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("haohao_speech_privacy", Context.MODE_PRIVATE)

    @Synchronized private fun cache(context: Context): SpeechDiskCache = cache ?: SpeechDiskCache(File(context.applicationContext.noBackupFilesDir, "speech-audio")).also { cache = it }
    fun clearCache(context: Context) {
        stop()
        scope.launch { withContext(Dispatchers.IO) { cache(context).clear() } }
    }
    suspend fun needsNetwork(context: Context, text: String, rate: SpeechRate): Boolean {
        val segments = speechSegments(text)
        return withContext(Dispatchers.IO) { segments.any { cache(context).get(speechCacheKey(it, rate)) == null } }
    }
    fun stop(owner: Any? = null) {
        if (owner != null && mutableState.value.owner !== owner) return
        revision++
        job?.cancel()
        job = null
        mutableState.value = SpeechState()
    }
    fun toggle(context: Context, owner: Any, text: String, rate: SpeechRate = SpeechRate.NORMAL) {
        val key = speechCacheKey(text, rate)
        if (mutableState.value.let { it.owner === owner && it.key == key && it.status in setOf(SpeechStatus.LOADING, SpeechStatus.PLAYING) }) {
            stop(owner)
            return
        }
        stop()
        val current = revision
        val app = context.applicationContext
        mutableState.value = SpeechState(owner, key, SpeechStatus.LOADING)
        job = scope.launch {
            try {
                val segments = speechSegments(text)
                val disk = cache(app)
                val epoch = disk.version()
                for (segment in segments) {
                    ensureActive()
                    mutableState.value = SpeechState(owner, key, SpeechStatus.LOADING)
                    val partKey = speechCacheKey(segment, rate)
                    val audio = withContext(Dispatchers.IO) { disk.get(partKey) } ?: run {
                        if (!consent(app)) throw SpeechException(SpeechFailure.CONSENT)
                        val bytes = transport.fetch(segment, rate)
                        ensureActive()
                        // A full disk must not turn an otherwise valid response into a failed playback.
                        withContext(Dispatchers.IO) { runCatching { disk.put(partKey, bytes, epoch) } }
                        bytes
                    }
                    ensureActive()
                    var temporary: File? = null
                    try {
                        withContext(Dispatchers.IO) {
                            temporary = File.createTempFile("haohao-speech-", ".mp3", app.cacheDir)
                            requireNotNull(temporary).writeBytes(audio)
                        }
                        SpeechForegroundService.acquire(app, current)
                        play(app, requireNotNull(temporary)) {
                            if (current == revision) mutableState.value = SpeechState(owner, key, SpeechStatus.PLAYING)
                        }
                    } catch (failure: SpeechException) {
                        if (failure.reason == SpeechFailure.INVALID_AUDIO) withContext(Dispatchers.IO) { disk.remove(partKey) }
                        throw failure
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                    }
                }
                if (current == revision) mutableState.value = SpeechState()
            } catch (_: TimeoutCancellationException) {
                if (current == revision) mutableState.value = SpeechState(owner, key, SpeechStatus.FAILED, SpeechFailure.TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current == revision) mutableState.value = SpeechState(owner, key, SpeechStatus.FAILED, (error as? SpeechException)?.reason ?: SpeechFailure.PLAYBACK)
            } finally {
                SpeechForegroundService.release(app, current)
            }
        }
    }

    private suspend fun play(context: Context, file: File, started: () -> Unit) {
        val player = MediaPlayer()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focusRevision = revision
        val focusListener = AudioManager.OnAudioFocusChangeListener { change -> if (change < 0) scope.launch { if (focusRevision == revision) stop() } }
        val focus = if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build()
        } else {
            null
        }
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (focusRevision == revision) stop()
            }
        }
        var registered = false
        try {
            val granted = if (Build.VERSION.SDK_INT >= 26 && focus != null) {
                audio.requestAudioFocus(focus)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw SpeechException(SpeechFailure.PLAYBACK)
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            player.setAudioAttributes(attributes)
            player.setDataSource(file.absolutePath)
            withTimeout(8000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    player.setOnPreparedListener { if (continuation.isActive) continuation.resume(Unit) }
                    player.setOnErrorListener { _, _, _ ->
                        if (continuation.isActive) continuation.resumeWithException(SpeechException(SpeechFailure.INVALID_AUDIO))
                        true
                    }
                    player.prepareAsync()
                }
            }
            withTimeout(180_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    player.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                    player.setOnErrorListener { _, _, _ ->
                        if (continuation.isActive) continuation.resumeWithException(SpeechException(SpeechFailure.INVALID_AUDIO))
                        true
                    }
                    player.start()
                    started()
                }
            }
        } finally {
            player.release()
            if (registered) context.unregisterReceiver(noisy)
            if (Build.VERSION.SDK_INT >= 26 && focus != null) {
                audio.abandonAudioFocusRequest(focus)
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(focusListener)
            }
        }
    }
}
