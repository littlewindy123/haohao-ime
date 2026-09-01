// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class VerifiedAssetCopy(
    val bytes: Long,
    val sha256: String,
)

internal fun copyVerifiedStream(
    input: InputStream,
    destination: File,
    expectedSha256: String,
): VerifiedAssetCopy {
    val target = destination.absoluteFile
    val parent = requireNotNull(target.parentFile).apply { mkdirs() }
    val temporary = parent.resolve(".${target.name}.copying").apply { delete() }
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        FileOutputStream(temporary).use { fileOutput ->
            val output = BufferedOutputStream(fileOutput)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                copiedBytes += count
            }
            output.flush()
            fileOutput.fd.sync()
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        check(actualSha256 == expectedSha256) {
            "Asset checksum mismatch for ${target.name}: $actualSha256 != $expectedSha256"
        }
        check(temporary.length() == copiedBytes) { "Incomplete asset copy for ${target.name}" }
        replaceVerifiedFile(temporary, target)
        VerifiedAssetCopy(copiedBytes, actualSha256)
    } finally {
        temporary.delete()
    }
}

private fun replaceVerifiedFile(
    source: File,
    destination: File,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        replaceVerifiedFileWithNio(source, destination)
        return
    }
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
        check(!backup.exists() || backup.delete()) { "Unable to delete ${backup.absolutePath}" }
    } catch (error: Throwable) {
        if (hadDestination && !destination.exists()) backup.renameTo(destination)
        throw error
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun replaceVerifiedFileWithNio(
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

object ResourceUtils {
    /** Copy files from assets */
    fun copyFile(
        path: String,
        dest: String,
    ): Result<Long> = runCatching {
        val assets = appContext.assets.list(path)
        if (!assets.isNullOrEmpty()) {
            assets.fold(0L) { acc, asset ->
                acc + copyFile("$path/$asset", "$dest/$asset").getOrDefault(0L)
            }
        } else {
            appContext.assets.open(path).use { i ->
                File(dest)
                    .also { it.parentFile?.mkdirs() }
                    .outputStream()
                    .use { o -> i.copyTo(o) }
            }
        }
    }.onFailure { Timber.e(it, "Caught a error in copying assets") }

    internal fun copyVerifiedFile(
        path: String,
        destination: File,
        expectedSha256: String,
    ): Result<VerifiedAssetCopy> = runCatching {
        appContext.assets.open(path).use { input ->
            copyVerifiedStream(input, destination, expectedSha256)
        }
    }.onFailure { Timber.e(it, "Unable to copy verified asset %s", path) }
}
