/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EditorAsciiModePolicyTest :
    FunSpec({
        test("password and email text variations require the ASCII keyboard") {
            listOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            ).forEach { variation ->
                editorKeyboardTarget(InputType.TYPE_CLASS_TEXT or variation, 0) shouldBe ".ascii"
            }
        }

        test("numeric phone and datetime editors retain the numeric layout with FORCE_ASCII") {
            listOf(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME,
            ).forEach { inputType ->
                editorKeyboardTarget(inputType, 0) shouldBe "number"
                editorKeyboardTarget(inputType, EditorInfo.IME_FLAG_FORCE_ASCII) shouldBe "number"
            }
        }

        test("FORCE_ASCII also applies to ordinary and unclassified editors") {
            editorKeyboardTarget(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_FORCE_ASCII) shouldBe ".ascii"
            editorKeyboardTarget(InputType.TYPE_NULL, EditorInfo.IME_FLAG_FORCE_ASCII) shouldBe ".ascii"
            editorKeyboardTarget(InputType.TYPE_CLASS_TEXT, 0) shouldBe ""
            editorKeyboardTarget(InputType.TYPE_NULL, 0) shouldBe ""
        }

        test("number to password resolves the last locked keyboard ASCII companion") {
            resolveAsciiKeyboard("", "english", "default", "number", listOf("default", "english", "number")) shouldBe "english"
        }

        test("ASCII keyboard resolution validates each fallback") {
            val available = listOf("default", "english", "custom", "number")
            resolveAsciiKeyboard("custom", "english", "default", "number", available) shouldBe "custom"
            resolveAsciiKeyboard("missing", "english", "default", "number", available) shouldBe "english"
            resolveAsciiKeyboard(null, "missing", "default", "number", available) shouldBe "default"
            resolveAsciiKeyboard(null, null, "missing", "number", available) shouldBe "number"
        }

        test("Chinese mode survives number password and email then restores once") {
            val policy = EditorAsciiModePolicy()
            policy.onStartInput(true, false, true) shouldBe true
            policy.onStartInput(true, true, false) shouldBe true
            policy.onStartInput(true, true, false) shouldBe true
            policy.onStartInput(false, true, true) shouldBe false
            policy.onStartInput(false, false, null) shouldBe null
        }

        test("English mode is restored even when the normal keyboard default is Chinese") {
            val policy = EditorAsciiModePolicy()
            policy.onStartInput(true, true, true) shouldBe true
            policy.onStartInput(true, true, false) shouldBe true
            policy.onStartInput(false, true, false) shouldBe true
            policy.onStartInput(false, true, false) shouldBe false
        }

        test("a new forced sequence captures a later manual language selection") {
            val policy = EditorAsciiModePolicy()
            policy.onStartInput(true, false, null) shouldBe true
            policy.onStartInput(false, true, null) shouldBe false
            // The user switches to English after restoration; it is not a permanent override.
            policy.onStartInput(true, true, null) shouldBe true
            policy.onStartInput(false, true, false) shouldBe true
        }

        test("required force and restore writes are returned even when current mode matches") {
            val policy = EditorAsciiModePolicy()
            policy.onStartInput(true, true, null) shouldBe true
            policy.onStartInput(false, true, false) shouldBe true
        }

        test("rapid queued editor transitions read engine mode at execution time") {
            val policy = EditorAsciiModePolicy()
            var engineMode = false
            val queue = mutableListOf<() -> Unit>()
            val writes = mutableListOf<Boolean>()
            // A queued user toggle is not yet reflected by any statusCached at posting time.
            queue += { engineMode = true }
            listOf(true, true, false).forEach { requireAscii ->
                queue += {
                    policy.onStartInput(requireAscii, engineMode, false)?.let {
                        writes += it
                        engineMode = it
                    }
                }
            }
            queue.forEach { it() }
            writes shouldBe listOf(true, true, true)
            engineMode shouldBe true
        }

        test("forced editor wins over attach defaults while ordinary manual modes remain available") {
            keyboardAsciiMode(true, false) shouldBe true
            keyboardAsciiMode(true, true) shouldBe true
            keyboardAsciiMode(false, false) shouldBe false
            keyboardAsciiMode(false, true) shouldBe true
        }

        test("language toggle preserves forced ASCII and ordinary toggles in all four mode combinations") {
            asciiModeToggleTarget(true) { false } shouldBe true
            asciiModeToggleTarget(true) { true } shouldBe true
            asciiModeToggleTarget(false) { false } shouldBe true
            asciiModeToggleTarget(false) { true } shouldBe false
        }

        test("forced language toggle does not read the previous engine option") {
            asciiModeToggleTarget(true) { error("Forced ASCII must not query or invert the old mode") } shouldBe true
        }

        test("ordinary editors respect optional focus reset without an earlier forced editor") {
            val policy = EditorAsciiModePolicy()
            policy.onStartInput(false, true, null) shouldBe null
            policy.onStartInput(false, true, false) shouldBe false
            policy.onStartInput(false, false, true) shouldBe true
        }
    })
