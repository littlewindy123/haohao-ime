// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import timber.log.Timber
import java.io.File
import java.io.InputStream
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
        temporary.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                copiedBytes += count
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        check(actualSha256 == expectedSha256) {
            "Asset checksum mismatch for ${target.name}: $actualSha256 != $expectedSha256"
        }
        check(temporary.length() == copiedBytes) { "Incomplete asset copy for ${target.name}" }
        if (!temporary.renameTo(target)) {
            check(!target.exists() || target.delete()) { "Unable to replace ${target.name}" }
            check(temporary.renameTo(target)) { "Unable to install ${target.name}" }
        }
        VerifiedAssetCopy(copiedBytes, actualSha256)
    } finally {
        temporary.delete()
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
