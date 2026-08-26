// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class CedictDictionaryGeneratorTest :
    StringSpec({
        "generator parses CEDICT and applies deterministic filtering and overrides" {
            val source =
                """
                # comment

                中國 中国 [Zhong1 guo2] /China/Middle Kingdom/
                學習 学习 [[xue2xi2]] /to learn/to study/
                天氣 天气 [tian1 qi4] /CL:個|个/weather/
                電視 电视 [dian4 shi4] /television; TV/
                異體 异体 [yi4 ti3] /variant of 異體|异体[yi4 ti3]/
                看 看 [kan4] /see 看見|看见[kan4 jian4]/to look/
                鈮 铌 [ni2] /niobium (chemistry)/
                倪 倪 [Ni2] /surname Ni/to look askance/
                伲 伲 [ni3] /(dialect) I/(dialect) me/
                艿 艿 [nai3] /used in 茉莉[moli]/
                鯢 鲵 [ni2] /Cryptobranchus japonicus/
                壞行
                重複 重复 [chong2 fu4] /first/
                重複 重复 [chong2 fu4] /second/
                """.trimIndent()
            val overrides = "# common candidates\n中国\tChina\n学习\tstudy\n"

            val first = generate(source, overrides)
            val second = generate(source, overrides)

            first shouldBe second
            sha256(first) shouldBe sha256(second)
            CedictDictionaryGenerator.readForTesting(first).entries.shouldContainExactly(
                "中国" to "China",
                "倪" to "to look askance",
                "天气" to "weather",
                "学习" to "study",
                "电视" to "television",
                "看" to "to look",
                "重复" to "first",
                "铌" to "niobium",
            )
        }

        "generator supports unicode keys and omits definitions longer than 18 code points" {
            val source =
                """
                電腦 电脑 [dian4 nao3] /computer/
                長詞 长词 [chang2 ci2] /this definition is definitely much too long/
                """.trimIndent()

            val dictionary = CedictDictionaryGenerator.readForTesting(generate(source, ""))

            dictionary.release shouldBe "2026-08-24"
            dictionary.entries.shouldContainExactly("电脑" to "computer")
        }

        "generated translations never expose metadata brackets" {
            val source =
                """
                詞 词 [ci2] /(literary) expression/plain word/
                條目 条目 [tiao2 mu4] /entry [in a dictionary]/
                """.trimIndent()

            val dictionary = CedictDictionaryGenerator.readForTesting(generate(source, ""))

            dictionary.entries.shouldContainExactly(
                "条目" to "entry",
                "词" to "plain word",
            )
            dictionary.entries.forEach { (_, translation) ->
                translation.contains('(') shouldBe false
                translation.contains(')') shouldBe false
                translation.contains('[') shouldBe false
                translation.contains(']') shouldBe false
            }
        }
    }) {
    companion object {
        private fun generate(
            source: String,
            overrides: String,
        ): ByteArray {
            val compressed = ByteArrayOutputStream()
            GZIPOutputStream(compressed).bufferedWriter(Charsets.UTF_8).use { it.write(source) }
            val output = ByteArrayOutputStream()
            CedictDictionaryGenerator.generate(
                source = ByteArrayInputStream(compressed.toByteArray()),
                overrides = overrides.reader(),
                release = "2026-08-24",
                output = output,
            )
            return output.toByteArray()
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
