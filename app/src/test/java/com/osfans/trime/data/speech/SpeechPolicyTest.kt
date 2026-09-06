// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant

class SpeechPolicyTest :
    StringSpec({
        "only the approved trial date is used" {
            Instant.ofEpochMilli(SPEECH_EXPIRES_AT).toString() shouldBe "2026-10-01T00:00:00Z"
        }
        "English punctuation numbers and full sentences are preserved" {
            speechSegments("  We’ll meet at 7:30, on September 9. ") shouldBe listOf("We’ll meet at 7:30, on September 9.")
            speechSegments("I love you") shouldBe listOf("I love you")
        }
        "segmentation preserves the complete normalized sentence within the provider limit" {
            val text = ("We will go out to dinner tomorrow night. ").repeat(40).trim()
            val segments = speechSegments(text)
            segments.joinToString(" ") shouldBe text
            segments.all { it.codePointCount(0, it.length) <= 450 && it.isNotBlank() } shouldBe true
        }
        "too long and unsafe inputs fail without truncation" {
            shouldThrow<SpeechException> { speechSegments("a".repeat(2001)) }.reason shouldBe SpeechFailure.TOO_LONG
            shouldThrow<SpeechException> { speechSegments("a".repeat(451)) }.reason shouldBe SpeechFailure.TOO_LONG
            for (text in listOf("", "你好", "<speak>Hello</speak>", "test\u0000")) shouldThrow<SpeechException> { speechSegments(text) }
        }
        "cache keys isolate case full text voice version and speed" {
            val normal = speechCacheKey("US", SpeechRate.NORMAL)
            normal.matches(Regex("[0-9a-f]{64}")) shouldBe true
            (normal != speechCacheKey("us", SpeechRate.NORMAL)) shouldBe true
            (normal != speechCacheKey("US", SpeechRate.SLOW)) shouldBe true
        }
        "cache validates audio and expires by creation rather than recent listening" {
            val dir = Files.createTempDirectory("speech-cache-test").toFile()
            try {
                var now = 1000L
                val cache = SpeechDiskCache(dir, lifetime = 100, now = { now })
                val key = speechCacheKey("hello", SpeechRate.NORMAL)
                cache.put(key, "ID3audio".toByteArray(), cache.version())
                cache.get(key)?.toString(Charsets.UTF_8) shouldBe "ID3audio"
                now = 1090
                cache.get(key)?.size shouldBe 8
                now = 1100
                cache.get(key) shouldBe null
                cache.put(key, "<html>error</html>".toByteArray(), cache.version())
                cache.get(key) shouldBe null
            } finally {
                dir.deleteRecursively()
            }
        }
        "cache LRU and clear prevent late requests repopulating removed data" {
            val dir = Files.createTempDirectory("speech-lru-test").toFile()
            try {
                var now = 1000L
                val cache = SpeechDiskCache(dir, maximumBytes = 16, now = { now })
                val keys = listOf("one", "two", "three").map { speechCacheKey(it, SpeechRate.NORMAL) }
                cache.put(keys[0], "ID3audio".toByteArray(), cache.version())
                now++
                cache.put(keys[1], "ID3audio".toByteArray(), cache.version())
                now++
                cache.get(keys[0])
                now++
                cache.put(keys[2], "ID3audio".toByteArray(), cache.version())
                cache.get(keys[1]) shouldBe null
                val old = cache.version()
                cache.clear()
                cache.put(keys[0], "ID3audio".toByteArray(), old)
                cache.get(keys[0]) shouldBe null
            } finally {
                dir.deleteRecursively()
            }
        }
        "gateway errors map to explicit bounded recovery states" {
            speechFailureForCode("QUOTA_UNAVAILABLE") shouldBe SpeechFailure.QUOTA
            speechFailureForCode("EXPIRED") shouldBe SpeechFailure.EXPIRED
            speechFailureForCode("TIMEOUT") shouldBe SpeechFailure.TIMEOUT
            speechFailureForCode("UNAUTHORIZED") shouldBe SpeechFailure.UNAUTHORIZED
            speechFailureForCode("RATE_LIMITED") shouldBe SpeechFailure.BUSY
        }
    })
