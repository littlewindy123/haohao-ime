// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TelephoneKeypadLayoutTest :
    StringSpec({
        "four symbol targets fill the same height as three digit rows" {
            val cells = telephoneKeyBounds(1200, 720)
            cells.size shouldBe 21
            cells.take(4).map { it.y } shouldBe listOf(0, 135, 270, 405)
            cells.take(4).map { it.height } shouldBe listOf(135, 135, 135, 135)
            cells[4] shouldBe TelephoneKeyBounds(198, 0, 268, 180)
            cells[7] shouldBe TelephoneKeyBounds(1002, 0, 198, 180)
            cells[18] shouldBe TelephoneKeyBounds(466, 540, 268, 180)
            cells[3].y + cells[3].height shouldBe cells[16].y
        }

        "portrait narrow one-handed and landscape bounds partition the entire input surface" {
            for ((width, height) in listOf(320 to 200, 393 to 227, 265 to 227, 1200 to 720, 2400 to 480, 411 to 251)) {
                val cells = telephoneKeyBounds(width, height)
                cells.sumOf { it.width * it.height } shouldBe width * height
                cells.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= width && it.y + it.height <= height } shouldBe true
                cells.forEachIndexed { index, a ->
                    cells.drop(index + 1).forEach { b ->
                        (a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height) shouldBe false
                    }
                }
            }
        }
    })
