/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.base.BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH
import com.osfans.trime.data.base.DEFAULT_SCHEMA_ID
import com.osfans.trime.data.base.LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH
import com.osfans.trime.data.base.SIMPLIFIED_SCHEMA_CUSTOM_PATCH
import com.osfans.trime.data.base.managedSchemaDisplayName
import com.osfans.trime.data.base.upgradeSimplifiedSchemaCustomPatch
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.float
import com.osfans.trime.util.yaml.int
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Properties

class HaoHaoDefaultsTest :
    StringSpec({
        val config = requireNotNull(
            Yaml.parseToYamlNode(
                File("src/main/assets/shared/haohao.trime.yaml").readText(),
            ).mapping,
        )
        val keyboards = requireNotNull(config["preset_keyboards"]?.mapping)
        val presetKeys = requireNotNull(config["preset_keys"]?.mapping)
        val style = requireNotNull(config["style"]?.mapping)
        val colorSchemes = requireNotNull(config["preset_color_schemes"]?.mapping)
        val wanxiangMetadata =
            Properties().apply {
                File("dictionary/wanxiang/source.properties").inputStream().use(::load)
            }

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
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("schema/name: 好好拼音") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("translator/dictionary: haohao_pinyin") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("translator/user_dict: luna_pinyin") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("- charset_filter") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("translator/enable_charset_filter: true") shouldBe true
            config["__include"]?.string shouldBe "trime:/"
            config["name"]?.string shouldBe "好好输入法"
            DEFAULT_FOLLOW_SYSTEM_DAY_NIGHT shouldBe true
        }

        "HaoHao Pinyin combines pinned Wanxiang data with hotword and translation overrides" {
            val composite = File("src/main/assets/shared/haohao_pinyin.dict.yaml").readText()
            val hotwords = File("src/main/assets/shared/haohao_hotwords.dict.yaml").readText()
            val translations = File("dictionary/cc-cedict/common_overrides_zh_en.tsv").readText()

            (composite.indexOf("  - haohao_hotwords") < composite.indexOf("  - haohao_wanxiang_core")) shouldBe true
            (composite.indexOf("  - haohao_wanxiang_core") < composite.indexOf("  - luna_pinyin")) shouldBe true
            hotwords.contains("塔斯汀\tta si ting\t1000000") shouldBe true
            hotwords.contains("老师\tlao shi\t1000000") shouldBe true
            hotwords.contains("搭子\tda zi\t300000") shouldBe true
            translations.contains("塔斯汀\tTastien") shouldBe true
            wanxiangMetadata.getProperty("release") shouldBe "v17.7.1"
            wanxiangMetadata.getProperty("entryCount") shouldBe "1418352"
            wanxiangMetadata.getProperty("sha256") shouldBe
                "ca3e83cd3ff1b6896a055c26cd24dc98b79f2c1fe56acd983eb7479a319b4240"
        }

        "Pixel theme defines stable geometry and light-dark palettes" {
            style["key_height"]?.int shouldBe 56
            style["key_text_size"]?.float shouldBe 23f
            style["horizontal_gap"]?.int shouldBe 5
            style["vertical_gap"]?.int shouldBe 6
            style["round_corner"]?.float shouldBe 8f
            style["key_press_offset_y"]?.float shouldBe 1f
            style["candidate_corner_radius"]?.float shouldBe 8f

            val light = requireNotNull(colorSchemes["default"]?.mapping)
            val dark = requireNotNull(colorSchemes["haohao_dark"]?.mapping)
            light["dark_scheme"]?.string shouldBe "haohao_dark"
            light["keyboard_back_color"]?.int shouldBe 0xf2f3f5
            light["key_back_color"]?.int shouldBe 0xffffff
            light["on_key_back_color"]?.int shouldBe 0x4f7df3
            dark["keyboard_back_color"]?.int shouldBe 0x1f2125
            dark["key_back_color"]?.int shouldBe 0x2b2e34
            dark["on_key_back_color"]?.int shouldBe 0x7ea2ff
        }

        "functional keys use compact icon labels" {
            presetKeys["BackSpace"]?.mapping?.get("label")?.string shouldBe "⌫"
            presetKeys["Shift_L"]?.mapping?.get("label")?.string shouldBe "⇧"
            presetKeys["Shift_L"]?.mapping?.get("send")?.string shouldBe "Shift_L"
            presetKeys["HaoHaoReturn"]?.mapping?.get("label")?.string shouldBe "↵"
            val spaceLabel = presetKeys["HaoHaoSpace"]?.mapping?.get("label")?.string
            spaceLabel shouldBe " "
            spaceLabel?.isNotEmpty() shouldBe true
            spaceLabel?.isBlank() shouldBe true
            listOf(
                "BackSpace",
                "Shift_L",
                "Mode_switch",
                "HaoHaoNumber",
                "HaoHaoSymbols",
                "HaoHaoLetters",
                "HaoHaoReturn",
            ).all { id -> presetKeys[id]?.mapping?.get("functional")?.boolean == true } shouldBe true
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
                "Shift_L", "z", "x", "c", "v", "b", "n", "m", "BackSpace",
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

        "managed simplified schema patch upgrades without overwriting user data" {
            val expected = SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()

            upgradeSimplifiedSchemaCustomPatch(LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()) shouldBe expected
            upgradeSimplifiedSchemaCustomPatch(BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()) shouldBe expected
            upgradeSimplifiedSchemaCustomPatch("${LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()}\n# user change") shouldBe null
            upgradeSimplifiedSchemaCustomPatch(expected) shouldBe null
        }

        "managed simplified schema name only changes the branded default schema" {
            managedSchemaDisplayName("luna_pinyin_simp", "朙月拼音·简化字") shouldBe "好好拼音"
            managedSchemaDisplayName("other_schema", "Other") shouldBe "Other"
        }

        "number and common-symbol pages always provide a path back to letters" {
            val number = keyboard("number")
            val symbols = keyboard("symbols")

            rowWidths(number) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            rowWidths(symbols) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            clickTokens(number).contains("HaoHaoLetters") shouldBe true
            clickTokens(number).contains("HaoHaoSymbols") shouldBe true
            clickTokens(number).contains("HaoHaoSpace") shouldBe true
            clickTokens(number).contains(".") shouldBe true
            clickTokens(symbols).contains("HaoHaoLetters") shouldBe true
            clickTokens(symbols).contains("HaoHaoSpace") shouldBe true
            clickTokens(symbols).contains("HaoHaoNumber") shouldBe true
            clickTokens(symbols).contains("；") shouldBe true
        }
    })
