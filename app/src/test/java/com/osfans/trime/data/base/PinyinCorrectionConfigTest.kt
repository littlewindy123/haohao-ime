/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.base

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

class PinyinCorrectionConfigTest :
    StringSpec({
        "default correction settings keep the precompiled patch unchanged" {
            PinyinCorrectionSettings.DEFAULT shouldBe PinyinCorrectionSettings(
                smartCorrection = true,
                adjacentKeyCorrection = false,
                fuzzyEnabled = false,
                fuzzyPairs = emptySet(),
            )
            renderPinyinCorrectionPatch(PinyinCorrectionSettings.DEFAULT) shouldBe
                SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
        }

        "Sogou-compatible fuzzy pairs have a stable display and config order" {
            PinyinFuzzyPair.entries.map { it.configKey } shouldContainExactly listOf(
                "s_sh",
                "c_ch",
                "z_zh",
                "l_n",
                "f_h",
                "r_l",
                "an_ang",
                "en_eng",
                "in_ing",
                "ian_iang",
                "uan_uang",
            )
        }

        "adjacent key correction emits one-hop variants without chained expansion" {
            val rules = File("src/main/assets/shared/haohao_pinyin_correction.yaml").readText()
            val adjacentRules = rules.substringAfter("adjacent_key_correction:")

            adjacentRules.lines().count { it.trimStart().startsWith("- derive/^([a-z]*)") } shouldBe 46
            adjacentRules shouldContain "- derive/^([a-z]*)q([a-z]*)$/Q\$1w\$2/"
            adjacentRules shouldContain "- xform/^Q//"
            adjacentRules.contains("- derive/q/w/") shouldBe false
        }

        "enabled options render only their requested algebra rules" {
            val patch = renderPinyinCorrectionPatch(
                PinyinCorrectionSettings(
                    smartCorrection = false,
                    adjacentKeyCorrection = true,
                    fuzzyEnabled = true,
                    fuzzyPairs = linkedSetOf(PinyinFuzzyPair.Z_ZH, PinyinFuzzyPair.EN_ENG),
                ),
            )

            patch shouldContain "- pinyin:/abbreviation"
            patch shouldContain "- pinyin:/spelling_correction"
            patch shouldContain "- haohao_pinyin_correction:/adjacent_key_correction"
            patch shouldContain "- haohao_pinyin_correction:/fuzzy_z_zh"
            patch shouldContain "- haohao_pinyin_correction:/fuzzy_en_eng"
            patch.contains("- pinyin:/key_correction") shouldBe false
            patch.contains("fuzzy_l_n") shouldBe false
        }

        "disabled fuzzy master preserves selections without rendering fuzzy rules" {
            val patch = renderPinyinCorrectionPatch(
                PinyinCorrectionSettings(
                    fuzzyEnabled = false,
                    fuzzyPairs = setOf(PinyinFuzzyPair.S_SH, PinyinFuzzyPair.L_N),
                ),
            )

            patch.contains("fuzzy_s_sh") shouldBe false
            patch.contains("fuzzy_l_n") shouldBe false
        }

        "full correction compile fixture matches the deterministic renderer" {
            val fixture = File("src/test/assets/pinyin-correction/luna_pinyin_simp.custom.yaml").readText()
            val expected = renderPinyinCorrectionPatch(
                PinyinCorrectionSettings(
                    smartCorrection = true,
                    adjacentKeyCorrection = true,
                    fuzzyEnabled = true,
                    fuzzyPairs = PinyinFuzzyPair.entries.toSet(),
                ),
            )

            fixture.trim() shouldBe expected.trim()
        }

        "known generated hash is managed while edited config is protected" {
            val generated = renderPinyinCorrectionPatch(
                PinyinCorrectionSettings(smartCorrection = false),
            )
            val hash = pinyinCorrectionSha256(generated)

            isManagedPinyinCorrectionConfig(generated, hash) shouldBe true
            isManagedPinyinCorrectionConfig("patch:\n  custom: true", hash) shouldBe false
            isManagedPinyinCorrectionConfig(SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent(), "") shouldBe true
        }

        "failed deployment restores the previous managed config" {
            val root = Files.createTempDirectory("haohao-pinyin-rollback").toFile()
            val config = root.resolve("luna_pinyin_simp.custom.yaml")
            val previous = SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
            config.writeText(previous)
            var deployCalls = 0

            val result = runBlocking {
                applyManagedPinyinCorrectionConfig(
                    configFile = config,
                    storedManagedHash = "",
                    settings = PinyinCorrectionSettings(smartCorrection = false),
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

        "unchanged managed config does not deploy or leave temporary files" {
            val root = Files.createTempDirectory("haohao-pinyin-unchanged").toFile()
            val config = root.resolve("luna_pinyin_simp.custom.yaml")
            val current = renderPinyinCorrectionPatch(PinyinCorrectionSettings.DEFAULT)
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
                    settings = PinyinCorrectionSettings(smartCorrection = false),
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
                    settings = PinyinCorrectionSettings(smartCorrection = false),
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
