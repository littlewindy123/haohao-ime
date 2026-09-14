package com.osfans.trime.ui.main

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.ReviewMode
import com.osfans.trime.data.footprints.StudyLexicon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Records only our real, isolated Activity and its own branded confirmation window. */
class WebsiteLearningRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = InputFootprints.store

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }

    private fun awaitUi(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Recording prerequisites did not become ready", check())
        instrumentation.waitForIdleSync()
    }

    private fun foreground() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity",
            ),
        ).use { it.readBytes() }
    }

    /** A known SentenceSheet tag AND the exact owner are required; no other windows are drawn. */
    private fun ownedSheets(activity: Activity): List<View> = WindowInspector.getGlobalWindowViews().filter { decor ->
        val body = decor.findViewWithTag<View>("sentence-sheet")
        decor.isShown && body?.context === activity
    }

    private fun tap(target: TextView) {
        val bounds = Rect()
        val location = IntArray(2)
        instrumentation.runOnMainSync {
            assertTrue("Recording target must be enabled: ${target.text}", target.isEnabled)
            assertTrue("Recording target must own focus: ${target.text}", target.hasWindowFocus())
            assertTrue("Recording target must be visible: ${target.text}", target.getGlobalVisibleRect(bounds))
            assertEquals("Recording must not click a clipped target", target.height, bounds.height())
            assertEquals("Recording must not click a clipped target", target.width, bounds.width())
            target.getLocationOnScreen(location)
        }
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0] + target.width / 2f, location[1] + target.height / 2f, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun tapTag(scenario: ActivityScenario<WordLearningActivity>, tag: String) {
        lateinit var target: TextView
        scenario.onActivity { target = requireNotNull(it.window.decorView.findViewWithTag(tag)) }
        tap(target)
    }

    private fun tapConfirmation(scenario: ActivityScenario<WordLearningActivity>) {
        lateinit var target: TextView
        scenario.onActivity { activity ->
            val sheet = ownedSheets(activity).single()
            target = views(sheet).filterIsInstance<TextView>().single {
                it.isShown && it.isClickable && it.text.toString() == context.getString(android.R.string.ok)
            }
        }
        tap(target)
    }

    /** 100 real frames on a 100 ms schedule. Drawing stays on main; PNG I/O does not. */
    private fun record(scenario: ActivityScenario<WordLearningActivity>, name: String, actions: Map<Int, Pair<String, () -> Unit>>) {
        require(name == "save" || name == "review")
        val directory = File(context.getExternalFilesDir(null), "website-recording/$name").apply { mkdirs() }
        val metadata = File(directory, "recording.json")
        val report = JSONObject().put("status", "recording").put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE).put("package", context.packageName)
            .put("frameIntervalMs", 100).put("frames", 100).put("outputWidth", 390)
            .put("capture", "Activity content View.draw plus only its own SentenceSheet at real window coordinates and configured dim; no system surfaces, compositor window animations or audio")
        metadata.writeText(report.toString(2))
        lateinit var bitmap: Bitmap
        var sourceWidth = 0
        var sourceHeight = 0
        scenario.onActivity { activity ->
            val content = activity.findViewById<View>(android.R.id.content)
            sourceWidth = content.width
            sourceHeight = content.height
            assertTrue(sourceWidth > 0 && sourceHeight > 0)
            bitmap = Bitmap.createBitmap(390, (sourceHeight * 390f / sourceWidth).toInt(), Bitmap.Config.ARGB_8888)
            report.put("sourceWidth", sourceWidth).put("sourceHeight", sourceHeight)
                .put("fontScale", activity.resources.configuration.fontScale).put("densityDpi", activity.resources.displayMetrics.densityDpi)
        }
        val frames = JSONArray()
        val events = JSONArray()
        var recordedConfirmation = false
        val started = SystemClock.elapsedRealtime()
        try {
            repeat(100) { frame ->
                val remaining = started + frame * 100L - SystemClock.elapsedRealtime()
                if (remaining > 0) SystemClock.sleep(remaining)
                actions[frame]?.let { (label, action) ->
                    action()
                    events.put(JSONObject().put("action", label).put("atMs", SystemClock.elapsedRealtime() - started))
                }
                var capturedAt = 0L
                var sheetCount = 0
                scenario.onActivity { activity ->
                    val content = activity.findViewById<View>(android.R.id.content)
                    check(content.width == sourceWidth && content.height == sourceHeight) { "Window resized during recording" }
                    capturedAt = SystemClock.elapsedRealtime() - started
                    bitmap.eraseColor(Color.TRANSPARENT)
                    val canvas = Canvas(bitmap)
                    canvas.scale(390f / sourceWidth, 390f / sourceWidth)
                    content.draw(canvas)
                    val origin = IntArray(2)
                    content.getLocationOnScreen(origin)
                    val sheets = ownedSheets(activity)
                    check(sheets.size <= 1) { "Unexpected additional owned dialog" }
                    sheetCount = sheets.size
                    if (sheets.isNotEmpty()) recordedConfirmation = true
                    sheets.forEach { dialog ->
                        val params = dialog.layoutParams as WindowManager.LayoutParams
                        if (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                            canvas.drawColor(Color.argb((params.dimAmount.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0))
                        }
                        val location = IntArray(2)
                        dialog.getLocationOnScreen(location)
                        val saved = canvas.save()
                        canvas.translate((location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat())
                        dialog.draw(canvas)
                        canvas.restoreToCount(saved)
                    }
                }
                File(directory, String.format(Locale.ROOT, "%04d.png", frame)).outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                frames.put(JSONObject().put("frame", frame).put("atMs", capturedAt).put("ownedSheets", sheetCount))
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Recording exceeded 12 seconds; inspect frame timing before publishing", elapsed <= 12_000)
            if (name == "save") assertTrue("The real confirmation sheet must appear in the recording", recordedConfirmation)
            report.put("status", "completed").put("elapsedMs", elapsed)
        } catch (failure: Throwable) {
            report.put("status", "failed")
            throw failure
        } finally {
            bitmap.recycle()
            report.put("timestamps", frames).put("actions", events)
            metadata.writeText(report.toString(2))
        }
    }

    @Test fun recordOwnedSaveAndReviewWithoutScoring() {
        check(context.packageName.endsWith(".regression")) { "Never record or mutate the everyday package" }
        val pref = AppPrefs.defaultInstance().advanced.uiMode
        val oldTheme = pref.getValue()
        val oldLocales = AppCompatDelegate.getApplicationLocales()
        val generation = store.learning.generation
        val previous = runBlocking {
            check(store.learning.find("学习", "learn") == null) { "Recording fixture already exists; use a clean isolated test run" }
            check(store.learning.savedWords().none { it.learning }) { "An unrelated learning queue exists" }
            check(store.learning.session() == null)
            check(store.learning.practice.session() == null && store.learning.practice.undoToken() == null)
            check(!store.isFavorite("学习"))
            check(store.learning.progress.dashboard(System.currentTimeMillis()).task == null)
            store.learning.settings()
        }
        val originalEvents = runBlocking { store.learning.progress.events() }
        val entry = requireNotNull(runBlocking { StudyLexicon.lookup(context, "learn") })
        check(entry.examples.isNotEmpty())
        try {
            pref.setValue(AppPrefs.Advanced.UiMode.LIGHT)
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            foreground()
            val detail = Intent(context, WordLearningActivity::class.java).putExtra("words.chinese", "学习")
                .putExtra("words.english", "learn").putExtra("words.phonetic", entry.phonetic).putExtra("words.source", "offline")
            ActivityScenario.launch<WordLearningActivity>(detail).use { scenario ->
                awaitUi {
                    var ready = false
                    scenario.onActivity { a ->
                        ready = a.window.decorView.findViewWithTag<View>("learning.detail.references.ready") != null &&
                            views(a.window.decorView).any { it.isShown && it.tag == "learning.reading.example" } && a.hasWindowFocus()
                    }
                    ready
                }
                record(scenario, "save", mapOf(
                    22 to ("tap favorite" to { tapTag(scenario, "learning.detail.favorite") }),
                    45 to ("confirm saved meaning" to { tapConfirmation(scenario) }),
                ))
                assertTrue(runBlocking { store.learning.find("学习", "learn")?.favorite == true })
                assertEquals(originalEvents, runBlocking { store.learning.progress.events() })
            }
            runBlocking {
                store.learning.saveMeaning("学习", "learn", entry.phonetic, "offline", learning = true, generation = generation)
                store.learning.saveSettings(false, 5, 10, false, ReviewMode.ENGLISH, generation)
            }
            foreground()
            ActivityScenario.launch<WordLearningActivity>(Intent(context, WordLearningActivity::class.java).putExtra("words.mode", "review")).use { scenario ->
                lateinit var reveal: TextView
                awaitUi {
                    var ready = false
                    scenario.onActivity { a ->
                        val tree = views(a.window.decorView).toList()
                        val button = tree.filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == context.getString(R.string.words_reveal) }
                        if (button != null && tree.any { it.tag == "learning.reading.example" } && a.hasWindowFocus()) {
                            reveal = button
                            ready = true
                        }
                    }
                    ready
                }
                record(scenario, "review", mapOf(30 to ("reveal answer without scoring" to { tap(reveal) })))
                assertTrue(runBlocking { store.learning.session()?.answerVisible == true })
                assertEquals(0, runBlocking { store.learning.find("学习", "learn")!!.reviewCount })
                assertEquals(originalEvents, runBlocking { store.learning.progress.events() })
            }
        } finally {
            // Remove only our previously absent fixture and restore the exact original settings.
            // Other wordbooks, sentences, history and recordings are left untouched.
            try {
                runBlocking {
                    if (generation == store.learning.generation) store.database.withTransaction {
                        if (store.learning.find("学习", "learn") != null) {
                            store.learning.saveMeaning("学习", "learn", entry.phonetic, "offline", favorite = false, learning = false, generation = generation)
                        }
                        store.database.wordLearningDao().saveState(previous)
                    }
                }
            } finally {
                pref.setValue(oldTheme)
                instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(oldLocales) }
                instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            }
        }
    }
}
