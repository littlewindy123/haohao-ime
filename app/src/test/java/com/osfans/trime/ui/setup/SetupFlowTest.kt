// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.setup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SetupFlowTest :
    StringSpec({
        "progress follows the three real setup steps" {
            SetupFlow.progressStep(0) shouldBe 1
            SetupFlow.progressStep(1) shouldBe 2
            SetupFlow.progressStep(2) shouldBe 3
        }

        "first undone step resumes incomplete setup" {
            SetupFlow.firstUndoneIndex(listOf(true, false, false)) shouldBe 1
            SetupFlow.firstUndoneIndex(listOf(true, true, false)) shouldBe 2
            SetupFlow.firstUndoneIndex(listOf(true, true, true)) shouldBe null
        }

        "a newly completed step advances once" {
            SetupFlow.nextIndexAfterSync(
                currentIndex = 0,
                wasDone = false,
                isDone = true,
                doneStates = listOf(true, false, false),
            ) shouldBe 1
        }

        "completed intermediate steps are skipped" {
            SetupFlow.nextIndexAfterSync(
                currentIndex = 0,
                wasDone = false,
                isDone = true,
                doneStates = listOf(true, true, false),
            ) shouldBe 2
        }

        "unchanged completion state does not advance" {
            SetupFlow.nextIndexAfterSync(
                0,
                wasDone = false,
                isDone = false,
                doneStates = listOf(false, false, false),
            ) shouldBe null
            SetupFlow.nextIndexAfterSync(
                0,
                wasDone = true,
                isDone = true,
                doneStates = listOf(true, false, false),
            ) shouldBe null
        }

        "the final step waits for start typing" {
            SetupFlow.nextIndexAfterSync(
                currentIndex = 2,
                wasDone = false,
                isDone = true,
                doneStates = listOf(true, true, true),
            ) shouldBe null
        }
    })
