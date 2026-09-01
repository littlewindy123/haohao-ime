/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class RimeInputPipelineTest :
    FunSpec({
        test("one thousand keys remain lossless and ordered while presentations coalesce") {
            runBlocking {
                val processed = mutableListOf<Int>()
                var presentationCount = 0
                val pipeline =
                    RimeInputPipeline(
                        scope = this,
                        flushPresentation = { presentationCount += 1 },
                    )

                repeat(1_000) { value ->
                    pipeline.postKey { processed += value }
                }
                pipeline.awaitIdle()

                processed shouldContainExactly (0 until 1_000).toList()
                presentationCount shouldBe 1
                pipeline.performanceSnapshot.value.processedKeyCount shouldBe 1_000
                pipeline.performanceSnapshot.value.discardedPresentationSnapshots shouldBe 999
                pipeline.close()
            }
        }

        test("barriers preserve ordering and split presentation batches") {
            runBlocking {
                val processed = mutableListOf<String>()
                var presentationCount = 0
                val pipeline =
                    RimeInputPipeline(
                        scope = this,
                        flushPresentation = { presentationCount += 1 },
                    )

                pipeline.postKey { processed += "key-1" }
                pipeline.postBarrier { processed += "barrier" }
                pipeline.postKey { processed += "key-2" }
                pipeline.awaitIdle()

                processed shouldContainExactly listOf("key-1", "barrier", "key-2")
                presentationCount shouldBe 2
                pipeline.close()
            }
        }

        test("a failed command does not kill later input") {
            runBlocking {
                val processed = mutableListOf<Int>()
                val failures = mutableListOf<Throwable>()
                val pipeline =
                    RimeInputPipeline(
                        scope = this,
                        flushPresentation = {},
                        onFailure = failures::add,
                    )

                pipeline.postKey { processed += 1 }
                pipeline.postKey { error("synthetic failure") }
                pipeline.postKey { processed += 3 }
                pipeline.awaitIdle()

                processed shouldContainExactly listOf(1, 3)
                failures.size shouldBe 1
                pipeline.performanceSnapshot.value.processedKeyCount shouldBe 3
                pipeline.close()
            }
        }

        test("candidate selection accepts only the presentation it was rendered from") {
            isCurrentPresentation(expectedVersion = 42, currentVersion = 42) shouldBe true
            isCurrentPresentation(expectedVersion = 41, currentVersion = 42) shouldBe false
        }

        test("commits from an old input field cannot enter the new input field") {
            isCurrentInputSession(commitSessionId = 4, activeSessionId = 4) shouldBe true
            isCurrentInputSession(commitSessionId = 3, activeSessionId = 4) shouldBe false
        }

        test("closing the service cancels pending input and rejects later commands") {
            runBlocking {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val pipeline =
                    RimeInputPipeline(
                        scope = this,
                        flushPresentation = {},
                    )

                pipeline.postKey {
                    started.complete(Unit)
                    release.await()
                }
                started.await()
                pipeline.close()
                yield()

                pipeline.postKey {} shouldBe false
                pipeline.performanceSnapshot.value.queueDepth shouldBe 0
            }
        }
    })
