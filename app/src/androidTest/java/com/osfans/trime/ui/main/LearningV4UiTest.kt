package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.footprints.SentenceBookActivity
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
                    scenario.onActivity { ready = button(it.window.decorView, R.string.learning_forms) != null }
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
