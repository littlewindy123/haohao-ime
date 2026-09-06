// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.ui.main.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Test-only quiet tone: exercises the player and cache, not Tencent voice quality. */
@RunWith(AndroidJUnit4::class)
class SpeechPlaybackTest {
    @Test
    fun cachedManualPlaybackCancelsAndDoesNotRequireCloudConsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".regression"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val disk = SpeechDiskCache(File(context.noBackupFilesDir, "speech-audio"))
        val bytes = instrumentation.context.assets.open("speech-tone.mp3").use { it.readBytes() }
        disk.put(speechCacheKey("Test tone", SpeechRate.NORMAL), bytes, disk.version())
        val owner = Any()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                instrumentation.runOnMainSync {
                    SpeechPlayback.setConsent(context, false)
                    SpeechPlayback.toggle(context, owner, "Test tone")
                }
                await { SpeechPlayback.state.value.status == SpeechStatus.PLAYING }
                assertFalse(SpeechPlayback.consent(context))
                instrumentation.runOnMainSync { SpeechPlayback.toggle(context, owner, "Test tone") }
                await { SpeechPlayback.state.value.status == SpeechStatus.IDLE }
                SystemClock.sleep(400)
                assertTrue(SpeechPlayback.state.value.status == SpeechStatus.IDLE)
                instrumentation.runOnMainSync { SpeechPlayback.toggle(context, owner, "Test tone") }
                await { SpeechPlayback.state.value.status == SpeechStatus.PLAYING }
                instrumentation.runOnMainSync { SpeechPlayback.stop(owner) }
                await { SpeechPlayback.state.value.status == SpeechStatus.IDLE }
            }
        } finally {
            instrumentation.runOnMainSync { SpeechPlayback.stop() }
            disk.clear()
        }
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Speech state was ${SpeechPlayback.state.value.status}: ${SpeechPlayback.state.value.failure}", check())
    }
}
