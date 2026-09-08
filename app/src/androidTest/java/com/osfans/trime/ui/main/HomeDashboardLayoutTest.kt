/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import splitties.dimensions.dp
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeDashboardLayoutTest {
    @Test
    fun homeHasReachableConsumerActionsAtNarrowLargeTypeAndLandscapeSizes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = ApplicationProvider.getApplicationContext<Context>()
            for (night in listOf(false, true)) {
                for (scale in listOf(1f, 1.3f, 2f)) {
                    for (width in listOf(320, 369, 800)) {
                        val config = Configuration(base.resources.configuration).apply {
                            fontScale = scale
                            screenWidthDp = width
                            screenHeightDp = if (width == 800) 360 else 820
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        val context = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_TrimeAppTheme)
                        var destination: NavigationRoute? = null
                        var tried = false
                        var style = AppPrefs.Keyboard.KeycapStyle.CLASSIC
                        var palette = "haohao_apricot"
                        val home = HaoHaoHomeView(context, { destination = it }, { tried = true }, {}, {
                            palette = it
                            true
                        }, palette, style, { style = it })
                        home.measure(View.MeasureSpec.makeMeasureSpec(context.dp(width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(context.dp(config.screenHeightDp), View.MeasureSpec.EXACTLY))
                        home.layout(0, 0, home.measuredWidth, home.measuredHeight)
                        val labels = descendants(home).filterIsInstance<TextView>().toList()
                        val raised = labels.filterIsInstance<RadioButton>().single { it.text.toString() == context.getString(R.string.keycap_style_raised) }
                        val classic = labels.filterIsInstance<RadioButton>().single { it.text.toString() == context.getString(R.string.keycap_style_classic) }
                        raised.performClick()
                        assertEquals(AppPrefs.Keyboard.KeycapStyle.RAISED, style)
                        assertTrue(raised.isChecked)
                        assertFalse(classic.isChecked)
                        assertEquals("haohao_apricot", palette)
                        val mist = labels.single { it.text.toString() == context.getString(R.string.home_mist) }
                        (mist.parent as View).performClick()
                        assertEquals("haohao_mist", palette)
                        assertEquals(AppPrefs.Keyboard.KeycapStyle.RAISED, style)
                        classic.performClick()
                        assertEquals(AppPrefs.Keyboard.KeycapStyle.CLASSIC, style)
                        assertFalse(raised.isChecked)
                        assertFalse(labels.any { it.text.toString().contains("输入引擎") || it.text.toString().contains("诊断") })
                        val tryButton = labels.single { it.text.toString() == context.getString(R.string.home_try) }
                        assertTrue(tryButton.height >= context.dp(48))
                        tryButton.performClick()
                        assertTrue(tried)
                        val input = labels.single { it.text.toString() == context.getString(R.string.home_input_preferences) }
                        (input.parent as View).performClick()
                        assertEquals(NavigationRoute.InputPreferences, destination)
                        for (label in labels) {
                            assertTrue("Unmeasured label: ${label.text}", label.width > 0 && label.height > 0)
                            assertTrue("Clipped text: ${label.text}", label.layout.height <= label.height - label.paddingTop - label.paddingBottom)
                        }
                        if (width == 369 && scale == 1f) {
                            val bitmap = Bitmap.createBitmap(home.width, home.height, Bitmap.Config.ARGB_8888)
                            home.draw(Canvas(bitmap))
                            File(base.cacheDir, if (night) "home022-dark.png" else "home022-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun toolbarEditorFitsLargeTypeAndPersistsSelectionWithoutNestedDialogs() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = ApplicationProvider.getApplicationContext<Context>()
            for (night in listOf(false, true)) {
                for (scale in listOf(1f, 1.3f, 2f)) {
                    for (width in listOf(320, 369, 800)) {
                        val config = Configuration(base.resources.configuration).apply {
                            fontScale = scale
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        val context = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_TrimeAppTheme)
                        var saved = ""
                        val labels = listOf("剪贴板", "翻译", "键盘设置", "常用短语", "文本编辑", "我的单词", "表情")
                        val actions = com.osfans.trime.data.theme.model.HAOHAO_TOOLBAR_ACTIONS
                        val editor = com.osfans.trime.ime.haohao.ToolbarEditorView(context, saved, { labels[actions.indexOf(it)] }, { saved = it })
                        fun measure() {
                            editor.measure(View.MeasureSpec.makeMeasureSpec(context.dp(width - 48), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            editor.layout(0, 0, editor.measuredWidth, editor.measuredHeight)
                            for (label in descendants(editor).filterIsInstance<TextView>()) {
                                assertTrue("Clipped $width/$scale/${label.text}", label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                                for (line in 0 until label.layout.lineCount) {
                                    assertEquals("Ellipsized ${label.text}", 0, label.layout.getEllipsisCount(line))
                                    assertTrue("Too wide ${label.text}", label.layout.getLineWidth(line) <= label.width - label.compoundPaddingLeft - label.compoundPaddingRight + 1)
                                }
                            }
                        }
                        fun check(label: String) = descendants(editor).filterIsInstance<android.widget.CheckBox>().single { it.text.toString() == label }.performClick()
                        measure()
                        check(labels[3])
                        assertEquals("", saved)
                        check(labels[0])
                        assertEquals("v2:HaoHaoTranslation,HaoHaoKeyboardMenu", saved)
                        check(labels[3])
                        assertEquals("v2:HaoHaoTranslation,HaoHaoKeyboardMenu,HaoHaoPhrases", saved)
                        measure()
                        descendants(editor).single { it.contentDescription == context.getString(R.string.product_move_before, labels[3]) }.performClick()
                        assertEquals("v2:HaoHaoTranslation,HaoHaoPhrases,HaoHaoKeyboardMenu", saved)
                        for (label in listOf(labels[1], labels[3], labels[2])) check(label)
                        assertEquals("v2:", saved)
                        editor.reset()
                        assertEquals("", saved)
                        measure()
                    }
                }
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
