// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import splitties.dimensions.dp

@RunWith(AndroidJUnit4::class)
class TestInputPanelLayoutTest {
    @Test
    fun shortWindowKeepsInputAndTypeControlsInOneReachableRow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = ApplicationProvider.getApplicationContext<Context>()
            for (scale in listOf(1f, 1.3f)) {
                val configured = base.createConfigurationContext(
                    Configuration(base.resources.configuration).apply {
                        orientation = Configuration.ORIENTATION_LANDSCAPE
                        screenHeightDp = 360
                        screenWidthDp = 800
                        fontScale = scale
                    },
                )
                val context = ContextThemeWrapper(configured, R.style.Theme_TrimeAppTheme)
                val panel = TestInputPanel(context)
                panel.measure(View.MeasureSpec.makeMeasureSpec(context.dp(800), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(context.dp(100), View.MeasureSpec.AT_MOST))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val input = descendants(panel).filterIsInstance<EditText>().single()
                assertTrue("Compact panel should leave room above the IME", panel.height <= context.dp(80))
                assertTrue(input.width >= context.dp(300))
                assertTrue(input.height >= context.dp(48))
                assertEquals(1, input.maxLines)
                for (label in listOf(R.string.text, R.string.number, R.string.password)) {
                    val button = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == context.getString(label) }
                    assertTrue(button.height >= context.dp(48))
                    assertTrue(button.width >= context.dp(48))
                }
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
