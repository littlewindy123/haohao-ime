/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme.model

import android.os.Parcelable
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.float
import com.osfans.trime.util.yaml.int
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import kotlinx.parcelize.Parcelize

@Parcelize
data class ToolBar(
    val primaryButton: Button? = null,
    val buttons: List<Button> = emptyList(),
    val buttonSpacing: Int = 18,
    val buttonFont: List<String> = emptyList(),
    val backStyle: String = "ic@arrow-left",
    val equalWidth: Boolean = false,
    val builtinIconSize: Int = 24,
    val builtinIconColor: String = "candidate_text_color",
    val builtinIconHighlightColor: String = "hilited_candidate_text_color",
) : Parcelable {

    fun equalWidthButtonsInDisplayOrder(): List<Button> = listOfNotNull(primaryButton) + buttons.drop(1) + listOfNotNull(buttons.firstOrNull())

    fun customizedHaoHaoButtons(saved: String): List<Button> {
        if (primaryButton?.action != "HaoHaoToolbox") return equalWidthButtonsInDisplayOrder()
        val template = buttons.firstOrNull() ?: return equalWidthButtonsInDisplayOrder()
        val icons = listOf("clipboard_text_outline", "translate", "keyboard_outline", "star_outline", "cursor_text", "book_open_variant", "emoticon_outline")
        val middle = resolveHaoHaoToolbarActions(saved).map { action ->
            template.copy(
                action = action,
                longPressAction = "HaoHaoToolbox",
                foreground = template.foreground.copy(style = "ic@${icons[HAOHAO_TOOLBAR_ACTIONS.indexOf(action)]}"),
            )
        }
        return listOf(primaryButton) + middle + template.copy(action = "Hide")
    }

    @Parcelize
    data class Button(
        val background: Background = Background(),
        val foreground: Foreground = Foreground(),
        val action: String = "",
        val longPressAction: String = "",
        val size: List<Int> = emptyList(),
    ) : Parcelable {

        @Parcelize
        data class Background(
            val type: Type = Type.RECTANGLE,
            val cornerRadius: Float = 10f,
            val normal: String = "",
            val highlight: String = "",
            val verticalInset: Int = 4,
            val horizontalInset: Int = 4,
        ) : Parcelable {
            enum class Type {
                RECTANGLE,
                CIRCLE,
            }
            companion object {
                fun decode(node: Node.Mapping): Background = Background(
                    type = runCatching {
                        val value = node["type"]?.string ?: "RECTANGLE"
                        Type.valueOf(value.uppercase())
                    }.getOrDefault(Type.RECTANGLE),
                    cornerRadius = node["corner_radius"]?.float ?: 10f,
                    normal = node["normal"]?.string ?: "",
                    highlight = node["highlight"]?.string ?: "",
                    verticalInset = node["vertical_inset"]?.int ?: 4,
                    horizontalInset = node["horizontal_inset"]?.int ?: 4,
                )
            }
        }

        @Parcelize
        data class Foreground(
            val style: String = "",
            val optionStyles: List<String> = emptyList(),
            val normal: String = "",
            val highlight: String = "",
            val fontSize: Float = 18f,
            val padding: Int = 4,
        ) : Parcelable {
            companion object {
                fun decode(node: Node.Mapping): Foreground = Foreground(
                    style = node["style"]?.string ?: "",
                    optionStyles = node["option_styles"]?.sequence
                        ?.mapNotNull(Node::string) ?: emptyList(),
                    normal = node["normal"]?.string ?: "",
                    highlight = node["highlight"]?.string ?: "",
                    fontSize = node["font_size"]?.float ?: 18f,
                    padding = node["padding"]?.int ?: 4,
                )
            }
        }

        companion object {
            fun decode(node: Node.Mapping): Button = Button(
                background = node["background"]?.mapping?.let {
                    Background.decode(it)
                } ?: Background(),
                foreground = node["foreground"]?.mapping?.let {
                    Foreground.decode(it)
                } ?: Foreground(),
                action = node["action"]?.string ?: "",
                longPressAction = node["long_press_action"]?.string ?: "",
                size = node["size"]?.sequence?.mapNotNull { it.int } ?: emptyList(),
            )
        }
    }

    companion object {
        fun decode(node: Node.Mapping?): ToolBar = ToolBar(
            primaryButton = node?.get("primary_button")?.mapping?.let { Button.decode(it) },
            buttons = node?.get("buttons")?.sequence?.map { Button.decode(it.mapping!!) } ?: emptyList(),
            buttonSpacing = node?.get("button_spacing")?.int ?: 18,
            buttonFont = node?.get("button_font")?.sequence
                ?.mapNotNull(Node::string) ?: emptyList(),
            backStyle = node?.get("back_style")?.string ?: "ic@arrow-left",
            equalWidth = node?.get("equal_width")?.boolean ?: false,
            builtinIconSize = node?.get("builtin_icon_size")?.int ?: 24,
            builtinIconColor = node?.get("builtin_icon_color")?.string ?: "candidate_text_color",
            builtinIconHighlightColor = node?.get("builtin_icon_highlight_color")?.string ?: "hilited_candidate_text_color",
        )
    }
}

internal val HAOHAO_TOOLBAR_ACTIONS = listOf(
    "clipboard_window",
    "HaoHaoTranslation",
    "HaoHaoKeyboardMenu",
    "HaoHaoPhrases",
    "HaoHaoEditor",
    "HaoHaoInputFootprints",
    "liquid_keyboard_emoji",
)

internal fun resolveHaoHaoToolbarActions(saved: String): List<String> {
    val explicit = saved.startsWith("v2:")
    val selected = saved.removePrefix("v2:").split(',').filter { it in HAOHAO_TOOLBAR_ACTIONS }.distinct()
    return (if (explicit) selected else selected + HAOHAO_TOOLBAR_ACTIONS).distinct().take(3)
}

internal fun encodeHaoHaoToolbarActions(actions: List<String>): String = "v2:" + actions.filter { it in HAOHAO_TOOLBAR_ACTIONS }.distinct().take(3).joinToString(",")

internal fun replaceHaoHaoToolbarAction(saved: String, slot: Int, action: String): String {
    val actions = resolveHaoHaoToolbarActions(saved).toMutableList()
    if (slot !in actions.indices || action !in HAOHAO_TOOLBAR_ACTIONS) return actions.joinToString(",")
    val previous = actions.indexOf(action)
    if (previous >= 0) actions[previous] = actions[slot]
    actions[slot] = action
    return actions.joinToString(",")
}
