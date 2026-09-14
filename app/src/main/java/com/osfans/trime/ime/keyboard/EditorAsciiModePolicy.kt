/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Pure editor classification; numeric editors keep their layout even with FORCE_ASCII. */
internal fun editorKeyboardTarget(inputType: Int, imeOptions: Int): String =
    when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME,
        -> "number"
        else -> {
            val forceAscii = imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0
            val textAscii = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                inputType and InputType.TYPE_MASK_VARIATION in setOf(
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                )
            if (forceAscii || textAscii) ".ascii" else ""
        }
    }

internal fun resolveAsciiKeyboard(
    currentAscii: String?,
    lastLockedAscii: String?,
    lastLockedId: String,
    currentId: String,
    available: Collection<String>,
): String =
    listOfNotNull(currentAscii, lastLockedAscii, lastLockedId, currentId)
        .firstOrNull { it.isNotEmpty() && it in available } ?: currentId

internal fun keyboardAsciiMode(requireAscii: Boolean, configuredMode: Boolean): Boolean =
    requireAscii || configuredMode

/** A forced editor never toggles out of ASCII, or needs to read the old engine option. */
internal inline fun asciiModeToggleTarget(requireAscii: Boolean, currentMode: () -> Boolean): Boolean =
    requireAscii || !currentMode()

/**
 * Owned only by the ordered Rime command queue, never by layout callbacks.
 * [currentMode] must be read from the engine when the editor barrier executes, not when posted.
 */
internal class EditorAsciiModePolicy {
    private var savedMode: Boolean? = null

    fun onStartInput(requireAscii: Boolean, currentMode: Boolean, normalMode: Boolean?): Boolean? {
        if (requireAscii) {
            if (savedMode == null) savedMode = currentMode
            // A required write must survive even when the current/cached mode is already true.
            return true
        }
        val restoredMode = savedMode
        savedMode = null
        // Restoration is one-shot: subsequent ordinary editor/manual changes keep their policy.
        return restoredMode ?: normalMode
    }
}
