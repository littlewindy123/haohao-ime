/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.base

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

class PinyinCorrectionConfigTest :
    StringSpec({
        "managed defaults use exact Pinyin with abbreviation only" {
            PinyinCorrectionSettings.DEFAULT shouldBe PinyinCorrectionSettings(
                smartCorrection = false,
                adjacentKeyCorrection = false,
                fuzzyEnabled = false,
                fuzzyPairs = emptySet(),
            )
            val patch = renderPinyinCorrectionPatch(PinyinCorrectionSettings.DEFAULT)
            patch shouldBe SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
            patch.contains("translator/max_word_length: 6") shouldBe true
            patch.contains("translator/enable_correction: false") shouldBe true
            patch.contains("- pinyin:/abbreviation") shouldBe true
            patch.contains("spelling_correction") shouldBe false
            patch.contains("key_correction") shouldBe false
            patch.contains("fuzzy_") shouldBe false
        }

        "legacy correction preferences cannot re-enable managed correction" {
            renderPinyinCorrectionPatch(
                PinyinCorrectionSettings(
                    smartCorrection = true,
                    adjacentKeyCorrection = true,
                    fuzzyEnabled = true,
                    fuzzyPairs = PinyinFuzzyPair.entries.toSet(),
                ),
            ) shouldBe SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
        }

        "known generated hash is managed while edited config is protected" {
            val generated = """
                # haohao-managed-pinyin-correction-v1
                patch:
                  speller/algebra:
                    __patch:
                      - pinyin:/spelling_correction
                      - pinyin:/key_correction
            """.trimIndent()
            val hash = pinyinCorrectionSha256(generated)

            isManagedPinyinCorrectionConfig(generated, hash) shouldBe true
            isManagedPinyinCorrectionConfig("patch:\n  custom: true", hash) shouldBe false
            isManagedPinyinCorrectionConfig(SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent(), "") shouldBe true
        }

        "failed deployment restores the previous managed config" {
            val root = Files.createTempDirectory("haohao-pinyin-rollback").toFile()
            val config = root.resolve("luna_pinyin_simp.custom.yaml")
            val previous = BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
            config.writeText(previous)
            var deployCalls = 0

            val result = runBlocking {
                applyManagedPinyinCorrectionConfig(
                    configFile = config,
                    storedManagedHash = "",
                    settings = PinyinCorrectionSettings.DEFAULT,
                ) {
                    deployCalls += 1
                    deployCalls > 1
                }
            }

            result.isFailure shouldBe true
            deployCalls shouldBe 2
            config.readText() shouldBe previous
            root.deleteRecursively()
        }

        "unchanged exact config does not deploy or leave temporary files" {
            val root = Files.createTempDirectory("haohao-pinyin-unchanged").toFile()
            val config = root.resolve("luna_pinyin_simp.custom.yaml")
            val current = SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
            config.writeText(current)
            var deployCalls = 0

            val result = runBlocking {
                applyManagedPinyinCorrectionConfig(
                    configFile = config,
                    storedManagedHash = "",
                    settings = PinyinCorrectionSettings.DEFAULT,
                ) {
                    deployCalls += 1
                    true
                }
            }

            result.isSuccess shouldBe true
            deployCalls shouldBe 0
            config.readText() shouldBe current
            root.listFiles()?.map(File::getName) shouldContainExactly listOf(config.name)
            root.deleteRecursively()
        }

        "failed first deployment removes the generated config" {
            val root = Files.createTempDirectory("haohao-pinyin-first-failure").toFile()
            val config = root.resolve("luna_pinyin_simp.custom.yaml")
            var deployCalls = 0

            val result = runBlocking {
                applyManagedPinyinCorrectionConfig(
                    configFile = config,
                    storedManagedHash = "",
                    settings = PinyinCorrectionSettings.DEFAULT,
                ) {
                    deployCalls += 1
                    false
                }
            }

            result.isFailure shouldBe true
            deployCalls shouldBe 2
            config.exists() shouldBe false
            root.listFiles()?.toList().orEmpty() shouldContainExactly emptyList()
            root.deleteRecursively()
        }

        "custom config is never overwritten or deployed" {
            val root = Files.createTempDirectory("haohao-pinyin-custom").toFile()
            val config = root.resolve("luna_pinyin_simp.custom.yaml")
            val custom = "patch:\n  speller/algebra: []"
            config.writeText(custom)
            var deployed = false

            val result = runBlocking {
                applyManagedPinyinCorrectionConfig(
                    configFile = config,
                    storedManagedHash = "",
                    settings = PinyinCorrectionSettings.DEFAULT,
                ) {
                    deployed = true
                    true
                }
            }

            result.isFailure shouldBe true
            deployed shouldBe false
            config.readText() shouldBe custom
            root.deleteRecursively()
        }
    })
