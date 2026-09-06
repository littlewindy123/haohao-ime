// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

internal class SpeechTransport(
    private val endpoint: String,
    private val token: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeout: Long = 8000,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun fetch(text: String, rate: SpeechRate): ByteArray {
        if (now() >= SPEECH_EXPIRES_AT) throw SpeechException(SpeechFailure.EXPIRED)
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null || token.isBlank()) {
            throw SpeechException(SpeechFailure.NOT_CONFIGURED)
        }
        return withTimeout(timeout) {
            suspendCancellableCoroutine { continuation ->
                val connection = connectionFactory(URL(endpoint))
                continuation.invokeOnCancellation {
                    Dispatchers.IO.dispatch(continuation.context, Runnable { connection.disconnect() })
                }
                Dispatchers.IO.dispatch(
                    continuation.context,
                    Runnable {
                        try {
                            if (!continuation.isActive) return@Runnable
                            connection.apply {
                                requestMethod = "POST"
                                connectTimeout = 6000
                                readTimeout = 6000
                                instanceFollowRedirects = false
                                doOutput = true
                                setRequestProperty("Authorization", "Bearer $token")
                                setRequestProperty("Content-Type", "application/json")
                                setRequestProperty("Accept", "audio/mpeg")
                            }
                            val body = buildJsonObject {
                                put("text", text)
                                put("rate", rate.wire)
                            }.toString().toByteArray()
                            connection.setFixedLengthStreamingMode(body.size)
                            connection.outputStream.use { it.write(body) }
                            if (connection.responseCode != 200) {
                                val error = connection.errorStream?.use { it.readBytesLimited(4096).toString(Charsets.UTF_8) }.orEmpty()
                                val code = runCatching { Json.parseToJsonElement(error).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
                                throw SpeechException(speechFailureForCode(code))
                            }
                            if (connection.contentType?.substringBefore(';') != "audio/mpeg") throw SpeechException(SpeechFailure.INVALID_AUDIO)
                            val audio = connection.inputStream.use { it.readBytesLimited(2_000_000) }
                            if (!hasMp3Header(audio)) throw SpeechException(SpeechFailure.INVALID_AUDIO)
                            if (continuation.isActive) continuation.resumeWith(Result.success(audio))
                        } catch (_: SocketTimeoutException) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(SpeechException(SpeechFailure.TIMEOUT)))
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(if (error is SpeechException) error else SpeechException(SpeechFailure.NETWORK)))
                        } finally {
                            connection.disconnect()
                        }
                    },
                )
            }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(maximum: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (result.size() + count > maximum) throw SpeechException(SpeechFailure.INVALID_AUDIO)
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}
