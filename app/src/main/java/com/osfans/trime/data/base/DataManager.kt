// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.base

import android.content.res.AssetManager
import android.os.Build
import android.os.Environment
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val DEFAULT_SCHEMA_ID = "luna_pinyin_simp"
internal const val SIMPLIFIED_SCHEMA_DISPLAY_NAME = "好好拼音"
internal const val LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH = """
  patch:
    translator/enable_charset_filter: true
    engine/filters/+:
      - charset_filter
"""
internal const val BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH = """
  patch:
    schema/name: 好好拼音
    translator/enable_charset_filter: true
    engine/filters/+:
      - charset_filter
"""
internal const val SIMPLIFIED_SCHEMA_CUSTOM_PATCH = """
  patch:
    schema/name: 好好拼音
    translator/dictionary: haohao_pinyin
    translator/user_dict: luna_pinyin
    translator/enable_charset_filter: true
    engine/filters/+:
      - charset_filter
"""

internal fun upgradeSimplifiedSchemaCustomPatch(existing: String): String? = SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent().takeIf {
    val normalized = existing.trim().replace("\r\n", "\n")
    normalized == LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent().trim() ||
        normalized == BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent().trim()
}

internal fun managedSchemaDisplayName(
    schemaId: String,
    currentName: String,
): String = if (schemaId == DEFAULT_SCHEMA_ID) SIMPLIFIED_SCHEMA_DISPLAY_NAME else currentName

internal data class LegacyRimeMigrationResult(
    val copiedFiles: Int = 0,
    val skippedExistingFiles: Int = 0,
    val skippedBuildFiles: Int = 0,
)

internal fun migrateLegacyRimeData(
    source: File,
    target: File,
): LegacyRimeMigrationResult {
    if (!source.isDirectory) return LegacyRimeMigrationResult()
    val sourceRoot = source.canonicalFile
    val targetRoot = target.canonicalFile
    if (sourceRoot == targetRoot) return LegacyRimeMigrationResult()
    targetRoot.mkdirs()

    var copiedFiles = 0
    var skippedExistingFiles = 0
    var skippedBuildFiles = 0
    sourceRoot.walkTopDown().forEach { entry ->
        if (entry == sourceRoot || !entry.isFile) return@forEach
        val relative = entry.relativeTo(sourceRoot)
        if (relative.invariantSeparatorsPath.split('/').firstOrNull() == "build") {
            skippedBuildFiles += 1
            return@forEach
        }
        val canonicalSource = entry.canonicalFile
        check(canonicalSource.path.startsWith(sourceRoot.path + File.separator)) {
            "Legacy Rime entry escaped source directory: $entry"
        }
        val destination = targetRoot.resolve(relative.path).canonicalFile
        check(destination.path.startsWith(targetRoot.path + File.separator)) {
            "Legacy Rime entry escaped target directory: $destination"
        }
        if (destination.exists()) {
            skippedExistingFiles += 1
        } else {
            destination.parentFile?.mkdirs()
            canonicalSource.copyTo(destination, overwrite = false)
            copiedFiles += 1
        }
    }
    return LegacyRimeMigrationResult(copiedFiles, skippedExistingFiles, skippedBuildFiles)
}

internal data class ManagedRimeRepairResult(
    val backedUpFiles: Int,
    val defaultPatch: String,
)

internal fun repairManagedRimeData(
    userDataDir: File,
    backupName: String,
): ManagedRimeRepairResult {
    val root = userDataDir.canonicalFile.apply { mkdirs() }
    val backupDir = root.resolve("repair-backups/$backupName").canonicalFile
    check(backupDir.path.startsWith(root.path + File.separator)) {
        "Repair backup escaped Rime directory: $backupDir"
    }
    var backedUpFiles = 0
    root.listFiles { file -> file.isFile && file.name.endsWith(".custom.yaml") }
        .orEmpty()
        .forEach { source ->
            backupDir.mkdirs()
            source.copyTo(backupDir.resolve(source.name), overwrite = false)
            backedUpFiles += 1
        }

    val defaultPatch = DataManager.SCHEMA_LIST_CUSTOM_PATCH.trimIndent()
    root.resolve(DataManager.DEFAULT_CUSTOM_FILE_NAME).writeText(defaultPatch)
    root.resolve(DataManager.SIMPLIFIED_SCHEMA_CUSTOM_FILE_NAME).writeText(
        SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent(),
    )

    val buildDir = root.resolve("build").canonicalFile
    check(buildDir.path.startsWith(root.path + File.separator)) {
        "Repair build directory escaped Rime directory: $buildDir"
    }
    if (buildDir.exists() && !buildDir.deleteRecursively()) {
        error("Failed to remove rebuildable Rime data: $buildDir")
    }
    return ManagedRimeRepairResult(backedUpFiles, defaultPatch)
}

object DataManager {
    internal const val DEFAULT_CUSTOM_FILE_NAME = "default.custom.yaml"
    internal const val SIMPLIFIED_SCHEMA_CUSTOM_FILE_NAME = "luna_pinyin_simp.custom.yaml"

    private const val DATA_CHECKSUMS_NAME = "checksums.json"

    internal const val SCHEMA_LIST_CUSTOM_PATCH = """
      patch:
        schema_list:
          - schema: $DEFAULT_SCHEMA_ID
    """

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataChecksums(raw: String): DataChecksums = json.decodeFromString<DataChecksums>(raw)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Timber.d("Using device protected storage")
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }

    private fun AssetManager.dataChecksums(): DataChecksums = open(DATA_CHECKSUMS_NAME)
        .bufferedReader()
        .use { it.readText() }
        .let { deserializeDataChecksums(it) }

    private val prefs by lazy { AppPrefs.defaultInstance() }

    private val privateUserDataDir = File(appContext.filesDir, "rime")

    val defaultDataDir: File
        get() = privateUserDataDir

    val legacyDefaultDataDir = File(Environment.getExternalStorageDirectory(), "rime")

    val sharedDataDir = File(appContext.getExternalFilesDir(null), "shared").also { it.mkdirs() }

    val userDataDir
        get() = privateUserDataDir.also { it.mkdirs() }

    val prebuiltDataDir = File(sharedDataDir, "build")
    val stagingDir get() = File(userDataDir, "build")

    private fun migrateLegacyDataIfNeeded() {
        if (prefs.internal.privateRimeDataMigrated.getValue()) return
        val configuredLegacyDir = File(prefs.profile.userDataDir.getValue())
        val sources = listOf(configuredLegacyDir, legacyDefaultDataDir).distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
        sources.forEach { source ->
            runCatching { migrateLegacyRimeData(source, userDataDir) }
                .onSuccess { result ->
                    Timber.i(
                        "Legacy Rime migration from %s: copied=%d, existing=%d, build=%d",
                        source,
                        result.copiedFiles,
                        result.skippedExistingFiles,
                        result.skippedBuildFiles,
                    )
                }.onFailure { error ->
                    Timber.w(error, "Unable to migrate legacy Rime data from %s", source)
                }
        }
        prefs.profile.userDataDir.setValue(userDataDir.absolutePath)
        prefs.internal.privateRimeDataMigrated.setValue(true)
    }

    internal fun repairManagedData(): ManagedRimeRepairResult = lock.withLock {
        val backupName = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        repairManagedRimeData(userDataDir, backupName)
    }

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }

    fun sync() = lock.withLock {
        migrateLegacyDataIfNeeded()
        val oldChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            oldChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()

        DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> {
                    val destPath = sharedDataDir.resolveSibling(it.path).absolutePath
                    ResourceUtils.copyFile(it.path, destPath)
                }
                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> FileUtils.delete(sharedDataDir.resolve(it.path.substringAfterLast('/'))).getOrThrow()
            }
        }

        ResourceUtils.copyFile(DATA_CHECKSUMS_NAME, dataDir.resolve(DATA_CHECKSUMS_NAME).absolutePath)

        listOf(
            DEFAULT_CUSTOM_FILE_NAME to SCHEMA_LIST_CUSTOM_PATCH,
            SIMPLIFIED_SCHEMA_CUSTOM_FILE_NAME to SIMPLIFIED_SCHEMA_CUSTOM_PATCH,
        ).forEach { (fileName, patch) ->
            val custom = userDataDir.resolve(fileName)
            val content = when {
                !custom.exists() -> patch.trimIndent()
                fileName == SIMPLIFIED_SCHEMA_CUSTOM_FILE_NAME ->
                    upgradeSimplifiedSchemaCustomPatch(custom.readText())
                else -> null
            }
            content?.let(custom::writeText)
        }

        Timber.d("Synced!")
    }
}
