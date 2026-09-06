// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.window

internal fun shouldReuseAttachedWindow(sameWindow: Boolean, viewStillAttached: Boolean): Boolean = sameWindow && viewStillAttached
