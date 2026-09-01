/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.base

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal enum class PinyinFuzzyPair(
    val configKey: String,
) {
    S_SH("s_sh"),
    C_CH("c_ch"),
    Z_ZH("z_zh"),
    L_N("l_n"),
    F_H("f_h"),
    R_L("r_l"),
    AN_ANG("an_ang"),
    EN_ENG("en_eng"),
    IN_ING("in_ing"),
    IAN_IANG("ian_iang"),
    UAN_UANG("uan_uang"),
}

internal data class PinyinCorrectionSettings(
    val smartCorrection: Boolean = true,
    val adjacentKeyCorrection: Boolean = false,
    val fuzzyEnabled: Boolean = false,
    val fuzzyPairs: Set<PinyinFuzzyPair> = emptySet(),
) {
    companion object {
        val DEFAULT = PinyinCorrectionSettings()
    }
}

internal fun renderPinyinCorrectionPatch(settings: PinyinCorrectionSettings): String {
    if (settings == PinyinCorrectionSettings.DEFAULT) {
        return SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
    }
    val algebra = buildList {
        add("pinyin:/abbreviation")
        add("pinyin:/spelling_correction")
        if (settings.smartCorrection) add("pinyin:/key_correction")
        if (settings.adjacentKeyCorrection) {
            add("haohao_pinyin_correction:/adjacent_key_correction")
        }
        if (settings.fuzzyEnabled) {
            PinyinFuzzyPair.entries
                .filter(settings.fuzzyPairs::contains)
                .forEach { add("haohao_pinyin_correction:/fuzzy_${it.configKey}") }
        }
    }
    return buildString {
        appendLine("# haohao-managed-pinyin-correction-v1")
        appendLine("patch:")
        appendLine("  schema/name: 好好拼音")
        appendLine("  translator/dictionary: haohao_pinyin")
        appendLine("  translator/user_dict: luna_pinyin")
        appendLine("  translator/enable_charset_filter: true")
        appendLine("  engine/filters/+:")
        appendLine("    - charset_filter")
        appendLine("  speller/algebra:")
        appendLine("    __patch:")
        algebra.forEach { appendLine("      - $it") }
    }.trimEnd()
}

internal fun pinyinCorrectionSha256(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun isManagedPinyinCorrectionConfig(
    content: String,
    storedManagedHash: String,
): Boolean {
    val normalized = content.trim().replace("\r\n", "\n")
    val knownPatches = listOf(
        LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH,
        BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH,
        SIMPLIFIED_SCHEMA_CUSTOM_PATCH,
    ).map { it.trimIndent().trim().replace("\r\n", "\n") }
    return normalized in knownPatches ||
        storedManagedHash.isNotBlank() && pinyinCorrectionSha256(normalized) == storedManagedHash
}

internal fun isManagedPinyinCorrectionConfig(
    configFile: File,
    storedManagedHash: String,
): Boolean = !configFile.isFile || isManagedPinyinCorrectionConfig(
    configFile.readText(StandardCharsets.UTF_8),
    storedManagedHash,
)

private val managedPinyinConfigMutex = Mutex()

internal suspend fun applyManagedPinyinCorrectionConfig(
    configFile: File,
    storedManagedHash: String,
    settings: PinyinCorrectionSettings,
    deploy: suspend () -> Boolean,
): Result<String> = managedPinyinConfigMutex.withLock {
    runCatching {
        val previous = configFile.takeIf(File::isFile)?.readText(StandardCharsets.UTF_8)
        if (previous != null && !isManagedPinyinCorrectionConfig(previous, storedManagedHash)) {
            error("Pinyin config is managed by the user")
        }
        val next = renderPinyinCorrectionPatch(settings)
        if (previous?.trim() == next.trim()) return@runCatching pinyinCorrectionSha256(next)

        writeUtf8Atomically(configFile, next)
        val deployed = runCatching { deploy() }
            .onFailure { Timber.e(it, "Pinyin correction deployment failed") }
            .getOrDefault(false)
        if (deployed) return@runCatching pinyinCorrectionSha256(next)

        if (previous == null) {
            deleteFileIfExists(configFile)
        } else {
            writeUtf8Atomically(configFile, previous)
        }
        runCatching { deploy() }
            .onFailure { Timber.e(it, "Pinyin correction rollback deployment failed") }
        error("Unable to apply Pinyin correction settings")
    }
}

private fun writeUtf8Atomically(
    destination: File,
    content: String,
) {
    val parent = requireNotNull(destination.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "Unable to create ${parent.absolutePath}" }
    val temporary = File.createTempFile(destination.name, ".tmp", parent)
    try {
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        replaceFile(temporary, destination)
    } finally {
        deleteFileIfExists(temporary)
    }
}

private fun replaceFile(
    source: File,
    destination: File,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        replaceFileWithNio(source, destination)
        return
    }

    // Android's File.renameTo() uses rename(2) on these releases, so a same-directory move
    // replaces the target atomically. Keep a defensive fallback for non-Android test hosts.
    if (source.renameTo(destination)) return
    val parent = requireNotNull(destination.parentFile)
    val backup = File.createTempFile(destination.name, ".bak", parent)
    check(backup.delete()) { "Unable to prepare ${backup.absolutePath}" }
    val hadDestination = destination.exists()
    if (hadDestination) {
        check(destination.renameTo(backup)) { "Unable to back up ${destination.absolutePath}" }
    }
    try {
        check(source.renameTo(destination)) { "Unable to replace ${destination.absolutePath}" }
        deleteFileIfExists(backup)
    } catch (error: Throwable) {
        if (hadDestination && !destination.exists()) backup.renameTo(destination)
        throw error
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun replaceFileWithNio(
    source: File,
    destination: File,
) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun deleteFileIfExists(file: File) {
    check(!file.exists() || file.delete()) { "Unable to delete ${file.absolutePath}" }
}
