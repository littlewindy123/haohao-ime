// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.data.translation.SentenceCandidateState
import com.osfans.trime.data.translation.SentenceCandidateStatus
import com.osfans.trime.ime.candidates.bilingual.bilingualTranslationLineHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import splitties.dimensions.dp

@RunWith(AndroidJUnit4::class)
class SentenceTranslationStripTest {
    @Test
    fun fastHideAndRevealReuseWordViewsWithoutKeepingOldActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val strip = SentenceTranslationStrip(context, 13f, Color.BLACK, Typeface.DEFAULT, 4, {}, {})
            val cells = List(4) { SentenceTranslationCell(240, "hello") }
            strip.bind(SentenceCandidateState("你好", "hello", SentenceCandidateStatus.READY), cells, true)
            val words = strip.root.getChildAt(0) as LinearLayout
            val original = (0 until words.childCount).map(words::getChildAt)
            repeat(100) {
                strip.bind(SentenceCandidateState(), emptyList(), false)
                assertEquals(View.INVISIBLE, words.visibility)
                strip.bind(SentenceCandidateState("好", "good", SentenceCandidateStatus.READY), cells, true)
                assertEquals(original, (0 until words.childCount).map(words::getChildAt))
            }
            strip.bind(SentenceCandidateState("好", status = SentenceCandidateStatus.WAITING), cells, true)
            assertTrue(!words.getChildAt(0).performLongClick())
        }
    }

    @Test
    fun phraseUsesWholeLaneAndChineseGeometryNeverMovesWhenTranslationArrives() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = ApplicationProvider.getApplicationContext<Context>()
            for (scale in listOf(1f, 1.5f, 2f)) {
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = scale })
                for (widthDp in listOf(240, 320, 700)) {
                    val width = context.dp(widthDp)
                    val height = context.dp(2 * bilingualTranslationLineHeight(13f * scale, 0))
                    val strip = SentenceTranslationStrip(context, 13f, Color.BLACK, Typeface.DEFAULT, context.dp(4), {}, {})
                    val chinese = View(context)
                    val wrapper = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(chinese, LinearLayout.LayoutParams(-1, context.dp(36)))
                        addView(strip.root, LinearLayout.LayoutParams(-1, height))
                    }
                    fun measure() {
                        wrapper.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        wrapper.layout(0, 0, width, wrapper.measuredHeight)
                    }
                    val cells = List(4) { SentenceTranslationCell(width / 4, "I") }
                    strip.bind(SentenceCandidateState("我爱你", status = SentenceCandidateStatus.WAITING), cells, true)
                    measure()
                    assertEquals("", strip.sentence.text.toString())
                    assertEquals(View.INVISIBLE, strip.action.visibility)
                    strip.bind(SentenceCandidateState("我爱你", status = SentenceCandidateStatus.TRANSLATING), cells, true)
                    measure()
                    assertEquals("", strip.sentence.text.toString())
                    assertEquals(View.INVISIBLE, strip.action.visibility)
                    val total = wrapper.height
                    val chineseBottom = chinese.bottom
                    strip.bind(SentenceCandidateState("我爱你", "I love you", SentenceCandidateStatus.READY), cells, true)
                    measure()
                    assertEquals("I love you", strip.sentence.text.toString())
                    assertEquals(View.VISIBLE, strip.root.getChildAt(1).visibility)
                    assertEquals(total, wrapper.height)
                    assertEquals(chineseBottom, chinese.bottom)
                    assertEquals(height, strip.root.height)
                    val long = "I love you, and I will see you at 8:30 tomorrow. ".repeat(12)
                    strip.bind(SentenceCandidateState("长句子", long, SentenceCandidateStatus.READY), cells, true)
                    measure()
                    assertEquals(long, strip.sentence.text.toString())
                    assertEquals(total, wrapper.height)
                    assertTrue((0 until strip.sentence.layout.lineCount).any { strip.sentence.layout.getEllipsisCount(it) > 0 })
                    strip.bind(SentenceCandidateState(), cells, false)
                    measure()
                    assertEquals("", strip.sentence.text.toString())
                    assertEquals(total, wrapper.height)
                }
            }
        }
    }
}
