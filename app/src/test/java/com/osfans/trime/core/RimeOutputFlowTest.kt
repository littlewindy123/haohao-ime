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
