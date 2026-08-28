/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

class HaoHaoPinyinRegressionDataTest :
    StringSpec({
        val corpusFile = File("dictionary/regression/haohao_pinyin.tsv")

        "balanced regression corpus has the fixed shape" {
            val cases = readCases(corpusFile)

            cases.size shouldBe 100
            cases.groupingBy(RegressionCase::category).eachCount() shouldContainExactly
                mapOf(
                    "daily" to 40,
                    "scenario" to 20,
                    "brand" to 15,
                    "tech" to 15,
                    "slang" to 10,
                )
            cases.groupingBy(RegressionCase::maxRank).eachCount() shouldContainExactly
                mapOf(1 to 50, 4 to 50)
            cases.map(RegressionCase::text).shouldContainAll(
                "塔斯汀",
                "库迪",
                "小红书",
                "通义千问",
                "豆包",
                "哔哩哔哩",
                "拼多多",
                "情绪价值",
                "提示词",
                "智能体",
            )
        }

        "regression rows are deterministic and valid" {
            val cases = readCases(corpusFile)

            cases.map(RegressionCase::pinyin).distinct().size shouldBe cases.size
            cases.map { it.pinyin to it.text }.distinct().size shouldBe cases.size
            cases.all { PINYIN.matches(it.pinyin) } shouldBe true
            cases.all { it.maxRank == 1 || it.maxRank == 4 } shouldBe true
        }

        "every shipped hotword is covered by a regression case" {
            val cases = readCases(corpusFile).map { it.pinyin to it.text }.toSet()
            val hotwords =
                File("src/main/assets/shared/haohao_hotwords.dict.yaml")
                    .readLines()
                    .dropWhile { it != "..." }
                    .drop(1)
                    .filter { it.isNotBlank() && !it.startsWith('#') }
                    .map { line ->
                        val columns = line.split('\t')
                        require(columns.size == 3) { "Malformed hotword row: $line" }
                        columns[1] to columns[0]
                    }

            hotwords.all(cases::contains) shouldBe true
        }
    }) {
    companion object {
        private data class RegressionCase(
            val pinyin: String,
            val text: String,
            val maxRank: Int,
            val category: String,
        )

        private fun readCases(file: File): List<RegressionCase> = file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank() || line.startsWith('#')) return@mapIndexedNotNull null
            val columns = line.split('\t')
            require(columns.size == 4) { "Malformed regression row ${index + 1}" }
            RegressionCase(columns[0], columns[1], columns[2].toInt(), columns[3])
        }

        private val PINYIN = Regex("[a-z]+(?: [a-z]+)*")
    }
}
