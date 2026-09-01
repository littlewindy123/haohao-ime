// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class TranslationQualityTest :
    StringSpec({
        "runtime translations are single lexical English words" {
            TranslationQuality.translationIssues("computer") shouldBe emptyList()
            TranslationQuality.translationIssues("good-looking") shouldBe emptyList()
            TranslationQuality.translationIssues("to sigh") shouldBe listOf("not a single English word")
            TranslationQuality.translationIssues("input method") shouldBe listOf("not a single English word")
        }

        "manual regression set has fixed quotas and traces every override" {
            val cases = repoFile("app/dictionary/cc-cedict/translation_quality_zh_en.tsv")
                .toFile().reader(Charsets.UTF_8).use(TranslationQuality::parseRegression)
            val overrides = repoFile("app/dictionary/cc-cedict/common_overrides_zh_en.tsv")
                .toFile().reader(Charsets.UTF_8).use(TranslationQuality::parseOverrides)

            cases.size shouldBe 200
            cases.groupingBy { it.category }.eachCount() shouldContainExactly mapOf(
                "single" to 50,
                "word" to 70,
                "phrase" to 35,
                "modern" to 25,
                "risk" to 20,
            )
            cases.map { it.text }.distinct().size shouldBe cases.size
            cases.associateBy { it.text }.getValue("倪").translation shouldBe null
            cases.filter { it.translation != null }.forEach {
                (it.translation!!.codePointCount(0, it.translation.length) <= 18) shouldBe true
            }
            overrides.keys.all(cases.associateBy { it.text }::containsKey) shouldBe true
        }

        "top headwords keep the highest duplicate weight and deterministic order" {
            val source =
                """
                ---
                name: sample
                ...
                乙\tyi\t20
                甲\tjia\t20
                丙\tbing\t30
                甲\tjia zhong\t40
                丁\tding\t10
                """.trimIndent().replace("\\t", "\t")

            TranslationQuality.selectTopWanxiang(source.reader(), 3).shouldContainExactly(
                TranslationQuality.WeightedHeadword("甲", 40),
                TranslationQuality.WeightedHeadword("丙", 30),
                TranslationQuality.WeightedHeadword("乙", 20),
            )
        }

        "quality scan reports missing entries and rejects malformed translations" {
            val top = listOf(
                TranslationQuality.WeightedHeadword("甲", 30),
                TranslationQuality.WeightedHeadword("乙", 20),
                TranslationQuality.WeightedHeadword("丙", 10),
            )
            val dictionary = mapOf(
                "甲" to CedictDictionaryGenerator.GeneratedEntry("甲", "good", "/g\u028ad/"),
                "乙" to CedictDictionaryGenerator.GeneratedEntry("乙", "bad (dialect)", null),
            )

            val report = TranslationQuality.scan(top, dictionary)

            report.coveredCount shouldBe 2
            report.missing.shouldContainExactly(TranslationQuality.WeightedHeadword("丙", 10))
            report.hardIssues.map { it.text }.shouldContainExactly("乙")
        }

        "quality scan rejects IPA that does not match the final English" {
            val top = listOf(TranslationQuality.WeightedHeadword("甲", 10))
            val dictionary = mapOf(
                "甲" to CedictDictionaryGenerator.GeneratedEntry("甲", "good", "/bad/"),
            )

            val report = TranslationQuality.scan(top, dictionary) { "/g\u028ad/" }

            report.hardIssues.single().reasons.shouldContainExactly("IPA does not match translation")
        }
    }) {
    companion object {
        private fun repoFile(path: String): Path = Path.of(System.getProperty("user.dir")).resolve("../..").normalize().resolve(path)
    }
}
