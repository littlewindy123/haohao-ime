/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
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
                for (scale in listOf(1f, 1.3f)) {
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
                        val home = HaoHaoHomeView(context, { destination = it }, { tried = true }, {}, { true }, "haohao_apricot")
                        home.measure(View.MeasureSpec.makeMeasureSpec(context.dp(width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(context.dp(config.screenHeightDp), View.MeasureSpec.EXACTLY))
                        home.layout(0, 0, home.measuredWidth, home.measuredHeight)
                        val labels = descendants(home).filterIsInstance<TextView>().toList()
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

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
