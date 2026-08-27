/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.base.DEFAULT_SCHEMA_ID
import com.osfans.trime.data.base.SIMPLIFIED_SCHEMA_CUSTOM_PATCH
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

class HaoHaoDefaultsTest :
    StringSpec({
        val config = requireNotNull(
            Yaml.parseToYamlNode(
                File("src/main/assets/shared/haohao.trime.yaml").readText(),
            ).mapping,
        )
        val keyboards = requireNotNull(config["preset_keyboards"]?.mapping)

        fun keyboard(id: String): TextKeyboard = TextKeyboard.decode(requireNotNull(keyboards[id]?.mapping))

        fun clickTokens(keyboard: TextKeyboard): List<String> = keyboard.keys.mapNotNull { key ->
            (key.behaviors[KeyBehavior.CLICK] as? KeyActionToken.Plain)?.token
        }

        fun rowWidths(keyboard: TextKeyboard): List<Float> {
            val rows = mutableListOf<Float>()
            var current = 0f
            keyboard.keys.forEach { key ->
                current += key.width.takeIf { it > 0 } ?: keyboard.width
                if (current == 100f) {
                    rows += current
                    current = 0f
                }
            }
            current shouldBe 0f
            return rows
        }

        "fresh installs default to HaoHao theme and simplified Luna Pinyin" {
            DEFAULT_THEME_ID shouldBe "haohao.trime"
            DEFAULT_SCHEMA_ID shouldBe "luna_pinyin_simp"
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("- charset_filter") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("translator/enable_charset_filter: true") shouldBe true
            config["__include"]?.string shouldBe "trime:/"
            config["name"]?.string shouldBe "好好输入法"
        }

        "main keyboard is a four-row 26-key layout without technical shortcuts" {
            val main = keyboard("default")

            main.asciiMode shouldBe false
            main.resetAsciiMode shouldBe true
            main.lock shouldBe true
            rowWidths(main) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            clickTokens(main) shouldContainExactly listOf(
                "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
                "a", "s", "d", "f", "g", "h", "j", "k", "l",
                "z", "x", "c", "v", "b", "n", "m", "BackSpace",
                "HaoHaoNumber", "Mode_switch", ",", "HaoHaoSpace", ".", "HaoHaoReturn",
            )
            main.keys.all { key ->
                key.hint.isEmpty() &&
                    key.labelSymbol.isEmpty() &&
                    key.behaviors[KeyBehavior.LONG_CLICK] == null
            } shouldBe true
        }

        "letter-only schemas route qwerty to the HaoHao main keyboard" {
            keyboard("qwerty").importPreset shouldBe "default"
        }

        "number and common-symbol pages always provide a path back to letters" {
            val number = keyboard("number")
            val symbols = keyboard("symbols")

            rowWidths(number) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            rowWidths(symbols) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            clickTokens(number).contains("HaoHaoLetters") shouldBe true
            clickTokens(number).contains("HaoHaoSymbols") shouldBe true
            clickTokens(symbols).contains("HaoHaoLetters") shouldBe true
            clickTokens(symbols).contains("HaoHaoNumber") shouldBe true
        }
    })
