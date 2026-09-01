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
import com.osfans.trime.data.base.invalidatePrebuiltRimeData
import com.osfans.trime.data.base.managedSchemaDisplayName
import com.osfans.trime.data.base.migrateLegacyRimeData
import com.osfans.trime.data.base.pinyinCorrectionSha256
import com.osfans.trime.data.base.repairManagedRimeData
import com.osfans.trime.data.base.upgradeSimplifiedSchemaCustomPatch
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.ToolBar
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
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeySurfaceRect
import com.osfans.trime.ime.keyboard.calculateKeySurfaceGeometry
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.float
import com.osfans.trime.util.yaml.int
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
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
            config["name"]?.string shouldBe "好好输入法"
            DEFAULT_FOLLOW_SYSTEM_DAY_NIGHT shouldBe true

            DataManager.SCHEMA_LIST_CUSTOM_PATCH
                .lines()
                .filter { it.trimStart().startsWith("- schema:") }
                .map { it.substringAfter(":").trim() } shouldContainExactly listOf(DEFAULT_SCHEMA_ID)
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
            style["candidate_text_size"]?.float shouldBe 16f
            style["comment_text_size"]?.float shouldBe 12f
            style["key_text_size"]?.float shouldBe 16f
            style["key_long_text_size"]?.float shouldBe 14f
            style["symbol_text_size"]?.float shouldBe 10f
            style["label_text_size"]?.float shouldBe 16f
            style["popup_text_size"]?.float shouldBe 16f
            style["horizontal_gap"]?.int shouldBe 4
            style["vertical_gap"]?.int shouldBe 9
            style["keyboard_padding"]?.int shouldBe 5
            style["keyboard_height"]?.int shouldBe 232
            style["round_corner"]?.float shouldBe 7f
            style["key_border"]?.int shouldBe 1
            style["key_press_offset_y"]?.float shouldBe 1f
            style["key_shadow_offset_y"]?.float shouldBe 2f
            style["candidate_corner_radius"]?.float shouldBe 8f
            decodedStyle.compactCandidateTextSize shouldBe 16f
            decodedStyle.compactTranslationTextSize shouldBe 12f
            decodedStyle.compactPhoneticTextSize shouldBe 10f

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
            light["keyboard_back_color"]?.int shouldBe 0xe9edf2
            light["key_back_color"]?.int shouldBe 0xffffff
            light["key_border_color"]?.int shouldBe 0xd5dbe4
            light["key_shadow_color"]?.int shouldBe 0x2b5c6878
            light["key_highlight_color"]?.int shouldBe 0x66ffffff
            light["off_key_back_color"]?.int shouldBe 0xdce2e9
            light["on_key_back_color"]?.int shouldBe 0x4f7df3
            dark["keyboard_back_color"]?.int shouldBe 0x1d2025
            dark["key_back_color"]?.int shouldBe 0x2e3239
            dark["key_border_color"]?.int shouldBe 0x444a55
            dark["key_shadow_color"]?.int shouldBe 0x66000000
            dark["key_highlight_color"]?.int shouldBe 0x24ffffff
            dark["off_key_back_color"]?.int shouldBe 0x3a3f49
            dark["on_key_back_color"]?.int shouldBe 0x7ea2ff
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

        "360 369 and 411dp viewports keep wider caps and continuous touch cells" {
            val density = 3
            val sidePadding = 5 * density
            val halfGap = 2 * density
            val expectedCapWidths = mapOf(360 to 93, 369 to 95, 411 to 108)

            expectedCapWidths.forEach { (viewportDp, expectedCapWidth) ->
                val availableWidth = viewportDp * density - sidePadding * 2
                val cellWidth = availableWidth / 10
                val geometry = calculateKeySurfaceGeometry(
                    width = cellWidth,
                    height = 58 * density,
                    paddingLeft = halfGap,
                    paddingTop = 4 * density,
                    paddingRight = halfGap,
                    paddingBottom = 4 * density,
                    shadowOffsetY = 2 * density,
                    pressOffsetX = 0,
                    pressOffsetY = density,
                    pressed = false,
                )

                geometry.cap.right - geometry.cap.left shouldBe expectedCapWidth
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
            presetKeys["BackSpace"]?.mapping?.get("label")?.string shouldBe "⌫"
            presetKeys["Shift_L"]?.mapping?.get("label")?.string shouldBe "⇧"
            presetKeys["Shift_L"]?.mapping?.get("send")?.string shouldBe "Shift_L"
            presetKeys["Shift_L"]?.mapping?.get("shift_lock")?.string shouldBe "long"
            presetKeys["HaoHaoReturn"]?.mapping?.get("label")?.string shouldBe "↵"
            val spaceLabel = presetKeys["HaoHaoSpace"]?.mapping?.get("label")?.string
            spaceLabel shouldBe " "
            spaceLabel?.isNotEmpty() shouldBe true
            spaceLabel?.isBlank() shouldBe true
            presetKeys["HaoHaoSpace"]?.mapping?.get("slide_cursor")?.boolean shouldBe true
            presetKeys["BackSpace"]?.mapping?.get("slide_delete")?.boolean shouldBe true
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

        "HaoHao idle toolbar exposes four reliable actions" {
            val primaryButton = requireNotNull(toolBar["primary_button"]?.mapping)
            val foreground = requireNotNull(primaryButton["foreground"]?.mapping)
            val decodedToolBar = ToolBar.decode(toolBar)

            primaryButton["action"]?.string shouldBe "HaoHaoToolbox"
            primaryButton["size"]?.sequence?.mapNotNull { it.int } shouldContainExactly listOf(48, 48)
            foreground["style"]?.string shouldBe "ic@view_grid_outline"
            decodedToolBar.equalWidth shouldBe true
            decodedToolBar.builtinIconSize shouldBe 21
            ToolBar.decode(null).equalWidth shouldBe false
            ToolBar.decode(null).builtinIconSize shouldBe 24
            decodedToolBar.equalWidthButtonsInDisplayOrder().all { button ->
                button.size == listOf(48, 48) && button.foreground.fontSize == 21f
            } shouldBe true
            toolButtonIconFrameSizeDp(decodedToolBar.builtinIconSize) shouldBe 29

            val toolbarActions = decodedToolBar.buttons.map { it.action }
            toolbarActions shouldContainExactly listOf(
                "Hide",
                "liquid_keyboard_emoji",
                "clipboard_window",
            )
            decodedToolBar.equalWidthButtonsInDisplayOrder().map { it.action } shouldContainExactly listOf(
                "HaoHaoToolbox",
                "liquid_keyboard_emoji",
                "clipboard_window",
                "Hide",
            )
            decodedToolBar.buttons.single { it.action == "clipboard_window" }
                .foreground.style shouldBe "ic@clipboard_text_outline"

            val toolboxKey = requireNotNull(presetKeys["HaoHaoToolbox"]?.mapping)
            toolboxKey["send"]?.string shouldBe "FUNCTION"
            toolboxKey["command"]?.string shouldBe "haohao_toolbox"
            presetKeys["HaoHaoTranslation"]?.mapping?.get("send")?.string shouldBe "FUNCTION"
            presetKeys["HaoHaoTranslation"]?.mapping?.get("command")?.string shouldBe "haohao_translation"
            presetKeys["HaoHaoSymbols"]?.mapping?.get("label")?.string shouldBe "符"
        }

        "HaoHao toolbox keeps five secondary actions without toolbar duplicates" {
            HaoHaoToolboxAction.entries.map { it.actionToken } shouldContainExactly listOf(
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
                "a" to "~", "s" to "@", "d" to "#", "f" to "!", "g" to "%",
                "h" to "&", "j" to "*", "k" to "(", "l" to ")",
                "z" to "`", "x" to "/", "c" to "-", "v" to "_", "b" to ":",
                "n" to ";", "m" to "?",
            )
            expectedSymbols.forEach { (click, symbol) ->
                val key = main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain(click) }
                key.labelSymbol shouldBe symbol
                key.behaviors[KeyBehavior.LONG_CLICK] shouldBe KeyActionToken.Plain(symbol)
            }
            main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain("Shift_L") }
                .behaviors[KeyBehavior.LONG_CLICK] shouldBe KeyActionToken.Plain("Shift_L")

            val bottomWidths = listOf(
                "HaoHaoSymbols" to 14f,
                "HaoHaoNumber" to 12f,
                "," to 10f,
                "HaoHaoSpace" to 28f,
                "." to 10f,
                "Mode_switch" to 12f,
                "HaoHaoReturn" to 14f,
            )
            bottomWidths.forEach { (click, width) ->
                main.keys.single { it.behaviors[KeyBehavior.CLICK] == KeyActionToken.Plain(click) }.width shouldBe width
            }
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
            val symbols = keyboard("symbols")

            number.height shouldBe 50f
            symbols.height shouldBe 50f
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

        "manifest does not request broad external storage access" {
            val manifest = File("src/main/AndroidManifest.xml").readText()

            manifest.contains("READ_EXTERNAL_STORAGE") shouldBe false
            manifest.contains("WRITE_EXTERNAL_STORAGE") shouldBe false
            manifest.contains("MANAGE_EXTERNAL_STORAGE") shouldBe false
            manifest.contains("requestLegacyExternalStorage") shouldBe false
        }
    })
