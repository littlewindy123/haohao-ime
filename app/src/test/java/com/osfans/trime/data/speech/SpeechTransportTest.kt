// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.speech

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class SpeechConnection(
    private val status: Int = 200,
    private val mime: String = "audio/mpeg",
    private val bytes: ByteArray = "ID3test-audio".toByteArray(),
    private val waitForDisconnect: Boolean = false,
    private val socketTimeout: Boolean = false,
) : HttpURLConnection(URL("https://speech.example/api/v1/speech")) {
    val entered = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val output = ByteArrayOutputStream()
    val responses = AtomicInteger()
    override fun connect() = Unit
    override fun usingProxy() = false
    override fun disconnect() {
        disconnected.countDown()
    }
    override fun getOutputStream() = output
    override fun getResponseCode(): Int {
        responses.incrementAndGet()
        entered.countDown()
        if (waitForDisconnect) disconnected.await(3, TimeUnit.SECONDS)
        if (socketTimeout) throw SocketTimeoutException("not shown to users")
        return status
    }
    override fun getContentType() = mime
    override fun getInputStream() = ByteArrayInputStream(bytes)
    override fun getErrorStream() = ByteArrayInputStream(bytes)
}

class SpeechTransportTest :
    StringSpec({
        "unconfigured insecure or expired clients never open a connection" {
            var connections = 0
            for (endpoint in listOf("", "http://speech.example/api", "https://user:pass@speech.example/api", "https://speech.example/api?key=test")) {
                shouldThrow<SpeechException> {
                    SpeechTransport(endpoint, "test", now = { 0 }, connectionFactory = {
                        connections++
                        SpeechConnection()
                    }).fetch("Hello", SpeechRate.NORMAL)
                }.reason shouldBe SpeechFailure.NOT_CONFIGURED
            }
            shouldThrow<SpeechException> {
                SpeechTransport("https://speech.example/api", "", now = { 0 }, connectionFactory = {
                    connections++
                    SpeechConnection()
                }).fetch("Hello", SpeechRate.NORMAL)
            }.reason shouldBe SpeechFailure.NOT_CONFIGURED
            shouldThrow<SpeechException> {
                SpeechTransport("https://speech.example/api", "test", now = { SPEECH_EXPIRES_AT }, connectionFactory = {
                    connections++
                    SpeechConnection()
                }).fetch("Hello", SpeechRate.NORMAL)
            }.reason shouldBe SpeechFailure.EXPIRED
            connections shouldBe 0
        }
        "one authenticated request preserves the selected text and speed without redirects" {
            val connection = SpeechConnection()
            val client = SpeechTransport("https://speech.example/api", "local-test-token", now = { 0 }, connectionFactory = { connection })
            client.fetch("I love you, 2026!", SpeechRate.SLOW).toString(Charsets.UTF_8) shouldBe "ID3test-audio"
            connection.output.toString("UTF-8") shouldBe "{\"text\":\"I love you, 2026!\",\"rate\":\"slow\"}"
            connection.getRequestProperty("Authorization") shouldBe "Bearer local-test-token"
            connection.instanceFollowRedirects shouldBe false
            connection.responses.get() shouldBe 1
            connection.disconnected.await(1, TimeUnit.SECONDS) shouldBe true
        }
        "invalid audio and excessive responses are rejected" {
            for (connection in listOf(
                SpeechConnection(mime = "text/html"),
                SpeechConnection(bytes = "error".toByteArray()),
                SpeechConnection(bytes = "ID3".toByteArray() + ByteArray(2_000_000)),
            )) {
                shouldThrow<SpeechException> {
                    SpeechTransport("https://speech.example/api", "test", now = { 0 }, connectionFactory = { connection }).fetch("Hello", SpeechRate.NORMAL)
                }.reason shouldBe SpeechFailure.INVALID_AUDIO
            }
        }
        "quota and authentication failures do not retry or expose the response" {
            for ((code, failure) in listOf("UNAUTHORIZED" to SpeechFailure.UNAUTHORIZED, "QUOTA_UNAVAILABLE" to SpeechFailure.QUOTA)) {
                val connection = SpeechConnection(status = 401, bytes = "{\"code\":\"$code\",\"detail\":\"private\"}".toByteArray())
                val error = shouldThrow<SpeechException> {
                    SpeechTransport("https://speech.example/api", "test", now = { 0 }, connectionFactory = { connection }).fetch("Hello", SpeechRate.NORMAL)
                }
                error.reason shouldBe failure
                error.message?.contains("private") shouldBe false
                connection.responses.get() shouldBe 1
            }
        }
        "cancellation disconnects the request and discards late audio" {
            coroutineScope {
                val connection = SpeechConnection(waitForDisconnect = true)
                val result = async {
                    SpeechTransport("https://speech.example/api", "test", now = { 0 }, connectionFactory = { connection }).fetch("Hello", SpeechRate.NORMAL)
                }
                withContext(Dispatchers.IO) { connection.entered.await(2, TimeUnit.SECONDS) } shouldBe true
                result.cancel()
                result.join()
                withContext(Dispatchers.IO) { connection.disconnected.await(2, TimeUnit.SECONDS) } shouldBe true
                result.isCancelled shouldBe true
            }
        }
        "wall clock and socket timeouts are bounded without automatic retries" {
            val blocking = SpeechConnection(waitForDisconnect = true)
            shouldThrow<TimeoutCancellationException> {
                SpeechTransport("https://speech.example/api", "test", now = { 0 }, timeout = 100, connectionFactory = { blocking }).fetch("Hello", SpeechRate.NORMAL)
            }
            withContext(Dispatchers.IO) { blocking.disconnected.await(2, TimeUnit.SECONDS) } shouldBe true
            delay(10)
            blocking.responses.get() shouldBe 1
            val timedOut = SpeechConnection(socketTimeout = true)
            shouldThrow<SpeechException> {
                SpeechTransport("https://speech.example/api", "test", now = { 0 }, connectionFactory = { timedOut }).fetch("Hello", SpeechRate.NORMAL)
            }.reason shouldBe SpeechFailure.TIMEOUT
            timedOut.responses.get() shouldBe 1
        }
    })
