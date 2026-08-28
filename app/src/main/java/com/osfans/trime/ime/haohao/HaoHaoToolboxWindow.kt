/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.view.Gravity
import android.view.View
import androidx.annotation.StringRes
import com.osfans.trime.R
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.switches.SwitchOptionEntryUi
import com.osfans.trime.ime.window.BoardWindow
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout

internal const val HAOHAO_TOOLBOX_KEY = "HaoHaoToolbox"
internal const val HAOHAO_TOOLBOX_BUTTON_WIDTH_DP = 48

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

    override fun onCreateView(): View = context.verticalLayout {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
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

    override fun onAttached() {}

    override fun onDetached() {}
}
