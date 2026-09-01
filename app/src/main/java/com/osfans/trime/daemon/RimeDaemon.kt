// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeLifecycle
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimeRuntimeDiagnostics
import com.osfans.trime.core.RimeRuntimeState
import com.osfans.trime.core.RimeUnavailableException
import com.osfans.trime.core.lifecycleScope
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.appContext
import com.osfans.trime.util.createNotificationChannel
import com.osfans.trime.util.readText
import com.osfans.trime.util.subprocess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import splitties.systemservices.notificationManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.sync.withLock as withSuspendLock

/**
 * Manage the singleton instance of [Rime]
 *
 * To use rime, client should call [createSession] to obtain a [RimeSession],
 * and call [destroySession] on client destroyed. Client should not leak the instance of [RimeApi],
 * and must use [RimeSession] to access rime functionalities.
 *
 * The instance of [Rime] always exists,but whether the dispatcher runs and callback works depend on clients, i.e.
 * if no clients are connected, [Rime.finalize] will be called.
 *
 * Functions are thread-safe in this class.
 *
 * Adapted from [fcitx5-android/FcitxDaemon.kt](https://github.com/fcitx5-android/fcitx5-android/blob/364afb44dcf0d9e3db3d43a21a32601b2190cbdf/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt)
 */
object RimeDaemon {
    private const val SETUP_PREWARM_SESSION = "haohao-setup-prewarm"
    private val realRime by lazy { Rime() }

    private val rimeImpl by lazy { object : RimeApi by realRime {} }

    private val sessions = mutableMapOf<String, RimeSession>()

    private val lock = ReentrantLock()
    private val lifecycleOperationLock = Mutex()
    private val repairInProgress = AtomicBoolean(false)

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in sessions) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        override fun <T> run(block: suspend RimeApi.() -> T): T = ensureEstablished {
            runBlocking { block(rimeImpl) }
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            val state = realRime.runtimeState.value.takeUnless { it == RimeRuntimeState.PREPARING }
                ?: realRime.runtimeState.first { it != RimeRuntimeState.PREPARING }
            if (state == RimeRuntimeState.FAILED) {
                throw RimeUnavailableException(realRime.lastFailure ?: "Rime is unavailable")
            }
            block(rimeImpl)
        }

        override fun runIfReady(block: suspend RimeApi.() -> Unit) {
            ensureEstablished {
                if (realRime.isReady) {
                    realRime.lifecycleScope.launch {
                        block(rimeImpl)
                    }
                }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realRime.lifecycle.lifecycleScope
    }

    fun createSession(name: String): RimeSession = lock.withLock {
        if (name in sessions) {
            return@withLock sessions.getValue(name)
        }
        if (realRime.lifecycle.currentState == RimeLifecycle.State.STOPPED) {
            realRime.startup()
        }
        val session = establish(name)
        sessions[name] = session
        return@withLock session
    }

    fun acquireSetupPrewarm() {
        createSession(SETUP_PREWARM_SESSION)
    }

    fun releaseSetupPrewarm() {
        destroySession(SETUP_PREWARM_SESSION)
    }

    fun destroySession(name: String) {
        val shouldStop = lock.withLock {
            if (name !in sessions) return
            sessions -= name
            sessions.isEmpty()
        }
        if (!shouldStop) return
        TrimeApplication.getInstance().coroutineScope.launch {
            lifecycleOperationLock.withSuspendLock {
                withContext(Dispatchers.IO) { realRime.finalize() }
                lock.withLock {
                    if (sessions.isNotEmpty() && realRime.lifecycle.currentState == RimeLifecycle.State.STOPPED) {
                        realRime.startup()
                    }
                }
            }
        }
    }

    /**
     * Reuse a session for remote service
     */
    fun getFirstSessionOrNull() = sessions.firstNotNullOfOrNull { it.value }

    private const val CHANNEL_ID = "rime-daemon"
    private const val MESSAGE_ID = 2331
    private var restartId = 0

    val runtimeState get() = realRime.runtimeState
    internal val runtimeSnapshot get() = realRime.runtimeSnapshot
    val lastFailure get() = realRime.lastFailure

    fun markThemeInitializing() = realRime.markThemeInitializing()

    fun markThemeReady() = realRime.markThemeReady()

    fun markThemeFailed(error: Throwable) = realRime.markThemeFailed(error)

    init {
        createNotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.rime_daemon),
        )
        TrimeApplication.getInstance().coroutineScope.launch {
            realRime.messageFlow.collect {
                handleRimeMessage(it)
            }
        }
    }

    private inline fun sendNotification(
        id: Int,
        buildAction: NotificationCompat.Builder.() -> Unit,
    ) {
        val builder =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.rime_daemon))
        builder.buildAction()
        builder.build().let { notificationManager.notify(id, it) }
    }

    /**
     * Restart Rime instance to deploy while keep the session
     */
    fun restartRime(fullCheck: Boolean = false) {
        val id = restartId++
        if (!fullCheck) {
            sendNotification(id) {
                setSmallIcon(R.drawable.ic_baseline_sync_24)
                setContentTitle(appContext.getString(R.string.rime_daemon))
                setContentText(appContext.getString(R.string.restarting_rime))
                setOngoing(true)
                setProgress(100, 0, true)
                setPriority(NotificationCompat.PRIORITY_HIGH)
            }
        }
        TrimeApplication.getInstance().coroutineScope.launch {
            lifecycleOperationLock.withSuspendLock {
                try {
                    withContext(Dispatchers.IO) { realRime.finalize() }
                    val started = lock.withLock {
                        if (sessions.isNotEmpty()) {
                            realRime.startup(fullCheck)
                            true
                        } else {
                            false
                        }
                    }
                    if (started) realRime.runtimeState.first { it != RimeRuntimeState.PREPARING }
                } finally {
                    notificationManager.cancel(id)
                }
            }
        }
    }

    fun repairRime() {
        if (!repairInProgress.compareAndSet(false, true)) return
        TrimeApplication.getInstance().coroutineScope.launch {
            try {
                lifecycleOperationLock.withSuspendLock {
                    withContext(Dispatchers.IO) {
                        realRime.finalize()
                        runCatching { DataManager.repairManagedData() }
                    }.onSuccess {
                        lock.withLock {
                            if (sessions.isNotEmpty()) realRime.startup(fullCheck = false)
                        }
                    }.onFailure(realRime::markRepairFailed)
                }
            } finally {
                repairInProgress.set(false)
            }
        }
    }

    fun diagnosticText(): String = RimeRuntimeDiagnostics.read().ifBlank {
        buildString {
            appendLine("HaoHao IME ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_VERSION_NAME})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Engine: ${runtimeState.value}")
            appendLine("Schema: ${realRime.schemaCached.schemaId}")
            realRime.lastDataSyncStats?.let { stats ->
                appendLine(
                    "Data preparation: ${realRime.lastDataPreparationMillis} ms " +
                        "(copied=${stats.copiedFiles}, bytes=${stats.copiedBytes}, reused=${stats.reusedPrebuilt})",
                )
            } ?: appendLine("Data preparation: not run")
            appendLine("Native startup: ${realRime.lastNativeStartupMillis.takeIf { it >= 0 }?.let { "$it ms" } ?: "not run"}")
            append("Last failure: ${lastFailure ?: "none"}")
        }
    }

    private suspend fun handleRimeMessage(it: RimeMessage<*>) {
        if (it is RimeMessage.DeployMessage) {
            val buildNotification: NotificationCompat.Builder.() -> Unit
            when (it.data) {
                RimeMessage.DeployMessage.State.Start -> {
                    buildNotification = {
                        setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
                        setContentText(appContext.getString(R.string.deploy_progress))
                        setProgress(0, 0, true)
                        setOngoing(true)
                        setAutoCancel(false)
                        setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    }
                    withContext(Dispatchers.IO) { subprocess("logcat", "--clear") }
                }
                RimeMessage.DeployMessage.State.Success -> {
                    buildNotification = {
                        setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
                        setColor(Color.GREEN)
                        setContentText(appContext.getString(R.string.deploy_finish))
                        setOngoing(false)
                        setTimeoutAfter(3000L)
                        setAutoCancel(true)
                        setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    }
                }
                RimeMessage.DeployMessage.State.Failure -> {
                    val intent =
                        Intent(appContext, LogActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            val log =
                                subprocess("logcat", "-v", "brief", "-s", "rime.trime:W", "-d")
                                    .readText()
                            putExtra(LogActivity.FROM_DEPLOY, true)
                            putExtra(LogActivity.DEPLOY_FAILURE_TRACE, log)
                        }
                    buildNotification = {
                        setSmallIcon(R.drawable.ic_baseline_warning_24)
                        setColor(Color.YELLOW)
                        setContentText(appContext.getString(R.string.view_deploy_failure_log))
                        setContentIntent(
                            PendingIntent.getActivity(
                                appContext,
                                0,
                                intent,
                                PendingIntent.FLAG_ONE_SHOT or
                                    PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                        setOngoing(false)
                        setAutoCancel(true)
                        setPriority(NotificationCompat.PRIORITY_HIGH)
                    }
                }
            }
            sendNotification(MESSAGE_ID, buildNotification)
        }
    }
}
