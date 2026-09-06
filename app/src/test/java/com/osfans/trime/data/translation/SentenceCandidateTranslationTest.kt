// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.translation

import com.osfans.trime.ime.candidates.compact.isTranslationIssue
import com.osfans.trime.ime.candidates.compact.needsSentenceTranslationLane
import com.osfans.trime.ime.candidates.compact.sentencePreviewText
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val cloudContext = SentenceCandidateContext(CandidateTranslationSourceMode.CLOUD_ONLY, "test-provider", true, true)

private suspend fun SentenceCandidateTranslationSession.awaitState(status: SentenceCandidateStatus) {
    withTimeout(2000) { while (state.status != status) delay(5) }
}

class SentenceCandidateTranslationTest :
    StringSpec({
        "waiting and translating never insert status copy into the English lane" {
            for (status in listOf(SentenceCandidateStatus.IDLE, SentenceCandidateStatus.WAITING, SentenceCandidateStatus.TRANSLATING, SentenceCandidateStatus.READY)) {
                status.isTranslationIssue() shouldBe false
            }
            for (status in listOf(SentenceCandidateStatus.TOO_LONG, SentenceCandidateStatus.UNAVAILABLE, SentenceCandidateStatus.FAILED)) {
                status.isTranslationIssue() shouldBe true
            }
        }
        "expanded translation contains English only without repeating the Chinese source" {
            sentencePreviewText(SentenceCandidateState("我爱你", "I love you", SentenceCandidateStatus.READY)) shouldBe "I love you"
            sentencePreviewText(SentenceCandidateState("我爱你", status = SentenceCandidateStatus.WAITING)) shouldBe ""
        }
        "I love you is already usable from the old cache without a cloud call" {
            coroutineScope {
                var requests = 0
                val session = SentenceCandidateTranslationSession(this, {
                    requests++
                    error("must not call")
                }, { text, _ -> if (text == "我爱你") "I love you" else null }, {})
                session.update("我爱你", cloudContext)
                session.state.translation shouldBe "I love you"
                session.state.status shouldBe SentenceCandidateStatus.READY
                requests shouldBe 0
            }
        }
        "sentence path preserves punctuation digits and more than four words" {
            coroutineScope {
                val translation = "I love you, and I will see you at 8:30 tomorrow!"
                val session = SentenceCandidateTranslationSession(this, {
                    it.purpose shouldBe TranslationPurpose.SENTENCE
                    it.texts shouldBe listOf("我爱你，明天八点半见")
                    CloudTranslationResult.Success(listOf(translation))
                }, { _, _ -> null }, {}, debounceMillis = 1)
                session.update("我爱你，明天八点半见", cloudContext)
                session.awaitState(SentenceCandidateStatus.READY)
                session.state.translation shouldBe translation
            }
        }
        "full 200 code points are accepted but 201 are not silently truncated" {
            coroutineScope {
                var count = 0
                val session = SentenceCandidateTranslationSession(this, {
                    count++
                    it.texts.single().codePointCount(0, it.texts.single().length) shouldBe 200
                    CloudTranslationResult.Success(listOf("An intact sentence."))
                }, { _, _ -> null }, {}, debounceMillis = 1)
                session.update("好".repeat(199) + "😀", cloudContext)
                session.awaitState(SentenceCandidateStatus.READY)
                session.update("好".repeat(200) + "😀", cloudContext)
                session.state.status shouldBe SentenceCandidateStatus.TOO_LONG
                session.state.translation shouldBe null
                count shouldBe 1
            }
        }
        "rapid typing debounces to the last first candidate only" {
            coroutineScope {
                val requests = mutableListOf<String>()
                val session = SentenceCandidateTranslationSession(this, {
                    requests += it.texts.single()
                    CloudTranslationResult.Success(listOf("I love you"))
                }, { _, _ -> null }, {}, debounceMillis = 50)
                session.update("我", cloudContext)
                session.update("我爱", cloudContext)
                session.update("我爱你", cloudContext)
                session.awaitState(SentenceCandidateStatus.READY)
                requests shouldBe listOf("我爱你")
            }
        }
        "repeated rendering does not restart the pending request" {
            coroutineScope {
                var count = 0
                val session = SentenceCandidateTranslationSession(this, {
                    count++
                    CloudTranslationResult.Success(listOf("I love you"))
                }, { _, _ -> null }, {}, debounceMillis = 30)
                repeat(10) { session.update("我爱你", cloudContext) }
                session.awaitState(SentenceCandidateStatus.READY)
                repeat(10) { session.update("我爱你", cloudContext) }
                count shouldBe 1
            }
        }
        "a cancelled non cooperative old result cannot replace the new sentence" {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val session = SentenceCandidateTranslationSession(this, {
                    if (it.texts.single() == "旧句子") {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            CloudTranslationResult.Success(listOf("old"))
                        }
                    } else {
                        CloudTranslationResult.Success(listOf("new"))
                    }
                }, { _, _ -> null }, {}, debounceMillis = 1)
                session.update("旧句子", cloudContext)
                entered.await()
                session.update("新句子", cloudContext)
                session.awaitState(SentenceCandidateStatus.READY)
                release.complete(Unit)
                delay(30)
                session.state.source shouldBe "新句子"
                session.state.translation shouldBe "new"
            }
        }
        "hide editor and preference reset clear the session and its cache" {
            coroutineScope {
                var count = 0
                val session = SentenceCandidateTranslationSession(this, {
                    count++
                    CloudTranslationResult.Success(listOf("I love you"))
                }, { _, _ -> null }, {}, debounceMillis = 1)
                session.update("我爱你", cloudContext)
                session.awaitState(SentenceCandidateStatus.READY)
                session.invalidate()
                session.update("我爱你", cloudContext)
                session.state.status shouldBe SentenceCandidateStatus.READY
                count shouldBe 1
                session.invalidate(clearCache = true)
                session.state shouldBe SentenceCandidateState()
                session.update("我爱你", cloudContext)
                session.awaitState(SentenceCandidateStatus.READY)
                count shouldBe 2
            }
        }
        "local only never requests cloud and refreshes when the local dictionary becomes ready" {
            coroutineScope {
                var cached: String? = null
                val session = SentenceCandidateTranslationSession(this, { error("no network") }, { _, mode ->
                    mode shouldBe CandidateTranslationSourceMode.LOCAL_ONLY
                    cached
                }, {})
                val local = cloudContext.copy(sourceMode = CandidateTranslationSourceMode.LOCAL_ONLY)
                session.update("我爱你", local)
                session.state.status shouldBe SentenceCandidateStatus.UNAVAILABLE
                cached = "I love you"
                session.update("我爱你", local)
                session.state.translation shouldBe cached
            }
        }
        "sensitive editors and disabled translation do not even consult caches" {
            coroutineScope {
                val session = SentenceCandidateTranslationSession(this, { error("no network") }, { _, _ -> error("no lookup") }, {})
                session.update("我爱你", cloudContext.copy(editorAllowed = false))
                session.state shouldBe SentenceCandidateState()
                session.update("我爱你", cloudContext.copy(enabled = false))
                session.state shouldBe SentenceCandidateState()
            }
        }
        "consent expired credentials and missing configuration report a failure without a request" {
            coroutineScope {
                val session = SentenceCandidateTranslationSession(this, { error("no network") }, { _, _ -> null }, {})
                for (failure in listOf(CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED, CloudTranslationResult.Failure.Kind.CONFIGURATION_EXPIRED, CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)) {
                    session.update("我爱你", cloudContext.copy(cloudFailure = failure))
                    session.state.status shouldBe SentenceCandidateStatus.FAILED
                    session.state.failure shouldBe failure
                }
            }
        }
        "source and provider changes cannot reuse another configuration result" {
            coroutineScope {
                var count = 0
                val session = SentenceCandidateTranslationSession(this, {
                    count++
                    CloudTranslationResult.Success(listOf("result $count"))
                }, { _, _ -> null }, {}, debounceMillis = 1)
                session.update("测试", cloudContext)
                session.awaitState(SentenceCandidateStatus.READY)
                session.update("测试", cloudContext.copy(providerFingerprint = "other"))
                session.awaitState(SentenceCandidateStatus.READY)
                session.state.translation shouldBe "result 2"
                session.update("测试", cloudContext.copy(sourceMode = CandidateTranslationSourceMode.LOCAL_ONLY))
                session.state.status shouldBe SentenceCandidateStatus.UNAVAILABLE
            }
        }
        "network failures have no retry loop but explicit retry recovers" {
            coroutineScope {
                var count = 0
                val session = SentenceCandidateTranslationSession(this, {
                    count++
                    if (count == 1) {
                        CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK)
                    } else {
                        CloudTranslationResult.Success(listOf("Recovered."))
                    }
                }, { _, _ -> null }, {}, debounceMillis = 1)
                session.update("测试", cloudContext)
                session.awaitState(SentenceCandidateStatus.FAILED)
                repeat(4) { session.update("测试", cloudContext) }
                count shouldBe 1
                session.retry()
                session.awaitState(SentenceCandidateStatus.READY)
                session.state.translation shouldBe "Recovered."
            }
        }
        "invalid payload and timeout remain explicit failures" {
            coroutineScope {
                for (result in listOf(CloudTranslationResult.Success(listOf("")), CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.TIMEOUT))) {
                    val session = SentenceCandidateTranslationSession(this, { result }, { _, _ -> null }, {}, debounceMillis = 1)
                    session.update("测试", cloudContext)
                    session.awaitState(SentenceCandidateStatus.FAILED)
                    session.state.translation shouldBe null
                }
            }
        }
        "phrases punctuation and overwide words choose the full width lane" {
            needsSentenceTranslationLane("I love you", 90, 100) shouldBe true
            needsSentenceTranslationLane("Hello!", 55, 100) shouldBe true
            needsSentenceTranslationLane("extraordinary", 115, 100) shouldBe true
            needsSentenceTranslationLane("computer", 70, 100) shouldBe false
            needsSentenceTranslationLane("don't", 50, 100) shouldBe false
        }
    })
