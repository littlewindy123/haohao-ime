// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class RimePrebuiltDataTest :
    StringSpec({
        "compile input hash is deterministic and ignores keyboard themes" {
            val root = Files.createTempDirectory("haohao-rime-prebuilt").toFile()
            try {
                val shared = root.resolve("shared").apply { mkdirs() }
                val compileShared = root.resolve("compile-shared").apply { mkdirs() }
                val compileUser = root.resolve("compile-user").apply { mkdirs() }
                val source = root.resolve("jichu.dict.yaml.gz").apply { writeText("source") }
                val sourceMetadata = root.resolve("source.properties").apply { writeText("release=v1") }
                shared.resolve("default.yaml").writeText("schema: first")
                shared.resolve("haohao.trime.yaml").writeText("theme: first")
                compileUser.resolve("default.custom.yaml").writeText("patch: first")

                val first =
                    VerifyRimePrebuiltDataTask.compileInputSha256(
                        shared,
                        compileShared,
                        compileUser,
                        source,
                        sourceMetadata,
                    )
                val second =
                    VerifyRimePrebuiltDataTask.compileInputSha256(
                        shared,
                        compileShared,
                        compileUser,
                        source,
                        sourceMetadata,
                    )
                first shouldBe second

                shared.resolve("haohao.trime.yaml").writeText("theme: second")
                VerifyRimePrebuiltDataTask.compileInputSha256(
                    shared,
                    compileShared,
                    compileUser,
                    source,
                    sourceMetadata,
                ) shouldBe first

                shared.resolve("default.yaml").writeText("schema: second")
                (
                    VerifyRimePrebuiltDataTask.compileInputSha256(
                        shared,
                        compileShared,
                        compileUser,
                        source,
                        sourceMetadata,
                    ) == first
                    ) shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        "required prebuilt files cover dictionary prism schema and reverse lookup" {
            VerifyRimePrebuiltDataTask.REQUIRED_FILES shouldBe
                setOf(
                    "haohao_pinyin.reverse.bin",
                    "haohao_pinyin.table.bin",
                    "luna_pinyin_simp.prism.bin",
                    "luna_pinyin_simp.schema.yaml",
                )
        }
    })
