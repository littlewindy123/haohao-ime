// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.window

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BoardWindowAttachmentPolicyTest :
    StringSpec({
        "reopening the same resident keyboard must not detach or animate it" {
            repeat(30) { shouldReuseAttachedWindow(true, true) shouldBe true }
        }
        "a different board or missing child must still attach" {
            shouldReuseAttachedWindow(false, true) shouldBe false
            shouldReuseAttachedWindow(true, false) shouldBe false
            shouldReuseAttachedWindow(false, false) shouldBe false
        }
    })
