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
                中古 中古 [Zhong1 Gu3] /Sino-Cuban/China-Cuba/
                中古 中古 [zhong1 gu3] /medieval/Middle Ages/
                比 比 [Bi3] /Belgium/Belgian/
                比 比 [bi3] /to compare/ratio/
                壞行
                重複 重复 [chong2 fu4] /first/
                重複 重复 [chong2 fu4] /second/
                """.trimIndent()
            val overrides = "# common candidates\n中国\tChina\n学习\tstudy\n"

            val pronunciations = listOf(
                "china\t/\u02c8t\u0283a\u026an\u0259/",
                "study\t/\u02c8st\u028cdi/",
                "weather\t/\u02c8w\u025b\u00f0\u0259\u0279/",
                "television\t/\u02c8t\u025bl\u0259\u02ccv\u026a\u0292\u0259n/",
                "medieval\t/\u02ccmidi\u02c8iv\u0259l/",
                "to\t/tu/",
                "look\t/l\u028ak/",
                "askance\t/\u0259\u02c8sk\u00e6ns/",
                "compare\t/k\u0259m\u02c8p\u025b\u0279/",
                "niobium\t/na\u026a\u02c8o\u028abi\u0259m/",
                "first\t/\u02c8f\u025d\u02d0st/",
            ).joinToString("\n")

            val first = generate(source, overrides, pronunciations)
            val second = generate(source, overrides, pronunciations)

            first shouldBe second
            sha256(first) shouldBe sha256(second)
            CedictDictionaryGenerator.readForTesting(first).entries.shouldContainExactly(
                CedictDictionaryGenerator.GeneratedEntry("中古", "medieval", "/\u02ccmidi\u02c8iv\u0259l/"),
                CedictDictionaryGenerator.GeneratedEntry("中国", "China", "/\u02c8t\u0283a\u026an\u0259/"),
                CedictDictionaryGenerator.GeneratedEntry("倪", "to look askance", "/tu l\u028ak \u0259\u02c8sk\u00e6ns/"),
                CedictDictionaryGenerator.GeneratedEntry("天气", "weather", "/\u02c8w\u025b\u00f0\u0259\u0279/"),
                CedictDictionaryGenerator.GeneratedEntry("学习", "study", "/\u02c8st\u028cdi/"),
                CedictDictionaryGenerator.GeneratedEntry("比", "to compare", "/tu k\u0259m\u02c8p\u025b\u0279/"),
                CedictDictionaryGenerator.GeneratedEntry("电视", "television", "/\u02c8t\u025bl\u0259\u02ccv\u026a\u0292\u0259n/"),
                CedictDictionaryGenerator.GeneratedEntry("看", "to look", "/tu l\u028ak/"),
                CedictDictionaryGenerator.GeneratedEntry("重复", "first", "/\u02c8f\u025d\u02d0st/"),
                CedictDictionaryGenerator.GeneratedEntry("铌", "niobium", "/na\u026a\u02c8o\u028abi\u0259m/"),
            )
        }

        "generator supports unicode keys and omits definitions longer than 18 code points" {
            val source =
                """
                電腦 电脑 [dian4 nao3] /computer/
                長詞 长词 [chang2 ci2] /this definition is definitely much too long/
                """.trimIndent()

            val dictionary = CedictDictionaryGenerator.readForTesting(
                generate(source, "", "computer\t/k\u0259m\u02c8pjut\u0259\u0279/"),
            )

            dictionary.release shouldBe "2026-08-24"
            dictionary.entries.shouldContainExactly(
                CedictDictionaryGenerator.GeneratedEntry("电脑", "computer", "/k\u0259m\u02c8pjut\u0259\u0279/"),
            )
        }

        "override tombstones remove unsafe translations" {
            val source =
                """
                倪 倪 [Ni2] /surname Ni/to look askance/
                鸡 鸡 [ji1] /chicken/
                """.trimIndent()

            CedictDictionaryGenerator.readForTesting(
                generate(source, "倪\t-\n鸡\tchicken\n"),
            ).entries.shouldContainExactly(
                CedictDictionaryGenerator.GeneratedEntry("鸡", "chicken", null),
            )
        }

        "generated translations never expose metadata brackets" {
            val source =
                """
                詞 词 [ci2] /(literary) expression/plain word/
                條目 条目 [tiao2 mu4] /entry [in a dictionary]/
                """.trimIndent()

            val dictionary = CedictDictionaryGenerator.readForTesting(
                generate(source, "", "entry\t/\u02c8\u025bntri/\nplain\t/ple\u026an/\nword\t/w\u025d\u02d0d/"),
            )

            dictionary.entries.shouldContainExactly(
                CedictDictionaryGenerator.GeneratedEntry("条目", "entry", "/\u02c8\u025bntri/"),
                CedictDictionaryGenerator.GeneratedEntry("词", "plain word", "/ple\u026an w\u025d\u02d0d/"),
            )
            dictionary.entries.forEach { (_, translation, _) ->
                translation.contains('(') shouldBe false
                translation.contains(')') shouldBe false
                translation.contains('[') shouldBe false
                translation.contains(']') shouldBe false
            }
        }

        "IPA selection is deterministic and never emits partial pronunciations" {
            val source =
                """
                問候 问候 [wen4 hou4] /hello/
                輸入法 输入法 [shu1 ru4 fa3] /input method/
                複合 复合 [fu4 he2] /well-known word/
                缺失 缺失 [que1 shi1] /known missing/
                """.trimIndent().replace("\\t", "\t")
            val pronunciations = listOf(
                "hello\t/h\u0259\u02c8\u026bo\u028a/, /h\u025b\u02c8\u026bo\u028a/",
                "input\t/\u02c8\u026an\u02ccp\u028at/",
                "method\t/\u02c8m\u025b\u03b8\u0259d/",
                "well\t/w\u025bl/",
                "known\t/no\u028an/",
                "word\t/w\u025d\u02d0d/",
            ).joinToString("\n")

            CedictDictionaryGenerator.readForTesting(
                generate(source, "", pronunciations),
            ).entries.shouldContainExactly(
                CedictDictionaryGenerator.GeneratedEntry("复合", "well-known word", "/w\u025bl no\u028an w\u025d\u02d0d/"),
                CedictDictionaryGenerator.GeneratedEntry("缺失", "known missing", null),
                CedictDictionaryGenerator.GeneratedEntry("输入法", "input method", "/\u02c8\u026an\u02ccp\u028at \u02c8m\u025b\u03b8\u0259d/"),
                CedictDictionaryGenerator.GeneratedEntry("问候", "hello", "/h\u0259\u02c8\u026bo\u028a/"),
            )
        }
    }) {
    companion object {
        private fun generate(
            source: String,
            overrides: String,
            pronunciations: String = "",
        ): ByteArray {
            val compressed = ByteArrayOutputStream()
            GZIPOutputStream(compressed).bufferedWriter(Charsets.UTF_8).use { it.write(source) }
            val compressedPronunciations = ByteArrayOutputStream()
            GZIPOutputStream(compressedPronunciations).bufferedWriter(Charsets.UTF_8).use {
                it.write(pronunciations)
            }
            val output = ByteArrayOutputStream()
            CedictDictionaryGenerator.generate(
                source = ByteArrayInputStream(compressed.toByteArray()),
                overrides = overrides.reader(),
                pronunciations = ByteArrayInputStream(compressedPronunciations.toByteArray()),
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
