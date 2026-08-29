// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import com.osfans.trime.core.RimeRuntimeState

internal enum class ImeBootstrapState {
    PREPARING,
    READY,
    FAILED,
}

internal object ImeBootstrapPolicy {
    fun resolve(
        runtimeState: RimeRuntimeState,
        hasTheme: Boolean,
    ): ImeBootstrapState = when {
        runtimeState == RimeRuntimeState.FAILED -> ImeBootstrapState.FAILED
        runtimeState == RimeRuntimeState.READY && hasTheme -> ImeBootstrapState.READY
        else -> ImeBootstrapState.PREPARING
    }
}
