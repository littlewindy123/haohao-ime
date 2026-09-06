// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Android 15 requires foreground playback even when the IME is visible over another app. */
class SpeechForegroundService : Service() {
    private var lease = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra(EXTRA_LEASE, -1) ?: -1
        if (intent?.action == ACTION_STOP) {
            if (requested == lease) SpeechPlayback.stop()
            return START_NOT_STICKY
        }
        if (requested != pending || !SpeechPlayback.isCurrent(requested)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        lease = requested
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.speech_consent_title), NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this,
            71,
            Intent(this, SpeechForegroundService::class.java)
                .setAction(ACTION_STOP).putExtra(EXTRA_LEASE, lease),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_haohao_volume_24)
            .setContentTitle(getString(R.string.speech_consent_title))
            .setContentText(getString(R.string.speech_playing))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(R.drawable.ic_haohao_stop_24, getString(R.string.speech_stop), stop)
            .build()
        ServiceCompat.startForeground(this, 18761, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        ready.value = lease
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (ready.value == lease) ready.value = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "haohao-manual-speech"
        private const val EXTRA_LEASE = "speech.lease"
        private const val ACTION_STOP = "speech.stop"
        private val ready = MutableStateFlow<Long?>(null)
        private var pending: Long? = null

        internal suspend fun acquire(context: Context, revision: Long) {
            if (Build.VERSION.SDK_INT < 35) return
            pending = revision
            ContextCompat.startForegroundService(context, Intent(context, SpeechForegroundService::class.java).putExtra(EXTRA_LEASE, revision))
            withTimeout(3000) { ready.first { it == revision } }
        }

        internal fun release(context: Context, revision: Long) {
            if (pending != revision) return
            pending = null
            ready.value = null
            context.stopService(Intent(context, SpeechForegroundService::class.java))
        }
    }
}
