package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.footprints.SentenceBookActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SentenceSheetUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun views(v: View): Sequence<View> = sequence { yield(v); if (v is ViewGroup) repeat(v.childCount) { yieldAll(views(v.getChildAt(it))) } }
    private fun waitFor(check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(80)
        assertTrue(check()); instrument.waitForIdleSync()
    }
    private fun sheet(): View? = WindowInspector.getGlobalWindowViews().asSequence().flatMap { views(it) }.firstOrNull { it.tag == "sentence-sheet" && it.isShown }
    private fun capture(name: String) {
        waitFor {
            var ready = false
            instrument.runOnMainSync {
                val metrics = context.resources.displayMetrics
                val expected = minOf(metrics.widthPixels - (24 * metrics.density).toInt(), (560 * metrics.density).toInt())
                ready = sheet()?.let { it.width >= expected - 2 && it.rootView.width >= expected - 2 && !it.isLayoutRequested } == true
            }
            ready
        }
        instrument.runOnMainSync {
            val body = sheet()!!
            val root = body.rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(context.getExternalFilesDir(null), "sentence-sheet/$name.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            views(body).filterIsInstance<TextView>().forEach { v ->
                v.layout?.let { assertTrue("Clipped sheet text", it.height <= v.height - v.totalPaddingTop - v.totalPaddingBottom) }
            }
        }
    }
    private fun clickSheet(id: Int) {
        lateinit var target: TextView
        instrument.runOnMainSync {
            target = views(sheet()!!).filterIsInstance<TextView>().first { it.text.toString() == context.getString(id) }
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            val visible = Rect(); assertTrue(target.getGlobalVisibleRect(visible)); assertEquals(target.height, visible.height())
            assertTrue(target.performClick())
        }
    }
    @Test fun consentPersistsAndCancelDoesNotDelete() {
        assertTrue(context.packageName.endsWith(".regression"))
        val store = InputFootprints.store.sentences
        val mode = AppPrefs.defaultInstance().advanced.uiMode
        val original = mode.getValue()
        mode.setValue(if (InstrumentationRegistry.getArguments().getString("dark") == "true") AppPrefs.Advanced.UiMode.DARK else AppPrefs.Advanced.UiMode.LIGHT)
        runBlocking { store.clear(true); store.setAutomatic(false) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("am start -W -a android.intent.action.RUN -n ${context.packageName}/com.osfans.trime.ui.main.MainActivity")).use { it.readBytes() }
        try {
            ActivityScenario.launch<SentenceBookActivity>(Intent(context, SentenceBookActivity::class.java)).use { s ->
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<SwitchCompat>().any { v -> v.isEnabled } }; ready }
                s.onActivity { views(it.window.decorView).filterIsInstance<SwitchCompat>().first().performClick() }
                capture("consent")
                clickSheet(R.string.sentences_not_now)
                assertFalse(runBlocking { store.automatic() })
                s.onActivity { views(it.window.decorView).filterIsInstance<SwitchCompat>().first().performClick() }
                clickSheet(R.string.sentences_enable)
                waitFor { runBlocking { store.automatic() } }
                s.recreate()
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<SwitchCompat>().any { v -> v.isEnabled && v.isChecked } }; ready }
                runBlocking { assertTrue(store.save("一起学习吧", "Let's learn together.", "test", false)) }
                waitFor { var ready = false; s.onActivity { ready = views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text == "Let's learn together." } }; ready }
                s.onActivity { (views(it.window.decorView).filterIsInstance<TextView>().first { v -> v.text == "Let's learn together." }.parent as View).performClick() }
                capture("detail")
                clickSheet(R.string.delete)
                capture("delete")
                clickSheet(android.R.string.cancel)
                s.onActivity { assertTrue(views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text == "Let's learn together." }) }
            }
        } finally { runBlocking { store.clear(true); store.setAutomatic(false) }; mode.setValue(original) }
    }
}
