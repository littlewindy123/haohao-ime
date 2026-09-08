/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.regression

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentenceCorrectionRegressionTest {
    @Test(timeout = 180_000)
    fun rapidSentenceTypingAndDeletionKeepEveryKey() = runBlocking {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".regression"))
        val session = RimeDaemon.createSession("rapid-sentence-deletion")
        try {
            session.runOnReady {
                selectSchema("luna_pinyin_simp")
                setRuntimeOption("ascii_mode", false)
                setRuntimeOption("_haohao_no_personalized_learning", true)
                for (input in listOf("woaini", "womenmingtianwanshangyiqiquchifan")) {
                    clearComposition()
                    input.forEach { processKeyDeferred(it.code) }
                    refreshPresentation()
                    assertEquals(input, getRawInput())
                    val times = mutableListOf<Long>()
                    repeat(input.length) { index ->
                        val start = System.nanoTime()
                        processKeyDeferred(0xff08)
                        times += (System.nanoTime() - start) / 1_000_000
                        assertEquals(input.dropLast(index + 1), getRawInput())
                    }
                    refreshPresentation()
                    assertEquals("", getRawInput())
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("stream", "\nDELETE chars=${input.length} totalMs=${times.sum()} maxMs=${times.maxOrNull()}\n")
                    })
                }
            }
        } finally {
            session.runOnReady {
                clearComposition()
                setRuntimeOption("_haohao_no_personalized_learning", false)
            }
            RimeDaemon.destroySession("rapid-sentence-deletion")
        }
    }

    @Test(timeout = 180_000)
    fun fixedSentenceDiagnostics() = runBlocking {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".regression"))
        val prefs = AppPrefs.defaultInstance().pinyin.smartCorrection
        val previous = prefs.getValue()
        val session = RimeDaemon.createSession("sentence-correction-diagnostics")
        try {
            session.runOnReady {
                selectSchema("luna_pinyin_simp")
                setRuntimeOption("ascii_mode", false)
                setRuntimeOption("_haohao_no_personalized_learning", true)
                for (enabled in listOf(false, true)) {
                    prefs.setValue(enabled)
                    for (input in listOf(
                        "nihao", "nihap", "woaini",
                        "wojintianxiangqugongyuansanbu",
                        "wojintianxiamgqugongyuansanbu",
                        "wojintianxingqugongyuansanbu",
                        "wojintiianxiangqugongyuansanbu",
                        "wojintainxiangqugongyuansanbu",
                        "womenmingtianwanshangyiqiquchifan",
                        "womemingtianwanshangyiqiquchifan",
                    )) {
                        clearComposition()
                        val started = System.nanoTime()
                        val keyTimes = input.map {
                            val keyStart = System.nanoTime()
                            processKey(it.code)
                            (System.nanoTime() - keyStart) / 1_000_000
                        }.sorted()
                        val candidates = getCandidates(0, 12).map { it.text }
                        assertEquals(input, getRawInput())
                        InstrumentationRegistry.getInstrumentation().sendStatus(
                            0,
                            Bundle().apply {
                                putString("stream", "\nCORRECTION $enabled $input ${((System.nanoTime() - started) / 1_000_000)}ms p95=${keyTimes[(keyTimes.size - 1) * 95 / 100]}ms max=${keyTimes.last()}ms $candidates\n")
                            },
                        )
                    }
                }
            }
        } finally {
            prefs.setValue(previous)
            session.runOnReady {
                clearComposition()
                setRuntimeOption("_haohao_no_personalized_learning", false)
            }
            RimeDaemon.destroySession("sentence-correction-diagnostics")
        }
    }

    @Test(timeout = 180_000)
    fun sentenceCorrectionsPreserveInputCommitAndExactLeaders() = runBlocking {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".regression"))
        val pref = AppPrefs.defaultInstance().pinyin.smartCorrection
        val previous = pref.getValue()
        val name = "sentence-correction-assertions"
        val session = RimeDaemon.createSession(name)
        try {
            session.runOnReady {
                selectSchema("luna_pinyin_simp")
                setRuntimeOption("ascii_mode", false)
                setRuntimeOption("_haohao_no_personalized_learning", true)
                for (input in listOf("nihao", "woaini", "wojintianxiangqugongyuansanbu", "womenmingtianwanshangyiqiquchifan", "xi'an", "bj", "wojintianxingqugongyuansanbu", "wojintiaxiangqugongyuansanbu")) {
                    pref.setValue(false)
                    clearComposition()
                    input.forEach { processKey(it.code) }
                    val original = getCandidates(0, 4).map { it.text }
                    pref.setValue(true)
                    clearComposition()
                    input.forEach { processKey(it.code) }
                    assertEquals("Exact/explicit input changed: $input", original, getCandidates(0, 4).map { it.text })
                }
                for (input in listOf("wojintianxiamgqugongyuansanbu", "wojintiianxiangqugongyuansanbu", "wojintainxiangqugongyuansanbu", "wojintianxiagqugongyuansanbu")) {
                    clearComposition()
                    input.forEach { processKey(it.code) }
                    assertEquals(input, getRawInput())
                    assertEquals("Correction missing: $input", "我今天想去公园散步", getCandidates(0, 1).first().text)
                    setCommitSessionId(924L)
                    val committed = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(3000) { commitFlow.first { it.inputSessionId == 924L } }
                    }
                    assertTrue(selectCandidate(0, true))
                    assertEquals("我今天想去公园散步", committed.await().commit.text)
                    assertEquals("", getRawInput())
                }
                clearComposition()
                "wojintiianxiangqugongyuansanbu".forEach { processKey(it.code) }
                val partial = getCandidates(0, 128).indexOfFirst { it.text == "我" }
                assertTrue(partial >= 0)
                assertTrue(selectCandidate(partial, true))
                assertEquals("今天想去公园散步", getCandidates(0, 1).first().text)
                val partialCommit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(3000) { commitFlow.first { it.inputSessionId == 924L } }
                }
                assertTrue(selectCandidate(0, true))
                assertEquals("我今天想去公园散步", partialCommit.await().commit.text)
                setCommitSessionId(0L)
                assertEquals("", getRawInput())
                clearComposition()
                "wojintiianxiangqugongyuansanbu".forEach { processKey(it.code) }
                moveCursorPos(2)
                refreshPresentation()
                val raw = getRawInput()
                pref.setValue(false)
                refreshPresentation()
                val disabled = getCandidates(0, 4).map { it.text }
                pref.setValue(true)
                refreshPresentation()
                assertEquals(raw, getRawInput())
                assertEquals(disabled, getCandidates(0, 4).map { it.text })
                clearComposition()
                "wojintiianxiamgqugongyuansanbu".forEach { processKey(it.code) }
                assertTrue("Two errors must not become a one-edit correction", getCandidates(0, 4).none { it.text == "我今天想去公园散步" })
                val overLimit = "nihao".repeat(20)
                val leaders = mutableListOf<List<String>>()
                for (enabled in listOf(false, true)) {
                    pref.setValue(enabled)
                    clearComposition()
                    overLimit.forEach { processKey(it.code) }
                    assertEquals(overLimit, getRawInput())
                    leaders += getCandidates(0, 4).map { it.text }
                }
                assertEquals("Long input must use the original decoder", leaders[0], leaders[1])
                clearComposition()
                "nihao".forEach { processKey(it.code) }
                processKey(0xff08)
                processKey('o'.code)
                assertEquals("你好", getCandidates(0, 1).first().text)
            }
        } finally {
            pref.setValue(previous)
            session.runOnReady {
                clearComposition()
                setRuntimeOption("_haohao_no_personalized_learning", false)
            }
            RimeDaemon.destroySession(name)
        }
    }

    @Test(timeout = 180_000)
    fun delayedAsciiTipsStaySerializedDuringRepeatedTyping() = runBlocking {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".regression"))
        val prefs = AppPrefs.defaultInstance()
        val oldTips = prefs.general.asciiSwitchTips.getValue()
        val oldCorrection = prefs.pinyin.smartCorrection.getValue()
        val name = "sentence-correction-ascii-tips"
        val session = RimeDaemon.createSession(name)
        try {
            prefs.general.asciiSwitchTips.setValue(true)
            prefs.pinyin.smartCorrection.setValue(true)
            session.runOnReady {
                selectSchema("luna_pinyin_simp")
                setRuntimeOption("_haohao_no_personalized_learning", true)
                repeat(20) {
                    clearComposition()
                    setRuntimeOption("ascii_mode", true)
                    refreshPresentation()
                    setRuntimeOption("ascii_mode", false)
                    refreshPresentation()
                    delay(850)
                    "wojintiianxiangqugongyuansanbu".forEach {
                        processKey(it.code)
                        getCandidates(0, 16)
                    }
                    assertEquals("我今天想去公园散步", getCandidates(0, 1).first().text)
                    repeat(6) {
                        processKey(0xff08)
                        getCandidates(0, 16)
                    }
                }
            }
        } finally {
            prefs.general.asciiSwitchTips.setValue(oldTips)
            prefs.pinyin.smartCorrection.setValue(oldCorrection)
            session.runOnReady {
                clearComposition()
                setRuntimeOption("_haohao_no_personalized_learning", false)
            }
            RimeDaemon.destroySession(name)
        }
    }
}
