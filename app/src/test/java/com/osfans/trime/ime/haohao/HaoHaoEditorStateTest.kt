/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.text.InputType
import android.view.KeyEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HaoHaoEditorStateTest :
    StringSpec({
        "selection availability follows range and password state" {
            HaoHaoEditorState().withSelection(2, 5).canCutOrCopy shouldBe true
            HaoHaoEditorState().withSelection(5, 5).canCutOrCopy shouldBe false
            HaoHaoEditorState(passwordEditor = true).withSelection(2, 5).canCutOrCopy shouldBe false
        }

        "selection mode transitions follow editing actions" {
            val selecting = HaoHaoEditorState().afterSuccessfulAction(HaoHaoEditorAction.SelectAll)
            selecting.selectionMode shouldBe true

            selecting.afterSuccessfulAction(HaoHaoEditorAction.MoveLeft).selectionMode shouldBe true
            selecting.afterSuccessfulAction(HaoHaoEditorAction.Copy).selectionMode shouldBe false
            selecting.afterSuccessfulAction(HaoHaoEditorAction.Cut).selectionMode shouldBe false
            selecting.afterSuccessfulAction(HaoHaoEditorAction.Paste).selectionMode shouldBe false
            selecting.afterSuccessfulAction(HaoHaoEditorAction.Backspace).selectionMode shouldBe false
            selecting.afterSuccessfulAction(HaoHaoEditorAction.ForwardDelete).selectionMode shouldBe false
        }

        "input switch resets transient editor state" {
            HaoHaoEditorState(
                selectionStart = 2,
                selectionEnd = 5,
                selectionMode = true,
            ).resetForEditor(passwordEditor = true) shouldBe HaoHaoEditorState(passwordEditor = true)
        }

        "password input variations are detected without reading text" {
            isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) shouldBe true
            isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) shouldBe true
            isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) shouldBe true
            isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL) shouldBe false
            isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL) shouldBe false
        }

        "only navigation and deletion actions repeat" {
            HaoHaoEditorAction.entries.filter { it.repeatable } shouldBe listOf(
                HaoHaoEditorAction.MoveUp,
                HaoHaoEditorAction.MoveDown,
                HaoHaoEditorAction.MoveLeft,
                HaoHaoEditorAction.MoveRight,
                HaoHaoEditorAction.Backspace,
                HaoHaoEditorAction.ForwardDelete,
            )
            HAOHAO_EDITOR_REPEAT_INITIAL_DELAY_MS shouldBe 350L
            HAOHAO_EDITOR_REPEAT_INTERVAL_MS shouldBe 50L
        }

        "action executor maps selection movement and editing shortcuts" {
            val sentKeys = mutableListOf<Pair<Int, Boolean>>()
            val menuActions = mutableListOf<Triple<Int, Int, Boolean>>()
            val executor = HaoHaoEditorActionExecutor(
                sendKey = { keyCode, shift ->
                    sentKeys += keyCode to shift
                    true
                },
                performMenuAction = { menuAction, fallbackKeyCode, shift ->
                    menuActions += Triple(menuAction, fallbackKeyCode, shift)
                    true
                },
            )

            executor.execute(HaoHaoEditorAction.MoveLeft, HaoHaoEditorState(selectionMode = true)) shouldBe true
            executor.execute(HaoHaoEditorAction.Redo, HaoHaoEditorState()) shouldBe true

            sentKeys shouldBe listOf(KeyEvent.KEYCODE_DPAD_LEFT to true)
            menuActions shouldBe listOf(Triple(android.R.id.redo, KeyEvent.KEYCODE_Z, true))
        }

        "action executor blocks cut and copy in password fields" {
            var menuCalls = 0
            val executor = HaoHaoEditorActionExecutor(
                sendKey = { _, _ -> true },
                performMenuAction = { _, _, _ ->
                    menuCalls += 1
                    true
                },
            )
            val passwordSelection = HaoHaoEditorState(passwordEditor = true).withSelection(1, 3)

            executor.execute(HaoHaoEditorAction.Cut, passwordSelection) shouldBe false
            executor.execute(HaoHaoEditorAction.Copy, passwordSelection) shouldBe false
            menuCalls shouldBe 0
        }

        "repeat controller cancels pending callbacks when stopped" {
            val scheduled = mutableListOf<Pair<Runnable, Long>>()
            val removed = mutableListOf<Runnable>()
            val performed = mutableListOf<HaoHaoEditorAction>()
            val controller = HaoHaoEditorRepeatController(
                postDelayed = { runnable, delay -> scheduled += runnable to delay },
                removeCallback = { removed += it },
                perform = {
                    performed += it
                    true
                },
            )

            controller.start(HaoHaoEditorAction.MoveRight) shouldBe true
            performed shouldBe listOf(HaoHaoEditorAction.MoveRight)
            scheduled.map { it.second } shouldBe listOf(HAOHAO_EDITOR_REPEAT_INITIAL_DELAY_MS)
            controller.isRunning shouldBe true

            val pending = scheduled.single().first
            pending.run()
            performed shouldBe listOf(
                HaoHaoEditorAction.MoveRight,
                HaoHaoEditorAction.MoveRight,
            )
            scheduled.last().second shouldBe HAOHAO_EDITOR_REPEAT_INTERVAL_MS
            controller.stop()
            controller.isRunning shouldBe false
            removed.last() shouldBe pending
        }
    })
