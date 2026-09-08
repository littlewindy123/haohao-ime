// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class KeycapStyleInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs get() = AppPrefs.defaultInstance().keyboard
    private val keyField = KeyView::class.java.getDeclaredField("key").apply { isAccessible = true }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun keys(): List<Pair<KeyView, Key>> {
        var result = emptyList<Pair<KeyView, Key>>()
        instrumentation.runOnMainSync {
            result = WindowInspector.getGlobalWindowViews().flatMap { descendants(it).filterIsInstance<KeyView>().toList() }
                .filter { it.isShown }.map { it to keyField.get(it) as Key }
        }
        return result
    }

    private fun awaitKeyboard(previous: KeyView? = null): List<Pair<KeyView, Key>> {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            val current = keys()
            if (current.size > 10 && current.first().first !== previous) return current
            SystemClock.sleep(50)
        }
        var windows = ""
        instrumentation.runOnMainSync {
            windows = WindowInspector.getGlobalWindowViews().joinToString { root ->
                "${root.javaClass.simpleName}: ${root.width}x${root.height}, shown=${root.isShown}, keys=${descendants(root).filterIsInstance<KeyView>().count()}"
            }
        }
        error("Keyboard did not become ready: $windows")
    }

    private fun touch(view: View, action: Int, down: Long) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0] + view.width / 2f, location[1] + view.height / 2f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun showEditor(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.showTestInputPanel()
            val editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
            editor.requestFocus()
            WindowCompat.getInsetsController(activity.window, editor).show(WindowInsetsCompat.Type.ime())
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    @Test(timeout = 180_000)
    fun bothStylesTypeThroughRealKeysAcrossPalettesAndWindowModes() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        check(context.packageName.endsWith(".regression"))
        assumeTrue(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).startsWith(context.packageName + "/"))
        val oldStyle = prefs.keycapStyle.getValue()
        val oldHand = prefs.oneHandMode.getValue()
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SHOW_TEST_INPUT, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            showEditor(scenario)
            awaitKeyboard()
            val oldPalette = ColorManager.activeColorScheme
            try {
                val session = requireNotNull(RimeDaemon.getFirstSessionOrNull())
                for (landscape in listOf(false, true)) {
                    scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    SystemClock.sleep(400)
                    showEditor(scenario)
                    for (style in AppPrefs.Keyboard.KeycapStyle.entries) {
                        instrumentation.runOnMainSync { prefs.keycapStyle.setValue(style) }
                        for (palette in listOf("default", "haohao_mist", "haohao_apricot", "haohao_graphite")) {
                            println("Keycap input: $style $palette landscape=$landscape")
                            instrumentation.runOnMainSync { ColorManager.setColorScheme(ThemeManager.activeTheme.colorSchemes.first { it.id == palette }) }
                            instrumentation.waitForIdleSync()
                            SystemClock.sleep(600)
                            lateinit var editor: EditText
                            scenario.onActivity { activity ->
                                editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
                                editor.setText("")
                                editor.requestFocus()
                            }
                            runBlocking {
                                withTimeout(10_000) {
                                    session.runOnReady {
                                        clearComposition()
                                    }
                                }
                            }
                            instrumentation.waitForIdleSync()
                            SystemClock.sleep(300)
                            if (!session.run { statusCached }.isAsciiMode) {
                                val mode = awaitKeyboard().first { it.second.click?.toggle == "ascii_mode" }.first
                                val down = SystemClock.uptimeMillis()
                                touch(mode, MotionEvent.ACTION_DOWN, down)
                                touch(mode, MotionEvent.ACTION_UP, down)
                                val end = SystemClock.uptimeMillis() + 5_000
                                while (!session.run { statusCached }.isAsciiMode && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
                                assertTrue("English mode did not activate", session.run { statusCached }.isAsciiMode)
                                SystemClock.sleep(200)
                            }
                            val current = awaitKeyboard()
                            val word = "hellohellohello"
                            for (letter in word) {
                                val view = current.first { it.second.getLabel().equals(letter.toString(), ignoreCase = true) }.first
                                val bounds = android.graphics.Rect(view.bounds)
                                val down = SystemClock.uptimeMillis()
                                touch(view, MotionEvent.ACTION_DOWN, down)
                                try {
                                    SystemClock.sleep(40)
                                    instrumentation.runOnMainSync {
                                        val location = IntArray(2).also { view.getLocationOnScreen(it) }
                                        assertTrue("Touch missed ${view.id} at ${location.toList()}, size=${view.width}x${view.height}, attached=${view.isAttachedToWindow}", view.isPressed)
                                    }
                                } finally {
                                    touch(view, MotionEvent.ACTION_UP, down)
                                }
                                assertEquals(bounds, view.bounds)
                            }
                            instrumentation.waitForIdleSync()
                            scenario.onActivity { assertEquals("$style $palette landscape=$landscape", word, editor.text.toString()) }
                            if (palette == "default" && !landscape) {
                                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                                    File(context.cacheDir, "keycaps-${style.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                SystemClock.sleep(400)
                showEditor(scenario)
                for (hand in listOf(AppPrefs.Keyboard.OneHandMode.LEFT, AppPrefs.Keyboard.OneHandMode.RIGHT)) {
                    val previous = awaitKeyboard().first().first
                    instrumentation.runOnMainSync { prefs.oneHandMode.setValue(hand) }
                    val current = awaitKeyboard(previous)
                    assertTrue(current.all { (view, _) -> view.width > 0 && view.height > 0 })
                }
                runBlocking {
                    withTimeout(10_000) {
                        session.runOnReady {
                            clearComposition()
                            setRuntimeOption("ascii_mode", false)
                            selectSchema("haohao_pinyin_9")
                        }
                    }
                }
                SystemClock.sleep(400)
                val nine = awaitKeyboard()
                assertTrue("Nine-key layout not active", nine.size < 30)
                for (style in AppPrefs.Keyboard.KeycapStyle.entries) {
                    val previous = awaitKeyboard().first().first
                    instrumentation.runOnMainSync { prefs.keycapStyle.setValue(style) }
                    val current = awaitKeyboard(previous)
                    instrumentation.runOnMainSync {
                        current.forEach { (view, _) ->
                            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bitmap))
                            bitmap.recycle()
                        }
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    prefs.keycapStyle.setValue(oldStyle)
                    prefs.oneHandMode.setValue(oldHand)
                    ColorManager.setColorScheme(oldPalette)
                }
                RimeDaemon.getFirstSessionOrNull()?.runIfReady {
                    clearComposition()
                    selectSchema("luna_pinyin_simp")
                    setRuntimeOption("ascii_mode", false)
                }
            }
        }
    }
}
