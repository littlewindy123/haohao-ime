// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import com.osfans.trime.core.RimeRuntimeState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ImeBootstrapPolicyTest :
    StringSpec({
        "input view waits until both Rime and theme are ready" {
            ImeBootstrapPolicy.resolve(RimeRuntimeState.PREPARING, hasTheme = false) shouldBe
                ImeBootstrapState.PREPARING
            ImeBootstrapPolicy.resolve(RimeRuntimeState.READY, hasTheme = false) shouldBe
                ImeBootstrapState.PREPARING
            ImeBootstrapPolicy.resolve(RimeRuntimeState.READY, hasTheme = true) shouldBe
                ImeBootstrapState.READY
        }

        "Rime failure wins even before a theme is available" {
            ImeBootstrapPolicy.resolve(RimeRuntimeState.FAILED, hasTheme = false) shouldBe
                ImeBootstrapState.FAILED
            ImeBootstrapPolicy.resolve(RimeRuntimeState.FAILED, hasTheme = true) shouldBe
                ImeBootstrapState.FAILED
        }
    })
