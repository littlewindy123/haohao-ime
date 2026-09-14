/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import com.osfans.trime.ime.core.RimeInputPipeline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class RimeCommitContextTest :
    FunSpec({
        test("commit metadata and native key use one dispatch while editor hooks stay on caller") {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
                val dispatchCount = AtomicInteger()
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        dispatchCount.incrementAndGet()
                        engine.dispatch(context, block)
                    }
                }
                runBlocking {
                    val caller = Thread.currentThread()
                    val sentence = CommitSentence("你好", "Hello", 7)
                    var applied: RimeCommitContext? = null
                    val events = mutableListOf<String>()

                    val handled = withRimeCommitContext(42, sentence) {
                        Thread.currentThread() shouldBe caller
                        events += "editor hook"
                        val result = runOnRimeDispatcher(
                            dispatcher,
                            applyCommitContext = {
                                (Thread.currentThread() == caller) shouldBe false
                                applied = it
                                events += "context"
                            },
                        ) {
                            applied shouldBe RimeCommitContext(42, sentence)
                            events += "native key"
                            true
                        }
                        Thread.currentThread() shouldBe caller
                        events += "editor result"
                        result
                    }

                    handled shouldBe true
                    dispatchCount.get() shouldBe 1
                    events shouldContainExactly listOf("editor hook", "context", "native key", "editor result")
                    currentCoroutineContext()[RimeCommitContext] shouldBe null
                }
            }
        }

        test("interleaved commands restore their own session and clear missing sentence metadata") {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                runBlocking {
                    val sentence = CommitSentence("你好", "Hello", 7)
                    val first = RimeCommitContext(11, sentence)
                    val second = RimeCommitContext(22, null)
                    val firstProcessed = CompletableDeferred<Unit>()
                    val secondProcessed = CompletableDeferred<Unit>()
                    val observed = mutableListOf<RimeCommitContext?>()
                    var active: RimeCommitContext? = null
                    suspend fun processKey() = runOnRimeDispatcher(dispatcher, { active = it }) {
                        observed += active
                    }

                    val firstCommand = async {
                        withRimeCommitContext(first.inputSessionId, first.sentence) {
                            processKey()
                            firstProcessed.complete(Unit)
                            secondProcessed.await()
                            processKey()
                        }
                    }
                    val secondCommand = async {
                        firstProcessed.await()
                        withRimeCommitContext(second.inputSessionId, second.sentence) {
                            processKey()
                        }
                        secondProcessed.complete(Unit)
                    }
                    firstCommand.await()
                    secondCommand.await()

                    observed shouldContainExactly listOf(first, second, first)
                    currentCoroutineContext()[RimeCommitContext] shouldBe null
                }
            }
        }

        test("nested failures and cancellation restore the outer command context") {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                runBlocking {
                    val outer = RimeCommitContext(11, null)
                    val observed = mutableListOf<RimeCommitContext>()
                    withRimeCommitContext(outer.inputSessionId, outer.sentence) {
                        shouldThrow<IllegalStateException> {
                            withRimeCommitContext(22, null) {
                                runOnRimeDispatcher(dispatcher, { observed += it }) {
                                    error("synthetic native failure")
                                }
                            }
                        }
                        currentCoroutineContext()[RimeCommitContext] shouldBe outer
                        shouldThrow<CancellationException> {
                            withRimeCommitContext(33, null) {
                                runOnRimeDispatcher(dispatcher, { observed += it }) {
                                    throw CancellationException("synthetic cancelled command")
                                }
                            }
                        }
                        currentCoroutineContext()[RimeCommitContext] shouldBe outer
                        runOnRimeDispatcher(dispatcher, { observed += it }) {}
                    }
                    observed shouldContainExactly listOf(RimeCommitContext(22, null), RimeCommitContext(33, null), outer)
                    currentCoroutineContext()[RimeCommitContext] shouldBe null
                }
            }
        }

        test("direct engine operations without a command retain explicitly installed metadata") {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                runBlocking {
                    val explicit = RimeCommitContext(71, CommitSentence("你好", "Hello", 9))
                    var active = explicit
                    val result = runOnRimeDispatcher(dispatcher, { active = it }) { active }

                    result shouldBe explicit
                    currentCoroutineContext()[RimeCommitContext] shouldBe null
                }
            }
        }

        test("batch flush keeps pending commit metadata and finishes before the next session barrier") {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                runBlocking {
                    val firstSentence = CommitSentence("你好", "Hello", 7)
                    val first = RimeCommitContext(11, firstSentence)
                    val second = RimeCommitContext(22, null)
                    var active = RimeCommitContext(0, null)
                    var pendingCommit: CommitProto? = null
                    val output = LosslessRimeCommitFlow()
                    val received = async { output.flow.take(2).toList() }
                    val pipeline = RimeInputPipeline(
                        scope = this,
                        clockNanos = { 0L },
                        flushPresentation = {
                            currentCoroutineContext()[RimeCommitContext] shouldBe null
                            runOnRimeDispatcher(dispatcher, { active = it }) {
                                pendingCommit?.let { output.publish(it, active.inputSessionId, active.sentence) }
                                pendingCommit = null
                            }
                        },
                    )
                    try {
                        pipeline.postKey {
                            withRimeCommitContext(first.inputSessionId, first.sentence) {
                                runOnRimeDispatcher(dispatcher, { active = it }) {
                                    pendingCommit = CommitProto("你好")
                                }
                            }
                        }
                        pipeline.postKey {
                            // An editor-only hook must not relabel an older native commit.
                            withRimeCommitContext(99, null) {}
                        }
                        pipeline.postBarrier {
                            withRimeCommitContext(second.inputSessionId, second.sentence) {
                                runOnRimeDispatcher(dispatcher, { active = it }) {
                                    pendingCommit shouldBe null
                                }
                            }
                        }
                        pipeline.postKey {
                            withRimeCommitContext(second.inputSessionId, second.sentence) {
                                runOnRimeDispatcher(dispatcher, { active = it }) {
                                    pendingCommit = CommitProto("再见")
                                }
                            }
                        }
                        val events = withTimeout(5_000) {
                            pipeline.awaitIdle()
                            received.await()
                        }
                        events shouldContainExactly listOf(
                            RimeCommitEvent(CommitProto("你好"), first.inputSessionId, firstSentence),
                            RimeCommitEvent(CommitProto("再见"), second.inputSessionId, null),
                        )
                        currentCoroutineContext()[RimeCommitContext] shouldBe null
                    } finally {
                        pipeline.close()
                        received.cancel()
                    }
                }
            }
        }
    })
