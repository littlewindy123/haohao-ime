// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.ime.candidates.bilingual.candidateSourceRowHeight
import com.osfans.trime.ime.core.AutoScaleTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import splitties.dimensions.dp

@RunWith(AndroidJUnit4::class)
class CandidateReadabilityTest {
    @Test
    fun longChineseKeepsItsTypeSizeIncludingAfterARecycledCellChangesMode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = ApplicationProvider.getApplicationContext<Context>()
            for (scale in listOf(1f, 1.3f, 2f)) {
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = scale })
                val height = context.dp(candidateSourceRowHeight(40, 20f, scale))
                val text = AutoScaleTextView(context).apply {
                    textSize = 20f
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                    this.text = "你好今天我们一起去公园散步然后回家吃饭"
                    scaleMode = AutoScaleTextView.Mode.Proportional
                }
                text.measure(View.MeasureSpec.makeMeasureSpec(context.dp(200), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                text.layout(0, 0, text.measuredWidth, text.measuredHeight)
                assertTrue(text.textScaleX < 1f)
                text.scaleMode = AutoScaleTextView.Mode.None
                val bitmap = Bitmap.createBitmap(text.width, text.height, Bitmap.Config.ARGB_8888)
                try {
                    text.draw(Canvas(bitmap))
                } finally {
                    bitmap.recycle()
                }
                assertEquals(1f, text.textScaleX, 0.001f)
                assertTrue(text.paint.fontMetrics.descent - text.paint.fontMetrics.ascent <= height)
            }
        }
    }
}
