package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.LEARNING_DAY_MS
import com.osfans.trime.data.footprints.RecallRating
import com.osfans.trime.data.footprints.ReviewMode
import com.osfans.trime.data.footprints.selectedMode
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uses only synthetic data in the independent regression package. */
class LearningModesUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val learning get() = InputFootprints.store.learning
    private var originalTheme = AppPrefs.Advanced.UiMode.AUTO

    @org.junit.Before fun configureWindow() {
        assertTrue(context.packageName.endsWith(".regression"))
        val theme = AppPrefs.defaultInstance().advanced.uiMode
        originalTheme = theme.getValue()
        theme.setValue(if (InstrumentationRegistry.getArguments().getString("dark") == "true") AppPrefs.Advanced.UiMode.DARK else AppPrefs.Advanced.UiMode.LIGHT)
        instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
    }

    @org.junit.After fun restoreWindow() {
        AppPrefs.defaultInstance().advanced.uiMode.setValue(originalTheme)
        instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }
    private fun waitFor(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(60)
        assertTrue("UI did not settle", check())
        instrumentation.waitForIdleSync()
    }
    private fun text(scenario: ActivityScenario<WordLearningActivity>, id: Int): Boolean {
        var found = false
        scenario.onActivity { a -> found = views(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == context.getString(id) } }
        return found
    }
    private fun touch(scenario: ActivityScenario<WordLearningActivity>, id: Int) {
        waitFor { text(scenario, id) }
        // Hardware typing leaves touch mode; entering it may scroll the focused editor.
        // Measure only after that native focus transition, just as a finger sees the screen.
        instrumentation.setInTouchMode(true)
        instrumentation.waitForIdleSync()
        lateinit var target: TextView
        scenario.onActivity { a ->
            target = views(a.window.decorView).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == context.getString(id) }
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        }
        instrumentation.waitForIdleSync()
        val rect = Rect()
        scenario.onActivity {
            assertTrue(target.isEnabled)
            assertTrue(target.getGlobalVisibleRect(rect))
            assertEquals("Action must be fully reachable", target.height, rect.height())
            assertTrue(target.layout.height <= target.height - target.totalPaddingTop - target.totalPaddingBottom)
        }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(scenario: ActivityScenario<WordLearningActivity>, name: String) {
        scenario.onActivity { a ->
            val view = a.findViewById<View>(android.R.id.content)
            assertEquals("Capture orientation must match the requested window", InstrumentationRegistry.getArguments().getString("landscape") == "true", a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            views(view).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.forEach { text ->
                text.layout?.let { layout ->
                    assertTrue("Vertical clipping: ${text.text}", layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
                    repeat(layout.lineCount) { line ->
                        assertTrue("Horizontal clipping: ${text.text} (${layout.getLineMax(line)} > ${text.width - text.totalPaddingLeft - text.totalPaddingRight})", layout.getLineMax(line) <= text.width - text.totalPaddingLeft - text.totalPaddingRight + 2)
                        assertEquals("Ellipsized: ${text.text}", 0, layout.getEllipsisCount(line))
                    }
                }
            }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "phone")
            val file = File(context.getExternalFilesDir(null), "learning-modes/$prefix-$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        if (name == "spelling-keyboard") {
            // Include the separate IME window; drawing only the Activity leaves a blank inset.
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "phone")
            File(context.getExternalFilesDir(null), "learning-modes/$prefix-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun foreground() {
        // Some devices select another IME when instrumentation restarts this package.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "ime set ${context.packageName}/com.osfans.trime.ime.core.TrimeInputMethodService",
            ),
        ).bufferedReader().use { it.readText() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity",
            ),
        ).bufferedReader().use { it.readText() }
    }
    private fun launch() = ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "review"))

    @Test fun modesHideAnswersAndSupportTouchRecreationCompletionAndUndo() {
        assertTrue(context.packageName.endsWith(".regression"))
        val theme = AppPrefs.defaultInstance().advanced.uiMode
        val original = theme.getValue()
        theme.setValue(if (InstrumentationRegistry.getArguments().getString("dark") == "true") AppPrefs.Advanced.UiMode.DARK else AppPrefs.Advanced.UiMode.LIGHT)
        instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
        try {
            foreground()
            ReviewMode.entries.forEach { selected ->
                runBlocking {
                    learning.clearAll()
                    learning.saveSettings(false, 5, 10, false)
                    learning.setReviewMode(selected)
                    learning.saveMeaning("不同寻常的；令人惊叹的；出乎意料的", "extraordinary", "/ɪkˈstrɔːdənəri/", "offline", learning = true, now = 1)
                }
                launch().use { scenario ->
                    val spelling = selected == ReviewMode.SPELLING
                    waitFor { text(scenario, if (spelling) R.string.study_spelling_check else R.string.words_reveal) }
                    scenario.onActivity { a ->
                        val visible = views(a.window.decorView).filter { it.isShown }.filterIsInstance<TextView>().map { it.text.toString() }.toList()
                        if (selected == ReviewMode.CHINESE || spelling) {
                            assertFalse(visible.contains("extraordinary"))
                            assertFalse(visible.contains("/ɪkˈstrɔːdənəri/"))
                            assertFalse(views(a.window.decorView).any { it.isShown && it.contentDescription == context.getString(R.string.input_footprints_speak) })
                        } else {
                            assertFalse(visible.contains("不同寻常的；令人惊叹的；出乎意料的"))
                        }
                    }
                    capture(scenario, "${selected.name}-front")
                    if (spelling) {
                        scenario.onActivity { a ->
                            val input = views(a.window.decorView).filterIsInstance<EditText>().first()
                            input.requestFocus()
                            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                        waitFor {
                            var visible = false
                            scenario.onActivity { a -> visible = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                            visible
                        }
                        // The native IME switches layouts asynchronously after a window resize.
                        waitFor { com.osfans.trime.daemon.RimeDaemon.getFirstSessionOrNull()?.run { statusCached }?.isAsciiMode == true }
                        instrumentation.sendStringSync("extrordinary")
                        waitFor { runBlocking { learning.session()?.spellingDraft == "extrordinary" } }
                        capture(scenario, "spelling-keyboard")
                        waitFor {
                            var fullyVisible = false
                            scenario.onActivity { a ->
                                val input = views(a.window.decorView).filterIsInstance<EditText>().first()
                                val rect = android.graphics.Rect()
                                fullyVisible = input.getGlobalVisibleRect(rect) && rect.height() >= input.height
                            }
                            fullyVisible
                        }
                        capture(scenario, "spelling-keyboard")
                        scenario.recreate()
                        waitFor { text(scenario, R.string.study_spelling_check) }
                        scenario.onActivity { a -> assertEquals("extrordinary", views(a.window.decorView).filterIsInstance<EditText>().first().text.toString()) }
                        touch(scenario, R.string.study_spelling_check)
                        waitFor { text(scenario, R.string.study_spelling_wrong) }
                        scenario.onActivity { a -> assertFalse(views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == context.getString(R.string.words_remembered) }.isEnabled) }
                    } else {
                        touch(scenario, R.string.words_reveal)
                    }
                    waitFor { text(scenario, R.string.words_uncertain) }
                    capture(scenario, "${selected.name}-answer")
                    scenario.recreate()
                    waitFor { text(scenario, R.string.words_uncertain) }
                    assertEquals(0, runBlocking { learning.savedWords().single().reviewCount })
                    touch(scenario, R.string.words_uncertain)
                    waitFor { text(scenario, R.string.words_review_done) }
                    capture(scenario, "${selected.name}-done")
                    touch(scenario, R.string.words_undo)
                    waitFor { text(scenario, R.string.words_uncertain) }
                    assertEquals(0, runBlocking { learning.savedWords().single().reviewCount })
                    assertEquals(0, runBlocking { learning.progress.events().size })
                }
            }
        } finally {
            runBlocking {
                learning.clearAll()
                learning.saveSettings(false, 5, 10, false)
            }
            theme.setValue(original)
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @Test fun dashboardModesStatisticsAndProfileAreReachable() {
        assertTrue(context.packageName.endsWith(".regression"))
        runBlocking {
            learning.clearAll()
            learning.saveSettings(true, 5, 10, false)
            learning.saveMeaning("学习", "learn", null, "offline", learning = true, favorite = true, now = 1)
            val now = System.currentTimeMillis()
            listOf(8, 7, 4, 0).forEach { ago ->
                val time = now - ago * LEARNING_DAY_MS
                val card = learning.startSession(true, now = time).cards.firstOrNull()
                if (card != null) {
                    learning.reveal(card.token)
                    learning.answer(card.token, RecallRating.REMEMBERED, time)
                }
            }
        }
        foreground()
        try {
            ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "plan")).use { scenario ->
                waitFor { text(scenario, R.string.wordbooks_change_mode) }
                capture(scenario, "dashboard")
                touch(scenario, R.string.wordbooks_change_mode)
                touch(scenario, R.string.study_mode_mixed)
                touch(scenario, R.string.words_plan_save)
                waitFor { runBlocking { learning.settings().selectedMode() == ReviewMode.MIXED } }
                touch(scenario, R.string.study_stats)
                waitFor { text(scenario, R.string.study_trend) }
                capture(scenario, "statistics")
                touch(scenario, R.string.study_range_month)
                scenario.recreate()
                waitFor { text(scenario, R.string.study_trend) }
                touch(scenario, R.string.study_tab_retention)
                waitFor { text(scenario, R.string.study_retention_heading) }
                capture(scenario, "retention")
                touch(scenario, R.string.study_tab_schedule)
                waitFor { text(scenario, R.string.study_schedule_heading) }
                capture(scenario, "schedule")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor { text(scenario, R.string.wordbooks_change_mode) }
                touch(scenario, R.string.study_profile)
                waitFor { text(scenario, R.string.study_profile_heading) }
                capture(scenario, "profile")
                scenario.recreate()
                waitFor { text(scenario, R.string.study_profile_heading) }
            }
        } finally {
            runBlocking {
                learning.clearAll()
                learning.saveSettings(false, 5, 10, false)
            }
        }
    }
}
