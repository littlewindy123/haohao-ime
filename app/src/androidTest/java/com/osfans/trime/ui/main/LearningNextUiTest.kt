package com.osfans.trime.ui.main

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.LearningBackupCodec
import com.osfans.trime.data.footprints.LearningBackupStore
import com.osfans.trime.data.footprints.LearningReviewEvent
import com.osfans.trime.data.footprints.RecallRating
import com.osfans.trime.data.footprints.ReviewMode
import com.osfans.trime.data.footprints.SpellingOutcome
import com.osfans.trime.data.footprints.learningDay
import com.osfans.trime.ui.main.footprints.LearningBackupActivity
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real touch dispatch and document-contract results, confined to the regression app. */
class LearningNextUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = InputFootprints.store
    private var oldTheme = com.osfans.trime.data.prefs.AppPrefs.Advanced.UiMode.AUTO

    @org.junit.Before fun configure() {
        val pref = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().advanced.uiMode
        oldTheme = pref.getValue()
        pref.setValue(if (InstrumentationRegistry.getArguments().getString("dark") == "true") com.osfans.trime.data.prefs.AppPrefs.Advanced.UiMode.DARK else com.osfans.trime.data.prefs.AppPrefs.Advanced.UiMode.LIGHT)
        instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
    }

    @org.junit.After fun reset() {
        com.osfans.trime.data.prefs.AppPrefs.defaultInstance().advanced.uiMode.setValue(oldTheme)
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
    private fun boot() {
        check(context.packageName.endsWith(".regression"))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity")).use { it.readBytes() }
        runBlocking {
            store.learning.clearAll()
            store.sentences.clear(true)
        }
        File(context.noBackupFilesDir, "learning-restore-rollback.json").delete()
    }
    private fun <A : AppCompatActivity> has(scenario: ActivityScenario<A>, id: Int): Boolean {
        var result = false
        scenario.onActivity { a -> result = views(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == context.getString(id) } }
        return result
    }
    private fun <A : AppCompatActivity> touch(scenario: ActivityScenario<A>, id: Int, index: Int = 0) {
        waitFor { has(scenario, id) }
        waitFor {
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        }
        SystemClock.sleep(200)
        instrumentation.setInTouchMode(true)
        lateinit var target: TextView
        scenario.onActivity { a ->
            target = views(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown && it.isClickable && it.text.toString() == context.getString(id) }.toList()[index]
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        }
        instrumentation.waitForIdleSync()
        press(target)
    }
    private fun press(target: TextView) {
        waitFor {
            var focused = false
            instrumentation.runOnMainSync { focused = target.hasWindowFocus() }
            focused
        }
        SystemClock.sleep(100)
        val rect = Rect()
        val location = IntArray(2)
        instrumentation.runOnMainSync {
            assertTrue(target.isEnabled && target.getGlobalVisibleRect(rect))
            assertEquals("Button must be fully reachable", target.height, rect.height())
            assertTrue("Clipped button text: ${target.text}", target.layout.height <= target.height - target.totalPaddingTop - target.totalPaddingBottom)
            target.getLocationOnScreen(location)
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0] + target.width / 2f, location[1] + target.height / 2f, 0)
            instrumentation.sendPointerSync(e)
            e.recycle()
        }
        instrumentation.waitForIdleSync()
    }
    private fun dialog(id: Int) {
        lateinit var target: TextView
        waitFor {
            var found: TextView? = null
            instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().flatMap { views(it).filterIsInstance<TextView>().toList() }.lastOrNull { it.isShown && it.isClickable && it.text.toString() == context.getString(id) } }
            if (found != null) target = found!!
            found != null
        }
        press(target)
    }
    private fun <A : AppCompatActivity> capture(scenario: ActivityScenario<A>, name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        scenario.onActivity { a ->
            assertEquals(InstrumentationRegistry.getArguments().getString("landscape") == "true", a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            views(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.forEach { t ->
                t.layout?.let { l ->
                    assertTrue("Vertical clipping: ${t.text}", l.height <= t.height - t.totalPaddingTop - t.totalPaddingBottom)
                    repeat(l.lineCount) { i ->
                        assertEquals("Ellipsis: ${t.text}", 0, l.getEllipsisCount(i))
                        assertTrue("Horizontal clipping: ${t.text}: ${l.getLineMax(i)} / ${t.width - t.totalPaddingLeft - t.totalPaddingRight}", l.getLineMax(i) <= t.width - t.totalPaddingLeft - t.totalPaddingRight + 2)
                    }
                }
            }
        }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "phone")
        val file = File(context.getExternalFilesDir(null), "learning-next/$prefix-$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        scenario.onActivity { a -> views(a.window.decorView).filterIsInstance<android.widget.ScrollView>().firstOrNull()?.fullScroll(View.FOCUS_DOWN) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bottom = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(file.parentFile, "$prefix-$name-bottom.png").outputStream().use { bottom.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bottom.recycle()
        // Long landscape pages need intermediate viewports as well as both endpoints.
        var scroll: android.widget.ScrollView? = null
        var pageHeight = 0
        var contentHeight = 0
        scenario.onActivity { a ->
            scroll = views(a.window.decorView).filterIsInstance<android.widget.ScrollView>().firstOrNull()
            scroll?.let {
                pageHeight = it.height
                contentHeight = it.getChildAt(0)?.height ?: 0
            }
        }
        if (pageHeight > 0) {
            val step = (pageHeight * 0.7f).toInt().coerceAtLeast(1)
            var offset = step
            var page = 1
            while (offset < contentHeight - pageHeight) {
                scenario.onActivity { scroll?.scrollTo(0, offset) }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                val middle = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(file.parentFile, "$prefix-$name-page-$page.png").outputStream().use { middle.compress(Bitmap.CompressFormat.PNG, 100, it) }
                middle.recycle()
                offset += step
                page++
            }
        }
    }
    private fun monitor(action: String, file: File?, result: Int = Activity.RESULT_OK): Instrumentation.ActivityMonitor {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? = if (intent.action == action) {
                Instrumentation.ActivityResult(result, Intent().apply { if (file != null) data = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) })
            } else {
                null
            }
        }
        instrumentation.addMonitor(monitor)
        return monitor
    }

    @Test fun backupDocumentFlowPreviewRecreationCancellationMergeRestoreAndRollback() {
        boot()
        runBlocking {
            store.learning.saveMeaning("你好", "hello", null, "offline", learning = true, favorite = true, now = 1)
            store.sentences.save("你好呀", "Hello there!", "test", true)
        }
        val before = runBlocking { LearningBackupStore(store, File(context.cacheDir, "unused-rollback")).snapshot(1) }
        val exported = File(context.cacheDir, "ui-backup-test.json")
        ActivityScenario.launch<LearningBackupActivity>(Intent(context, LearningBackupActivity::class.java)).use { scenario ->
            var m = monitor(Intent.ACTION_CREATE_DOCUMENT, exported)
            try {
                touch(scenario, R.string.learning_backup_export)
                waitFor { has(scenario, R.string.learning_backup_exported) }
            } finally {
                instrumentation.removeMonitor(m)
            }
            assertEquals(before.words, exported.inputStream().use(LearningBackupCodec::read).words)
            m = monitor(Intent.ACTION_OPEN_DOCUMENT, null, Activity.RESULT_CANCELED)
            try {
                touch(scenario, R.string.learning_backup_import)
            } finally {
                instrumentation.removeMonitor(m)
            }
            assertFalse(has(scenario, R.string.learning_backup_preview))
            runBlocking { store.learning.saveMeaning("世界", "world", null, "offline", favorite = true, now = 2) }
            m = monitor(Intent.ACTION_OPEN_DOCUMENT, exported)
            try {
                touch(scenario, R.string.learning_backup_import)
                waitFor { has(scenario, R.string.learning_backup_preview) }
            } finally {
                instrumentation.removeMonitor(m)
            }
            scenario.recreate()
            waitFor { has(scenario, R.string.learning_backup_preview) }
            capture(scenario, "backup-preview")
            val broken = File(context.cacheDir, "ui-broken-backup.json").apply { writeText("{broken") }
            m = monitor(Intent.ACTION_OPEN_DOCUMENT, broken)
            try {
                touch(scenario, R.string.learning_backup_import)
                waitFor { has(scenario, R.string.learning_backup_failed) }
            } finally {
                instrumentation.removeMonitor(m)
                broken.delete()
            }
            assertFalse(has(scenario, R.string.learning_backup_preview))
            assertEquals(2, runBlocking { store.learning.savedWords().size })
            m = monitor(Intent.ACTION_OPEN_DOCUMENT, exported)
            try {
                touch(scenario, R.string.learning_backup_import)
                waitFor { has(scenario, R.string.learning_backup_preview) }
            } finally {
                instrumentation.removeMonitor(m)
            }
            touch(scenario, R.string.learning_backup_full)
            dialog(R.string.learning_backup_cancel)
            assertEquals(2, runBlocking { store.learning.savedWords().size })
            touch(scenario, R.string.learning_backup_merge)
            dialog(R.string.learning_backup_confirm)
            waitFor { has(scenario, R.string.learning_backup_merged) }
            assertEquals(2, runBlocking { store.learning.savedWords().size })
            m = monitor(Intent.ACTION_OPEN_DOCUMENT, exported)
            try {
                touch(scenario, R.string.learning_backup_import)
                waitFor { has(scenario, R.string.learning_backup_preview) }
            } finally {
                instrumentation.removeMonitor(m)
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lock = CoroutineScope(Dispatchers.IO).launch {
                store.database.withTransaction {
                    entered.complete(Unit)
                    release.await()
                }
            }
            runBlocking { entered.await() }
            try {
                touch(scenario, R.string.learning_backup_full)
                dialog(R.string.learning_backup_confirm)
                waitFor { has(scenario, R.string.learning_backup_working) }
                scenario.recreate()
                assertTrue(has(scenario, R.string.learning_backup_working))
            } finally {
                release.complete(Unit)
                runBlocking { lock.join() }
            }
            waitFor { has(scenario, R.string.learning_backup_restored) }
            assertEquals(before.words, runBlocking { store.learning.savedWords() })
            capture(scenario, "backup-restored")
            touch(scenario, R.string.learning_backup_rollback)
            dialog(R.string.learning_backup_confirm)
            waitFor { !has(scenario, R.string.learning_backup_rollback) }
            assertEquals(2, runBlocking { store.learning.savedWords().size })
        }
        exported.delete()
    }

    @Test fun largeWeakListsArePagedAndRoundsStayLimitedToTen() {
        boot()
        val now = System.currentTimeMillis()
        runBlocking {
            repeat(51) { index ->
                val english = "${'a' + index / 26}${'a' + index % 26}"
                store.learning.saveMeaning("测试$index", english, null, "offline", learning = true, now = 1)
                repeat(2) { attempt -> store.database.learningProgressDao().record(LearningReviewEvent("large-$index-$attempt", "测试$index", english, now - 1000 + attempt, learningDay(now), RecallRating.FORGOTTEN.name, "review")) }
            }
        }
        ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "plan")).use { scenario ->
            touch(scenario, R.string.learning_weak_words)
            fun lastWordVisible(): Boolean {
                var found = false
                scenario.onActivity { a -> found = views(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "by · 测试50" } }
                return found
            }
            assertFalse(lastWordVisible())
            touch(scenario, R.string.learning_weak_next)
            waitFor { has(scenario, R.string.learning_weak_previous) }
            assertTrue(lastWordVisible())
            scenario.recreate()
            waitFor { has(scenario, R.string.learning_weak_previous) }
            assertTrue(lastWordVisible())
            touch(scenario, R.string.learning_practice_start)
            waitFor { has(scenario, R.string.words_reveal) }
            assertEquals(10, runBlocking { store.learning.practice.session()!!.total })
            assertEquals("aa", runBlocking { store.learning.practice.session()!!.cards.first().english })
        }
    }

    @Test fun weaknessPracticeUsesTouchDraftRecreationFeedbackCompletionAndUndo() {
        boot()
        val now = System.currentTimeMillis()
        runBlocking {
            store.learning.saveMeaning("不同寻常的；令人惊叹的；出乎意料的", "extraordinary", null, "offline", learning = true, now = 1)
            store.learning.saveSettings(true, 5, 10, false)
            store.learning.startSession(true)
            repeat(2) { i -> store.database.learningProgressDao().record(LearningReviewEvent("weak-$i", "不同寻常的；令人惊叹的；出乎意料的", "extraordinary", now - 1000 + i, learningDay(now), RecallRating.FORGOTTEN.name, "review", mode = ReviewMode.SPELLING.name, spellingOutcome = SpellingOutcome.WRONG.name)) }
        }
        val backup = LearningBackupStore(store, File(context.cacheDir, "unused-rollback"))
        val before = runBlocking { backup.snapshot(now) }
        ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "plan")).use { scenario ->
            touch(scenario, R.string.learning_weak_words)
            waitFor { has(scenario, R.string.learning_often_misspelled) }
            capture(scenario, "weak-words")
            touch(scenario, R.string.learning_practice_start, 1)
            waitFor { has(scenario, R.string.study_spelling_check) }
            scenario.onActivity { a ->
                assertFalse(views(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "extraordinary" })
                views(a.window.decorView).filterIsInstance<EditText>().first().setText("extrordinarxy")
            }
            waitFor { runBlocking { store.learning.practice.session()?.spellingDraft == "extrordinarxy" } }
            scenario.recreate()
            waitFor { has(scenario, R.string.study_spelling_check) }
            scenario.onActivity { a -> assertEquals("extrordinarxy", views(a.window.decorView).filterIsInstance<EditText>().first().text.toString()) }
            touch(scenario, R.string.study_spelling_check)
            waitFor { has(scenario, R.string.study_spelling_wrong) }
            capture(scenario, "practice-feedback")
            touch(scenario, R.string.words_uncertain)
            waitFor { has(scenario, R.string.learning_practice_misses) }
            capture(scenario, "practice-complete")
            scenario.recreate()
            waitFor { has(scenario, R.string.learning_practice_misses) }
            touch(scenario, R.string.words_undo)
            waitFor { has(scenario, R.string.study_spelling_wrong) }
            val after = runBlocking { backup.snapshot(now) }
            assertEquals(before.words, after.words)
            assertEquals(before.settings, after.settings)
            assertEquals(before.days, after.days)
            assertEquals(before.tasks, after.tasks)
            assertEquals(before.events, after.events)
        }
    }
}
