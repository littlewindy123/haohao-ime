/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.regression

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.compact.toCompactCandidateItems
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HaoHaoPinyinRegressionTest {
    @Test(timeout = 120_000L)
    fun conservativeCorrectionRecoversFinalSyllableWithoutChangingExactLeaders() = runBlocking {
        prepareRegressionStorage()
        val preference = AppPrefs.defaultInstance().pinyin.smartCorrection
        val previous = preference.getValue()
        val name = "$SESSION_NAME-correction"
        val session = RimeDaemon.createSession(name)
        try {
            session.runOnReady {
                for ((schema, exact, typos) in listOf(
                    Triple(SCHEMA_ID, "nihao", listOf("nihaoo", "nihap", "niha")),
                    Triple("haohao_pinyin_9", "64426", listOf("644266", "64436", "6442")),
                )) {
                    clearComposition()
                    selectSchema(schema)
                    setRuntimeOption("ascii_mode", false)
                    setRuntimeOption("_haohao_no_personalized_learning", true)
                    preference.setValue(false)
                    exact.forEach { processKey(it.code) }
                    val original = getCandidates(0, 4).map { it.text }
                    clearComposition()
                    preference.setValue(true)
                    exact.forEach { processKey(it.code) }
                    assertEquals("Exact candidates changed for $schema", original, getCandidates(0, 4).map { it.text })
                    for (typo in typos) {
                        clearComposition()
                        typo.forEach { processKey(it.code) }
                        val candidates = getCandidates(0, 128).map { it.text }
                        assertTrue("$schema $typo missing correction: $candidates", "你好" in candidates)
                        assertEquals("Correction must not rewrite typed input", typo, getRawInput())
                    }
                }
            }
        } finally {
            preference.setValue(previous)
            session.runOnReady {
                clearComposition()
                selectSchema(SCHEMA_ID)
                setRuntimeOption("_haohao_no_personalized_learning", false)
            }
            RimeDaemon.destroySession(name)
        }
    }

    @Test(timeout = 120_000L)
    fun nineKeyDecodesContinuousPinyinAndSeparators() = runBlocking<Unit> {
        prepareRegressionStorage()
        val name = "$SESSION_NAME-nine-decoding"
        val session = RimeDaemon.createSession(name)
        try {
            session.runOnReady {
                clearComposition()
                assertTrue(selectSchema("haohao_pinyin_9"))
                assertEquals("haohao_pinyin_9", selectedSchemaId())
                setRuntimeOption("ascii_mode", false)
                for ((input, expected) in listOf("64426" to "你好", "962464" to "我爱你", "9426" to "先", "94'26" to "西安")) {
                    clearComposition()
                    input.forEach { assertTrue("Unhandled digit: $it", processKey(it.code)) }
                    val candidates = getCandidates(0, 128).map { it.text }
                    assertTrue("$input missing $expected: $candidates", expected in candidates)
                }
                clearComposition()
                "64426".forEach { processKey(it.code) }
                assertEquals("64426", getNineKeyInput())
                assertTrue(filterNineKeySyllable("ni", "64426"))
                assertEquals("ni'426", getRawInput())
                assertTrue(!filterNineKeySyllable("mi", "64426")) // stale tap cannot edit a newer composition
                assertEquals("426", getNineKeyInput())
                assertTrue(filterNineKeySyllable("hao", "426"))
                assertTrue(getCandidates(0, 16).any { it.text == "你好" })
                processKey(0xff08)
                assertEquals("ni'ha", getRawInput())
                repeat(5) { processKey(0xff08) }
                assertEquals("", getRawInput())
                clearComposition()
                selectSchema(SCHEMA_ID)
            }
        } finally {
            session.runOnReady {
                clearComposition()
                selectSchema(SCHEMA_ID)
            }
            RimeDaemon.destroySession(name)
        }
    }

    @Test(timeout = 120_000L)
    fun nineKeyFilteringPreservesPartialSelectionAndDefersLayoutSwitch() = runBlocking {
        prepareRegressionStorage()
        val name = "$SESSION_NAME-nine-selection"
        val session = RimeDaemon.createSession(name)
        try {
            session.runOnReady {
                clearComposition()
                selectSchema("haohao_pinyin_9")
                setRuntimeOption("ascii_mode", false)
                "64426".forEach { processKey(it.code) }
                filterNineKeySyllable("ni", "64426")
                val partial = getCandidates(0, 512).indexOfFirst { it.text == "你" }
                assertTrue("Missing partial candidate", partial >= 0)
                assertTrue(selectCandidate(partial, true))
                assertEquals("426", getNineKeyInput())
                assertTrue(filterNineKeySyllable("hao", "426"))
                assertTrue("Selected prefix lost", compositionCached.commitTextPreview?.startsWith("你") == true)
                val raw = getRawInput()
                setRuntimeOption("_haohao_no_personalized_learning", true)
                selectSchema(SCHEMA_ID)
                assertEquals("haohao_pinyin_9", selectedSchemaId())
                assertEquals(raw, getRawInput())
                coroutineScope {
                    val commit = async(start = CoroutineStart.UNDISPATCHED) {
                        commitFlow.first { !it.commit.text.isNullOrEmpty() }.commit.text
                    }
                    assertTrue(selectCandidate(0, true))
                    assertEquals("你好", withTimeout(2_000) { commit.await() })
                }
                assertEquals("", getRawInput())
                assertEquals(SCHEMA_ID, selectedSchemaId())
                assertTrue(getRuntimeOption("_haohao_no_personalized_learning"))
                "nihao".forEach { processKey(it.code) }
                assertTrue(getCandidates(0, 16).any { it.text == "你好" })
                selectSchema("haohao_pinyin_9")
                clearComposition()
                assertEquals("haohao_pinyin_9", selectedSchemaId())
                setRuntimeOption("ascii_mode", true)
                assertEquals("", getNineKeyInput())
                setRuntimeOption("ascii_mode", false)
                "64426".forEach { processKey(it.code) }
                assertTrue(getCandidates(0, 16).any { it.text == "你好" })
            }
        } finally {
            session.runOnReady {
                clearComposition()
                selectSchema(SCHEMA_ID)
                setRuntimeOption("_haohao_no_personalized_learning", false)
            }
            RimeDaemon.destroySession(name)
        }
    }

    @Test(timeout = 120_000L)
    fun rapidlyReplacingTheLastClientKeepsTheNewSessionUsable() = runBlocking {
        repeat(10) { index ->
            val name = "$SESSION_NAME-replace-$index"
            val session = RimeDaemon.createSession(name)
            try {
                awaitAndSelectSchema(session)
                session.runOnReady {
                    setRuntimeOption("ascii_mode", false)
                    clearComposition()
                    "nihao".forEach { processKey(it.code) }
                    assertTrue(getCandidates(0, 16).any { it.text == "你好" })
                    clearComposition()
                }
            } finally {
                RimeDaemon.destroySession(name)
            }
        }
    }

    @Test(timeout = 900_000L)
    fun expectedWordsStayVisibleInTheCompactCandidates() = runBlocking {
        val userDataDir = prepareRegressionStorage()
        val cases = readCases()
        val firstFailures = runRegressionPass("first", cases)
        val compiledTable = userDataDir.resolve(COMPILED_TABLE_PATH).takeIf { it.isFile }
            ?: DataManager.sharedDataDir.resolve(COMPILED_TABLE_PATH)
        assertTrue("Missing compiled dictionary: $compiledTable", compiledTable.isFile)
        val compiledTableModifiedAt = compiledTable.lastModified()

        val secondFailures = runRegressionPass("second", cases)
        assertEquals(
            "Regression results changed between identical runs",
            firstFailures,
            secondFailures,
        )
        assertEquals(
            "The second Rime startup unexpectedly rebuilt the dictionary",
            compiledTableModifiedAt,
            compiledTable.lastModified(),
        )
        assertTrue(firstFailures.joinToString(separator = "\n"), firstFailures.isEmpty())
    }

    private suspend fun runRegressionPass(
        passName: String,
        cases: List<RegressionCase>,
    ): List<String> {
        val sessionName = "$SESSION_NAME-$passName"
        val session = RimeDaemon.createSession(sessionName)
        try {
            awaitAndSelectSchema(session)
            return session.runOnReady {
                check(selectedSchemaId() == SCHEMA_ID) { "Unable to select $SCHEMA_ID" }
                setRuntimeOption("ascii_mode", false)
                setCandidatePagingMode(false)

                val failures = mutableListOf<String>()
                cases.forEach { case ->
                    clearComposition()
                    case.pinyin.filterNot(Char::isWhitespace).forEach { processKey(it.code) }

                    val rawCandidates = getCandidates(0, RAW_CANDIDATE_LIMIT)
                    val compactCandidates =
                        rawCandidates
                            .toCompactCandidateItems(
                                maxCount = COMPACT_CANDIDATE_LIMIT,
                                preedit = compositionCached.preedit,
                            ).map { it.candidate.text }
                    val actualRank = compactCandidates.indexOf(case.text) + 1
                    if (actualRank == 0 || actualRank > case.maxRank) {
                        failures +=
                            "${case.pinyin} -> ${case.text} expected <= ${case.maxRank}, " +
                            "compact=$compactCandidates, raw=${rawCandidates.map { it.text }}"
                    }
                }

                clearComposition()
                failures
            }
        } finally {
            RimeDaemon.destroySession(sessionName)
        }
    }

    private fun prepareRegressionStorage(): File {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        check(targetContext.packageName.endsWith(".regression")) {
            "Refusing to run against ${targetContext.packageName}"
        }
        return DataManager.userDataDir.also { it.mkdirs() }
    }

    private suspend fun awaitAndSelectSchema(session: RimeSession) {
        withTimeout(DEPLOY_TIMEOUT_MS) {
            while (true) {
                val schemaSelected = session.runOnReady {
                    selectedSchemaId() == SCHEMA_ID || selectSchema(SCHEMA_ID)
                }
                if (schemaSelected) return@withTimeout
                delay(SCHEMA_POLL_INTERVAL_MS)
            }
        }
    }

    private fun readCases(): List<RegressionCase> = InstrumentationRegistry
        .getInstrumentation()
        .context.assets
        .open(CORPUS_ASSET)
        .bufferedReader(Charsets.UTF_8)
        .useLines { lines ->
            lines.mapNotNull { rawLine ->
                val line = rawLine.removeSuffix("\r")
                if (line.isBlank() || line.startsWith('#')) return@mapNotNull null
                val columns = line.split('\t')
                require(columns.size == 4) { "Malformed regression row: $line" }
                RegressionCase(columns[0], columns[1], columns[2].toInt())
            }.toList()
        }

    private data class RegressionCase(
        val pinyin: String,
        val text: String,
        val maxRank: Int,
    )

    private companion object {
        const val SESSION_NAME = "haohao-pinyin-regression"
        const val SCHEMA_ID = "luna_pinyin_simp"
        const val CORPUS_ASSET = "haohao_pinyin.tsv"
        const val RAW_CANDIDATE_LIMIT = 16
        const val COMPACT_CANDIDATE_LIMIT = 4
        const val COMPILED_TABLE_PATH = "build/haohao_pinyin.table.bin"
        const val DEPLOY_TIMEOUT_MS = 600_000L
        const val SCHEMA_POLL_INTERVAL_MS = 250L
    }
}
