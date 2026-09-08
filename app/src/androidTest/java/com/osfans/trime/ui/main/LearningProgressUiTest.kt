package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LearningProgressUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun views(v: View): Sequence<View> = sequence { yield(v); if (v is ViewGroup) repeat(v.childCount) { yieldAll(views(v.getChildAt(it))) } }
    private fun waitFor(check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(80)
        assertTrue("Learning UI did not settle", check()); instrument.waitForIdleSync()
    }
    private fun click(s: ActivityScenario<WordLearningActivity>, id: Int) {
        var target: TextView? = null
        waitFor { s.onActivity { a -> target = views(a.window.decorView).filterIsInstance<TextView>().firstOrNull { it.isShown && it.isClickable && it.text.toString() == a.getString(id) } }; target != null }
        s.onActivity { target!!.requestRectangleOnScreen(Rect(0, 0, target!!.width, target!!.height), true) }
        instrument.waitForIdleSync()
        s.onActivity {
            val visible = Rect(); assertTrue(target!!.getGlobalVisibleRect(visible))
            assertEquals(target!!.height, visible.height()); assertTrue(target!!.isEnabled)
            target!!.performClick()
        }
    }
    private fun capture(s: ActivityScenario<WordLearningActivity>, name: String) {
        val expected = when {
            name.startsWith("calendar") -> R.string.study_calendar_legend
            name == "statistics" -> R.string.study_ratings
            name == "settings" -> R.string.study_plan_heading
            else -> R.string.study_today
        }
        waitFor {
            var ready = false
            s.onActivity { a -> ready = views(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == a.getString(expected) } }
            ready
        }
        instrument.waitForIdleSync()
        s.onActivity { a ->
            val args = InstrumentationRegistry.getArguments()
            assertEquals(args.getString("dark") == "true", a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            args.getString("fontScale")?.toFloat()?.let { assertEquals(it, a.resources.configuration.fontScale, 0.01f) }
            args.getString("landscape")?.let { assertEquals(it == "true", a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) }
            val root = a.findViewById<View>(android.R.id.content)
            if (name.startsWith("calendar")) {
                val rootPosition = IntArray(2).also { root.getLocationOnScreen(it) }
                views(root).filterIsInstance<TextView>().filter { it.contentDescription?.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) == true }.forEach { cell ->
                    val position = IntArray(2).also { cell.getLocationOnScreen(it) }
                    assertTrue("Calendar day hidden horizontally", position[0] >= rootPosition[0] && position[0] + cell.width <= rootPosition[0] + root.width)
                    assertTrue("Calendar touch target too small", cell.width >= (48 * a.resources.displayMetrics.density).toInt())
                }
            }
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(context.getExternalFilesDir(null), "learning-progress/$name.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.forEach { v ->
                val layout = v.layout ?: return@forEach
                assertTrue("Text vertically clipped in $name: ${v.text} (${layout.height}/${v.height - v.totalPaddingTop - v.totalPaddingBottom})", layout.height <= v.height - v.totalPaddingTop - v.totalPaddingBottom)
                for (line in 0 until layout.lineCount) assertEquals("Ellipsized text", 0, layout.getEllipsisCount(line))
            }
        }
    }
    @Test fun dashboardCalendarStatisticsAndCheckInSurviveRecreation() {
        assertTrue(context.packageName.endsWith(".regression"))
        val prefs = AppPrefs.defaultInstance().advanced.uiMode
        val original = prefs.getValue()
        prefs.setValue(if (InstrumentationRegistry.getArguments().getString("dark") == "true") AppPrefs.Advanced.UiMode.DARK else AppPrefs.Advanced.UiMode.LIGHT)
        val learning = InputFootprints.store.learning
        runBlocking {
            learning.clearAll(); learning.saveSettings(false, 5, 10, false)
            learning.saveMeaning("学习", "learn", "/lɜːn/", "offline", learning = true)
        }
        val landscape = InstrumentationRegistry.getArguments().getString("landscape") == "true"
        assertTrue(instrument.uiAutomation.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0))
        // Foreground only our isolated package, never a chat activity.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity",
        )).use { it.readBytes() }
        try {
            ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "plan")).use { s ->
                waitFor { var ready = false; s.onActivity { ready = (it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) == landscape }; ready }
                click(s, R.string.words_plan_enable)
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == context.getString(R.string.words_plan_start) } }; ready }
                capture(s, "home")
                click(s, R.string.words_plan_start); click(s, R.string.words_reveal); click(s, R.string.words_remembered)
                waitFor { runBlocking { learning.progress.dashboard(System.currentTimeMillis()).task?.completed == true } }
                click(s, R.string.study_home)
                click(s, R.string.study_calendar)
                capture(s, "calendar")
                s.onActivity { activity ->
                    views(activity.window.decorView).filterIsInstance<TextView>().first {
                        it.isClickable && it.text.toString().startsWith(activity.getString(R.string.journal_rewards))
                    }.performClick()
                }
                instrument.waitForIdleSync()
                s.onActivity { activity ->
                    val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    val rewardRoot = roots.first { root -> views(root).any { it.contentDescription?.toString()?.startsWith("初次见面，已解锁") == true } }
                    assertTrue(views(rewardRoot).any { it.contentDescription?.toString()?.startsWith("向阳生长，累计 7 天解锁") == true })
                    val bitmap = Bitmap.createBitmap(rewardRoot.width, rewardRoot.height, Bitmap.Config.ARGB_8888)
                    rewardRoot.draw(Canvas(bitmap))
                    val file = File(context.getExternalFilesDir(null), "learning-progress/rewards.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    views(rewardRoot).filterIsInstance<TextView>().first { it.isClickable && it.text.toString() == activity.getString(R.string.sentences_close) }.performClick()
                }
                var lastDay: TextView? = null
                s.onActivity { a ->
                    lastDay = views(a.window.decorView).filterIsInstance<TextView>().last { it.contentDescription?.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) == true }
                    lastDay!!.requestRectangleOnScreen(Rect(0, 0, lastDay!!.width, lastDay!!.height), true)
                }
                instrument.waitForIdleSync()
                s.onActivity {
                    val visible = Rect()
                    assertTrue(lastDay!!.getGlobalVisibleRect(visible))
                    assertEquals(lastDay!!.height, visible.height())
                    assertEquals(lastDay!!.width, visible.width())
                }
                capture(s, "calendar-tail")
                s.recreate()
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == context.getString(R.string.study_calendar_legend) } }; ready }
                capture(s, "calendar-restored")
                s.onActivity { a -> views(a.window.decorView).first { it.contentDescription == a.getString(R.string.words_back) }.performClick() }
                click(s, R.string.study_stats)
                capture(s, "statistics")
                s.recreate()
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == context.getString(R.string.study_ratings) } }; ready }
                assertEquals(1, runBlocking { learning.progress.dashboard(System.currentTimeMillis()).checkins })
                s.onActivity { a -> views(a.window.decorView).first { it.contentDescription == a.getString(R.string.words_back) }.performClick() }
                click(s, R.string.words_plan_settings)
                capture(s, "settings")
                s.recreate()
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == context.getString(R.string.study_plan_heading) } }; ready }
                click(s, R.string.words_plan_save)
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString() == context.getString(R.string.study_today) } }; ready }
            }
        } finally {
            runBlocking { learning.clearAll() }
            prefs.setValue(original)
            instrument.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
