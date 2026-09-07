package com.osfans.trime.data.footprints

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SentencePolicyTest : StringSpec({
    "sentences are not subject to the vocabulary four-word filter" {
        validSentencePair("我们明天九点一起学习吧。", "Let's study together at 9:00 tomorrow morning!") shouldBe true
        normalizeSavedEnglish("Let's study together at 9:00 tomorrow morning!") shouldBe null
    }
    "limits reject instead of truncating and retain punctuation and numbers" {
        validSentencePair("中".repeat(200), "a".repeat(2000)) shouldBe true
        validSentencePair("中".repeat(201), "hello") shouldBe false
        validSentencePair("中文", "a".repeat(2001)) shouldBe false
        validSentencePair("中文", "hello\u0000") shouldBe false
        validSentencePair("", "hello") shouldBe false
    }
})
