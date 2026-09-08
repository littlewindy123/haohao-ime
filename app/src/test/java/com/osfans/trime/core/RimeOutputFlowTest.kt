/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class RimeOutputFlowTest :
    FunSpec({
        test("translation snapshots are immutable and only accompany their exact submitted Chinese") {
            runBlocking {
                val output = LosslessRimeCommitFlow()
                val original = CommitSentence("我爱你", "I love you!", 4)
                val received = async { output.flow.take(3).toList() }
                output.publish(CommitProto("我爱你"), 11, original)
                output.publish(CommitProto("我"), 11, original)
                output.publish(CommitProto("我爱你"), 12, null)
                val events = received.await()
                events[0].sentence shouldBe original
                events[1].sentence shouldBe null
                events[2].sentence shouldBe null
                events.map { it.inputSessionId } shouldContainExactly listOf(11L, 11L, 12L)
            }
        }
        test("one thousand commits are delivered without loss or reordering") {
            runBlocking {
                val output = LosslessRimeCommitFlow()
                val received = async {
                    output.flow.take(1_000).map { it.commit.text }.toList()
                }

                repeat(1_000) { index ->
                    output.publish(CommitProto(index.toString()), inputSessionId = 7) shouldBe true
                }

                received.await() shouldContainExactly (0 until 1_000).map(Int::toString)
            }
        }

        test("empty commits do not consume delivery slots") {
            runBlocking {
                val output = LosslessRimeCommitFlow()
                output.publish(CommitProto(""), inputSessionId = 7) shouldBe true
                val received = async { output.flow.take(1).toList() }
                output.publish(CommitProto("你好"), inputSessionId = 9) shouldBe true
                received.await().single() shouldBe RimeCommitEvent(CommitProto("你好"), 9)
            }
        }
    })
