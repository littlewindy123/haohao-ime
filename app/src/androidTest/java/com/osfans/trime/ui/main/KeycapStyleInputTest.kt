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
    @Test(timeout = 180_000)
    fun scaledKeycapsFitAndTypeWithBothStylesAndHands() {
        check(context.packageName.endsWith(".regression"))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("ime set ${context.packageName}/com.osfans.trime.ime.core.TrimeInputMethodService")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity")).use { it.readBytes() }
        val oldStyle = prefs.keycapStyle.getValue()
        val oldHand = prefs.oneHandMode.getValue()
        instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)).use { scenario ->
            showEditor(scenario)
            awaitKeyboard()
            val oldPalette = ColorManager.activeColorScheme
            val session = requireNotNull(RimeDaemon.getFirstSessionOrNull())
            try {
                runBlocking {
                    session.runOnReady {
                        selectSchema("luna_pinyin_simp")
                        clearComposition()
                        setRuntimeOption("ascii_mode", true)
                    }
                }
                for (style in AppPrefs.Keyboard.KeycapStyle.entries) {
                    for (palette in listOf("default", "haohao_graphite")) {
                        for (hand in AppPrefs.Keyboard.OneHandMode.entries) {
                            instrumentation.runOnMainSync {
                                prefs.keycapStyle.setValue(style)
                                prefs.oneHandMode.setValue(hand)
                                ColorManager.setColorScheme(ThemeManager.activeTheme.colorSchemes.first { it.id == palette })
                            }
                            SystemClock.sleep(350)
                            showEditor(scenario)
                            runBlocking {
                                session.runOnReady {
                                    clearComposition()
                                    setRuntimeOption("ascii_mode", true)
                                }
                            }
                            SystemClock.sleep(150)
                            val current = awaitKeyboard()
                            instrumentation.runOnMainSync {
                                current.forEach { (view, key) ->
                                    val originalOn = key.isOn
                                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                                    try {
                                        for (pressed in listOf(false, true)) {
                                            view.setPressedState(pressed)
                                            view.draw(Canvas(bitmap))
                                            val field = KeyView::class.java.getDeclaredField(if (pressed) "pressedSurface" else "restingSurface").apply { isAccessible = true }
                                            val surface = field.get(view) as? com.osfans.trime.ime.keyboard.KeySurfaceGeometry
                                            val cap = surface?.cap
                                            val allowed = if (cap == null) android.graphics.RectF(view.paddingLeft.toFloat(), view.paddingTop.toFloat(), (view.width - view.paddingRight).toFloat(), (view.height - view.paddingBottom).toFloat()) else android.graphics.RectF(cap.left.toFloat(), cap.top.toFloat(), cap.right.toFloat(), cap.bottom.toFloat())
                                            allowed.inset(-1f, -1f)
                                            val bounds = view.adaptiveGlyphBounds.filter { !it.isEmpty }
                                            assertTrue("No measured glyph: ${key.getLabel()}", bounds.isNotEmpty())
                                            bounds.forEach { assertTrue("Outside keycap ${key.getLabel()} $style $palette $hand pressed=$pressed: $it / $allowed", allowed.contains(it)) }
                                            bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b -> assertTrue("Overlapping ${key.getLabel()} $style $hand: $a / $b", !android.graphics.RectF.intersects(a, b)) } }
                                        }
                                    } finally {
                                        view.setPressedState(false)
                                        if (key.isOn != originalOn) {
                                            view.setPressedState(true)
                                            view.setPressedState(false)
                                        }
                                        bitmap.recycle()
                                    }
                                }
                            }
                            scenario.onActivity { a -> descendants(a.window.decorView).filterIsInstance<EditText>().first { it.isShown }.setText("") }
                            SystemClock.sleep(300)
                            if (!session.run { statusCached }.isAsciiMode) {
                                val toggle = awaitKeyboard().first { it.second.click?.toggle == "ascii_mode" }.first
                                val down = SystemClock.uptimeMillis()
                                touch(toggle, MotionEvent.ACTION_DOWN, down)
                                touch(toggle, MotionEvent.ACTION_UP, down)
                                val end = SystemClock.uptimeMillis() + 5000
                                while (!session.run { statusCached }.isAsciiMode && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
                                assertTrue("Language key must activate English", session.run { statusCached }.isAsciiMode)
                            }
                            val inputKeys = awaitKeyboard()
                            for (letter in "hello") {
                                val key = inputKeys.first { it.second.getLabel().equals(letter.toString(), true) }.first
                                val down = SystemClock.uptimeMillis()
                                touch(key, MotionEvent.ACTION_DOWN, down)
                                SystemClock.sleep(30)
                                instrumentation.runOnMainSync { assertTrue("Real finger missed $letter attached=${key.isAttachedToWindow} shown=${key.isShown} location=${IntArray(2).also { key.getLocationOnScreen(it) }.toList()}", key.isPressed) }
                                touch(key, MotionEvent.ACTION_UP, down)
                            }
                            instrumentation.waitForIdleSync()
                            SystemClock.sleep(300)
                            val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "phone")
                            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                                val file = File(context.getExternalFilesDir(null), "learning-next/$prefix-keycap-$style-$palette-$hand.png").apply { parentFile!!.mkdirs() }
                                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                bitmap.recycle()
                            }
                            scenario.onActivity { a -> assertEquals("$style $palette $hand ascii=${session.run { statusCached }.isAsciiMode}", "hello", descendants(a.window.decorView).filterIsInstance<EditText>().first { it.isShown }.text.toString()) }
                        }
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    prefs.keycapStyle.setValue(oldStyle)
                    prefs.oneHandMode.setValue(oldHand)
                    ColorManager.setColorScheme(oldPalette)
                }
                instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            }
        }
    }

    @Test(timeout = 180_000)
    fun rapidBurstPublishesBeforeEntireInputQueueIsDrained() {
        check(context.packageName.endsWith(".regression"))
        check(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).startsWith(context.packageName + "/"))
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            showEditor(scenario)
            awaitKeyboard()
            val session = requireNotNull(RimeDaemon.getFirstSessionOrNull())
            lateinit var service: com.osfans.trime.ime.core.TrimeInputMethodService
            instrumentation.runOnMainSync {
                service = WindowInspector.getGlobalWindowViews().flatMap {
                    descendants(it).filterIsInstance<com.osfans.trime.ime.keyboard.KeyboardView>().toList()
                }.first { it.isShown }.service
            }
            try {
                runBlocking {
                    session.runOnReady {
                        selectSchema("luna_pinyin_simp")
                        setRuntimeOption("ascii_mode", false)
                        setRuntimeOption("_haohao_no_personalized_learning", true)
                        clearComposition()
                    }
                }
                val input = "womenmingtianwanshangyiqiquchifan".repeat(2)
                val before = com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount
                instrumentation.runOnMainSync {
                    input.forEach { letter -> service.postRimeKey { processKeyDeferred(letter.code) } }
                }
                val versions = mutableSetOf<Long>()
                val deadline = SystemClock.uptimeMillis() + 60_000
                while (SystemClock.uptimeMillis() < deadline) {
                    val processed = com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount - before
                    if (processed >= input.length) break
                    if (processed > 0) versions += session.run { presentationFlow.value.version }
                    SystemClock.sleep(16)
                }
                assertTrue("Continuous input starved all intermediate presentations", versions.size >= 2)
                assertEquals(input.length, com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount - before)
                runBlocking { session.runOnReady { assertEquals(input, getRawInput()) } }
                instrumentation.sendStatus(
                    2,
                    android.os.Bundle().apply {
                        putString("stream", "\nBURST keys=${input.length} intermediateVersions=${versions.size}\n")
                    },
                )
            } finally {
                runBlocking {
                    session.runOnReady {
                        clearComposition()
                        setRuntimeOption("_haohao_no_personalized_learning", false)
                    }
                }
            }
        }
    }

    @Test(timeout = 180_000)
    fun heldDeleteDoesNotBuildBacklogOrContinueAfterRelease() {
        check(context.packageName.endsWith(".regression"))
        check(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).startsWith(context.packageName + "/"))
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            showEditor(scenario)
            awaitKeyboard()
            val session = requireNotNull(RimeDaemon.getFirstSessionOrNull())
            val oldRepeat = prefs.repeatInterval.getValue()
            try {
                // Deliberately fast timer: the producer must slow down when the engine is busy.
                instrumentation.runOnMainSync { prefs.repeatInterval.setValue(20) }
                runBlocking {
                    session.runOnReady {
                        selectSchema("luna_pinyin_simp")
                        setRuntimeOption("ascii_mode", false)
                        setRuntimeOption("_haohao_no_personalized_learning", true)
                        clearComposition()
                        "womenmingtianwanshangyiqiquchifan".repeat(2).forEach { processKeyDeferred(it.code) }
                        refreshPresentation()
                    }
                }
                SystemClock.sleep(400)
                val delete = awaitKeyboard().first { it.second.getCode(com.osfans.trime.ime.keyboard.KeyBehavior.CLICK) == android.view.KeyEvent.KEYCODE_DEL }.first
                val before = com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value
                var processedAtRelease = -1
                var originalCancel: (() -> Unit)? = null
                instrumentation.runOnMainSync {
                    originalCancel = delete.onCancel
                    delete.onCancel = {
                        // Capture when ACTION_UP is handled, not before injection/main-thread handoff.
                        if (processedAtRelease < 0) {
                            processedAtRelease = com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount
                        }
                        originalCancel?.invoke()
                    }
                }
                val down = SystemClock.uptimeMillis()
                touch(delete, MotionEvent.ACTION_DOWN, down)
                var maximumDepth = 0
                try {
                    repeat(40) {
                        SystemClock.sleep(100)
                        val snapshot = com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value
                        maximumDepth = maxOf(maximumDepth, snapshot.queueDepth)
                    }
                } finally {
                    touch(delete, MotionEvent.ACTION_UP, down)
                    instrumentation.runOnMainSync { delete.onCancel = originalCancel }
                }
                SystemClock.sleep(1200)
                val after = com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value
                instrumentation.sendStatus(
                    2,
                    android.os.Bundle().apply {
                        putString("stream", "\nHELD_DELETE sampledQueue=$maximumDepth trailing=${after.processedKeyCount - processedAtRelease}\n")
                    },
                )
                assertTrue("Release callback must have run", processedAtRelease >= 0)
                assertTrue("Delete events built up: depth=$maximumDepth", maximumDepth <= 1)
                assertTrue("Long hold must delete several characters", processedAtRelease - before.processedKeyCount >= 3)
                assertTrue("Historical peak grew beyond one outstanding repeat", after.maximumQueueDepth <= maxOf(before.maximumQueueDepth, 1))
                assertTrue("Release must not enqueue another deletion", after.processedKeyCount - processedAtRelease <= 1)
                assertEquals(0, after.queueDepth)
                val settled = after.processedKeyCount
                SystemClock.sleep(500)
                assertEquals(settled, com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount)
            } finally {
                instrumentation.runOnMainSync { prefs.repeatInterval.setValue(oldRepeat) }
                runBlocking {
                    session.runOnReady {
                        clearComposition()
                        setRuntimeOption("_haohao_no_personalized_learning", false)
                    }
                }
            }
        }
    }

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
        // ActivityScenario's RESUMED callback can precede window focus. Requesting
        // IME in that gap is ignored on some Xiaomi builds.
        val focusDeadline = SystemClock.uptimeMillis() + 10_000
        var focused = false
        while (!focused && SystemClock.uptimeMillis() < focusDeadline) {
            scenario.onActivity { focused = it.hasWindowFocus() }
            if (!focused) SystemClock.sleep(50)
        }
        assertTrue("Test activity must own window focus before requesting IME", focused)
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.showTestInputPanel()
            val editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
            editor.requestFocus()
            WindowCompat.getInsetsController(activity.window, editor).show(WindowInsetsCompat.Type.ime())
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        lateinit var editor: EditText
        scenario.onActivity { activity ->
            editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
        }
        val down = SystemClock.uptimeMillis()
        touch(editor, MotionEvent.ACTION_DOWN, down)
        touch(editor, MotionEvent.ACTION_UP, down)
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), "input-start.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
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
