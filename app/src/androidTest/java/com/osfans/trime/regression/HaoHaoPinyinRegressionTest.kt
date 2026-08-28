/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.regression

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.compact.toCompactCandidateItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HaoHaoPinyinRegressionTest {
    @Test(timeout = 900_000L)
    fun expectedWordsStayVisibleInTheCompactCandidates() = runBlocking {
        val userDataDir = prepareRegressionStorage()
        val cases = readCases()
        val firstFailures = runRegressionPass("first", cases)
        val compiledTable = userDataDir.resolve(COMPILED_TABLE_PATH)
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        check(targetContext.packageName.endsWith(".regression")) {
            "Refusing to run against ${targetContext.packageName}"
        }
        val externalFilesDir = checkNotNull(targetContext.getExternalFilesDir(null))
        val userDataDir = externalFilesDir.resolve("regression-rime").also { it.mkdirs() }
        AppPrefs.defaultInstance().profile.userDataDir.setValue(userDataDir.absolutePath)
        val output = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow",
        )
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        return userDataDir
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
