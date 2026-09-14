package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.StudyLexicon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.footprints.SentenceBookActivity
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic data only, guarded against launch in the everyday package. */
class LearningV4UiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = InputFootprints.store
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(80)
        assertTrue("UI condition timed out", check())
        instrumentation.waitForIdleSync()
    }
    private fun button(root: View, id: Int) = views(root).filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == context.getString(id) }
    private fun capture(view: View, name: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "learning-v4")
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap)) // Activity content only: no status bar, chats or floating overlays.
        val file = File(context.getExternalFilesDir(null), "learning-v4/$prefix-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        views(view).filter { it.tag == "learning.reading.surface" || it.tag == "learning.reading.example" }.forEach {
            assertNull("Reading content must not become a nested card again", it.background)
        }
        views(view).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.forEach { text ->
            val layout = text.layout ?: return@forEach
            assertTrue("Clipped $name: ${text.text}", layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
        }
    }

    @Test fun reviewGeometryAndSentenceSearch() {
        fun stage(name: String) {
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nV4: $name\n") })
        }
        stage("start")
        assertTrue(context.packageName.endsWith(".regression"))
        val mode = AppPrefs.defaultInstance().advanced.uiMode
        val original = mode.getValue()
        val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
        mode.setValue(if (dark) AppPrefs.Advanced.UiMode.DARK else AppPrefs.Advanced.UiMode.LIGHT)
        stage("mode configured")
        runBlocking {
            store.clearAll()
            store.sentences.clear(true)
            store.sentences.setAutomatic(false)
            store.learning.saveMeaning("我保存的学习含义", "learn", "/lɜːn/", "offline", learning = true, now = 1)
        }
        stage("fixtures saved")
        val landscape = InstrumentationRegistry.getArguments().getString("landscape") == "true"
        assertTrue(instrumentation.uiAutomation.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0))
        // Some OEMs block background ActivityScenario/bootstrap launches. Bring the app's
        // existing exported main screen forward via the test shell before normal navigation.
        foreground()
        try {
            val intent = Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "review")
            ActivityScenario.launch<WordLearningActivity>(intent).use { scenario ->
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = button(it.window.decorView, R.string.words_reveal) != null }
                    ready
                }
                scenario.onActivity { assertEquals(landscape, it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) }
                var originalScroll: ScrollView? = null
                var revealY = 0
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    originalScroll = views(decor).filterIsInstance<ScrollView>().first()
                    val reveal = button(decor, R.string.words_reveal)!!
                    val location = IntArray(2)
                    reveal.getLocationOnScreen(location)
                    revealY = location[1]
                    assertTrue(reveal.height >= 55 * activity.resources.displayMetrics.density)
                    capture(activity.findViewById(android.R.id.content), "front")
                    reveal.performClick()
                }
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = button(it.window.decorView, R.string.words_remembered) != null }
                    ready
                }
                SystemClock.sleep(600)
                waitFor {
                    var highlighted = false
                    scenario.onActivity { a ->
                        highlighted = views(a.window.decorView).filterIsInstance<TextView>().any { v ->
                            val text = v.text as? android.text.Spanned
                            text != null && text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java).any { span ->
                                text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString().equals("learn", true)
                            }
                        }
                    }
                    highlighted
                }
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    assertSame(originalScroll, views(decor).filterIsInstance<ScrollView>().first())
                    assertTrue(views(decor).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "我保存的学习含义" })
                    assertEquals(activity.getColor(R.color.learning_meaning_ink), views(decor).filterIsInstance<TextView>().first { it.text.toString() == "我保存的学习含义" }.currentTextColor)
                    val rating = button(decor, R.string.words_remembered)!!
                    val location = IntArray(2)
                    rating.getLocationOnScreen(location)
                    if (activity.resources.configuration.screenHeightDp >= 480 && activity.resources.configuration.fontScale < 1.8f) assertEquals(revealY, location[1])
                    assertTrue(rating.height >= 55 * activity.resources.displayMetrics.density)
                    originalScroll!!.scrollTo(0, originalScroll!!.getChildAt(0).height)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val rating = button(it.window.decorView, R.string.words_remembered)!!
                    val visible = android.graphics.Rect()
                    assertTrue(rating.getGlobalVisibleRect(visible))
                    assertEquals(rating.height, visible.height())
                    capture(it.findViewById(android.R.id.content), "answer")
                }
                scenario.recreate()
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = button(it.window.decorView, R.string.words_remembered) != null }
                    ready
                }
                assertEquals(0, runBlocking { store.learning.find("我保存的学习含义", "learn")!!.reviewCount })
                scenario.onActivity {
                    originalScroll = views(it.window.decorView).filterIsInstance<ScrollView>().first()
                    originalScroll!!.scrollTo(0, 0)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { capture(it.findViewById(android.R.id.content), "answer-top") }
            }
            foreground()
            val detail = Intent(context, WordLearningActivity::class.java)
                .putExtra("words.mode", "meaning")
                .putExtra("words.chinese", "我保存的学习含义")
                .putExtra("words.english", "learn")
            ActivityScenario.launch<WordLearningActivity>(detail).use { scenario ->
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = it.window.decorView.findViewWithTag<View>("learning.detail.references.ready") != null }
                    ready
                }
                scenario.onActivity { capture(it.findViewById(android.R.id.content), "detail") }
            }
            stage("review and recreation verified")
            runBlocking {
                store.sentences.save("我爱你", "I love you!", "test", true)
                store.sentences.setAutomatic(true)
                store.sentences.save("明天上午九点一起学习吧", "Let's study together at 9:00 tomorrow morning!", "test", false)
            }
            foreground()
            ActivityScenario.launch<SentenceBookActivity>(Intent(context, SentenceBookActivity::class.java)).use { scenario ->
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString().startsWith("Let's study together") } }
                    ready
                }
                scenario.onActivity { activity ->
                    capture(activity.findViewById(android.R.id.content), "recent")
                    button(activity.window.decorView, R.string.sentences_favorites)!!.performClick()
                    views(activity.window.decorView).filterIsInstance<android.widget.EditText>().first().setText("我爱你")
                }
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == "I love you!" } }
                    ready
                }
                scenario.recreate()
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == "I love you!" } }
                    ready
                }
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<androidx.appcompat.widget.SwitchCompat>().any { v -> v.isEnabled && v.isChecked } }
                    ready
                }
                scenario.onActivity { capture(it.findViewById(android.R.id.content), "favorites") }
            }
        } finally {
            runBlocking {
                store.clearAll()
                store.sentences.clear(true)
                store.sentences.setAutomatic(false)
            }
            mode.setValue(original)
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
    private fun foreground() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity",
            ),
        ).bufferedReader().use { it.readText() }
    }

    private fun readingAppearance(test: () -> Unit) {
        check(context.packageName.endsWith(".regression"))
        val pref = AppPrefs.defaultInstance().advanced.uiMode
        val previous = pref.getValue()
        pref.setValue(if (InstrumentationRegistry.getArguments().getString("dark") == "true") AppPrefs.Advanced.UiMode.DARK else AppPrefs.Advanced.UiMode.LIGHT)
        instrumentation.uiAutomation.setRotation(if (InstrumentationRegistry.getArguments().getString("landscape") == "true") android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0)
        foreground()
        try { test() } finally {
            pref.setValue(previous)
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            runBlocking { store.learning.clearAll() }
        }
    }

    private fun detailReady(scenario: ActivityScenario<WordLearningActivity>) = waitFor {
        var ready = false
        scenario.onActivity { ready = it.window.decorView.findViewWithTag<View>("learning.detail.references.ready") != null }
        ready
    }

    private fun touchDetail(scenario: ActivityScenario<WordLearningActivity>, tag: String) {
        lateinit var target: TextView
        waitFor {
            var found = false
            scenario.onActivity { a ->
                a.window.decorView.findViewWithTag<TextView>(tag)?.let { target = it; found = true }
            }
            found
        }
        readingTouch(target)
    }

    private fun sheetTouch(id: Int) {
        lateinit var target: TextView
        waitFor {
            var found = false
            instrumentation.runOnMainSync {
                WindowInspector.getGlobalWindowViews().flatMap { views(it).filterIsInstance<TextView>().toList() }
                    .lastOrNull { it.isShown && it.isClickable && it.text.toString() == context.getString(id) }
                    ?.let { target = it; found = true }
            }
            found
        }
        readingTouch(target)
    }

    /** Scrolls only to locate a real pointer target; never substitutes performClick. */
    private fun readingTouch(target: TextView, clicks: Int = 1) {
        val bounds = Rect()
        waitFor {
            var ready = false
            instrumentation.runOnMainSync {
                if (target.width > 0 && target.height > 0) {
                    target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
                    generateSequence(target.parent as? View) { it.parent as? View }.filterIsInstance<ScrollView>().forEach { scroll ->
                        val rect = Rect(0, 0, target.width, target.height)
                        scroll.offsetDescendantRectToMyCoords(target, rect)
                        scroll.scrollTo(scroll.scrollX, (rect.centerY() - scroll.height / 2).coerceAtLeast(0))
                    }
                    ready = target.isEnabled && target.hasWindowFocus() && target.getGlobalVisibleRect(bounds) && bounds.height() == target.height && bounds.width() == target.width
                }
            }
            ready
        }
        val location = IntArray(2)
        instrumentation.runOnMainSync {
            assertTrue("Unavailable ${target.text}: $bounds", target.isEnabled && target.getGlobalVisibleRect(bounds))
            assertEquals("Clipped ${target.text}", target.height, bounds.height())
            assertTrue("Text clipped: ${target.text}", target.layout.height <= target.height - target.totalPaddingTop - target.totalPaddingBottom)
            target.getLocationOnScreen(location)
        }
        repeat(clicks) {
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0] + target.width / 2f, location[1] + target.height / 2f, 0)
                instrumentation.sendPointerSync(event)
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun detailActionsKeepPositionAndDisclosuresAcrossRecreation() = readingAppearance {
        val original = runBlocking {
            store.learning.clearAll()
            store.learning.saveMeaning("我保存的设置词义", "set", "/set/", "offline", favorite = true, learning = true)
        }
        val intent = Intent(context, WordLearningActivity::class.java).putExtra("words.chinese", original.chinese).putExtra("words.english", original.english)
        ActivityScenario.launch<WordLearningActivity>(intent).use { scenario ->
            detailReady(scenario)
            lateinit var originalScroll: ScrollView
            scenario.onActivity { a ->
                val decor = a.window.decorView
                originalScroll = decor.findViewWithTag("learning.detail.scroll")
                assertFalse(views(decor).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == context.getString(R.string.words_correct_case) })
                assertEquals(1, views(decor).count { it.isShown && it.tag == "learning.reading.example" })
                val references = decor.findViewWithTag<android.widget.LinearLayout>("learning.detail.references.ready")
                assertEquals(context.getString(R.string.learning_examples), (references.getChildAt(0) as TextView).text.toString())
                listOf("meanings", "definition", "examples", "forms").forEach { key ->
                    assertFalse(decor.findViewWithTag<View>("learning.detail.panel.$key").isShown)
                }
                capture(a.findViewById(android.R.id.content), "detail-first-example")
            }
            listOf("meanings", "definition", "examples", "forms").forEach { touchDetail(scenario, "learning.detail.section.$it") }
            var scrollY = 0
            scenario.onActivity { a ->
                originalScroll.scrollTo(0, (originalScroll.getChildAt(0).height - originalScroll.height) / 2)
                scrollY = originalScroll.scrollY
                assertTrue(scrollY > 0)
                capture(a.findViewById(android.R.id.content), "detail-expanded")
            }
            touchDetail(scenario, "learning.detail.favorite")
            waitFor { runBlocking { store.learning.find(original.chinese, original.english)?.favorite == false } }
            scenario.onActivity {
                assertSame(originalScroll, it.window.decorView.findViewWithTag<ScrollView>("learning.detail.scroll"))
                assertEquals(scrollY, originalScroll.scrollY)
            }
            touchDetail(scenario, "learning.detail.favorite")
            sheetTouch(android.R.string.cancel)
            assertFalse(runBlocking { store.learning.find(original.chinese, original.english)!!.favorite })
            touchDetail(scenario, "learning.detail.favorite")
            sheetTouch(android.R.string.ok)
            waitFor { runBlocking { store.learning.find(original.chinese, original.english)?.favorite == true } }
            scenario.recreate()
            detailReady(scenario)
            scenario.onActivity { a ->
                originalScroll = a.window.decorView.findViewWithTag("learning.detail.scroll")
                assertEquals(scrollY, originalScroll.scrollY)
                listOf("meanings", "definition", "examples", "forms").forEach { key ->
                    assertTrue(a.window.decorView.findViewWithTag<View>("learning.detail.panel.$key").isShown)
                }
            }
            touchDetail(scenario, "learning.detail.learning")
            sheetTouch(android.R.string.cancel)
            assertTrue(runBlocking { store.learning.find(original.chinese, original.english)!!.learning })
            touchDetail(scenario, "learning.detail.learning")
            sheetTouch(android.R.string.ok)
            waitFor { runBlocking { store.learning.find(original.chinese, original.english)?.learning == false } }
            touchDetail(scenario, "learning.detail.learning")
            sheetTouch(android.R.string.ok)
            waitFor { runBlocking { store.learning.find(original.chinese, original.english)?.learning == true } }
            scenario.onActivity {
                assertSame(originalScroll, it.window.decorView.findViewWithTag<ScrollView>("learning.detail.scroll"))
                assertEquals(scrollY, originalScroll.scrollY)
            }
            sheetTouch(R.string.reading_more)
            sheetTouch(R.string.words_correct_case)
            instrumentation.runOnMainSync {
                val edit = WindowInspector.getGlobalWindowViews().flatMap { views(it).toList() }.filterIsInstance<EditText>().single { it.tag == "learning.detail.case.input" }
                edit.setText("different")
            }
            sheetTouch(android.R.string.ok)
            instrumentation.runOnMainSync {
                val edit = WindowInspector.getGlobalWindowViews().flatMap { views(it).toList() }.filterIsInstance<EditText>().single { it.tag == "learning.detail.case.input" }
                assertTrue(edit.error != null)
                edit.setText("SET")
            }
            sheetTouch(android.R.string.ok)
            waitFor { runBlocking { store.learning.find(original.chinese, original.english)?.displayEnglish == "SET" } }
            waitFor {
                var updated = false
                scenario.onActivity { updated = it.window.decorView.findViewWithTag<TextView>("learning.detail.word").text.toString() == "SET" }
                updated
            }
            scenario.onActivity { a ->
                assertSame(originalScroll, a.window.decorView.findViewWithTag<ScrollView>("learning.detail.scroll"))
                assertEquals(scrollY, originalScroll.scrollY)
                assertEquals("SET", a.window.decorView.findViewWithTag<TextView>("learning.detail.word").text.toString())
            }
            assertEquals(original.copy(displayEnglish = "SET"), runBlocking { store.learning.find(original.chinese, original.english) })
            touchDetail(scenario, "learning.detail.section.meanings")
            scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("learning.detail.panel.meanings").isShown) }
            scenario.recreate()
            detailReady(scenario)
            scenario.onActivity { a ->
                assertFalse(a.window.decorView.findViewWithTag<View>("learning.detail.panel.meanings").isShown)
                capture(a.findViewById(android.R.id.content), "detail-restored")
            }
        }
    }

    @Test fun detailLateReferencesAndFailedWritesKeepActionsStable() = readingAppearance {
        val original = runBlocking {
            store.learning.clearAll()
            store.learning.saveMeaning("学习", "learn", "/lɜːn/", "offline", favorite = true, learning = true)
        }
        val lexiconLocked = CountDownLatch(1)
        val releaseLexicon = CountDownLatch(1)
        val loader = Thread {
            synchronized(StudyLexicon) {
                lexiconLocked.countDown()
                releaseLexicon.await(30, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(lexiconLocked.await(5, TimeUnit.SECONDS))
        try {
            val intent = Intent(context, WordLearningActivity::class.java).putExtra("words.chinese", original.chinese).putExtra("words.english", original.english)
            ActivityScenario.launch<WordLearningActivity>(intent).use { scenario ->
                lateinit var favorite: TextView
                lateinit var scroll: ScrollView
                var footerY = 0
                waitFor {
                    var ready = false
                    scenario.onActivity { a ->
                        a.window.decorView.findViewWithTag<TextView>("learning.detail.favorite")?.let { favorite = it; ready = it.width > 0 && it.hasWindowFocus() }
                    }
                    ready
                }
                scenario.onActivity { a ->
                    assertNull(a.window.decorView.findViewWithTag<View>("learning.detail.references.ready"))
                    scroll = a.window.decorView.findViewWithTag("learning.detail.scroll")
                    val location = IntArray(2)
                    favorite.getLocationOnScreen(location)
                    footerY = location[1]
                    assertFalse(generateSequence(favorite.parent as? View) { it.parent as? View }.any { it is ScrollView })
                    capture(a.findViewById(android.R.id.content), "detail-before-references")
                }
                releaseLexicon.countDown()
                detailReady(scenario)
                scenario.onActivity { a ->
                    val location = IntArray(2)
                    favorite.getLocationOnScreen(location)
                    assertEquals(footerY, location[1])
                    assertSame(scroll, a.window.decorView.findViewWithTag<ScrollView>("learning.detail.scroll"))
                }
                // Only this isolated database receives the disposable failing trigger.
                store.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reading_test_failure BEFORE UPDATE ON saved_words BEGIN SELECT RAISE(ABORT, 'reading_test_failure'); END")
                try {
                    val locked = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val job = CoroutineScope(Dispatchers.IO).launch {
                        store.database.withTransaction { locked.complete(Unit); release.await() }
                    }
                    try {
                        runBlocking { withTimeout(5000) { locked.await() } }
                        readingTouch(favorite, clicks = 2)
                        scenario.onActivity { a ->
                            assertFalse(favorite.isEnabled)
                            assertFalse(a.window.decorView.findViewWithTag<View>("learning.detail.learning").isEnabled)
                        }
                    } finally {
                        release.complete(Unit)
                        runBlocking { job.join() }
                    }
                    waitFor {
                        var enabled = false
                        scenario.onActivity { enabled = favorite.isEnabled }
                        enabled
                    }
                    assertEquals(original, runBlocking { store.learning.find(original.chinese, original.english) })
                    scenario.onActivity { a ->
                        assertSame(scroll, a.window.decorView.findViewWithTag<ScrollView>("learning.detail.scroll"))
                        assertEquals(context.getString(R.string.input_footprints_remove_favorite), favorite.text.toString())
                    }
                } finally {
                    store.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reading_test_failure")
                }
                readingTouch(favorite)
                waitFor { runBlocking { store.learning.find(original.chinese, original.english)?.favorite == false } }
                waitFor {
                    var enabled = false
                    scenario.onActivity { enabled = favorite.isEnabled }
                    enabled
                }
                scenario.onActivity { a ->
                    assertTrue(favorite.isEnabled)
                    assertSame(scroll, a.window.decorView.findViewWithTag<ScrollView>("learning.detail.scroll"))
                    capture(a.findViewById(android.R.id.content), "detail-retry-succeeded")
                }
            }
        } finally {
            releaseLexicon.countDown()
            loader.join(5000)
        }
    }

    @Test fun reverseReadingKeepsEnglishAndAudioHiddenUntilReveal() {
        assertTrue(context.packageName.endsWith(".regression"))
        runBlocking {
            store.learning.clearAll()
            store.learning.saveSettings(true, 5, 10, true)
            store.learning.saveMeaning("学习", "learn", "/lɜːn/", "offline", learning = true, now = 1)
        }
        foreground()
        try {
            ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "plan")).use { scenario ->
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = button(it.window.decorView, R.string.words_plan_start) != null }
                    ready
                }
                scenario.onActivity { button(it.window.decorView, R.string.words_plan_start)!!.performClick() }
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = button(it.window.decorView, R.string.words_reveal) != null }
                    ready
                }
                scenario.onActivity { activity ->
                    val visible = views(activity.window.decorView).filter { it.isShown }.toList()
                    assertFalse(visible.filterIsInstance<TextView>().any { it.text.toString() == "learn" || it.text.toString() == "/lɜːn/" })
                    assertFalse(visible.any { it.contentDescription == activity.getString(R.string.input_footprints_speak) })
                    button(activity.window.decorView, R.string.words_reveal)!!.performClick()
                }
                waitFor {
                    var ready = false
                    scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.isShown && v.text.toString() == "learn" } }
                    ready
                }
                assertEquals(0, runBlocking { store.learning.find("学习", "learn")!!.reviewCount })
            }
        } finally {
            runBlocking {
                store.learning.clearAll()
                store.learning.saveSettings(false, 5, 10, false)
            }
        }
    }
}
