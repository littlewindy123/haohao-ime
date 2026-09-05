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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec
import kotlin.system.measureTimeMillis

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

        "translation requests are fixed to Chinese to English" {
            var requestCount = 0
            val provider = AliyunTranslationProvider(
                accessKeyId = "testid",
                accessKeySecret = "testsecret",
                transport = TranslationHttpTransport {
                    requestCount += 1
                    TranslationHttpResponse(200, "{\"Code\":\"200\",\"Data\":{\"Translated\":\"unused\"}}")
                },
            )

            provider.translate(
                CloudTranslationRequest(
                    texts = listOf("hello"),
                    purpose = TranslationPurpose.SENTENCE,
                    sourceLanguage = "en",
                    targetLanguage = "zh",
                ),
            ) shouldBe CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
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

        "candidate source modes have exact local cloud and hybrid semantics" {
            val offline = CandidateTranslationEntry("offline", null)
            val cloud = CandidateTranslationEntry("cloud", null)

            lookupCandidateTranslation("词", CandidateTranslationSourceMode.LOCAL_ONLY, { offline }, { cloud }) shouldBe offline
            lookupCandidateTranslation("缺义项", CandidateTranslationSourceMode.LOCAL_ONLY, { null }, { error("Local mode must never consult cloud") }) shouldBe null
            lookupCandidateTranslation("词", CandidateTranslationSourceMode.CLOUD_ONLY, { offline }, { cloud }) shouldBe cloud
            lookupCandidateTranslation("词", CandidateTranslationSourceMode.LOCAL_THEN_CLOUD, { offline }, { cloud }) shouldBe offline
            lookupCandidateTranslation("词", CandidateTranslationSourceMode.LOCAL_THEN_CLOUD, { null }, { cloud }) shouldBe cloud

            selectCloudCandidates(CandidateTranslationSourceMode.LOCAL_ONLY, listOf("一")) { error("Local mode must never schedule a request") } shouldBe emptyList()
            selectCloudCandidates(
                CandidateTranslationSourceMode.CLOUD_ONLY,
                listOf("一", "一", "二", "三", "四", "五", "六"),
                shouldRequest = { true },
            ).shouldContainExactly("一", "二", "三", "四", "五")
            selectCloudCandidates(
                CandidateTranslationSourceMode.LOCAL_THEN_CLOUD,
                listOf("本地", "缺失"),
                offlineLookup = { text -> CandidateTranslationEntry("offline", null).takeIf { text == "本地" } },
                shouldRequest = { true },
            ).shouldContainExactly("缺失")
        }

        "legacy candidate fallback migrates only enabled installs to hybrid" {
            resolveCandidateTranslationSourceMode(null, legacyFallbackEnabled = false) shouldBe
                CandidateTranslationSourceMode.LOCAL_ONLY
            resolveCandidateTranslationSourceMode(null, legacyFallbackEnabled = true) shouldBe
                CandidateTranslationSourceMode.LOCAL_THEN_CLOUD
            resolveCandidateTranslationSourceMode(CandidateTranslationSourceMode.CLOUD_ONLY, true) shouldBe
                CandidateTranslationSourceMode.CLOUD_ONLY
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
            saved.size shouldBe 2
            saved.all { it.text.startsWith("sha256:") } shouldBe true
            saved.none { it.text in setOf("甲", "乙", "丙") } shouldBe true

            now += CLOUD_CANDIDATE_POSITIVE_TTL_MS
            cache.lookup("provider-a", "乙") shouldBe null
        }

        "candidate cache accepts short English phrases and rejects unusable translations" {
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
                    "我" to "I",
                    "中国" to "China",
                    "回家" to "go home",
                    "合作" to "win-win cooperation",
                    "太长" to "one two three four five",
                ),
            )

            cache.lookup("provider", "叹")?.translation shouldBe "to sigh"
            cache.lookup("provider", "电脑")?.translation shouldBe "computer"
            cache.lookup("provider", "我")?.translation shouldBe "I"
            cache.lookup("provider", "中国")?.translation shouldBe "China"
            cache.lookup("provider", "回家")?.translation shouldBe "go home"
            cache.lookup("provider", "合作")?.translation shouldBe "win-win cooperation"
            cache.lookup("provider", "太长") shouldBe null
            cache.shouldRequest("provider", "太长") shouldBe false
            cache.lookup("provider", "旧叹")?.translation shouldBe "to sigh"
            cache.lookup("provider", "旧回家")?.translation shouldBe "go home"
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

        "global failures use a thirty second service cooldown without poisoning candidates" {
            var now = 1_000L
            val cooldown = CloudCandidateServiceCooldown { now }
            cooldown.record(CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK))
            cooldown.isActive() shouldBe true
            now += CLOUD_CANDIDATE_SERVICE_COOLDOWN_MS
            cooldown.isActive() shouldBe false

            cooldown.record(CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST))
            cooldown.isActive() shouldBe false
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

        "aliyun translates up to five candidates concurrently while preserving source order" {
            val provider = AliyunTranslationProvider(
                accessKeyId = "testid",
                accessKeySecret = "testsecret",
                transport = TranslationHttpTransport { request ->
                    delay(50)
                    val body = request.body.toString(Charsets.UTF_8)
                    val translated = when {
                        "SourceText=%E4%B8%80" in body -> "one"
                        "SourceText=%E4%BA%8C" in body -> "two"
                        "SourceText=%E4%B8%89" in body -> "three"
                        "SourceText=%E5%9B%9B" in body -> "four"
                        else -> "five"
                    }
                    TranslationHttpResponse(200, "{\"Code\":\"200\",\"Data\":{\"Translated\":\"$translated\"}}")
                },
                timestamp = { "2026-08-31T00:00:00Z" },
                nonce = { "fixed-nonce" },
            )
            lateinit var result: CloudTranslationResult
            val elapsed = measureTimeMillis {
                result = runBlocking {
                    provider.translate(
                        CloudTranslationRequest(listOf("一", "二", "三", "四", "五"), TranslationPurpose.CANDIDATE),
                    )
                }
            }

            result shouldBe CloudTranslationResult.Success(listOf("one", "two", "three", "four", "five"))
            (elapsed < 200L) shouldBe true
        }

        "dual cloud falls back on recoverable failures but not invalid requests" {
            var fallbackCalls = 0
            val fallback = CloudTranslationProvider {
                fallbackCalls += 1
                CloudTranslationResult.Success(listOf("hello"))
            }
            val request = CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE)

            DirectDualCloudTranslationProvider(
                primary = CloudTranslationProvider {
                    CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.AUTHENTICATION)
                },
                fallback = fallback,
            ).translate(request) shouldBe CloudTranslationResult.Success(listOf("hello"))
            fallbackCalls shouldBe 1

            DirectDualCloudTranslationProvider(
                primary = CloudTranslationProvider {
                    CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
                },
                fallback = fallback,
            ).translate(request) shouldBe
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
            fallbackCalls shouldBe 1
        }

        "baidu caches refreshes and safely replaces expired access tokens" {
            var now = 1_000L
            var tokenCalls = 0
            val translatedTokens = mutableListOf<String>()
            val provider = BaiduTranslationProvider(
                apiKey = "api-key",
                secretKey = "secret-key",
                tokenCache = BaiduAccessTokenCache { now },
                transport = TranslationHttpTransport { request ->
                    if ("/oauth/2.0/token" in request.url) {
                        tokenCalls += 1
                        TranslationHttpResponse(
                            200,
                            "{\"access_token\":\"token-$tokenCalls\",\"expires_in\":100}",
                        )
                    } else {
                        translatedTokens += request.url.substringAfter("access_token=")
                        TranslationHttpResponse(
                            200,
                            "{\"result\":{\"trans_result\":[{\"src\":\"x\",\"dst\":\"hello\"}]}}",
                        )
                    }
                },
            )
            val request = CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE)

            provider.translate(request) shouldBe CloudTranslationResult.Success(listOf("hello"))
            provider.translate(request) shouldBe CloudTranslationResult.Success(listOf("hello"))
            tokenCalls shouldBe 1
            now += 40_001L
            provider.translate(request) shouldBe CloudTranslationResult.Success(listOf("hello"))
            tokenCalls shouldBe 2
            translatedTokens.shouldContainExactly("token-1", "token-1", "token-2")
        }

        "embedded cloud configuration validates all fields and expiry day" {
            val dayStart = 1_788_220_800_000L
            isInternalCloudConfigurationValid(true, "a", "b", "c", "d", "2026-09-01", dayStart) shouldBe true
            isInternalCloudConfigurationValid(true, "a", "b", "c", "d", "2026-08-31", dayStart) shouldBe false
            isInternalCloudConfigurationValid(true, "", "b", "c", "d", "2026-09-01", dayStart) shouldBe false
            isInternalCloudConfigurationValid(true, "a", "b", "c", "d", "bad-date", dayStart) shouldBe false
        }
    })
