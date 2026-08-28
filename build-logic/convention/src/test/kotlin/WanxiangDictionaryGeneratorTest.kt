// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.StringWriter

class WanxiangDictionaryGeneratorTest :
    StringSpec({
        "generator removes tones and keeps upstream weights deterministically" {
            val source =
                """
                # encoding utf-8
                ---
                name: jichu
                version: "LTS"
                sort: by_weight
                ...
                塔斯汀\ttǎ sī tīng\t234
                绿女\tlǜ nǚ\t88
                你好\tnǐ hǎo\t9000
                """.trimIndent().replace("\\t", "\t")

            val first = generate(source)
            val second = generate(source)

            first shouldBe second
            first.text shouldBe
                """
                # Rime dictionary
                # encoding: utf-8
                # Generated from Rime Wanxiang v17.7.1.
                ---
                name: haohao_wanxiang_core
                version: "v17.7.1"
                sort: by_weight
                ...
                塔斯汀\tta si ting\t234
                绿女\tlv nv\t88
                你好\tni hao\t9000

                """.trimIndent().replace("\\t", "\t")
            first.stats.sourceEntries shouldBe 3
            first.stats.generatedEntries shouldBe 3
            first.stats.ignoredEntries shouldBe 0
        }

        "generator records the pinned source row without a weight as ignored" {
            val generated =
                generate(
                    """
                    ---
                    ...
                    省行政\tshěng xíng zhèng
                    正常\tzhèng cháng\t100
                    """.trimIndent().replace("\\t", "\t"),
                )

            generated.text.contains("省行政") shouldBe false
            generated.text.contains("正常\tzheng chang\t100") shouldBe true
            generated.stats.sourceEntries shouldBe 2
            generated.stats.generatedEntries shouldBe 1
            generated.stats.ignoredEntries shouldBe 1
        }

        "generator rejects malformed entries and unsupported pinyin" {
            shouldThrow<IllegalArgumentException> {
                generate("---\n...\n坏行")
            }
            shouldThrow<IllegalArgumentException> {
                generate("---\n...\n测试\tce4 shi4\t100")
            }
        }
    }) {
    companion object {
        private data class Generated(
            val text: String,
            val stats: WanxiangDictionaryGenerator.Stats,
        )

        private fun generate(source: String): Generated {
            val output = StringWriter()
            val stats = WanxiangDictionaryGenerator.generate(source.reader(), "v17.7.1", output)
            return Generated(output.toString(), stats)
        }
    }
}
