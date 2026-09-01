/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import android.os.Build
import android.system.Os
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.base.DataSyncStats
import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

private const val DIAGNOSTIC_FAILURE_SEPARATOR = "\n--- failure ---\n"

internal fun boundedDiagnosticFailureHistory(
    previous: String,
    record: String,
    currentBytes: Int,
    maxTotalBytes: Int = 64 * 1024,
    maxFailures: Int = 3,
): String {
    val failures =
        (previous.split(DIAGNOSTIC_FAILURE_SEPARATOR).filter(String::isNotBlank) + record)
            .takeLast(maxFailures)
            .joinToString(DIAGNOSTIC_FAILURE_SEPARATOR)
    val available = (maxTotalBytes - currentBytes).coerceAtLeast(0)
    val bytes = failures.toByteArray(Charsets.UTF_8)
    if (bytes.size <= available) return failures
    var start = (bytes.size - available).coerceAtMost(bytes.size)
    while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start += 1
    return bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
}

internal object RimeRuntimeDiagnostics {
    private const val MAX_FAILURES = 3
    private const val MAX_TOTAL_BYTES = 64 * 1024

    private val lock = Any()
    private val directory: File
        get() = appContext.noBackupFilesDir.resolve("rime/diagnostics")
    private val currentFile: File
        get() = directory.resolve("current-startup.txt")
    private val failuresFile: File
        get() = directory.resolve("recent-failures.txt")

    fun recordSnapshot(
        snapshot: RimeRuntimeSnapshot,
        runtimeState: RimeRuntimeState,
        schemaId: String,
        dataPreparationMillis: Long,
        nativeStartupMillis: Long,
        dataSyncStats: DataSyncStats?,
        error: Throwable? = null,
    ) = synchronized(lock) {
        runCatching {
            val record =
                buildRecord(
                    snapshot,
                    runtimeState,
                    schemaId,
                    dataPreparationMillis,
                    nativeStartupMillis,
                    dataSyncStats,
                    error,
                )
            atomicWrite(currentFile, record)
            if (runtimeState == RimeRuntimeState.FAILED) appendFailure(record)
        }.onFailure { Timber.w(it, "Unable to persist Rime diagnostics") }
    }

    fun recordCrash(error: Throwable) = synchronized(lock) {
        runCatching {
            val record = buildString {
                appendCommonHeader()
                appendLine("Event: uncaught-crash")
                appendLine("Exception: ${error.javaClass.name}")
                appendStack(error)
            }
            appendFailure(record)
        }.onFailure { Timber.w(it, "Unable to persist crash diagnostics") }
    }

    fun recordOptionalModuleFailure(
        module: String,
        error: Throwable,
    ) = synchronized(lock) {
        runCatching {
            val record = buildString {
                appendCommonHeader()
                appendLine("Event: optional-module-disabled")
                appendLine("Module: ${module.singleLine(80)}")
                appendLine("Exception: ${error.javaClass.name}")
                appendStack(error)
            }
            appendFailure(record)
        }.onFailure { Timber.w(it, "Unable to persist optional module diagnostics") }
    }

    fun read(): String = synchronized(lock) {
        runCatching {
            buildString {
                currentFile.takeIf(File::isFile)?.readText()?.let {
                    appendLine(it.trimEnd())
                }
                failuresFile.takeIf(File::isFile)?.readText()?.takeIf(String::isNotBlank)?.let {
                    appendLine("Recent failures:")
                    append(it.trimEnd())
                }
            }.trim()
        }.onFailure { Timber.w(it, "Unable to read Rime diagnostics") }.getOrDefault("")
    }

    private fun buildRecord(
        snapshot: RimeRuntimeSnapshot,
        runtimeState: RimeRuntimeState,
        schemaId: String,
        dataPreparationMillis: Long,
        nativeStartupMillis: Long,
        dataSyncStats: DataSyncStats?,
        error: Throwable?,
    ): String = buildString {
        appendCommonHeader()
        appendLine("Engine: $runtimeState")
        appendLine("Attempt: ${snapshot.attemptId}")
        appendLine("Phase: ${snapshot.phase}")
        appendLine("Elapsed: ${snapshot.elapsedMillis} ms")
        appendLine("Automatic retries: ${snapshot.autoRetryCount}")
        appendLine("Schema: $schemaId")
        appendLine("Failure code: ${snapshot.failureCode ?: "none"}")
        snapshot.failureMessage?.let { appendLine("Failure: ${it.singleLine(512)}") }
        appendLine("Data preparation: ${dataPreparationMillis.asTiming()}")
        dataSyncStats?.let {
            appendLine("Data copied: files=${it.copiedFiles}, bytes=${it.copiedBytes}, reused=${it.reusedPrebuilt}")
        }
        appendLine("Native startup: ${nativeStartupMillis.asTiming()}")
        snapshot.phaseDurationsMillis.forEach { (phase, duration) ->
            appendLine("Phase timing: $phase=${duration}ms")
        }
        error?.let {
            appendLine("Exception: ${it.javaClass.name}")
            appendStack(it)
        }
    }

    private fun StringBuilder.appendCommonHeader() {
        appendLine("HaoHao IME ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_VERSION_NAME})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Time: ${System.currentTimeMillis()}")
    }

    private fun StringBuilder.appendStack(error: Throwable) {
        error.stackTrace.take(40).forEach { frame -> appendLine("  at $frame") }
    }

    private fun appendFailure(record: String) {
        val previous = failuresFile.takeIf(File::isFile)?.readText().orEmpty()
        val currentBytes = currentFile.takeIf(File::isFile)?.length()?.toInt() ?: 0
        atomicWrite(
            failuresFile,
            boundedDiagnosticFailureHistory(
                previous = previous,
                record = record,
                currentBytes = currentBytes,
                maxTotalBytes = MAX_TOTAL_BYTES,
                maxFailures = MAX_FAILURES,
            ),
        )
    }

    private fun atomicWrite(
        destination: File,
        content: String,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = destination.resolveSibling(".${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, destination.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun String.singleLine(maxLength: Int): String = replace('\n', ' ').replace('\r', ' ').take(maxLength)

    private fun Long.asTiming(): String = takeIf { it >= 0L }?.let { "$it ms" } ?: "not run"
}
