// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.translation

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

class CloudTranslationTest :
    StringSpec({
        "aliyun RPC signature matches the official fixed vector" {
            val parameters = linkedMapOf(
                "AccessKeyId" to "testid",
                "Action" to "DescribeDedicatedHosts",
                "Format" to "JSON",
                "RegionId" to "cn-beijing",
                "SignatureMethod" to "HMAC-SHA1",
                "SignatureVersion" to "1.0",
                "SignatureNonce" to "edb2b34af0af9a6d14deaf7c1a5315eb",
                "Timestamp" to "2023-03-13T08:34:30Z",
                "Version" to "2014-05-26",
            )

            AliyunRpcSigner.sign(parameters, "testsecret", httpMethod = "GET")["Signature"] shouldBe
                "9NaGiOspFP5UPcwX8Iwt2YJXXuk="
        }

        "aliyun percent encoding follows RFC 3986" {
            AliyunRpcSigner.percentEncode("中 文+~*") shouldBe "%E4%B8%AD%20%E6%96%87%2B~%2A"
        }

        "AES-GCM secret envelopes round-trip without containing plaintext" {
            val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            val wrongKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

            val envelope = AesGcmSecretCodec.encrypt("RAM-secret-value", key)

            ("RAM-secret-value" in envelope) shouldBe false
            AesGcmSecretCodec.decrypt(envelope, key) shouldBe "RAM-secret-value"
            AesGcmSecretCodec.decrypt(envelope, wrongKey) shouldBe null
        }

        "release custom endpoints require HTTPS" {
            isAllowedTranslationEndpoint("https://translate.example.com/v1", false) shouldBe true
            isAllowedTranslationEndpoint("http://translate.example.com/v1", false) shouldBe false
            isAllowedTranslationEndpoint("javascript:alert(1)", true) shouldBe false
            isAllowedTranslationEndpoint("http://127.0.0.1:8080/v1", true) shouldBe true
            isAllowedTranslationEndpoint("http://10.0.2.2:8080/v1", true) shouldBe true
        }

        "custom providers accept the three documented string response fields" {
            listOf("translation", "data", "result").forEach { field ->
                val requests = mutableListOf<TranslationHttpRequest>()
                val provider = CustomTranslationProvider(
                    endpoint = "https://translate.example.com/v1",
                    bearerToken = "secret-token",
                    transport = TranslationHttpTransport { request ->
                        requests += request
                        TranslationHttpResponse(200, "{\"$field\":\"hello\"}")
                    },
                    allowLoopbackHttp = false,
                )

                val result = runBlocking {
                    provider.translate(
                        CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE),
                    )
                }

                result shouldBe CloudTranslationResult.Success(listOf("hello"))
                requests.single().headers["Authorization"] shouldBe "Bearer secret-token"
            }
        }

        "candidate requests reject more than five items without touching the network" {
            var requestCount = 0
            val provider = CustomTranslationProvider(
                endpoint = "https://translate.example.com/v1",
                bearerToken = null,
                transport = TranslationHttpTransport {
                    requestCount += 1
                    TranslationHttpResponse(200, "{\"translation\":\"unused\"}")
                },
                allowLoopbackHttp = false,
            )

            val result = runBlocking {
                provider.translate(
                    CloudTranslationRequest(
                        texts = listOf("一", "二", "三", "四", "五", "六"),
                        purpose = TranslationPurpose.CANDIDATE,
                    ),
                )
            }

            result shouldBe CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
            requestCount shouldBe 0
        }

        "provider cancellation reaches the active transport" {
            val cancelled = AtomicBoolean(false)
            val provider = CustomTranslationProvider(
                endpoint = "https://translate.example.com/v1",
                bearerToken = null,
                transport = TranslationHttpTransport {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { cancelled.set(true) }
                    }
                },
                allowLoopbackHttp = false,
            )

            val result = runBlocking {
                withTimeoutOrNull(25) {
                    provider.translate(
                        CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE),
                    )
                }
            }

            result shouldBe null
            cancelled.get() shouldBe true
        }

        "sensitive editor flags disable every cloud feature" {
            CloudTranslationPrivacyPolicy.allows(InputType.TYPE_CLASS_TEXT, 0) shouldBe true
            CloudTranslationPrivacyPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            ) shouldBe false
            CloudTranslationPrivacyPolicy.allows(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ) shouldBe false
        }

        "candidate fallback stays offline first and uploads at most five unique misses" {
            val offline = setOf("你好", "电脑")
            val blocked = setOf("缓存未命中")
            selectCloudCandidateMisses(
                texts = listOf("你好", "鸡", "电脑", "鸡", "天气", "输入法", "塔斯汀", "豆包", "缓存未命中"),
                offlineLookup = { text ->
                    CandidateTranslationEntry("offline", null).takeIf { text in offline }
                },
                shouldRequest = { it !in blocked },
            ).shouldContainExactly("鸡", "天气", "输入法", "塔斯汀", "豆包")
        }

        "candidate cache isolates providers, expires entries and keeps only the newest values" {
            var now = 1_000L
            val saved = mutableListOf<CloudCandidateCacheEntry>()
            val storage = object : CloudCandidateCacheStorage {
                override fun load(): List<CloudCandidateCacheEntry> = saved.toList()

                override fun save(entries: List<CloudCandidateCacheEntry>) {
                    saved.clear()
                    saved += entries
                }
            }
            val cache = CloudCandidateTranslationCache(storage, { now }, maximumEntries = 2)

            cache.put("provider-a", mapOf("甲" to "first"))
            now += 1
            cache.put("provider-a", mapOf("乙" to "second"))
            now += 1
            cache.put("provider-a", mapOf("丙" to "third"))

            cache.lookup("provider-a", "甲") shouldBe null
            cache.lookup("provider-a", "乙")?.translation shouldBe "second"
            cache.lookup("provider-b", "乙") shouldBe null
            saved.map { it.text } shouldContainExactly listOf("乙", "丙")

            now += CLOUD_CANDIDATE_POSITIVE_TTL_MS
            cache.lookup("provider-a", "乙") shouldBe null
        }

        "candidate cache keeps words, normalizes infinitives and rejects phrases" {
            val cache = CloudCandidateTranslationCache(
                storage = object : CloudCandidateCacheStorage {
                    override fun load(): List<CloudCandidateCacheEntry> = listOf(
                        CloudCandidateCacheEntry("provider", "旧叹", "to sigh", 900L),
                        CloudCandidateCacheEntry("provider", "旧回家", "go home", 900L),
                    )

                    override fun save(entries: List<CloudCandidateCacheEntry>) = Unit
                },
                nowMillis = { 1_000L },
            )

            cache.put(
                "provider",
                mapOf(
                    "叹" to "to sigh",
                    "电脑" to "computer",
                    "回家" to "go home",
                ),
            )

            cache.lookup("provider", "叹")?.translation shouldBe "sigh"
            cache.lookup("provider", "电脑")?.translation shouldBe "computer"
            cache.lookup("provider", "回家") shouldBe null
            cache.shouldRequest("provider", "回家") shouldBe false
            cache.lookup("provider", "旧叹")?.translation shouldBe "sigh"
            cache.lookup("provider", "旧回家") shouldBe null
        }

        "negative candidate cache retries only after eight minutes" {
            var now = 5_000L
            val cache = CloudCandidateTranslationCache(
                storage = object : CloudCandidateCacheStorage {
                    override fun load(): List<CloudCandidateCacheEntry> = emptyList()

                    override fun save(entries: List<CloudCandidateCacheEntry>) = Unit
                },
                nowMillis = { now },
            )

            cache.markNegative("provider", listOf("未命中"))
            cache.shouldRequest("provider", "未命中") shouldBe false
            now += CLOUD_CANDIDATE_NEGATIVE_TTL_MS
            cache.shouldRequest("provider", "未命中") shouldBe true
        }

        "aliyun translates every requested item without leaking credentials into headers" {
            val requests = mutableListOf<TranslationHttpRequest>()
            val provider = AliyunTranslationProvider(
                accessKeyId = "testid",
                accessKeySecret = "testsecret",
                transport = TranslationHttpTransport { request ->
                    requests += request
                    TranslationHttpResponse(200, "{\"Code\":\"200\",\"Data\":{\"Translated\":\"ok\"}}")
                },
                timestamp = { "2026-08-31T00:00:00Z" },
                nonce = { "fixed-nonce" },
            )

            val result = runBlocking {
                provider.translate(
                    CloudTranslationRequest(listOf("你好", "中国"), TranslationPurpose.CANDIDATE),
                )
            }

            result shouldBe CloudTranslationResult.Success(listOf("ok", "ok"))
            requests.map { it.url }.shouldContainExactly(
                "https://mt.cn-hangzhou.aliyuncs.com/",
                "https://mt.cn-hangzhou.aliyuncs.com/",
            )
            requests.all { "testsecret" !in it.body.toString(Charsets.UTF_8) } shouldBe true
            requests.all { it.headers.isEmpty() } shouldBe true
        }
    })
