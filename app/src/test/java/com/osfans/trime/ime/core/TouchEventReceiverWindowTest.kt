// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TouchEventReceiverWindowTest :
    StringSpec({
        "composition replay before attach or without a token never opens a popup" {
            canShowTouchReceiver(false, false, true, 100, 40) shouldBe false
            canShowTouchReceiver(true, false, true, 100, 40) shouldBe false
        }
        "unmeasured and hidden preedit never installs a zero origin touch window" {
            canShowTouchReceiver(true, true, true, 0, 40) shouldBe false
            canShowTouchReceiver(true, true, true, 100, 0) shouldBe false
            canShowTouchReceiver(true, true, false, 100, 40) shouldBe false
        }
        "measured attached visible preedit can receive touches" {
            canShowTouchReceiver(true, true, true, 100, 40) shouldBe true
        }
    })
