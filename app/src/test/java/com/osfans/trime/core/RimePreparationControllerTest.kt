/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class RimePreparationControllerTest :
    StringSpec({
        var now = 1_000L
        lateinit var controller: RimePreparationController

        beforeTest {
            now = 1_000L
            controller = RimePreparationController(clock = { now })
        }

        "a maintenance-free startup becomes ready only after schema activation" {
            val attempt = controller.beginAttempt(autoRetry = false)
            controller.advance(attempt, RimePreparationPhase.NATIVE_INITIALIZATION) shouldBe true
            controller.advance(attempt, RimePreparationPhase.SCHEMA_ACTIVATION) shouldBe true

            controller.runtimeState.value shouldBe RimeRuntimeState.PREPARING
            controller.markReady(attempt) shouldBe true
            controller.runtimeState.value shouldBe RimeRuntimeState.READY
        }

        "asynchronous maintenance success keeps the engine preparing until completion" {
            val attempt = controller.beginAttempt(autoRetry = false)
            controller.advance(attempt, RimePreparationPhase.MAINTENANCE_DEPLOYMENT) shouldBe true

            controller.runtimeState.value shouldBe RimeRuntimeState.PREPARING
            now += 420L
            controller.advance(attempt, RimePreparationPhase.SCHEMA_ACTIVATION) shouldBe true
            controller.markReady(attempt) shouldBe true

            controller.snapshot.value.phaseDurationsMillis[RimePreparationPhase.MAINTENANCE_DEPLOYMENT] shouldBe 420L
        }

        "theme completion records its final phase duration" {
            val attempt = controller.beginAttempt(autoRetry = false)
            controller.advance(attempt, RimePreparationPhase.THEME_INITIALIZATION) shouldBe true
            now += 75L

            controller.finishCurrentPhase(attempt) shouldBe true

            controller.snapshot.value.phaseDurationsMillis[RimePreparationPhase.THEME_INITIALIZATION] shouldBe 75L
        }

        "a retryable failure requests exactly one automatic retry" {
            val first = controller.beginAttempt(autoRetry = false)
            controller.fail(first, RimeFailureCode.MAINTENANCE_FAILURE, "deploy failed") shouldBe
                RimeRecoveryAction.RETRY

            val second = controller.beginAttempt(autoRetry = true)
            controller.snapshot.value.autoRetryCount shouldBe 1
            controller.fail(second, RimeFailureCode.MAINTENANCE_TIMEOUT, "timeout") shouldBe
                RimeRecoveryAction.STOP
            controller.runtimeState.value shouldBe RimeRuntimeState.FAILED
        }

        "late callbacks from an expired attempt cannot overwrite the retry" {
            val first = controller.beginAttempt(autoRetry = false)
            controller.fail(first, RimeFailureCode.MAINTENANCE_TIMEOUT, "timeout") shouldBe
                RimeRecoveryAction.RETRY
            val second = controller.beginAttempt(autoRetry = true)

            controller.markReady(first) shouldBe false
            controller.advance(first, RimePreparationPhase.SCHEMA_ACTIVATION) shouldBe false
            controller.runtimeState.value shouldBe RimeRuntimeState.PREPARING
            controller.snapshot.value.attemptId shouldBe second
        }

        "stopping invalidates callbacks before a later restart begins" {
            val first = controller.beginAttempt(autoRetry = false)
            val stopped = controller.invalidateCurrentAttempt()

            controller.markReady(first) shouldBe false
            controller.isPreparing(stopped) shouldBe true
            controller.snapshot.value.failureCode shouldBe null
        }

        "duplicate failures from the same attempt are ignored" {
            val attempt = controller.beginAttempt(autoRetry = false)

            controller.fail(attempt, RimeFailureCode.MAINTENANCE_FAILURE, "first") shouldBe
                RimeRecoveryAction.RETRY
            controller.fail(attempt, RimeFailureCode.MAINTENANCE_FAILURE, "late") shouldBe
                RimeRecoveryAction.IGNORE
            controller.snapshot.value.failureMessage shouldBe "first"
        }

        "data and space failures stop for explicit repair instead of retrying" {
            val dataAttempt = controller.beginAttempt(autoRetry = false)
            controller.fail(dataAttempt, RimeFailureCode.DATA_CORRUPTION, "bad checksum") shouldBe
                RimeRecoveryAction.STOP

            val spaceAttempt = controller.beginAttempt(autoRetry = false)
            controller.fail(spaceAttempt, RimeFailureCode.INSUFFICIENT_SPACE, "no space") shouldBe
                RimeRecoveryAction.STOP
        }

        "a preparing lifecycle can stop before it ever becomes ready" {
            val lifecycle = RimeLifecycleRegistry()

            lifecycle.emitState(RimeLifecycle.State.STARTING)
            lifecycle.emitState(RimeLifecycle.State.STOPPING)
            lifecycle.emitState(RimeLifecycle.State.STOPPED)

            lifecycle.currentState shouldBe RimeLifecycle.State.STOPPED
        }

        "diagnostics keep only three UTF-8 safe failures within 64 KiB" {
            val separator = "\n--- failure ---\n"
            val previous = (1..4).joinToString(separator) { "failure-$it-错误" }

            val history = boundedDiagnosticFailureHistory(previous, "failure-5-错误", currentBytes = 60 * 1024)

            history.split(separator) shouldContainExactly
                listOf("failure-3-错误", "failure-4-错误", "failure-5-错误")
            (history.toByteArray(Charsets.UTF_8).size + 60 * 1024 <= 64 * 1024) shouldBe true

            val truncated = boundedDiagnosticFailureHistory(
                previous = "",
                record = "错误".repeat(40_000),
                currentBytes = 1_024,
            )
            (truncated.toByteArray(Charsets.UTF_8).size + 1_024 <= 64 * 1024) shouldBe true
            truncated.contains('\uFFFD') shouldBe false
        }
    })
