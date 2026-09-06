// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import java.io.File

/** Private no-backup directory; filenames are hashes, metadata contains only a creation timestamp. */
internal class SpeechDiskCache(
    private val directory: File,
    private val maximumBytes: Long = 50L * 1024 * 1024,
    private val lifetime: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var generation = 0L

    @Synchronized fun version(): Long = generation
    private fun file(key: String, suffix: String): File {
        require(key.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "$key.$suffix")
    }

    @Synchronized fun get(key: String): ByteArray? {
        prune()
        val audio = file(key, "mp3")
        if (!audio.isFile) return null
        val bytes = runCatching { audio.takeIf { it.length() <= 2_000_000 }?.readBytes() }.getOrNull()
        if (bytes == null || !hasMp3Header(bytes)) {
            remove(key)
            return null
        }
        audio.setLastModified(now())
        return bytes
    }

    @Synchronized fun put(key: String, bytes: ByteArray, expectedVersion: Long) {
        if (expectedVersion != generation || !hasMp3Header(bytes) || bytes.size > 2_000_000) return
        directory.mkdirs()
        val temporary = file(key, "tmp")
        try {
            temporary.writeBytes(bytes)
            val audio = file(key, "mp3")
            if (audio.exists()) audio.delete()
            check(temporary.renameTo(audio))
            file(key, "age").writeText(now().toString())
            audio.setLastModified(now())
        } finally {
            temporary.delete()
        }
        prune()
    }

    @Synchronized fun remove(key: String) {
        file(key, "mp3").delete()
        file(key, "age").delete()
    }

    @Synchronized fun clear() {
        generation++
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.(mp3|age|tmp)")) }?.forEach { it.delete() }
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.extension == "mp3" && it.nameWithoutExtension.matches(Regex("[0-9a-f]{64}")) }.orEmpty()
        files.forEach { audio ->
            val created = runCatching { file(audio.nameWithoutExtension, "age").readText().toLongOrNull() }.getOrNull()
            if (created == null || created > now() || now() - created >= lifetime) remove(audio.nameWithoutExtension)
        }
        val survivors = files.filter { it.exists() }.sortedBy { it.lastModified() }
        var bytes = survivors.sumOf { it.length() }
        survivors.forEach {
            if (bytes > maximumBytes) {
                bytes -= it.length()
                remove(it.nameWithoutExtension)
            }
        }
    }
}
