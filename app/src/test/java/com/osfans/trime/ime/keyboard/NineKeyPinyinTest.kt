// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class NineKeyPinyinTest :
    StringSpec({
        "continuous pinyin maps to telephone digits" {
            nineKeyDigits("nihao") shouldBe "64426"
            nineKeyDigits("woaini") shouldBe "962464"
            nineKeyDigits("xian") shouldBe "9426"
        }
        "filter offers only complete matching syllables before a separator" {
            val syllables = listOf("mi", "ni", "n", "hao", "gan", "han", "gao", "xi", "xia", "xian")
            nineKeySpellings("64426", syllables) shouldBe listOf("mi", "ni", "n")
            nineKeySpellings("94'26", syllables) shouldBe listOf("xi")
            nineKeySpellings("9426", syllables) shouldBe listOf("xian", "xia", "xi")
            nineKeySpellings("", syllables) shouldBe emptyList()
        }
        "nine key targets fill the keyboard without overlap at every tested size" {
            listOf(360 to 252, 800 to 180, 280 to 252).forEach { (width, height) ->
                val cells = telephoneKeyBounds(width, height, nineKey = true)
                cells.sumOf { it.width * it.height } shouldBe width * height
                for (a in cells.indices) {
                    for (b in 0 until a) {
                        val x = cells[a]
                        val y = cells[b]
                        (x.x < y.x + y.width && x.x + x.width > y.x && x.y < y.y + y.height && x.y + x.height > y.y) shouldBe false
                    }
                }
                cells[18].width shouldBe (width * .35f).toInt()
            }
        }
    })
