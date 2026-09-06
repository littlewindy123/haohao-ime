// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.translation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull

class TranslationRequestBoundaryTest :
    StringSpec({
        val request = CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE)
        val success = CloudTranslationResult.Success(listOf("hello"))
        val network = CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK)

        "a stalled primary leaves time for the fallback" {
            var fallbackCalls = 0
            val provider = DirectDualCloudTranslationProvider(
                primary = CloudTranslationProvider { awaitCancellation() },
                fallback = CloudTranslationProvider {
                    fallbackCalls++
                    success
                },
                primaryTimeoutMillis = 30,
                fallbackTimeoutMillis = 100,
            )
            executeTranslationRequest(provider, request, 500) shouldBe success
            fallbackCalls shouldBe 1
        }
        "two stalled providers finish with an explicit failure" {
            val provider = DirectDualCloudTranslationProvider(
                CloudTranslationProvider { awaitCancellation() },
                CloudTranslationProvider { awaitCancellation() },
                primaryTimeoutMillis = 30,
                fallbackTimeoutMillis = 30,
            )
            withTimeoutOrNull(1000) { executeTranslationRequest(provider, request, 500) } shouldBe
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.TIMEOUT)
        }
        "unexpected provider exceptions become failures and the next request can succeed" {
            executeTranslationRequest(CloudTranslationProvider { error("private upstream failure") }, request) shouldBe network
            executeTranslationRequest(CloudTranslationProvider { success }, request) shouldBe success
        }
        "exceptions in the primary can still use the backup" {
            DirectDualCloudTranslationProvider(
                CloudTranslationProvider { error("unavailable") },
                CloudTranslationProvider { success },
            ).translate(request) shouldBe success
        }
        "invalid successful payloads never leave a ready state with no translation" {
            for (values in listOf(emptyList(), listOf(""), listOf("hello", "extra"))) {
                executeTranslationRequest(CloudTranslationProvider { CloudTranslationResult.Success(values) }, request) shouldBe
                    CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_RESPONSE)
            }
        }
        "editor cancellation propagates without starting the backup" {
            val entered = CompletableDeferred<Unit>()
            var fallbackCalls = 0
            val provider = DirectDualCloudTranslationProvider(
                CloudTranslationProvider {
                    entered.complete(Unit)
                    awaitCancellation()
                },
                CloudTranslationProvider {
                    fallbackCalls++
                    success
                },
            )
            val job = async { executeTranslationRequest(provider, request) }
            entered.await()
            job.cancelAndJoin()
            job.isCancelled shouldBe true
            fallbackCalls shouldBe 0
        }
        "an outer timeout is not swallowed by an inner provider timeout" {
            val result = withTimeoutOrNull(30) {
                executeTranslationRequest(CloudTranslationProvider { awaitCancellation() }, request, 500)
            }
            result shouldBe null
        }
        "configuration expiry and consent failure never call the backup" {
            for (kind in listOf(CloudTranslationResult.Failure.Kind.CONFIGURATION_EXPIRED, CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED)) {
                var calls = 0
                DirectDualCloudTranslationProvider(
                    CloudTranslationProvider { CloudTranslationResult.Failure(kind) },
                    CloudTranslationProvider {
                        calls++
                        success
                    },
                ).translate(request) shouldBe CloudTranslationResult.Failure(kind)
                calls shouldBe 0
            }
        }
        "internal test expiry stops at the end of September 30 in Shanghai and rejects impossible dates" {
            val cutoff = java.time.Instant.parse("2026-09-30T16:00:00Z").toEpochMilli()
            isInternalCloudConfigurationValid(true, "test", "test", "test", "test", "2026-09-30", cutoff - 1) shouldBe true
            isInternalCloudConfigurationValid(true, "test", "test", "test", "test", "2026-09-30", cutoff) shouldBe false
            isInternalCloudConfigurationValid(true, "test", "test", "test", "test", "2026-02-30", cutoff) shouldBe false
        }
        "changing cloud configuration clears the previous provider cooldown" {
            val cooldown = CloudCandidateServiceCooldown { 1_000L }
            cooldown.record(CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.TIMEOUT))
            cooldown.isActive() shouldBe true
            cooldown.reset()
            cooldown.isActive() shouldBe false
        }
    })
