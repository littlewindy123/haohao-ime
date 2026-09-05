/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.instance
import java.io.File

/** Runs only in the isolated regression application; fixtures never touch the user's IME. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class WordLearningUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = InputFootprints.store

    @Test
    fun syntheticTypingBenchmark() {
        assertTrue(context.packageName.endsWith(".regression"))
        val samples = mutableListOf<Double>()
        val launches = mutableListOf<Double>()
        var keysWithoutChineseCandidates = 0
        val measureDraw = InstrumentationRegistry.getArguments().getString("measureDraw") == "true"
        val memoryBefore = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }.totalPss
        val prefs = AppPrefs.defaultInstance()
        val oldHand = prefs.keyboard.oneHandMode.getValue()
        prefs.keyboard.oneHandMode.setValue(AppPrefs.Keyboard.OneHandMode.OFF)
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "ime set ${context.packageName}/com.osfans.trime.ime.core.TrimeInputMethodService",
            ),
        ).bufferedReader().use { it.readText() }
        try {
            repeat(2) { launch ->
                val started = System.nanoTime()
                ActivityScenario.launch<MainActivity>(
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
                        .putExtra(MainActivity.EXTRA_SHOW_TEST_INPUT, true),
                ).use { scenario ->
                    awaitCondition {
                        scenario.onActivity { activity ->
                            descendants(activity.window.decorView).filterIsInstance<EditText>().firstOrNull { it.isShown }?.let { input ->
                                val noLearning = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                                if (input.imeOptions and noLearning == 0) {
                                    input.imeOptions = input.imeOptions or noLearning
                                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(input)
                                }
                                input.requestFocus()
                                if (input.hasWindowFocus()) androidx.core.view.ViewCompat.getWindowInsetsController(input)?.show(WindowInsetsCompat.Type.ime())
                            }
                        }
                        keyboardKeys().isNotEmpty()
                    }
                    launches += (System.nanoTime() - started) / 1_000_000.0
                    if (launch == 0) {
                        // Fixed synthetic corpus only; never read or export user editor text.
                        val corpus = listOf("nihao", "zhongguo", "wojintianxiangqugongyuansanbu", "nh", "shanghai", "xuexi")
                        repeat(2) {
                            corpus.forEach { pinyin ->
                                pinyin.forEach { char ->
                                    if (measureDraw) {
                                        val latency = candidateDrawLatency(char.toString())
                                        if (latency == null) keysWithoutChineseCandidates++ else samples += latency
                                        return@forEach
                                    }
                                    val before = com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount
                                    val start = System.nanoTime()
                                    tapKey(char.toString())
                                    val deadline = SystemClock.elapsedRealtime() + 3_000
                                    while (com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount <= before && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(1)
                                    assertTrue(com.osfans.trime.ime.core.TypingPerformanceMonitor.snapshot.value.processedKeyCount > before)
                                    val frame = java.util.concurrent.CountDownLatch(1)
                                    instrumentation.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() } }
                                    assertTrue(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
                                    samples += (System.nanoTime() - start) / 1_000_000.0
                                }
                                tapKey("space")
                            }
                            repeat(6) { tapKey("del") }
                            scenario.onActivity { activity ->
                                val input = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
                                input.setSelection(0)
                            }
                            tapKey("n")
                            tapKey("i")
                            tapKey("space")
                        }
                    }
                }
            }
        } finally {
            prefs.keyboard.oneHandMode.setValue(oldHand)
        }
        val sorted = samples.sorted()
        val gfx = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo ${context.packageName}"))
            .bufferedReader().use { it.readText() }
        val report = org.json.JSONObject().put("samples", org.json.JSONArray(samples))
            .put("keysWithoutChineseCandidates", keysWithoutChineseCandidates)
            .put("p50Ms", sorted[(sorted.size - 1) / 2]).put("p95Ms", sorted[kotlin.math.ceil(sorted.size * 0.95).toInt() - 1])
            .put("activityAndImeFirstShowMs", launches[0]).put("activityAndImeReshowMs", launches[1])
            .put("pssBeforeKb", memoryBefore).put("pssAfterKb", android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }.totalPss)
            .put("measurement", if (measureDraw) "Pointer DOWN injection to OnDraw with a newer rendered candidate presentation and visible Chinese; excludes idle wait, not physical display latency" else "Synthetic pointer injection through next frame after native key completion; includes test driver overhead")
            .put("gfxinfo", gfx)
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "benchmark")
        File(context.getExternalFilesDir(null), "word-ui/$prefix-performance.json").apply {
            parentFile!!.mkdirs()
            writeText(report.toString(2))
        }
    }

    @Test
    fun oneHandKeyboardStillCommitsChinese() {
        assertTrue(context.packageName.endsWith(".regression"))
        val preference = AppPrefs.defaultInstance().keyboard.oneHandMode
        val original = preference.getValue()
        try {
            val requested = InstrumentationRegistry.getArguments().getString("hand", "LEFT")
            preference.setValue(AppPrefs.Keyboard.OneHandMode.valueOf(requested))
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("ime set ${context.packageName}/com.osfans.trime.ime.core.TrimeInputMethodService"),
            ).bufferedReader().use { it.readText() }
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
                .putExtra(MainActivity.EXTRA_SHOW_TEST_INPUT, true)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                awaitCondition {
                    var ready = false
                    scenario.onActivity { ready = descendants(it.window.decorView).filterIsInstance<EditText>().any { input -> input.isShown && input.hasWindowFocus() } }
                    ready
                }
                var inputX = 0f
                var inputY = 0f
                scenario.onActivity { activity ->
                    val input = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
                    val location = IntArray(2)
                    input.getLocationOnScreen(location)
                    inputX = location[0] + input.width / 2f
                    inputY = location[1] + input.height / 2f
                }
                val inputDown = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(inputDown, inputDown, MotionEvent.ACTION_DOWN, inputX, inputY, 0))
                instrumentation.sendPointerSync(MotionEvent.obtain(inputDown, inputDown + 60, MotionEvent.ACTION_UP, inputX, inputY, 0))
                awaitCondition {
                    scenario.onActivity { activity ->
                        val input = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }
                        input.requestFocus()
                        if (input.hasWindowFocus()) androidx.core.view.ViewCompat.getWindowInsetsController(input)?.show(WindowInsetsCompat.Type.ime())
                    }
                    keyboardKeys().isNotEmpty()
                }
                listOf("n", "i", "h", "a", "o").forEach { tapKey(it) }
                SystemClock.sleep(900)
                capture("keyboard-$requested")
                instrumentation.runOnMainSync {
                    val keyboardRoot = WindowInspector.getGlobalWindowViews().first { descendants(it).any { view -> view is KeyView && view.isShown } }
                    val english = descendants(keyboardRoot).filterIsInstance<TextView>().first { it.text.toString() == "hello" }
                    val glyphs = android.graphics.Rect()
                    english.paint.getTextBounds(english.text.toString(), 0, english.text.length, glyphs)
                    assertTrue("English candidate glyphs must remain inside the visible line", english.baseline + glyphs.top >= 0 && english.baseline + glyphs.bottom <= english.height)
                }
                tapKey("space")
                awaitCondition {
                    var committed = false
                    scenario.onActivity { committed = descendants(it.window.decorView).filterIsInstance<EditText>().any { input -> input.text.toString().contains("你好") } }
                    committed
                }
            }
        } finally {
            preference.setValue(original)
        }
    }

    private fun keyboardKeys(): List<KeyView> {
        var keys = emptyList<KeyView>()
        instrumentation.runOnMainSync {
            keys = WindowInspector.getGlobalWindowViews().flatMap { descendants(it).filterIsInstance<KeyView>().filter { key -> key.isShown }.toList() }
        }
        return keys
    }

    private fun candidateDrawLatency(label: String): Double? {
        val di = InputDependencyManager.getInstance().di
        val delegate: CompactCandidateDelegate by di.instance()
        val version = CompactCandidateDelegate::class.java.getDeclaredField("renderedCandidatePresentationVersion").apply { isAccessible = true }
        val candidates = CompactCandidateDelegate::class.java.getDeclaredField("latestCandidates").apply { isAccessible = true }
        val start = java.util.concurrent.atomic.AtomicLong()
        val drawn = java.util.concurrent.atomic.AtomicLong()
        val frame = java.util.concurrent.CountDownLatch(1)
        var before = 0L
        var noChineseCandidate = false
        val listener = android.view.ViewTreeObserver.OnDrawListener {
            if (start.get() != 0L && version.getLong(delegate) > before && !delegate.view.hasPendingAdapterUpdates() &&
                descendants(delegate.view).filterIsInstance<AutoScaleTextView>().any { it.isShown && it.text.any { char -> char in '\u4e00'..'\u9fff' } }
            ) {
                if (drawn.compareAndSet(0, System.nanoTime())) frame.countDown()
            }
        }
        instrumentation.runOnMainSync {
            before = version.getLong(delegate)
            delegate.view.viewTreeObserver.addOnDrawListener(listener)
        }
        try {
            tapKey(label, waitForIdle = false) { start.set(System.nanoTime()) }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (frame.count > 0 && !noChineseCandidate && SystemClock.elapsedRealtime() < deadline) {
                frame.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                instrumentation.runOnMainSync {
                    val latest = candidates.get(delegate) as? com.osfans.trime.core.Candidates.Bulk
                    noChineseCandidate = version.getLong(delegate) > before && latest != null &&
                        latest.candidates.none { item -> item.text.any { char -> char in '\u4e00'..'\u9fff' } }
                }
            }
            val ready = frame.count == 0L || noChineseCandidate
            if (!ready) {
                var state = ""
                instrumentation.runOnMainSync {
                    state = "key=$label version=$before->${version.getLong(delegate)} attached=${delegate.view.isAttachedToWindow} shown=${delegate.view.isShown} pending=${delegate.view.hasPendingAdapterUpdates()} text=${descendants(delegate.view).filterIsInstance<AutoScaleTextView>().map { it.text }.toList()}"
                }
                capture("draw-failure")
                assertTrue("A newer Chinese candidate presentation must draw: $state", ready)
            }
        } finally {
            instrumentation.runOnMainSync { delegate.view.viewTreeObserver.removeOnDrawListener(listener) }
            instrumentation.waitForIdleSync()
        }
        return if (noChineseCandidate) null else (drawn.get() - start.get()) / 1_000_000.0
    }

    private fun tapKey(label: String, waitForIdle: Boolean = true, beforeDown: () -> Unit = {}) {
        val field = KeyView::class.java.getDeclaredField("key").apply { isAccessible = true }
        var x = 0f
        var y = 0f
        val keys = keyboardKeys()
        instrumentation.runOnMainSync {
            val view = keys.first { view ->
                val key = field.get(view) as Key
                key.code == KeyEvent.keyCodeFromString("KEYCODE_${label.uppercase()}")
            }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            x = location[0] + view.width / 2f
            y = location[1] + view.height / 2f
        }
        val down = SystemClock.uptimeMillis()
        beforeDown()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0))
        if (waitForIdle) instrumentation.waitForIdleSync()
    }

    @Test
    fun wordsAndReviewRemainUsableAndPersistAcrossRecreation() {
        assertTrue(context.packageName.endsWith(".regression"))
        runBlocking {
            store.clearAll()
            store.record("学习", 1)
            store.record("明天", 2)
            store.record("回家", 3)
            store.learning.saveMeaning("学习", "learn", "/lɜːrn/", "offline", learning = true, now = 1)
            store.learning.saveMeaning("明天", "tomorrow", "/təˈmɒrəʊ/", "offline", learning = true, now = 2)
            store.learning.saveMeaning("回家", "go home", null, "cloud", favorite = true, learning = true, now = 3)
        }
        try {
            val wordsIntent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
                .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.InputFootprints)
            ActivityScenario.launch<MainActivity>(wordsIntent).use { scenario ->
                awaitCondition {
                    var ready = false
                    scenario.onActivity { ready = it.findViewById<View>(R.id.learning_tab) != null }
                    ready
                }
                scenario.onActivity { it.findViewById<View>(R.id.learning_tab).performClick() }
                awaitCondition {
                    var ready = false
                    scenario.onActivity { ready = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.footprint_list)?.adapter?.itemCount == 3 }
                    ready
                }
                capture("words")
            }
            val intent = Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "review")
            ActivityScenario.launch<WordLearningActivity>(intent).use { scenario ->
                awaitButton(scenario, R.string.words_reveal)
                capture("review-question")
                click(scenario, R.string.words_reveal)
                awaitButton(scenario, R.string.words_remembered)
                assertTrue(runBlocking { store.learning.session()!!.answerVisible })
                scenario.recreate()
                awaitButton(scenario, R.string.words_remembered)
                capture("review-answer")
                click(scenario, R.string.words_remembered)
                awaitCondition { runBlocking { store.learning.session()!!.completed == 1 } }
                assertFalse(runBlocking { store.learning.session()!!.answerVisible })
                assertEquals(1, runBlocking { store.learning.find("学习", "learn")!!.reviewCount })
                awaitButton(scenario, R.string.words_undo)
                scenario.recreate()
                awaitButton(scenario, R.string.words_undo)
                click(scenario, R.string.words_undo)
                awaitButton(scenario, R.string.words_remembered)
                assertEquals(0, runBlocking { store.learning.find("学习", "learn")!!.reviewCount })
                capture("review-undo")
            }
            runBlocking {
                store.clearAll()
                store.learning.saveMeaning("回家", "go home", null, "cloud", learning = true)
                store.learning.saveSettings(true, 5, 10, true)
            }
            ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "plan")).use { scenario ->
                awaitButton(scenario, R.string.words_plan_start)
                capture("plan")
                click(scenario, R.string.words_plan_settings)
                awaitButton(scenario, R.string.words_plan_save)
                click(scenario, R.string.words_plan_save)
                awaitButton(scenario, R.string.words_plan_start)
                assertTrue(runBlocking { store.learning.session() == null })
                click(scenario, R.string.words_plan_start)
                awaitButton(scenario, R.string.words_reveal)
                assertTrue(runBlocking { store.learning.session()!!.daily && store.learning.session()!!.reverse })
                scenario.recreate()
                awaitButton(scenario, R.string.words_reveal)
                capture("plan-review")
            }
        } finally {
            runBlocking { store.clearAll() }
        }
    }

    private fun click(scenario: ActivityScenario<WordLearningActivity>, title: Int) {
        scenario.onActivity { activity ->
            val button = descendants(activity.window.decorView).filterIsInstance<TextView>()
                .first { it.text.toString() == activity.getString(title) }
            assertTrue(button.isEnabled)
            button.performClick()
        }
    }

    @Test
    fun confirmedCaseCanBeCorrectedFromTheMeaningPage() {
        assertTrue(context.packageName.endsWith(".regression"))
        runBlocking {
            store.clearAll()
            store.learning.saveMeaning("中国", "China", "/ˈtʃaɪnə/", "offline", favorite = true, learning = true)
        }
        try {
            val intent = Intent(context, WordLearningActivity::class.java)
                .putExtra("words.chinese", "中国").putExtra("words.english", "CHINA")
            ActivityScenario.launch<WordLearningActivity>(intent).use { scenario ->
                awaitButton(scenario, R.string.words_correct_case)
                capture("meaning-case")
                click(scenario, R.string.words_correct_case)
                instrumentation.runOnMainSync {
                    val views = WindowInspector.getGlobalWindowViews().flatMap { descendants(it).toList() }
                    views.filterIsInstance<EditText>().first { it.isShown }.setText("CHINA")
                    views.first { it.id == android.R.id.button1 && it.isShown }.performClick()
                }
                awaitCondition { runBlocking { store.learning.find("中国", "china")!!.displayEnglish == "CHINA" } }
                assertEquals(0, runBlocking { store.learning.find("中国", "China")!!.reviewCount })
                awaitButton(scenario, R.string.input_footprints_speak)
                SystemClock.sleep(1_000)
                click(scenario, R.string.input_footprints_speak)
                SystemClock.sleep(1_000)
                capture("speech-state")
            }
        } finally {
            runBlocking { store.clearAll() }
        }
    }

    private fun awaitButton(scenario: ActivityScenario<WordLearningActivity>, title: Int) = awaitCondition {
        var found = false
        scenario.onActivity { activity ->
            found = descendants(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == activity.getString(title) }
        }
        found
    }

    private fun awaitCondition(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        if (!check()) capture("failure")
        assertTrue("Page did not reach the expected state", check())
        instrumentation.waitForIdleSync()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "default")
        val file = File(context.getExternalFilesDir(null), "word-ui/$prefix-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
