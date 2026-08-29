// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.setup

import android.content.Intent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SetupFlowTest :
    StringSpec({
        "progress follows the two permission-free setup steps" {
            SetupPage.entries.map { it.name } shouldBe listOf("Enable", "Select")
            SetupFlow.progressStep(0) shouldBe 1
            SetupFlow.progressStep(1) shouldBe 2
        }

        "ordinary launches always resume incomplete setup including Xiaomi installer intents" {
            SetupLaunchPolicy.shouldOpenSetup(null, false, true) shouldBe true
            SetupLaunchPolicy.shouldOpenSetup(Intent.ACTION_MAIN, false, true) shouldBe true
            SetupLaunchPolicy.shouldOpenSetup("vendor.installer.OPEN", false, true) shouldBe true
            SetupLaunchPolicy.shouldOpenSetup(Intent.ACTION_RUN, false, true) shouldBe false
            SetupLaunchPolicy.shouldOpenSetup(null, true, true) shouldBe false
            SetupLaunchPolicy.shouldOpenSetup(null, false, false) shouldBe false
        }

        "first undone step resumes incomplete setup" {
            SetupFlow.firstUndoneIndex(listOf(false, false)) shouldBe 0
            SetupFlow.firstUndoneIndex(listOf(true, false)) shouldBe 1
            SetupFlow.firstUndoneIndex(listOf(true, true)) shouldBe null
        }

        "a newly completed step advances once" {
            SetupFlow.nextIndexAfterSync(
                currentIndex = 0,
                wasDone = false,
                isDone = true,
                doneStates = listOf(true, false),
            ) shouldBe 1
        }

        "completed intermediate steps are skipped" {
            SetupFlow.nextIndexAfterSync(
                currentIndex = 0,
                wasDone = false,
                isDone = true,
                doneStates = listOf(true, true),
            ) shouldBe 1
        }

        "unchanged completion state does not advance" {
            SetupFlow.nextIndexAfterSync(
                0,
                wasDone = false,
                isDone = false,
                doneStates = listOf(false, false),
            ) shouldBe null
            SetupFlow.nextIndexAfterSync(
                0,
                wasDone = true,
                isDone = true,
                doneStates = listOf(true, false),
            ) shouldBe null
        }

        "the final step waits for start typing" {
            SetupFlow.nextIndexAfterSync(
                currentIndex = 1,
                wasDone = false,
                isDone = true,
                doneStates = listOf(true, true),
            ) shouldBe null
        }

        "default picker is available only after the IME is enabled" {
            SetupFlow.canOpenDefaultPicker(listOf(true, false)) shouldBe true
            SetupFlow.canOpenDefaultPicker(listOf(true, true)) shouldBe true
            SetupFlow.canOpenDefaultPicker(listOf(false, false)) shouldBe false
        }
    })
