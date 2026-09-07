/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.core.RimeRuntimeState
import com.osfans.trime.data.base.BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH
import com.osfans.trime.data.base.DEFAULT_SCHEMA_ID
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.base.LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH
import com.osfans.trime.data.base.SIMPLIFIED_SCHEMA_CUSTOM_PATCH
import com.osfans.trime.data.base.alignManagedRimeSourceTimestamps
import com.osfans.trime.data.base.invalidateCompiledThemeData
import com.osfans.trime.data.base.invalidatePrebuiltRimeData
import com.osfans.trime.data.base.managedSchemaDisplayName
import com.osfans.trime.data.base.migrateLegacyRimeData
import com.osfans.trime.data.base.pinyinCorrectionSha256
import com.osfans.trime.data.base.repairManagedRimeData
import com.osfans.trime.data.base.upgradeSimplifiedSchemaCustomPatch
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.model.replaceHaoHaoToolbarAction
import com.osfans.trime.data.theme.model.resolveHaoHaoToolbarActions
import com.osfans.trime.data.translation.CloudTranslationResult
import com.osfans.trime.ime.bar.ui.toolButtonIconFrameSizeDp
import com.osfans.trime.ime.haohao.HAOHAO_EDITOR_ACTION
import com.osfans.trime.ime.haohao.HAOHAO_EDITOR_KEY
import com.osfans.trime.ime.haohao.HAOHAO_INPUT_FOOTPRINTS_ACTION
import com.osfans.trime.ime.haohao.HAOHAO_INPUT_FOOTPRINTS_KEY
import com.osfans.trime.ime.haohao.HAOHAO_TRANSLATION_KEY
import com.osfans.trime.ime.haohao.HaoHaoToolAvailability
import com.osfans.trime.ime.haohao.HaoHaoToolUnavailableReason
import com.osfans.trime.ime.haohao.HaoHaoToolboxAction
import com.osfans.trime.ime.haohao.resolveHaoHaoToolAvailability
import com.osfans.trime.ime.keyboard.HaoHaoModeLabel
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeySurfaceRect
import com.osfans.trime.ime.keyboard.calculateKeySurfaceGeometry
import com.osfans.trime.ime.keyboard.calculateKeyVerticalPadding
import com.osfans.trime.ime.keyboard.resolveHaoHaoModeLabel
import com.osfans.trime.ui.main.settings.prioritizeHaoHaoPalettes
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.float
import com.osfans.trime.util.yaml.int
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files
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
        val decodedStyle = GeneralStyle.decode(style)
        val colorSchemes = requireNotNull(config["preset_color_schemes"]?.mapping)
        val toolBar = requireNotNull(config["tool_bar"]?.mapping)
        val wanxiangMetadata =
            Properties().apply {
                File("dictionary/wanxiang/source.properties").inputStream().use(::load)
            }

        fun keyboard(id: String): TextKeyboard = TextKeyboard.decode(requireNotNull(keyboards[id]?.mapping))

        "new palette choices appear first without removing inherited user choices" {
            val ids = listOf("inherited", "haohao_graphite", "haohao_mist", "default", "haohao_apricot", "another")
            val result = prioritizeHaoHaoPalettes(ids.map { ColorScheme(it, emptyMap()) })
            result.map { it.id } shouldBe listOf("default", "haohao_mist", "haohao_apricot", "haohao_graphite", "inherited", "another")
        }

        "compact typography shares regular system fonts and leaves room inside symbol keys" {
            decodedStyle.candidateFont shouldBe listOf("system:sans-serif")
            decodedStyle.keyFont shouldBe decodedStyle.candidateFont
            decodedStyle.commentFont shouldBe decodedStyle.candidateFont
            decodedStyle.symbolFont shouldBe decodedStyle.candidateFont
            for ((layout, token) in listOf("default" to "HaoHaoSymbols", "number" to "HaoHaoNumberSymbols")) {
                val key = keyboard(layout).keys.single {
                    (it.behaviors[KeyBehavior.CLICK] as? KeyActionToken.Plain)?.token == token
                }
                key.keyTextSize shouldBe 16f
            }
            toolButtonIconFrameSizeDp(32) shouldBe 40
        }

        "three new palettes each have a complete reversible night pair and readable text" {
            val required = requireNotNull(colorSchemes["default"]?.mapping).keys.mapNotNull { it.string }
                .filterNot { it in setOf("dark_scheme", "light_scheme") }
            fun luminance(rgb: Int): Double {
                fun channel(shift: Int): Double {
                    val value = ((rgb shr shift) and 255) / 255.0
                    return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
                }
                return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
            }
            for (id in listOf("haohao_mist", "haohao_apricot", "haohao_graphite")) {
                val light = requireNotNull(colorSchemes[id]?.mapping)
                val dark = requireNotNull(colorSchemes["${id}_dark"]?.mapping)
                light["dark_scheme"]?.string shouldBe "${id}_dark"
                dark["light_scheme"]?.string shouldBe id
                for (palette in listOf(light, dark)) {
                    required.forEach { palette[it] shouldNotBe null }
                    for ((foreground, background) in listOf(
                        "key_text_color" to "key_back_color",
                        "candidate_text_color" to "candidate_background",
                        "comment_text_color" to "candidate_background",
                        "on_key_text_color" to "on_key_back_color",
                    )) {
                        val a = luminance(requireNotNull(palette[foreground]?.int))
                        val b = luminance(requireNotNull(palette[background]?.int))
                        ((maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05) >= 4.5) shouldBe true
                    }
                }
            }
        }

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
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("translator/max_word_length: 6") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("translator/enable_correction: false") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("- haohao_script_translator") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("- pinyin:/abbreviation") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("spelling_correction") shouldBe false
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("key_correction") shouldBe false
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("- charset_filter") shouldBe true
            SIMPLIFIED_SCHEMA_CUSTOM_PATCH.contains("translator/enable_charset_filter: true") shouldBe true
            config["__include"]?.string shouldBe "trime:/"
            config["config_version"]?.string shouldBe "3.3"
            config["name"]?.string shouldBe "好好输入法"
            DEFAULT_FOLLOW_SYSTEM_DAY_NIGHT shouldBe true

            DataManager.SCHEMA_LIST_CUSTOM_PATCH
                .lines()
                .filter { it.trimStart().startsWith("- schema:") }
                .map { it.substringAfter(":").trim() } shouldContainExactly listOf(DEFAULT_SCHEMA_ID, "haohao_pinyin_9")
        }

        "HaoHao Pinyin combines pinned Wanxiang data with hotword and translation overrides" {
            val composite = File("dictionary/rime-prebuilt/compile-shared/haohao_pinyin.dict.yaml").readText()
            val hotwords = File("dictionary/rime-prebuilt/compile-shared/haohao_hotwords.dict.yaml").readText()
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

        "compact theme defines reference geometry and light-dark palettes" {
            style["key_height"]?.int shouldBe 50
            style["key_cap_height"]?.int shouldBe 0
            style["candidate_text_size"]?.float shouldBe 20f
            style["candidate_view_height"]?.int shouldBe 40
            style["comment_text_size"]?.float shouldBe 12f
            style["key_text_size"]?.float shouldBe 20f
            style["key_long_text_size"]?.float shouldBe 16f
            style["symbol_text_size"]?.float shouldBe 11f
            style["label_text_size"]?.float shouldBe 16f
            style["popup_text_size"]?.float shouldBe 16f
            style["horizontal_gap"]?.int shouldBe 3
            style["vertical_gap"]?.int shouldBe 8
            style["keyboard_padding"]?.int shouldBe 3
            style["keyboard_height"]?.int shouldBe 252
            style["round_corner"]?.float shouldBe 7f
            style["key_border"]?.int shouldBe 1
            style["key_press_offset_y"]?.float shouldBe 1f
            style["key_shadow_offset_y"]?.float shouldBe 1f
            style["candidate_corner_radius"]?.float shouldBe 8f
            decodedStyle.compactCandidateTextSize shouldBe 20f
            decodedStyle.compactTranslationTextSize shouldBe 13f
            decodedStyle.compactPhoneticTextSize shouldBe 10f
            decodedStyle.keyCapHeight shouldBe 0

            val preedit = requireNotNull(config["preedit"]?.mapping)
            requireNotNull(preedit["foreground"]?.mapping)["font_size"]?.float shouldBe 14f

            val candidateWindow = requireNotNull(config["window"]?.mapping)
            val windowForeground = requireNotNull(candidateWindow["foreground"]?.mapping)
            windowForeground["label_font_size"]?.float shouldBe 12f
            windowForeground["text_font_size"]?.float shouldBe 16f
            windowForeground["comment_font_size"]?.float shouldBe 12f

            val light = requireNotNull(colorSchemes["default"]?.mapping)
            val dark = requireNotNull(colorSchemes["haohao_dark"]?.mapping)
            light["dark_scheme"]?.string shouldBe "haohao_dark"
            light["keyboard_back_color"]?.int shouldBe 0xe7e2d8
            light["key_back_color"]?.int shouldBe 0xfffdf8
            light["key_border_color"]?.int shouldBe 0xe3ddd2
            light["key_shadow_color"]?.int shouldBe 0x1f513a32
            light["key_highlight_color"]?.int shouldBe 0x66ffffff
            light["key_symbol_color"]?.int shouldBe 0xaeb2b8
            light["off_key_symbol_color"]?.int shouldBe 0xaeb2b8
            light["toolbar_icon_color"]?.int shouldBe 0x7a6860
            light["hilited_toolbar_icon_color"]?.int shouldBe 0x426c54
            light["off_key_back_color"]?.int shouldBe 0xdcece2
            light["on_key_back_color"]?.int shouldBe 0xf4bf61
            dark["keyboard_back_color"]?.int shouldBe 0x24231f
            dark["key_back_color"]?.int shouldBe 0x302d28
            dark["key_border_color"]?.int shouldBe 0x45413a
            dark["key_shadow_color"]?.int shouldBe 0x66000000
            dark["key_highlight_color"]?.int shouldBe 0x24ffffff
            dark["key_symbol_color"]?.int shouldBe 0x767c86
            dark["off_key_symbol_color"]?.int shouldBe 0x767c86
            dark["toolbar_icon_color"]?.int shouldBe 0x9298a2
            dark["hilited_toolbar_icon_color"]?.int shouldBe 0xf0bd60
            dark["off_key_back_color"]?.int shouldBe 0x354d41
            dark["on_key_back_color"]?.int shouldBe 0xe0a947
        }

        "layered key surface keeps the visual gutter inside a complete touch cell" {
            val resting = calculateKeySurfaceGeometry(
                width = 36,
                height = 58,
                paddingLeft = 2,
                paddingTop = 4,
                paddingRight = 2,
                paddingBottom = 4,
                shadowOffsetY = 2,
                pressOffsetX = 0,
                pressOffsetY = 1,
                pressed = false,
            )
            resting.logicalCell shouldBe KeySurfaceRect(0, 0, 36, 58)
            resting.cap shouldBe KeySurfaceRect(2, 4, 34, 54)
            resting.shadow shouldBe KeySurfaceRect(2, 6, 34, 56)
            resting.logicalCell.contains(1, 1) shouldBe true
            resting.cap.contains(1, 1) shouldBe false
            val adjacentCell = resting.logicalCell.offset(36, 0)
            resting.logicalCell.contains(35, 29) shouldBe true
            resting.logicalCell.contains(36, 29) shouldBe false
            adjacentCell.contains(35, 29) shouldBe false
            adjacentCell.contains(36, 29) shouldBe true

            val pressed = calculateKeySurfaceGeometry(
                width = 36,
                height = 58,
                paddingLeft = 2,
                paddingTop = 4,
                paddingRight = 2,
                paddingBottom = 4,
                shadowOffsetY = 2,
                pressOffsetX = 0,
                pressOffsetY = 1,
                pressed = true,
            )
            pressed.cap shouldBe KeySurfaceRect(2, 5, 34, 55)
            pressed.shadow shouldBe null
        }

        "height modes keep reference caps centered inside touch rows" {
            listOf(
                58 to 49,
                63 to 54,
                68 to 59,
            ).forEach { (rowHeight, capHeight) ->
                val (top, bottom) = calculateKeyVerticalPadding(
                    cellHeight = rowHeight,
                    minimumVerticalGap = 9,
                    capHeight = capHeight,
                )
                kotlin.math.abs(top - bottom) shouldBeLessThanOrEqual 1
                rowHeight - top - bottom shouldBe capHeight
            }
        }

        "360 393 and 411dp viewports keep reference caps and continuous touch cells" {
            val density = 3
            val sidePadding = 3 * density
            val halfGap = 4
            val expectedCapWidths = mapOf(360 to 98, 393 to 108, 411 to 113)
            val (paddingTop, paddingBottom) = calculateKeyVerticalPadding(
                cellHeight = 58 * density,
                minimumVerticalGap = 9 * density,
                capHeight = 49 * density,
            )

            expectedCapWidths.forEach { (viewportDp, expectedCapWidth) ->
                val availableWidth = viewportDp * density - sidePadding * 2
                val cellWidth = availableWidth / 10
                val geometry = calculateKeySurfaceGeometry(
                    width = cellWidth,
                    height = 58 * density,
                    paddingLeft = halfGap,
                    paddingTop = paddingTop,
                    paddingRight = halfGap,
                    paddingBottom = paddingBottom,
                    shadowOffsetY = 2 * density,
                    pressOffsetX = 0,
                    pressOffsetY = density,
                    pressed = false,
                )

                geometry.cap.right - geometry.cap.left shouldBe expectedCapWidth
                geometry.cap.bottom - geometry.cap.top shouldBe 49 * density
                repeat(9) { index ->
                    val current = geometry.logicalCell.offset(index * cellWidth, 0)
                    val next = geometry.logicalCell.offset((index + 1) * cellWidth, 0)
                    current.right shouldBe next.left
                    current.contains(current.right - 1, current.bottom / 2) shouldBe true
                    next.contains(current.right, current.bottom / 2) shouldBe true
                }
            }
        }

        "themes without layered depth retain their original key surface" {
            calculateKeyVerticalPadding(
                cellHeight = 58,
                minimumVerticalGap = 9,
                capHeight = 0,
            ) shouldBe (4 to 4)
            val geometry = calculateKeySurfaceGeometry(
                width = 36,
                height = 58,
                paddingLeft = 2,
                paddingTop = 3,
                paddingRight = 2,
                paddingBottom = 3,
                shadowOffsetY = 0,
                pressOffsetX = 0,
                pressOffsetY = 1,
                pressed = true,
            )
            geometry.cap shouldBe KeySurfaceRect(2, 3, 34, 55)
            geometry.shadow shouldBe null
        }

        "functional keys use compact icon labels" {
            presetKeys["BackSpace"]?.mapping?.get("label")?.string shouldBe "ic@backspace_outline"
            presetKeys["Shift_L"]?.mapping?.get("label")?.string shouldBe "ic@apple_keyboard_shift"
            presetKeys["Shift_L"]?.mapping?.get("send")?.string shouldBe "Shift_L"
            presetKeys["Shift_L"]?.mapping?.get("shift_lock")?.string shouldBe "long"
            presetKeys["Mode_switch"]?.mapping?.get("states")?.sequence?.mapNotNull { it.string } shouldContainExactly
                listOf("中", "英")
            resolveHaoHaoModeLabel(asciiMode = false) shouldBe HaoHaoModeLabel("中", "英", 20f, 10f)
            resolveHaoHaoModeLabel(asciiMode = true) shouldBe HaoHaoModeLabel("英", "中", 20f, 10f)
            presetKeys["HaoHaoReturn"]?.mapping?.get("label")?.string shouldBe "ic@keyboard_return"
            val spaceLabel = presetKeys["HaoHaoSpace"]?.mapping?.get("label")?.string
            spaceLabel shouldBe "ic@keyboard_space"
            spaceLabel?.isNotEmpty() shouldBe true
            spaceLabel?.isBlank() shouldBe false
            presetKeys["HaoHaoSpace"]?.mapping?.get("slide_cursor")?.boolean shouldBe true
            presetKeys["BackSpace"]?.mapping?.get("slide_delete")?.boolean shouldBe true
            listOf(
                "BackSpace",
                "Shift_L",
                "Mode_switch",
                "HaoHaoNumber",
                "HaoHaoNumberSymbols",
                "HaoHaoNumberBack",
                "HaoHaoSymbols",
                "HaoHaoLetters",
                "HaoHaoReturn",
            ).all { id -> presetKeys[id]?.mapping?.get("functional")?.boolean == true } shouldBe true
        }

        "HaoHao idle toolbar follows the six reference positions and retains clipboard access" {
            val primaryButton = requireNotNull(toolBar["primary_button"]?.mapping)
            val foreground = requireNotNull(primaryButton["foreground"]?.mapping)
            val decodedToolBar = ToolBar.decode(toolBar)

            primaryButton["action"]?.string shouldBe "HaoHaoToolbox"
            primaryButton["size"]?.sequence?.mapNotNull { it.int } shouldContainExactly listOf(48, 48)
            foreground["style"]?.string shouldBe "ic@view_grid_outline"
            decodedToolBar.equalWidth shouldBe true
            decodedToolBar.builtinIconSize shouldBe 18
            decodedToolBar.builtinIconColor shouldBe "toolbar_icon_color"
            decodedToolBar.builtinIconHighlightColor shouldBe "hilited_toolbar_icon_color"
            ToolBar.decode(null).equalWidth shouldBe false
            ToolBar.decode(null).builtinIconSize shouldBe 24
            ToolBar.decode(null).builtinIconColor shouldBe "candidate_text_color"
            ToolBar.decode(null).builtinIconHighlightColor shouldBe "hilited_candidate_text_color"
            decodedToolBar.equalWidthButtonsInDisplayOrder().all { button ->
                button.size == listOf(48, 48) &&
                    button.foreground.fontSize == 18f &&
                    button.foreground.normal == "toolbar_icon_color" &&
                    button.foreground.highlight == "hilited_toolbar_icon_color"
            } shouldBe true
            toolButtonIconFrameSizeDp(decodedToolBar.builtinIconSize) shouldBe 26

            val toolbarActions = decodedToolBar.buttons.map { it.action }
            toolbarActions shouldContainExactly listOf(
                "Hide",
                "HaoHaoKeyboardMenu",
                "liquid_keyboard_emoji",
                "HaoHaoEditor",
                "VOICE_ASSIST",
            )
            decodedToolBar.equalWidthButtonsInDisplayOrder().map { it.action } shouldContainExactly listOf(
                "HaoHaoToolbox",
                "HaoHaoKeyboardMenu",
                "liquid_keyboard_emoji",
                "HaoHaoEditor",
                "VOICE_ASSIST",
                "Hide",
            )
            decodedToolBar.buttons.single { it.action == "HaoHaoEditor" }
                .longPressAction shouldBe "clipboard_window"

            val toolboxKey = requireNotNull(presetKeys["HaoHaoToolbox"]?.mapping)
            toolboxKey["send"]?.string shouldBe "FUNCTION"
            toolboxKey["command"]?.string shouldBe "haohao_toolbox"
            presetKeys["HaoHaoTranslation"]?.mapping?.get("send")?.string shouldBe "FUNCTION"
            presetKeys["HaoHaoTranslation"]?.mapping?.get("command")?.string shouldBe "haohao_translation"
            presetKeys["HaoHaoSymbols"]?.mapping?.get("label")?.string shouldBe "符"
        }

        "HaoHao toolbox exposes clipboard phrases and customization alongside existing tools" {
            HaoHaoToolboxAction.entries.map { it.actionToken } shouldContainExactly listOf(
                "clipboard_window", "HaoHaoPhrases", "", "",
                HAOHAO_EDITOR_KEY,
                HAOHAO_TRANSLATION_KEY,
                HAOHAO_INPUT_FOOTPRINTS_KEY,
                "VOICE_ASSIST",
                "Settings",
            )
            HAOHAO_INPUT_FOOTPRINTS_ACTION shouldBe "haohao_input_footprints"
            val footprintKey = requireNotNull(presetKeys[HAOHAO_INPUT_FOOTPRINTS_KEY]?.mapping)
            footprintKey["send"]?.string shouldBe "FUNCTION"
            footprintKey["command"]?.string shouldBe HAOHAO_INPUT_FOOTPRINTS_ACTION

            HAOHAO_EDITOR_ACTION shouldBe "haohao_editor"
            val editorKey = requireNotNull(presetKeys[HAOHAO_EDITOR_KEY]?.mapping)
            editorKey["send"]?.string shouldBe "FUNCTION"
            editorKey["command"]?.string shouldBe HAOHAO_EDITOR_ACTION
            presetKeys.values.count { key ->
                key.mapping?.get("command")?.string == HAOHAO_EDITOR_ACTION
            } shouldBe 1
        }

        "toolbar restores safe defaults and swaps selected shortcuts without duplicates" {
            val defaults = listOf("clipboard_window", "HaoHaoTranslation", "HaoHaoKeyboardMenu")
            resolveHaoHaoToolbarActions("") shouldContainExactly defaults
            resolveHaoHaoToolbarActions("invalid,Hide,clipboard_window,clipboard_window") shouldContainExactly defaults
            val swapped = replaceHaoHaoToolbarAction("", 0, "HaoHaoKeyboardMenu")
            resolveHaoHaoToolbarActions(swapped) shouldContainExactly listOf("HaoHaoKeyboardMenu", "HaoHaoTranslation", "clipboard_window")
            resolveHaoHaoToolbarActions(replaceHaoHaoToolbarAction(swapped, 1, "HaoHaoPhrases")) shouldContainExactly listOf("HaoHaoKeyboardMenu", "HaoHaoPhrases", "clipboard_window")
            replaceHaoHaoToolbarAction(swapped, -1, "Hide") shouldBe swapped
            val customTheme = ToolBar(primaryButton = ToolBar.Button(action = "custom"))
            customTheme.customizedHaoHaoButtons(swapped) shouldContainExactly customTheme.equalWidthButtonsInDisplayOrder()
        }

        "HaoHao toolbox resolves unavailable tools without waiting" {
            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Editor,
                rimeState = RimeRuntimeState.PREPARING,
                composing = false,
                translationFailure = null,
                footprintsAvailable = true,
                voiceAvailable = true,
            ).reason shouldBe HaoHaoToolUnavailableReason.RIME_PREPARING

            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Translation,
                rimeState = RimeRuntimeState.READY,
                composing = true,
                translationFailure = null,
                footprintsAvailable = true,
                voiceAvailable = true,
            ).reason shouldBe HaoHaoToolUnavailableReason.COMPOSING

            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Translation,
                rimeState = RimeRuntimeState.READY,
                composing = false,
                translationFailure = CloudTranslationResult.Failure.Kind.NOT_CONFIGURED,
                footprintsAvailable = true,
                voiceAvailable = true,
            ).reason shouldBe HaoHaoToolUnavailableReason.NOT_CONFIGURED

            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Translation,
                rimeState = RimeRuntimeState.READY,
                composing = false,
                translationFailure = CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED,
                footprintsAvailable = true,
                voiceAvailable = true,
            ) shouldBe HaoHaoToolAvailability(
                enabled = true,
                reason = HaoHaoToolUnavailableReason.CONSENT_REQUIRED,
            )

            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Editor,
                rimeState = RimeRuntimeState.FAILED,
                composing = false,
                translationFailure = null,
                footprintsAvailable = true,
                voiceAvailable = true,
            ).reason shouldBe HaoHaoToolUnavailableReason.RIME_FAILED

            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Footprints,
                rimeState = RimeRuntimeState.READY,
                composing = false,
                translationFailure = null,
                footprintsAvailable = false,
                voiceAvailable = true,
            ).reason shouldBe HaoHaoToolUnavailableReason.LOCAL_DATA_UNAVAILABLE

            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Voice,
                rimeState = RimeRuntimeState.READY,
                composing = false,
                translationFailure = null,
                footprintsAvailable = true,
                voiceAvailable = false,
            ).reason shouldBe HaoHaoToolUnavailableReason.UNSUPPORTED

            resolveHaoHaoToolAvailability(
                action = HaoHaoToolboxAction.Settings,
                rimeState = RimeRuntimeState.FAILED,
                composing = true,
                translationFailure = CloudTranslationResult.Failure.Kind.NOT_CONFIGURED,
                footprintsAvailable = false,
                voiceAvailable = false,
            ).enabled shouldBe true
        }

        "main keyboard follows the compact four-row layout with long-press symbols" {
            val main = keyboard("default")

            main.asciiMode shouldBe false
            main.resetAsciiMode shouldBe true
            main.lock shouldBe true
            main.height shouldBe 50f
            rowWidths(main) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            clickTokens(main) shouldContainExactly listOf(
                "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
                "a", "s", "d", "f", "g", "h", "j", "k", "l",
                "Shift_L", "z", "x", "c", "v", "b", "n", "m", "BackSpace",
                "HaoHaoSymbols", "HaoHaoNumber", ",", "HaoHaoSpace", ".", "Mode_switch", "HaoHaoReturn",
            )

            val expectedSymbols = linkedMapOf(
                "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
                "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
                "a" to "~", "s" to "!", "d" to "@", "f" to "#", "g" to "%",
                "h" to "“", "j" to "”", "k" to "*", "l" to "?",
                "z" to "(", "x" to ")", "c" to "-", "v" to "_", "b" to ":",
                "n" to ";", "m" to "、",
            )
            expectedSymbols.forEach { (click, symbol) ->
                val key = main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain(click) }
                key.labelSymbol shouldBe symbol
                key.behaviors[KeyBehavior.LONG_CLICK] shouldBe KeyActionToken.Plain(symbol)
            }
            main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain("Shift_L") }.let { shift ->
                shift.behaviors[KeyBehavior.LONG_CLICK] shouldBe KeyActionToken.Plain("Shift_L")
                shift.labelSymbol shouldBe "\u200B"
            }
            main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain("HaoHaoReturn") }
                .keyTextSize shouldBe 18f

            val bottomWidths = listOf(
                "HaoHaoSymbols" to 15.5f,
                "HaoHaoNumber" to 11.5f,
                "," to 10f,
                "HaoHaoSpace" to 26f,
                "." to 10f,
                "Mode_switch" to 11.5f,
                "HaoHaoReturn" to 15.5f,
            )
            bottomWidths.forEach { (click, width) ->
                main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain(click) }.width shouldBe width
            }
            main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain(",") }
                .keyTextOffsetY shouldBe -3f
            main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain(".") }
                .keyTextOffsetY shouldBe -1f
        }

        "letter-only schemas route qwerty to the HaoHao main keyboard" {
            keyboard("qwerty").importPreset shouldBe "default"
        }

        "managed simplified schema patch upgrades without overwriting user data" {
            val expected = SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
            val oldManagedCorrection = """
                # haohao-managed-pinyin-correction-v1
                patch:
                  speller/algebra:
                    __patch:
                      - pinyin:/spelling_correction
                      - pinyin:/key_correction
            """.trimIndent()

            upgradeSimplifiedSchemaCustomPatch(LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()) shouldBe expected
            upgradeSimplifiedSchemaCustomPatch(BRANDED_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()) shouldBe expected
            upgradeSimplifiedSchemaCustomPatch(
                oldManagedCorrection,
                pinyinCorrectionSha256(oldManagedCorrection),
            ) shouldBe expected
            upgradeSimplifiedSchemaCustomPatch("${LEGACY_SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()}\n# user change") shouldBe null
            upgradeSimplifiedSchemaCustomPatch(expected) shouldBe null
        }

        "managed simplified schema name only changes the branded default schema" {
            managedSchemaDisplayName("luna_pinyin_simp", "朙月拼音·简化字") shouldBe "好好拼音"
            managedSchemaDisplayName("other_schema", "Other") shouldBe "Other"
        }

        "legacy Rime migration skips compiled data and never overwrites private files" {
            val root = Files.createTempDirectory("haohao-rime-migration").toFile()
            val legacy = root.resolve("legacy").apply { mkdirs() }
            val target = root.resolve("private").apply { mkdirs() }
            legacy.resolve("luna_pinyin_simp.custom.yaml").writeText("legacy config")
            legacy.resolve("luna_pinyin.userdb/value").apply {
                parentFile.mkdirs()
                writeText("learned")
            }
            legacy.resolve("build/luna_pinyin_simp.prism.bin").apply {
                parentFile.mkdirs()
                writeText("compiled")
            }
            target.resolve("luna_pinyin_simp.custom.yaml").writeText("private config")

            val result = migrateLegacyRimeData(legacy, target)

            target.resolve("luna_pinyin_simp.custom.yaml").readText() shouldBe "private config"
            target.resolve("luna_pinyin.userdb/value").readText() shouldBe "learned"
            target.resolve("build").exists() shouldBe false
            result.copiedFiles shouldBe 1
            result.skippedExistingFiles shouldBe 1
            result.skippedBuildFiles shouldBe 1
            root.deleteRecursively()
        }

        "managed repair backs up custom yaml and preserves learned data" {
            val root = Files.createTempDirectory("haohao-rime-repair").toFile()
            root.resolve("default.custom.yaml").writeText("user default")
            root.resolve("luna_pinyin_simp.custom.yaml").writeText("user schema")
            root.resolve("extra.custom.yaml").writeText("user extra")
            root.resolve("luna_pinyin.userdb/value").apply {
                parentFile.mkdirs()
                writeText("learned")
            }
            root.resolve("build/obsolete.bin").apply {
                parentFile.mkdirs()
                writeText("compiled")
            }

            val result = repairManagedRimeData(root, "test-backup")

            result.backedUpFiles shouldBe 3
            root.resolve("repair-backups/test-backup/default.custom.yaml").readText() shouldBe "user default"
            root.resolve("repair-backups/test-backup/extra.custom.yaml").readText() shouldBe "user extra"
            root.resolve("default.custom.yaml").readText() shouldBe result.defaultPatch
            root.resolve("luna_pinyin_simp.custom.yaml").readText() shouldBe SIMPLIFIED_SCHEMA_CUSTOM_PATCH.trimIndent()
            root.resolve("build").exists() shouldBe false
            root.resolve("luna_pinyin.userdb/value").readText() shouldBe "learned"
            root.deleteRecursively()
        }

        "managed repair invalidates prebuilt data without touching user data" {
            val root = Files.createTempDirectory("haohao-prebuilt-repair").toFile()
            val prebuilt = root.resolve("shared/build").apply {
                mkdirs()
                resolve("haohao_pinyin.table.bin").writeText("broken")
            }
            val checksums = root.resolve("checksums.json").apply { writeText("stale") }
            val learned = root.resolve("user/luna_pinyin.userdb/value").apply {
                parentFile.mkdirs()
                writeText("learned")
            }

            invalidatePrebuiltRimeData(prebuilt, checksums)

            prebuilt.exists() shouldBe false
            checksums.exists() shouldBe false
            learned.readText() shouldBe "learned"
            root.deleteRecursively()
        }

        "managed Rime sources use the precompiled timestamp without changing custom user files" {
            val root = Files.createTempDirectory("haohao-prebuilt-timestamps").toFile()
            val shared = root.resolve("shared").apply { mkdirs() }
            val user = root.resolve("user").apply { mkdirs() }
            val source = shared.resolve("default.yaml").apply { writeText("source") }
            val prebuilt = shared.resolve("build/haohao_pinyin.table.bin").apply {
                parentFile.mkdirs()
                writeText("prebuilt")
            }
            val managed = user.resolve("default.custom.yaml").apply {
                writeText(DataManager.SCHEMA_LIST_CUSTOM_PATCH.trimIndent())
            }
            val custom = user.resolve("luna_pinyin_simp.custom.yaml").apply { writeText("user custom") }

            alignManagedRimeSourceTimestamps(shared, user, 1_700_000_000L) shouldBe 2

            source.lastModified() / 1000L shouldBe 1_700_000_000L
            managed.lastModified() / 1000L shouldBe 1_700_000_000L
            prebuilt.lastModified() / 1000L shouldNotBe 1_700_000_000L
            custom.lastModified() / 1000L shouldNotBe 1_700_000_000L
            root.deleteRecursively()
        }

        "number and common-symbol pages always provide a path back to letters" {
            val number = keyboard("number")
            val numberSymbols = keyboard("number_symbols")
            val symbols = keyboard("symbols")

            number.height shouldBe 50f
            symbols.height shouldBe 50f
            number.keyLayout shouldBe "telephone"
            rowWidths(symbols) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            clickTokens(number).contains("HaoHaoNumberBack") shouldBe true
            clickTokens(number).contains("HaoHaoNumberSymbols") shouldBe true
            clickTokens(number).contains("HaoHaoNumberSpace") shouldBe true
            clickTokens(number).contains(".") shouldBe true
            clickTokens(symbols).contains("HaoHaoLetters") shouldBe true
            clickTokens(symbols).contains("HaoHaoSpace") shouldBe true
            clickTokens(symbols).contains("HaoHaoNumber") shouldBe true
            clickTokens(symbols).contains("；") shouldBe true
            rowWidths(numberSymbols) shouldContainExactly listOf(100f, 100f, 100f, 100f)
            clickTokens(numberSymbols).containsAll(listOf("HaoHaoLetters", "HaoHaoNumber", "%", "=", "*", "$", "￥", "[", "]", "{", "}")) shouldBe true
            presetKeys["HaoHaoNumberSymbols"]?.mapping?.get("select")?.string shouldBe "number_symbols"
            presetKeys["HaoHaoNumber"]?.mapping?.get("select")?.string shouldBe "number"
        }

        "number pad centers a large telephone-order digit grid with fixed actions" {
            val number = keyboard("number")
            number.columns shouldBe 5
            number.width shouldBe 22f
            number.keyLayout shouldBe "telephone"
            number.keyCapHeight shouldBe 0
            number.verticalGap shouldBe 4
            clickTokens(number) shouldContainExactly listOf(
                "%", "/", "-", "+",
                "1", "2", "3", "BackSpace",
                "4", "5", "6", ".",
                "7", "8", "9", "@",
                "HaoHaoNumberSymbols", "HaoHaoNumberBack", "0", "HaoHaoNumberSpace", "HaoHaoReturn",
            )
            val digits = number.keys.filter { (it.behaviors[KeyBehavior.CLICK] as? KeyActionToken.Plain)?.token?.singleOrNull()?.isDigit() == true }
            digits.size shouldBe 10
            digits.all { (it.width.takeIf { width -> width > 0 } ?: number.width) == 22f && it.keyTextSize == 22f } shouldBe true
            number.keys.size shouldBe 21
            number.keys[18].keyTextSize shouldBe 22f
            // ASCII mode renders action labels rather than per-key label overrides.
            presetKeys["HaoHaoNumberBack"]?.mapping?.get("label")?.string shouldBe "返回"
            presetKeys["HaoHaoNumberSpace"]?.mapping?.get("label")?.string shouldBe "ic@keyboard_space"
            number.keys.last().keyBackColor shouldBe "on_key_back_color"
        }

        "numeric layer keeps ASCII punctuation and existing deletion and cursor gestures" {
            val number = keyboard("number")
            number.asciiMode shouldBe true
            keyboard("number_symbols").asciiMode shouldBe true
            keyboard("default").asciiMode shouldBe false
            keyboard("default").resetAsciiMode shouldBe true
            number.keys.take(4).all { it.behaviors[KeyBehavior.LONG_CLICK] == null && it.labelSymbol.isEmpty() } shouldBe true
            presetKeys["BackSpace"]?.mapping?.get("repeatable")?.boolean shouldBe true
            presetKeys["BackSpace"]?.mapping?.get("slide_delete")?.boolean shouldBe true
            presetKeys["HaoHaoSpace"]?.mapping?.get("slide_cursor")?.boolean shouldBe true
            presetKeys["HaoHaoLetters"]?.mapping?.get("select")?.string shouldBe ".default"
            presetKeys["HaoHaoNumberBack"]?.mapping?.get("select")?.string shouldBe ".default"
            presetKeys["HaoHaoNumberSpace"]?.mapping?.get("slide_cursor")?.boolean shouldBe true
            presetKeys["HaoHaoNumberSpace"]?.mapping?.get("send")?.string shouldBe "space"
        }

        "theme-only upgrades invalidate inherited theme caches and retain dictionaries and user customization" {
            val user = Files.createTempDirectory("haohao-theme-upgrade").toFile()
            try {
                val build = user.resolve("build").apply { mkdirs() }
                listOf("haohao.trime.yaml", "tongwenfeng.trime.yaml", "trime.yaml", "luna_pinyin_simp.schema.yaml", "luna_pinyin.table.bin").forEach {
                    build.resolve(it).writeText("existing data")
                }
                user.resolve("haohao.trime.custom.yaml").writeText("user customization")
                user.resolve("haohao.trime.yaml").writeText("user theme source")
                invalidateCompiledThemeData(user, setOf("shared/haohao.trime.yaml")) shouldBe 3
                build.listFiles()!!.map { it.name }.sorted() shouldContainExactly listOf("luna_pinyin.table.bin", "luna_pinyin_simp.schema.yaml")
                user.resolve("haohao.trime.custom.yaml").readText() shouldBe "user customization"
                user.resolve("haohao.trime.yaml").readText() shouldBe "user theme source"
                invalidateCompiledThemeData(user, setOf("shared/haohao.trime.yaml")) shouldBe 0
            } finally {
                user.deleteRecursively()
            }
        }

        "unchanged assets keep compiled themes and default-theme changes invalidate dependents" {
            val user = Files.createTempDirectory("haohao-theme-cache").toFile()
            try {
                invalidateCompiledThemeData(user, setOf("shared/trime.yaml")) shouldBe 0
                val cached = user.resolve("build/haohao.trime.yaml").apply {
                    parentFile!!.mkdirs()
                    writeText("compiled")
                }
                invalidateCompiledThemeData(user, emptySet()) shouldBe 0
                invalidateCompiledThemeData(user, setOf("shared/default.yaml", "dictionary/haohao.trime.yaml")) shouldBe 0
                cached.readText() shouldBe "compiled"
                invalidateCompiledThemeData(user, setOf("shared/trime.yaml")) shouldBe 1
                cached.exists() shouldBe false
            } finally {
                user.deleteRecursively()
            }
        }

        "manifest does not request broad external storage access" {
            val manifest = File("src/main/AndroidManifest.xml").readText()

            manifest.contains("READ_EXTERNAL_STORAGE") shouldBe false
            manifest.contains("WRITE_EXTERNAL_STORAGE") shouldBe false
            manifest.contains("MANAGE_EXTERNAL_STORAGE") shouldBe false
            manifest.contains("requestLegacyExternalStorage") shouldBe false
        }
    })
