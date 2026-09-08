// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.translation

import java.security.MessageDigest

/** Same persistent SHA-256 format, without creating a Formatter for every byte on each key. */
internal fun translationFingerprint(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 255
            append(alphabet[value ushr 4])
            append(alphabet[value and 15])
        }
    }
}
