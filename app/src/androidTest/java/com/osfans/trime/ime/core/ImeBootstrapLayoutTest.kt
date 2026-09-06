// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeBootstrapLayoutTest {
    @Test
    fun placeholderStaysAtBottomAndDoesNotConsumeTouchesAboveIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = ImeBootstrapLayout(context)
            assertNull(root.background)
            for ((width, height) in listOf(1200 to 2550, 2550 to 1080, 800 to 700, 1200 to 2550)) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, width, height)
                assertTrue(root.panel.height > 0)
                assertTrue(root.panel.height <= height / 2)
                assertEquals(height, root.panel.bottom)
                assertTrue(root.panel.top >= height / 2)
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
                try {
                    assertFalse(root.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
        }
    }

    @Test
    fun navigationAreaRemainsBelowThePanel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = ImeBootstrapLayout(ApplicationProvider.getApplicationContext<Context>())
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 72))
                .build()
            root.dispatchApplyWindowInsets(requireNotNull(insets.toWindowInsets()))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2550, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1200, 2550)
            assertEquals(72, (root.panel.layoutParams as FrameLayout.LayoutParams).bottomMargin)
            assertEquals(2478, root.panel.bottom)
        }
    }
}
