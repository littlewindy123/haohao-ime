/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.translation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TranslationTransportCancellationTest :
    StringSpec({
        "a server that never responds cannot retain the cancelled editor coroutine" {
            ServerSocket(0).use { server ->
                val accepted = CountDownLatch(1)
                val release = CountDownLatch(1)
                val worker = thread(isDaemon = true, name = "translation-test-server") {
                    server.accept().use {
                        accepted.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
                try {
                    runBlocking {
                        val request = async(Dispatchers.Default) {
                            UrlConnectionTranslationTransport.post(
                                TranslationHttpRequest(url = "http://127.0.0.1:${server.localPort}/", body = "{}".toByteArray(), contentType = "application/json"),
                            )
                        }
                        accepted.await(3, TimeUnit.SECONDS) shouldBe true
                        val startedAt = System.nanoTime()
                        request.cancelAndJoin()
                        (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000) shouldBe true
                    }
                } finally {
                    release.countDown()
                    worker.join(1_000)
                }
            }
        }
    })
