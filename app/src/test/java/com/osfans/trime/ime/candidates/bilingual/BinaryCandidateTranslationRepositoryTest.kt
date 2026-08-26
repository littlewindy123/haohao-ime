// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.bilingual

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

class BinaryCandidateTranslationRepositoryTest :
    StringSpec({
        "binary dictionary finds simplified Chinese and unicode keys" {
            val repository =
                load(
                    "你好" to "hello",
                    "中国" to "China",
                    "学习" to "study",
                    "天气" to "weather",
                    "电脑" to "computer",
                    "输入法" to "input method",
                    "是" to "to be",
                    "时" to "time",
                    "事" to "matter",
                    "市" to "city",
                    "十" to "ten",
                )

            repository.lookup("你好") shouldBe "hello"
            repository.lookup("中国") shouldBe "China"
            repository.lookup("学习") shouldBe "study"
            repository.lookup("天气") shouldBe "weather"
            repository.lookup("电脑") shouldBe "computer"
            repository.lookup("输入法") shouldBe "input method"
            repository.lookup("是") shouldBe "to be"
            repository.lookup("时") shouldBe "time"
            repository.lookup("事") shouldBe "matter"
            repository.lookup("市") shouldBe "city"
            repository.lookup("十") shouldBe "ten"
            repository.lookup("未收录") shouldBe null
        }

        "invalid magic version release and offsets degrade to an empty dictionary" {
            listOf(
                dictionaryBytes("你好" to "hello").also { it[0] = 0 },
                dictionaryBytes("你好" to "hello").also { putInt(it, 8, 99) },
                dictionaryBytes("你好" to "hello", release = "wrong-release"),
                dictionaryBytes("你好" to "hello").also { putInt(it, 20, Int.MAX_VALUE) },
                dictionaryBytes("你好" to "hello").also { putInt(it, 24, Int.MAX_VALUE) },
            ).forEach { corrupted ->
                var failureCount = 0
                val repository =
                    BinaryCandidateTranslationRepository.load(
                        bufferProvider = { ByteBuffer.wrap(corrupted) },
                        onFailure = { failureCount++ },
                    )

                repository.lookup("你好") shouldBe null
                failureCount shouldBe 1
            }

            var missingFailureCount = 0
            val missingRepository =
                BinaryCandidateTranslationRepository.load(
                    bufferProvider = { error("missing asset") },
                    onFailure = { missingFailureCount++ },
                )
            missingRepository.lookup("你好") shouldBe null
            missingFailureCount shouldBe 1
        }

        "ten warm candidate lookups stay below five milliseconds" {
            val entries = (0 until 1_000).map { "候选$it" to "word$it" }
            val repository = load(*entries.toTypedArray())
            repeat(100) { repository.lookup("候选${it % 10}") }

            val elapsed = measureNanoTime { repeat(10) { repository.lookup("候选$it") } }

            elapsed shouldBeLessThan 5_000_000L
        }
    }) {
    companion object {
        private val magic = byteArrayOf('H'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0)

        private fun load(vararg entries: Pair<String, String>): CandidateTranslationRepository = BinaryCandidateTranslationRepository.load {
            ByteBuffer.wrap(dictionaryBytes(*entries))
        }

        private fun dictionaryBytes(
            vararg entries: Pair<String, String>,
            release: String = "2026-08-24",
        ): ByteArray {
            val sorted = entries.sortedWith(compareBy { it.first })
            val releaseBytes = release.encodeToByteArray()
            val indexOffset = magic.size + Int.SIZE_BYTES * 5 + releaseBytes.size
            val dataOffset = indexOffset + sorted.size * 16
            val payload = ByteArrayOutputStream()
            val records = ArrayList<IntArray>()
            sorted.forEach { (key, value) ->
                val keyBytes = key.encodeToByteArray()
                val valueBytes = value.encodeToByteArray()
                val keyOffset = payload.size()
                payload.write(keyBytes)
                val valueOffset = payload.size()
                payload.write(valueBytes)
                records += intArrayOf(keyOffset, keyBytes.size, valueOffset, valueBytes.size)
            }
            return ByteBuffer
                .allocate(dataOffset + payload.size())
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(magic)
                .putInt(1)
                .putInt(releaseBytes.size)
                .putInt(sorted.size)
                .putInt(indexOffset)
                .putInt(dataOffset)
                .put(releaseBytes)
                .apply { records.forEach { record -> record.forEach(::putInt) } }
                .put(payload.toByteArray())
                .array()
        }

        private fun putInt(
            bytes: ByteArray,
            offset: Int,
            value: Int,
        ) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
        }
    }
}
