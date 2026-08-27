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
                    Triple("你好", "hello", "/h\u0259\u02c8\u026bo\u028a/"),
                    Triple("中国", "China", "/\u02c8t\u0283a\u026an\u0259/"),
                    Triple("学习", "study", "/\u02c8st\u028cdi/"),
                    Triple("天气", "weather", null),
                    Triple("电脑", "computer", null),
                    Triple("输入法", "input method", "/\u02c8\u026an\u02ccp\u028at \u02c8m\u025b\u03b8\u0259d/"),
                    Triple("是", "to be", null),
                    Triple("时", "time", null),
                    Triple("事", "matter", null),
                    Triple("市", "city", null),
                    Triple("十", "ten", null),
                )

            repository.lookup("你好") shouldBe CandidateTranslationEntry("hello", "/h\u0259\u02c8\u026bo\u028a/")
            repository.lookup("中国") shouldBe CandidateTranslationEntry("China", "/\u02c8t\u0283a\u026an\u0259/")
            repository.lookup("学习") shouldBe CandidateTranslationEntry("study", "/\u02c8st\u028cdi/")
            repository.lookup("天气") shouldBe CandidateTranslationEntry("weather", null)
            repository.lookup("电脑") shouldBe CandidateTranslationEntry("computer", null)
            repository.lookup("输入法") shouldBe CandidateTranslationEntry("input method", "/\u02c8\u026an\u02ccp\u028at \u02c8m\u025b\u03b8\u0259d/")
            repository.lookup("是") shouldBe CandidateTranslationEntry("to be", null)
            repository.lookup("时") shouldBe CandidateTranslationEntry("time", null)
            repository.lookup("事") shouldBe CandidateTranslationEntry("matter", null)
            repository.lookup("市") shouldBe CandidateTranslationEntry("city", null)
            repository.lookup("十") shouldBe CandidateTranslationEntry("ten", null)
            repository.lookup("未收录") shouldBe null
        }

        "invalid magic version release and offsets degrade to an empty dictionary" {
            listOf(
                dictionaryBytes(Triple("你好", "hello", null)).also { it[0] = 0 },
                dictionaryBytes(Triple("你好", "hello", null)).also { putInt(it, 8, 99) },
                dictionaryBytes(Triple("你好", "hello", null), release = "wrong-release"),
                dictionaryBytes(Triple("你好", "hello", null)).also { putInt(it, 20, Int.MAX_VALUE) },
                dictionaryBytes(Triple("你好", "hello", null)).also { putInt(it, 24, Int.MAX_VALUE) },
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
            val entries = (0 until 1_000).map { Triple("候选$it", "word$it", null) }
            val repository = load(*entries.toTypedArray())
            repeat(100) { repository.lookup("候选${it % 10}") }

            val elapsed = measureNanoTime { repeat(10) { repository.lookup("候选$it") } }

            elapsed shouldBeLessThan 5_000_000L
        }
    }) {
    companion object {
        private val magic = byteArrayOf('H'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte(), 0)

        private fun load(vararg entries: Triple<String, String, String?>): CandidateTranslationRepository = BinaryCandidateTranslationRepository.load {
            ByteBuffer.wrap(dictionaryBytes(*entries))
        }

        private fun dictionaryBytes(
            vararg entries: Triple<String, String, String?>,
            release: String = "2026-08-24",
        ): ByteArray {
            val sorted = entries.sortedWith(compareBy { it.first })
            val releaseBytes = release.encodeToByteArray()
            val indexOffset = magic.size + Int.SIZE_BYTES * 5 + releaseBytes.size
            val dataOffset = indexOffset + sorted.size * 24
            val payload = ByteArrayOutputStream()
            val records = ArrayList<IntArray>()
            sorted.forEach { (key, value, phonetic) ->
                val keyBytes = key.encodeToByteArray()
                val valueBytes = value.encodeToByteArray()
                val phoneticBytes = phonetic?.encodeToByteArray() ?: ByteArray(0)
                val keyOffset = payload.size()
                payload.write(keyBytes)
                val valueOffset = payload.size()
                payload.write(valueBytes)
                val phoneticOffset = payload.size()
                payload.write(phoneticBytes)
                records += intArrayOf(
                    keyOffset,
                    keyBytes.size,
                    valueOffset,
                    valueBytes.size,
                    phoneticOffset,
                    phoneticBytes.size,
                )
            }
            return ByteBuffer
                .allocate(dataOffset + payload.size())
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(magic)
                .putInt(2)
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
