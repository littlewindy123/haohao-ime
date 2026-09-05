// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

/** Manages [Keyboard]s and their status. **/
@Deprecated("Migrate into KeyboardWindow")
object KeyboardSwitcher {
    private var reference = java.lang.ref.WeakReference<Keyboard>(null)
    var currentKeyboard: Keyboard
        get() = checkNotNull(reference.get()) { "Keyboard window is not active" }
        set(value) {
            reference = java.lang.ref.WeakReference(value)
        }
}
