package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import com.osfans.trime.ui.main.footprints.SentenceBookActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
        assertTrue("UI condition timed out", check()); instrumentation.waitForIdleSync()
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
    }
    @Test fun reviewGeometryAndSentenceSearch() {
        fun stage(name: String) { instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nV4: $name\n") }) }
        stage("start")
        assertTrue(context.packageName.endsWith(".regression"))
        val mode = AppPrefs.defaultInstance().advanced.uiMode
        val original = mode.getValue()
        val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
        mode.setValue(if (dark) AppPrefs.Advanced.UiMode.DARK else AppPrefs.Advanced.UiMode.LIGHT)
        stage("mode configured")
        runBlocking {
            store.clearAll(); store.sentences.clear(true); store.sentences.setAutomatic(false)
            store.learning.saveMeaning("我保存的学习含义", "learn", "/lɜːn/", "offline", learning = true, now = 1)
        }
        stage("fixtures saved")
        // Some OEMs block background ActivityScenario/bootstrap launches. Bring the app's
        // existing exported main screen forward via the test shell before normal navigation.
        foreground()
        try {
            val intent = Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "review")
            ActivityScenario.launch<WordLearningActivity>(intent).use { scenario ->
                waitFor { var ready = false; scenario.onActivity { ready = button(it.window.decorView, R.string.words_reveal) != null }; ready }
                var originalScroll: ScrollView? = null
                var revealY = 0
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    originalScroll = views(decor).filterIsInstance<ScrollView>().first()
                    val reveal = button(decor, R.string.words_reveal)!!
                    val location = IntArray(2); reveal.getLocationOnScreen(location); revealY = location[1]
                    assertTrue(reveal.height >= 55 * activity.resources.displayMetrics.density)
                    capture(activity.findViewById(android.R.id.content), "front")
                    reveal.performClick()
                }
                waitFor { var ready = false; scenario.onActivity { ready = button(it.window.decorView, R.string.words_remembered) != null }; ready }
                SystemClock.sleep(600)
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    assertSame(originalScroll, views(decor).filterIsInstance<ScrollView>().first())
                    assertTrue(views(decor).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "我保存的学习含义" })
                    val rating = button(decor, R.string.words_remembered)!!
                    val location = IntArray(2); rating.getLocationOnScreen(location)
                    if (activity.resources.configuration.screenHeightDp >= 480) assertEquals(revealY, location[1])
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
                waitFor { var ready = false; scenario.onActivity { ready = button(it.window.decorView, R.string.words_remembered) != null }; ready }
                assertEquals(0, runBlocking { store.learning.find("我保存的学习含义", "learn")!!.reviewCount })
            }
            stage("review and recreation verified")
            runBlocking {
                store.sentences.save("我爱你", "I love you!", "test", true)
                store.sentences.setAutomatic(true)
                store.sentences.save("明天上午九点一起学习吧", "Let's study together at 9:00 tomorrow morning!", "test", false)
            }
            foreground()
            ActivityScenario.launch<SentenceBookActivity>(Intent(context, SentenceBookActivity::class.java)).use { scenario ->
                waitFor { var ready = false; scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString().startsWith("Let's study together") } }; ready }
                scenario.onActivity { activity ->
                    capture(activity.findViewById(android.R.id.content), "recent")
                    button(activity.window.decorView, R.string.sentences_favorites)!!.performClick()
                    views(activity.window.decorView).filterIsInstance<android.widget.EditText>().first().setText("我爱你")
                }
                waitFor { var ready = false; scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == "I love you!" } }; ready }
                scenario.recreate()
                waitFor { var ready = false; scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == "I love you!" } }; ready }
                waitFor { var ready = false; scenario.onActivity { ready = views(it.window.decorView).filterIsInstance<androidx.appcompat.widget.SwitchCompat>().any { v -> v.isEnabled && v.isChecked } }; ready }
                scenario.onActivity { capture(it.findViewById(android.R.id.content), "favorites") }
            }
        } finally {
            runBlocking { store.clearAll(); store.sentences.clear(true); store.sentences.setAutomatic(false) }
            mode.setValue(original)
        }
    }
    private fun foreground() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity",
        )).bufferedReader().use { it.readText() }
    }
}
