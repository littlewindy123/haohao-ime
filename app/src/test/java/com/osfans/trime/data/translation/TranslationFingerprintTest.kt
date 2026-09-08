// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.translation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

class TranslationFingerprintTest : StringSpec({
    "fingerprints remain byte-for-byte compatible with existing caches" {
        for (text in listOf("", "abc", "我爱你", "A sentence with numbers 123!", "你好".repeat(200))) {
            translationFingerprint(text) shouldBe MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
})
