/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.view.KeyEvent
import com.osfans.trime.core.RimeLifecycle
import com.osfans.trime.core.RimeLifecycleRegistry
import com.osfans.trime.core.RimeRuntimeState
import com.osfans.trime.data.prefs.AppPrefs
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HaoHaoTypingErgonomicsTest :
    StringSpec({
        "height modes scale HaoHao keyboard only" {
            AppPrefs.Keyboard.KeyboardHeightMode.COMPACT.percent shouldBe 90
            AppPrefs.Keyboard.KeyboardHeightMode.STANDARD.percent shouldBe 100
            AppPrefs.Keyboard.KeyboardHeightMode.ROOMY.percent shouldBe 110

            scaleHaoHaoKeyboardHeight(264, AppPrefs.Keyboard.KeyboardHeightMode.COMPACT, true) shouldBe 238
            scaleHaoHaoKeyboardHeight(264, AppPrefs.Keyboard.KeyboardHeightMode.STANDARD, true) shouldBe 264
            scaleHaoHaoKeyboardHeight(264, AppPrefs.Keyboard.KeyboardHeightMode.ROOMY, true) shouldBe 290
            scaleHaoHaoKeyboardHeight(264, AppPrefs.Keyboard.KeyboardHeightMode.COMPACT, false) shouldBe 264
        }

        "one hand viewport reserves a fixed rail in portrait" {
            calculateHaoHaoKeyboardViewport(
                availableWidth = 360,
                themePadding = 6,
                railWidth = 52,
                mode = AppPrefs.Keyboard.OneHandMode.LEFT,
                landscape = false,
            ) shouldBe HaoHaoKeyboardViewport(leftInset = 6, rightInset = 52, contentWidth = 302)

            calculateHaoHaoKeyboardViewport(
                availableWidth = 411,
                themePadding = 6,
                railWidth = 52,
                mode = AppPrefs.Keyboard.OneHandMode.RIGHT,
                landscape = false,
            ) shouldBe HaoHaoKeyboardViewport(leftInset = 52, rightInset = 6, contentWidth = 353)
        }

        "one hand preference is retained but ignored in landscape" {
            calculateHaoHaoKeyboardViewport(
                availableWidth = 411,
                themePadding = 24,
                railWidth = 52,
                mode = AppPrefs.Keyboard.OneHandMode.LEFT,
                landscape = true,
            ) shouldBe HaoHaoKeyboardViewport(leftInset = 24, rightInset = 24, contentWidth = 363)
        }

        "gesture policy protects composition passwords and selections" {
            HaoHaoGesturePolicy.canSlideCursor(composing = false) shouldBe true
            HaoHaoGesturePolicy.canSlideCursor(composing = true) shouldBe false

            HaoHaoGesturePolicy.canSlideDelete(
                composing = false,
                password = false,
                selectionStart = 3,
                selectionEnd = 3,
            ) shouldBe true
            HaoHaoGesturePolicy.canSlideDelete(false, true, 3, 3) shouldBe false
            HaoHaoGesturePolicy.canSlideDelete(true, false, 3, 3) shouldBe false
            HaoHaoGesturePolicy.canSlideDelete(false, false, 2, 5) shouldBe false
        }

        "slide delete keeps Unicode code points intact and restores in order" {
            var text = "A😀B"
            val controller = HaoHaoSlideDeleteController(
                readPreviousCodePoint = { previousCodePoint(text) },
                deletePreviousCodePoint = {
                    val previous = previousCodePoint(text) ?: return@HaoHaoSlideDeleteController false
                    text = text.dropLast(previous.length)
                    true
                },
                restoreText = {
                    text += it
                    true
                },
            )

            controller.slide(-3) shouldBe 3
            text shouldBe ""
            controller.slide(2) shouldBe 2
            text shouldBe "A😀"
            controller.slide(1) shouldBe 1
            text shouldBe "A😀B"
        }

        "clearing slide state prevents stale restoration" {
            var text = "好"
            val controller = HaoHaoSlideDeleteController(
                readPreviousCodePoint = { previousCodePoint(text) },
                deletePreviousCodePoint = {
                    text = ""
                    true
                },
                restoreText = {
                    text += it
                    true
                },
            )

            controller.slide(-1) shouldBe 1
            controller.clear()
            controller.slide(1) shouldBe 0
            text shouldBe ""
        }

        "HaoHao shift commits one uppercase letter only outside composition" {
            HaoHaoShiftPolicy.shouldCommitSingleUppercase(
                asciiMode = false,
                composing = false,
                shifted = true,
                keyCode = KeyEvent.KEYCODE_A,
            ) shouldBe true
            HaoHaoShiftPolicy.shouldCommitSingleUppercase(false, true, true, KeyEvent.KEYCODE_A) shouldBe false
            HaoHaoShiftPolicy.shouldCommitSingleUppercase(true, false, true, KeyEvent.KEYCODE_A) shouldBe false
            HaoHaoShiftPolicy.shouldCommitSingleUppercase(false, false, false, KeyEvent.KEYCODE_A) shouldBe false
            HaoHaoShiftPolicy.shouldCommitSingleUppercase(false, false, true, KeyEvent.KEYCODE_SPACE) shouldBe false
            HaoHaoShiftPolicy.uppercaseFor(KeyEvent.KEYCODE_Z) shouldBe "Z"
        }

        "Rime startup failure returns lifecycle to a retryable stopped state" {
            val registry = RimeLifecycleRegistry()
            registry.emitState(RimeLifecycle.State.STARTING)
            registry.emitStartupFailed()

            registry.currentState shouldBe RimeLifecycle.State.STOPPED
            RimeRuntimeState.entries.map { it.name } shouldBe listOf("PREPARING", "READY", "FAILED")
        }
    })

private fun previousCodePoint(text: String): String? {
    if (text.isEmpty()) return null
    val codePoint = text.codePointBefore(text.length)
    return String(Character.toChars(codePoint))
}
