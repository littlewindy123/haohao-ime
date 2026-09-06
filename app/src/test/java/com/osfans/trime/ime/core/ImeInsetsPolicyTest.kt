// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ImeInsetsPolicyTest :
    StringSpec({
        "ready keyboard and bottom loading panel expose only their actual area" {
            ImeInsetsPolicy.contentTop(2550, true, 1592, 958, 696) shouldBe 1592
            ImeInsetsPolicy.contentTop(2550, true, 1854, 696, 696) shouldBe 1854
            ImeInsetsPolicy.contentTop(2550, true, 400, 2150, 696) shouldBe 400
        }
        "missing detached and unmeasured panels use bounded fallback geometry" {
            for (top in listOf(null, 0, -1, 2600)) {
                ImeInsetsPolicy.contentTop(2550, true, top, 0, 696) shouldBe 1854
            }
        }
        "stale geometry after rotation cannot claim the entire window" {
            ImeInsetsPolicy.contentTop(1000, true, 1592, 958, 464) shouldBe 536
            ImeInsetsPolicy.contentTop(1000, true, 700, 958, 464) shouldBe 536
        }
        "hidden input never retains a touch region or a previous keyboard inset" {
            ImeInsetsPolicy.contentTop(2550, false, 1592, 958, 696) shouldBe 2550
        }
        "zero sized windows and excessive fallbacks are safe" {
            ImeInsetsPolicy.contentTop(0, true, 0, 0, 696) shouldBe 0
            ImeInsetsPolicy.contentTop(-1, true, null, 0, 696) shouldBe 0
            ImeInsetsPolicy.contentTop(1, true, 0, 1, 696) shouldBe 1
            ImeInsetsPolicy.contentTop(400, true, null, 0, 696) shouldBe 200
        }
    })
