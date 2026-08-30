/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class PinyinCorrectionUiTest :
    StringSpec({
        "deployment starts before waiting for runtime readiness" {
            val calls = mutableListOf<String>()

            val result = runBlocking {
                confirmSuccessfulDeployment(
                    startDeployment = { calls += "start" },
                    awaitRuntimeReady = { calls += "ready" },
                )
            }

            result shouldBe true
            calls shouldContainExactly listOf("start", "ready")
        }

        "deployment start failure does not wait for runtime readiness" {
            var readyWaited = false

            shouldThrow<IllegalStateException> {
                runBlocking {
                    confirmSuccessfulDeployment(
                        startDeployment = { error("Unable to start Rime") },
                        awaitRuntimeReady = { readyWaited = true },
                    )
                }
            }

            readyWaited shouldBe false
        }

        "runtime activation failure propagates to the rollback path" {
            shouldThrow<IllegalStateException> {
                runBlocking {
                    confirmSuccessfulDeployment(
                        startDeployment = {},
                        awaitRuntimeReady = { error("Rime runtime failed") },
                    )
                }
            }
        }
    })
