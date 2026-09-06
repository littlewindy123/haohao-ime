// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import java.security.MessageDigest

internal const val SPEECH_EXPIRES_AT = 1790812800000L
internal const val SPEECH_VOICE = "tencent-101050-mp3-16000-v1"
internal enum class SpeechRate(val wire: String) { NORMAL("normal"), SLOW("slow") }
internal enum class SpeechFailure { INVALID_TEXT, TOO_LONG, CONSENT, NOT_CONFIGURED, EXPIRED, NETWORK, TIMEOUT, UNAUTHORIZED, QUOTA, BUSY, INVALID_AUDIO, PLAYBACK }
internal class SpeechException(val reason: SpeechFailure) : Exception(reason.name)

/** Preserve punctuation and word boundaries; never silently truncate a sentence. */
internal fun speechSegments(input: String): List<String> {
    val text = input.trim().replace(Regex("\\s+"), " ")
    if (text.codePointCount(0, text.length) > 2000) throw SpeechException(SpeechFailure.TOO_LONG)
    if (!text.any { it in 'a'..'z' || it in 'A'..'Z' } || text.any { it == '<' || it == '>' || it.isISOControl() || (it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z') }) {
        throw SpeechException(SpeechFailure.INVALID_TEXT)
    }
    val result = mutableListOf<String>()
    var remaining = text
    while (remaining.codePointCount(0, remaining.length) > 450) {
        val limit = remaining.offsetByCodePoints(0, 450)
        val prefix = remaining.substring(0, limit)
        val sentenceEnd = Regex("[.!?;:]\\s").findAll(prefix).lastOrNull()?.range?.last
        val end = sentenceEnd ?: prefix.lastIndexOf(' ')
        if (end <= 0) throw SpeechException(SpeechFailure.TOO_LONG)
        result += remaining.substring(0, end).trim()
        remaining = remaining.substring(end).trimStart()
    }
    if (remaining.isNotBlank()) result += remaining
    return result
}

internal fun speechCacheKey(text: String, rate: SpeechRate): String = MessageDigest.getInstance("SHA-256")
    .digest("$SPEECH_VOICE\u0000${rate.wire}\u0000$text".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun speechFailureForCode(code: String): SpeechFailure = when (code) {
    "UNAUTHORIZED", "UPSTREAM_AUTH" -> SpeechFailure.UNAUTHORIZED
    "EXPIRED" -> SpeechFailure.EXPIRED
    "NOT_CONFIGURED" -> SpeechFailure.NOT_CONFIGURED
    "QUOTA_UNAVAILABLE" -> SpeechFailure.QUOTA
    "RATE_LIMITED", "BUSY" -> SpeechFailure.BUSY
    "TIMEOUT" -> SpeechFailure.TIMEOUT
    "INVALID_TEXT", "INVALID_REQUEST" -> SpeechFailure.INVALID_TEXT
    "INVALID_AUDIO" -> SpeechFailure.INVALID_AUDIO
    else -> SpeechFailure.NETWORK
}

internal fun hasMp3Header(data: ByteArray): Boolean = data.size >= 3 &&
    (
        (data[0] == 0x49.toByte() && data[1] == 0x44.toByte() && data[2] == 0x33.toByte()) ||
            (data[0].toInt() and 0xff == 0xff && data[1].toInt() and 0xe0 == 0xe0)
        )
