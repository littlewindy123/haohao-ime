/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.switches.SwitchOptionEntryUi
import com.osfans.trime.ime.window.BoardWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout

internal const val HAOHAO_TOOLBOX_KEY = "HaoHaoToolbox"
internal const val HAOHAO_TOOLBOX_BUTTON_WIDTH_DP = 48
internal const val HAOHAO_INPUT_FOOTPRINTS_KEY = "HaoHaoInputFootprints"
internal const val HAOHAO_INPUT_FOOTPRINTS_ACTION = "haohao_input_footprints"

internal enum class HaoHaoToolboxAction(
    @param:StringRes val labelRes: Int,
    val actionToken: String,
) {
    Clipboard(R.string.haohao_toolbox_clipboard, "clipboard_window"),
    Emoji(R.string.haohao_toolbox_emoji, "liquid_keyboard_emoji"),
    Voice(R.string.haohao_toolbox_voice, "VOICE_ASSIST"),
    Settings(R.string.haohao_toolbox_settings, "Settings"),
}

class HaoHaoToolboxWindow : BoardWindow.BarBoardWindow() {
    private val theme: Theme by di.instance()
    private val actionListener: CommonKeyboardActionListener by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private var countsText: TextView? = null
    private var countsJob: Job? = null

    override val title: String
        get() = context.getString(R.string.haohao_toolbox_title)

    private fun item(action: HaoHaoToolboxAction): View {
        val label = context.getString(action.labelRes)
        return SwitchOptionEntryUi(context, theme).apply {
            setEntry(label)
            root.setOnClickListener {
                actionListener.listener.onAction(KeyActionManager.getAction(action.actionToken))
            }
        }.root
    }

    private fun footprintsEntry(): View {
        val label = context.getString(R.string.input_footprints_title)
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ColorManager.getDecorDrawable(
                "key_back_color",
                "key_border_color",
                dp(theme.generalStyle.keyBorder),
                dp(theme.generalStyle.roundCorner),
            )
            isClickable = true
            isFocusable = true
            contentDescription = label
            addView(
                TextView(context).apply {
                    text = "好"
                    gravity = Gravity.CENTER
                    textSize = 22f
                    setTextColor(ColorManager.getColor("key_text_color"))
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = label
                            textSize = 16f
                            setTextColor(ColorManager.getColor("key_text_color"))
                        },
                    )
                    addView(
                        TextView(context).apply {
                            countsText = this
                            text = context.getString(R.string.input_footprints_toolbox_summary, 0, 0)
                            textSize = 12f
                            setTextColor(ColorManager.getColor("comment_text_color"))
                        },
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                TextView(context).apply {
                    text = "›"
                    gravity = Gravity.CENTER
                    textSize = 26f
                    setTextColor(ColorManager.getColor("key_text_color"))
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            setOnClickListener {
                actionListener.listener.onAction(KeyActionManager.getAction(HAOHAO_INPUT_FOOTPRINTS_KEY))
            }
        }
    }

    override fun onCreateView(): View = context.verticalLayout {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        add(footprintsEntry(), lParams(matchParent, dp(72)))
        HaoHaoToolboxAction.entries.chunked(2).forEach { rowActions ->
            add(
                horizontalLayout {
                    rowActions.forEach { action ->
                        add(item(action), lParams(0, matchParent, weight = 1f))
                    }
                },
                lParams(matchParent, dp(104)),
            )
        }
    }

    override fun onAttached() {
        countsJob = service.lifecycleScope.launch {
            InputFootprints.store.counts.collect { counts ->
                countsText?.text = context.getString(
                    R.string.input_footprints_toolbox_summary,
                    counts.recent,
                    counts.favorites,
                )
            }
        }
    }

    override fun onDetached() {
        countsJob?.cancel()
        countsJob = null
        countsText = null
    }
}
