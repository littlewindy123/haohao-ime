/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.base

import com.osfans.trime.util.VerifiedAssetCopy
import com.osfans.trime.util.copyVerifiedStream
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest

class ManagedPrebuiltDataTest :
    StringSpec({
        "verified copy hashes while writing and atomically replaces the destination" {
            val root = Files.createTempDirectory("haohao-verified-copy").toFile()
            val destination = root.resolve("dictionary.bin").apply { writeText("old") }
            val payload = "verified prebuilt dictionary".toByteArray()

            val result = copyVerifiedStream(
                ByteArrayInputStream(payload),
                destination,
                sha256(payload),
            )

            result shouldBe VerifiedAssetCopy(payload.size.toLong(), sha256(payload))
            destination.readBytes() shouldBe payload
            root.listFiles().orEmpty().map { it.name } shouldContainExactly listOf("dictionary.bin")
            root.deleteRecursively()
        }

        "verified copy preserves the previous file when the checksum is wrong" {
            val root = Files.createTempDirectory("haohao-rejected-copy").toFile()
            val destination = root.resolve("dictionary.bin").apply { writeText("old") }

            shouldThrow<IllegalStateException> {
                copyVerifiedStream(
                    ByteArrayInputStream("broken".toByteArray()),
                    destination,
                    sha256("expected".toByteArray()),
                )
            }

            destination.readText() shouldBe "old"
            root.listFiles().orEmpty().map { it.name } shouldContainExactly listOf("dictionary.bin")
            root.deleteRecursively()
        }

        "unchanged prebuilt files use the fast size-only path" {
            val root = Files.createTempDirectory("haohao-prebuilt-reuse").toFile()
            val shared = root.resolve("shared").apply { mkdirs() }
            val assetPath = "shared/build/dictionary.bin"
            shared.resolve("build/dictionary.bin").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            var copyCalls = 0

            val result = prepareManagedPrebuiltAssets(
                sharedDataDir = shared,
                checksums = DataChecksums("current", mapOf(assetPath to sha256(byteArrayOf(1, 2, 3, 4)))),
                expectedSizes = mapOf(assetPath to 4L),
                changedPaths = emptySet(),
            ) { _, _, _ ->
                copyCalls += 1
                error("The fast path must not read or copy the asset")
            }

            result shouldBe ManagedPrebuiltSyncResult(copiedFiles = 0, copiedBytes = 0, reusedPrebuilt = true)
            copyCalls shouldBe 0
            root.deleteRecursively()
        }

        "missing truncated and upgraded prebuilt files are verified exactly once" {
            val root = Files.createTempDirectory("haohao-prebuilt-repair").toFile()
            val shared = root.resolve("shared").apply { mkdirs() }
            val missing = "shared/build/missing.bin"
            val truncated = "shared/build/truncated.bin"
            val upgraded = "shared/build/upgraded.bin"
            shared.resolve("build/truncated.bin").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1))
            }
            shared.resolve("build/upgraded.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
            val expectedPayload = byteArrayOf(5, 6, 7, 8)
            val expectedSha = sha256(expectedPayload)
            val copied = mutableListOf<String>()

            val result = prepareManagedPrebuiltAssets(
                sharedDataDir = shared,
                checksums = DataChecksums(
                    "new",
                    mapOf(missing to expectedSha, truncated to expectedSha, upgraded to expectedSha),
                ),
                expectedSizes = mapOf(missing to 4L, truncated to 4L, upgraded to 4L),
                changedPaths = setOf(upgraded),
            ) { path, destination, sha ->
                copied += path
                destination.parentFile?.mkdirs()
                destination.writeBytes(expectedPayload)
                VerifiedAssetCopy(destination.length(), sha)
            }

            copied.sorted() shouldContainExactly listOf(missing, truncated, upgraded)
            result shouldBe ManagedPrebuiltSyncResult(copiedFiles = 3, copiedBytes = 12, reusedPrebuilt = false)
            root.deleteRecursively()
        }

        "checksum manifest only changes after a version change or repair" {
            val current = DataChecksums("current", emptyMap())

            shouldUpdateManagedChecksums(current, current, repairedPrebuiltFiles = 0) shouldBe false
            shouldUpdateManagedChecksums(current, current, repairedPrebuiltFiles = 1) shouldBe true
            shouldUpdateManagedChecksums(current, DataChecksums("next", emptyMap()), repairedPrebuiltFiles = 0) shouldBe true
        }

        "prebuilt installation reserves the payload plus sixteen MiB" {
            val payload = 50L * 1024L * 1024L

            hasEnoughPrebuiltSpace(payload + PREBUILT_FREE_SPACE_RESERVE_BYTES, payload) shouldBe true
            hasEnoughPrebuiltSpace(payload + PREBUILT_FREE_SPACE_RESERVE_BYTES - 1L, payload) shouldBe false
        }

        "legacy managed cleanup removes only the old prebuilt build directory" {
            val root = Files.createTempDirectory("haohao-legacy-prebuilt-cleanup").toFile()
            val oldShared = root.resolve("shared").apply { mkdirs() }
            oldShared.resolve("build/dictionary.bin").apply {
                parentFile.mkdirs()
                writeText("managed")
            }
            val unrelated = oldShared.resolve("user-theme.yaml").apply { writeText("keep") }

            cleanupLegacyManagedPrebuilt(oldShared) shouldBe true

            oldShared.resolve("build").exists() shouldBe false
            unrelated.readText() shouldBe "keep"
            root.deleteRecursively()
        }

        "upgraded prebuilt data invalidates only stale compiled user data" {
            val root = Files.createTempDirectory("haohao-stale-user-build").toFile()
            val userData = root.resolve("user").apply { mkdirs() }
            val userDictionary = userData.resolve("luna_pinyin.userdb").apply { writeText("learned words") }
            val staleBuild = userData.resolve("build").apply {
                mkdirs()
                resolve("haohao_pinyin.table.bin").writeText("stale")
            }

            invalidateStaleCompiledUserData(userData, prebuiltUpdated = true) shouldBe true

            staleBuild.exists() shouldBe false
            userDictionary.readText() shouldBe "learned words"
            invalidateStaleCompiledUserData(userData, prebuiltUpdated = false) shouldBe false
            root.deleteRecursively()
        }
    })

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
