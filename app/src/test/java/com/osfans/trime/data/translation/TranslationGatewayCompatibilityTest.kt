// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.translation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TranslationGatewayCompatibilityTest :
    StringSpec({
        "gateway quota exhaustion remains distinct from temporary rate limits" {
            val errors = mapOf(
                "PROVIDER_QUOTA" to CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED,
                "MONTHLY_QUOTA" to CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED,
                "PROVIDER_RATE_LIMIT" to CloudTranslationResult.Failure.Kind.RATE_LIMITED,
                "DAILY_LIMIT" to CloudTranslationResult.Failure.Kind.RATE_LIMITED,
            )
            for ((code, expected) in errors) {
                val provider = HaoHaoTranslationProvider(
                    baseUrl = "https://translate.example.com",
                    installId = "test-install",
                    transport = TranslationHttpTransport {
                        TranslationHttpResponse(429, "{\"code\":\"$code\"}")
                    },
                    allowLoopbackHttp = false,
                )

                provider.translate(
                    CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE),
                ) shouldBe CloudTranslationResult.Failure(expected)
            }
        }

        "candidate length matches the gateway Unicode character limit" {
            for (character in listOf("中", "𠀀")) {
                var requestCount = 0
                val provider = HaoHaoTranslationProvider(
                    baseUrl = "https://translate.example.com",
                    installId = "test-install",
                    transport = TranslationHttpTransport {
                        requestCount += 1
                        TranslationHttpResponse(200, "{\"translations\":[\"hello\"]}")
                    },
                    allowLoopbackHttp = false,
                )

                provider.translate(
                    CloudTranslationRequest(listOf(character.repeat(32)), TranslationPurpose.CANDIDATE),
                ) shouldBe CloudTranslationResult.Success(listOf("hello"))
                provider.translate(
                    CloudTranslationRequest(listOf(character.repeat(33)), TranslationPurpose.CANDIDATE),
                ) shouldBe CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
                requestCount shouldBe 1
            }
        }
    })
