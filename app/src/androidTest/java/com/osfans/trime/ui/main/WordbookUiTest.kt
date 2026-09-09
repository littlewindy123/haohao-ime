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
import com.osfans.trime.data.footprints.ImportWord
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
import com.osfans.trime.ui.main.footprints.WordbookActivity
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
class WordbookUiTest {
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
            store.learning.saveSettings(false, 5, 10, false)
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
            assertEquals("Button must be fully reachable: ${target.text}", target.height, rect.height())
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
    private fun dialog(id: Int) = dialogText(context.getString(id))
    private fun dialogText(text: String) {
        lateinit var target: TextView
        waitFor {
            var found: TextView? = null
            instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().flatMap { views(it).filterIsInstance<TextView>().toList() }.lastOrNull { it.isShown && it.text.toString() == text } }
            if (found != null) target = found!!
            found != null
        }
        instrumentation.runOnMainSync { target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        press(target)
    }
    private fun <A : AppCompatActivity> capture(scenario: ActivityScenario<A>, name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        scenario.onActivity { a ->
            assertEquals(InstrumentationRegistry.getArguments().getString("landscape") == "true", a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            assertEquals(InstrumentationRegistry.getArguments().getString("dark") == "true", a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
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
        val file = File(context.getExternalFilesDir(null), "wordbooks/$prefix-$name.png").apply { parentFile!!.mkdirs() }
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

    private fun openImport(id: String): ActivityScenario<WordbookActivity> {
        val scenario = ActivityScenario.launch<WordbookActivity>(Intent(context, WordbookActivity::class.java).putExtra("import", true))
        touch(scenario, R.string.wordbooks_choose_target)
        lateinit var target: TextView
        waitFor {
            var found: TextView? = null
            instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().flatMap { views(it).filterIsInstance<TextView>().toList() }.lastOrNull { it.isShown && it.text.toString() == id } }
            if (found != null) target = found!!
            found != null
        }
        press(target)
        waitFor { has(scenario, R.string.wordbooks_pick_csv) }
        return scenario
    }

    @Test fun csvPreviewCancellationRecreationAndConfirmedImportPreserveExistingProgress() {
        boot()
        val book = runBlocking {
            store.learning.saveMeaning("你好", "hello", null, "offline", favorite = true, learning = true, now = 1)
            store.wordbooks.create("测试词本")
        }
        val before = runBlocking { store.learning.savedWords() }
        val csv = File(context.cacheDir, "words-ui.csv").apply { writeText("english,chinese,phonetic\r\nhello,你好,\r\nextraordinary,\"不同寻常的；令人惊叹的\n超乎预期的；值得关注的\",\r\nhello,你好,\r\n123,无效,\r\n", Charsets.UTF_8) }
        openImport(book.name).use { s ->
            val m = monitor(Intent.ACTION_OPEN_DOCUMENT, csv)
            try {
                touch(s, R.string.wordbooks_pick_csv)
                waitFor { has(s, R.string.wordbooks_preview) }
            } finally {
                instrumentation.removeMonitor(m)
            }
            assertEquals(before, runBlocking { store.learning.savedWords() })
            s.recreate()
            waitFor { has(s, R.string.wordbooks_preview) }
            capture(s, "import-preview")
            touch(s, R.string.wordbooks_invalid)
            waitFor { has(s, R.string.wordbooks_preview) }
            capture(s, "invalid-rows")
            touch(s, R.string.wordbooks_preview)
            touch(s, R.string.wordbooks_confirm_import)
            dialog(R.string.learning_backup_cancel)
            assertEquals(before, runBlocking { store.learning.savedWords() })
            touch(s, R.string.wordbooks_confirm_import)
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
                dialog(R.string.learning_backup_confirm)
                waitFor { has(s, R.string.wordbooks_busy) }
                s.recreate()
                assertTrue(has(s, R.string.wordbooks_busy))
            } finally {
                release.complete(Unit)
                runBlocking { lock.join() }
            }
            waitFor { has(s, R.string.wordbooks_learn) }
            assertEquals(2, runBlocking { store.wordbooks.dao.counts(book.id, "", 1).total })
            assertEquals(before.single(), runBlocking { store.learning.find("你好", "hello") })
            val imported = runBlocking { store.learning.savedWords().first { it.english == "extraordinary" } }
            assertEquals("import", imported.source)
            assertFalse(imported.learning)
            capture(s, "personal-book")
            touch(s, R.string.wordbooks_select_page)
            touch(s, R.string.wordbooks_learn)
            dialog(R.string.learning_backup_confirm)
            waitFor { runBlocking { store.wordbooks.dao.counts(book.id, "", 1).learning == 2 } }
            touch(s, R.string.wordbooks_delete)
            dialog(R.string.learning_backup_confirm)
            waitFor { has(s, R.string.wordbooks_added) }
            assertEquals(2, runBlocking { store.learning.savedWords().size })
            assertEquals(2, runBlocking { store.wordbooks.dao.counts("", "", 1).total })
        }
        csv.delete()
    }

    @Test fun pastedColumnsErrorsAndInvalidFileNeverImportSilently() {
        boot()
        val book = runBlocking { store.wordbooks.create("粘贴测试") }
        openImport(book.name).use { s ->
            s.onActivity { a -> views(a.window.decorView).filterIsInstance<EditText>().single().setText("世界\tworld\n你好\thello") }
            touch(s, R.string.wordbooks_paste_preview)
            waitFor { has(s, R.string.wordbooks_preview) }
            assertFalse(has(s, R.string.wordbooks_confirm_import))
            touch(s, R.string.wordbooks_order_en)
            waitFor { has(s, R.string.wordbooks_confirm_import) }
            s.recreate()
            waitFor { has(s, R.string.wordbooks_confirm_import) }
            touch(s, R.string.learning_backup_cancel)
            val broken = File(context.cacheDir, "broken-words.csv").apply { writeText("\"broken") }
            val m = monitor(Intent.ACTION_OPEN_DOCUMENT, broken)
            try {
                touch(s, R.string.wordbooks_pick_csv)
                waitFor { has(s, R.string.wordbooks_syntax) }
            } finally {
                instrumentation.removeMonitor(m)
                broken.delete()
            }
            s.recreate()
            assertFalse(has(s, R.string.wordbooks_confirm_import))
            assertTrue(runBlocking { store.learning.savedWords().isEmpty() })
        }
    }

    @Test fun catalogSelectionAndFiftyItemPaginationUseRealTouch() {
        boot()
        ActivityScenario.launch<WordbookActivity>(Intent(context, WordbookActivity::class.java).putExtra("catalog", true)).use { s ->
            waitFor { has(s, R.string.wordbooks_add_catalog) }
            capture(s, "catalog")
            touch(s, R.string.wordbooks_add_catalog, 0)
            waitFor { has(s, R.string.wordbooks_learn_all) }
            assertEquals(1000, runBlocking { store.learning.savedWords().size })
            assertTrue(runBlocking { store.learning.savedWords().none { it.learning } })
            s.onActivity { a -> assertEquals(50, views(a.window.decorView).filterIsInstance<android.widget.CheckBox>().count()) }
            touch(s, R.string.learning_weak_next)
            waitFor { has(s, R.string.learning_weak_previous) }
            s.recreate()
            waitFor { has(s, R.string.learning_weak_previous) }
            s.onActivity { a -> views(a.window.decorView).filterIsInstance<EditText>().single().setText("water") }
            touch(s, R.string.wordbooks_search_action)
            waitFor {
                var count = 0
                s.onActivity { count = views(it.window.decorView).filterIsInstance<android.widget.CheckBox>().count() }
                count < 50
            }
            capture(s, "book-search")
            touch(s, R.string.wordbooks_learn_all)
            dialog(R.string.learning_backup_confirm)
            waitFor { runBlocking { store.learning.savedWords().count { it.learning } == 1000 } }
            val navigation = instrumentation.addMonitor(WordLearningActivity::class.java.name, null, false)
            try {
                touch(s, R.string.wordbooks_plan)
                val plan = requireNotNull(instrumentation.waitForMonitorWithTimeout(navigation, 10000))
                dialog(R.string.words_plan_enable)
                dialog(R.string.words_plan_save)
                dialog(R.string.words_plan_start)
                waitFor { runBlocking { store.learning.session()?.cards?.isNotEmpty() == true } }
                assertEquals(5, runBlocking { store.learning.session()!!.total })
                assertTrue(runBlocking { store.learning.progress.events().isEmpty() })
                instrumentation.runOnMainSync { plan.finish() }
            } finally {
                instrumentation.removeMonitor(navigation)
            }
        }
    }

    @Test fun emptyHomeHasClearFirstStepAndAddedBookDoesNotGenerateCheckIn() {
        boot()
        ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "plan")).use { s ->
            waitFor { has(s, R.string.wordbooks_catalog) }
            assertTrue(has(s, R.string.wordbooks_import))
            assertTrue(has(s, R.string.wordbooks_title))
            capture(s, "empty-home")
        }
        assertTrue(runBlocking { store.learning.progress.events().isEmpty() })
    }

    @Test fun createRenameCopyMoveAndDeleteKeepTheSavedWord() {
        boot()
        fun name(text: String) {
            lateinit var input: EditText
            waitFor {
                var found: EditText? = null
                instrumentation.runOnMainSync {
                    found = WindowInspector.getGlobalWindowViews().flatMap { views(it).filterIsInstance<EditText>().toList() }.lastOrNull { it.isShown && it.hint?.toString() == context.getString(R.string.wordbooks_name) }
                }
                if (found != null) input = found!!
                found != null
            }
            instrumentation.runOnMainSync { input.setText(text) }
            dialog(R.string.learning_backup_confirm)
        }
        ActivityScenario.launch<WordbookActivity>(Intent(context, WordbookActivity::class.java)).use { s ->
            touch(s, R.string.wordbooks_create)
            name("工作英语")
            waitFor { has(s, R.string.wordbooks_rename) }
            touch(s, R.string.wordbooks_rename)
            name("日常表达")
            waitFor { runBlocking { store.wordbooks.dao.books().single().name == "日常表达" } }
            val a = runBlocking { store.wordbooks.dao.books().single() }
            val b = runBlocking { store.wordbooks.create("旅行") }
            runBlocking { store.wordbooks.importWords(a.id, listOf(ImportWord("hello", "你好"))) }
            s.recreate()
            waitFor { has(s, R.string.wordbooks_select_page) }
            touch(s, R.string.wordbooks_select_page)
            touch(s, R.string.wordbooks_copy)
            dialogText(b.name)
            waitFor { runBlocking { store.wordbooks.dao.members().size == 2 } }
            touch(s, R.string.wordbooks_select_page)
            touch(s, R.string.wordbooks_move)
            dialogText(b.name)
            waitFor { runBlocking { store.wordbooks.dao.counts(a.id, "", 1).total == 0 } }
            assertEquals(1, runBlocking { store.learning.savedWords().size })
            touch(s, R.string.wordbooks_delete)
            dialog(R.string.learning_backup_confirm)
            waitFor { has(s, R.string.wordbooks_added) }
            assertEquals(1, runBlocking { store.wordbooks.dao.counts(b.id, "", 1).total })
        }
    }
}
