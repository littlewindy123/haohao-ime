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
import com.osfans.trime.util.VerifiedAssetCopy
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
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

internal fun invalidatePrebuiltRimeData(
    prebuiltDataDir: File,
    checksumsFile: File,
) {
    if (prebuiltDataDir.exists() && !prebuiltDataDir.deleteRecursively()) {
        error("Failed to remove prebuilt Rime data: $prebuiltDataDir")
    }
    if (checksumsFile.exists() && !checksumsFile.delete()) {
        error("Failed to invalidate managed Rime checksums: $checksumsFile")
    }
}

internal fun invalidateStaleCompiledUserData(
    userDataDir: File,
    prebuiltUpdated: Boolean,
): Boolean {
    if (!prebuiltUpdated) return false
    val userRoot = userDataDir.canonicalFile
    val buildDir = userRoot.resolve("build").canonicalFile
    check(buildDir.path.startsWith(userRoot.path + File.separator)) {
        "Compiled Rime data escaped user directory: $buildDir"
    }
    if (!buildDir.exists()) return false
    check(buildDir.deleteRecursively()) { "Failed to remove stale compiled Rime data: $buildDir" }
    return true
}

internal data class ManagedPrebuiltSyncResult(
    val copiedFiles: Int,
    val copiedBytes: Long,
    val reusedPrebuilt: Boolean,
)

internal data class DataSyncStats(
    val copiedFiles: Int,
    val copiedBytes: Long,
    val reusedPrebuilt: Boolean,
)

internal fun prepareManagedPrebuiltAssets(
    sharedDataDir: File,
    checksums: DataChecksums,
    expectedSizes: Map<String, Long>,
    changedPaths: Set<String>,
    copyAsset: (String, File, String) -> VerifiedAssetCopy,
): ManagedPrebuiltSyncResult {
    val dataRoot = requireNotNull(sharedDataDir.parentFile).canonicalFile
    val managedFiles =
        checksums.files
            .filterKeys { it.startsWith("shared/build/") }
            .filterValues { it.isNotBlank() }
    check(managedFiles.keys == expectedSizes.keys) { "Rime prebuilt metadata does not match checksums" }
    var copiedFiles = 0
    var copiedBytes = 0L
    managedFiles
        .toSortedMap()
        .forEach { (assetPath, expectedSha256) ->
            val destination = dataRoot.resolve(assetPath).canonicalFile
            check(destination.path.startsWith(dataRoot.path + File.separator)) {
                "Managed Rime asset escaped data directory: $assetPath"
            }
            val expectedBytes = expectedSizes.getValue(assetPath)
            if (assetPath in changedPaths || !destination.isFile || destination.length() != expectedBytes) {
                val copied = copyAsset(assetPath, destination, expectedSha256)
                check(copied.bytes == expectedBytes && copied.sha256 == expectedSha256) {
                    "Unable to verify managed Rime asset: $assetPath"
                }
                copiedFiles += 1
                copiedBytes += copied.bytes
            }
        }
    return ManagedPrebuiltSyncResult(copiedFiles, copiedBytes, copiedFiles == 0)
}

internal fun shouldUpdateManagedChecksums(
    old: DataChecksums,
    new: DataChecksums,
    repairedPrebuiltFiles: Int,
): Boolean = old.sha256 != new.sha256 || repairedPrebuiltFiles > 0

internal fun alignManagedRimeSourceTimestamps(
    sharedDataDir: File,
    userDataDir: File,
    epochSeconds: Long,
): Int {
    require(epochSeconds > 0) { "Invalid Rime source timestamp: $epochSeconds" }
    val timestampMillis = Math.multiplyExact(epochSeconds, 1000L)
    val files = mutableListOf<File>()
    sharedDataDir.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        val topLevel = file.relativeTo(sharedDataDir).invariantSeparatorsPath.substringBefore('/')
        if (topLevel != "build") files += file
    }
    listOf(
        DataManager.DEFAULT_CUSTOM_FILE_NAME to DataManager.SCHEMA_LIST_CUSTOM_PATCH,
        DataManager.SIMPLIFIED_SCHEMA_CUSTOM_FILE_NAME to SIMPLIFIED_SCHEMA_CUSTOM_PATCH,
    ).forEach { (name, managedContent) ->
        userDataDir.resolve(name).takeIf { file ->
            file.isFile && file.readText().trim().replace("\r\n", "\n") == managedContent.trimIndent().trim()
        }?.let(files::add)
    }
    files.forEach { file ->
        if (file.lastModified() / 1000L != epochSeconds) {
            check(file.setLastModified(timestampMillis)) { "Failed to align Rime source timestamp: $file" }
        }
        check(file.lastModified() / 1000L == epochSeconds) { "Rime source timestamp mismatch: $file" }
    }
    return files.size
}

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
    private const val PREBUILT_METADATA_FILE_NAME = "haohao_prebuilt.properties"
    private const val PREBUILT_ASSET_PREFIX = "shared/build/"

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

    private fun AssetManager.prebuiltExpectedSizes(): Map<String, Long> {
        val metadataPath = "$PREBUILT_ASSET_PREFIX$PREBUILT_METADATA_FILE_NAME"
        val metadataBytes = open(metadataPath).use { it.readBytes() }
        val properties = Properties().apply { metadataBytes.inputStream().use(::load) }
        val result = mutableMapOf(metadataPath to metadataBytes.size.toLong())
        repeat(properties.getProperty("fileCount")?.toIntOrNull() ?: error("Invalid Rime prebuilt file count")) { index ->
            val name = properties.getProperty("file.$index.name")?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Missing Rime prebuilt file name: $index")
            val bytes = properties.getProperty("file.$name.bytes")?.toLongOrNull()
                ?: error("Missing Rime prebuilt file size: $name")
            result["$PREBUILT_ASSET_PREFIX$name"] = bytes
        }
        return result
    }

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
        repairManagedRimeData(userDataDir, backupName).also {
            invalidatePrebuiltRimeData(prebuiltDataDir, dataDir.resolve(DATA_CHECKSUMS_NAME))
        }
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

    internal fun sync(): DataSyncStats = lock.withLock {
        migrateLegacyDataIfNeeded()
        val oldChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            oldChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()
        val expectedPrebuiltSizes = appContext.assets.prebuiltExpectedSizes()
        val diffs = DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }
        val changedPrebuiltPaths =
            diffs.mapNotNull { diff ->
                when (diff) {
                    is DataDiff.CreateFile,
                    is DataDiff.UpdateFile,
                    -> diff.path.takeIf { it.startsWith(PREBUILT_ASSET_PREFIX) }
                    else -> null
                }
            }.toSet()
        var copiedFiles = 0
        var copiedBytes = 0L

        diffs.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> {
                    if (!it.path.startsWith(PREBUILT_ASSET_PREFIX)) {
                        val destPath = sharedDataDir.resolveSibling(it.path).absolutePath
                        copiedBytes += ResourceUtils.copyFile(it.path, destPath).getOrThrow()
                        copiedFiles += 1
                    }
                }
                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> FileUtils.delete(sharedDataDir.resolve(it.path.substringAfterLast('/'))).getOrThrow()
            }
        }

        val prebuilt = prepareManagedPrebuiltAssets(
            sharedDataDir,
            newChecksums,
            expectedPrebuiltSizes,
            changedPrebuiltPaths,
        ) { assetPath, destination, expectedSha256 ->
            ResourceUtils.copyVerifiedFile(assetPath, destination, expectedSha256).getOrThrow()
        }
        copiedFiles += prebuilt.copiedFiles
        copiedBytes += prebuilt.copiedBytes
        if (prebuilt.copiedFiles > 0) {
            Timber.i("Prepared %d Rime prebuilt files (%d bytes)", prebuilt.copiedFiles, prebuilt.copiedBytes)
        }
        if (invalidateStaleCompiledUserData(userDataDir, prebuilt.copiedFiles > 0)) {
            Timber.i("Removed stale compiled user data after updating Rime prebuilt files")
        }

        if (shouldUpdateManagedChecksums(oldChecksums, newChecksums, prebuilt.copiedFiles)) {
            ResourceUtils.copyFile(DATA_CHECKSUMS_NAME, dataDir.resolve(DATA_CHECKSUMS_NAME).absolutePath).getOrThrow()
        }

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

        val prebuiltMetadataFile = prebuiltDataDir.resolve(PREBUILT_METADATA_FILE_NAME)
        check(prebuiltMetadataFile.isFile) { "Missing Rime prebuilt metadata: $prebuiltMetadataFile" }
        val prebuiltMetadata = Properties().apply { prebuiltMetadataFile.inputStream().use(::load) }
        val sourceTimestamp =
            prebuiltMetadata.getProperty("sourceTimestampEpochSeconds")?.trim()?.toLongOrNull()
                ?: error("Invalid Rime prebuilt source timestamp")
        alignManagedRimeSourceTimestamps(sharedDataDir, userDataDir, sourceTimestamp)

        Timber.d("Synced!")
        DataSyncStats(copiedFiles, copiedBytes, prebuilt.reusedPrebuilt)
    }
}
