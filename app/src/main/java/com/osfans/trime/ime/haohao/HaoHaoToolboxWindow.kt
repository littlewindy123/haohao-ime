/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.footprints.InputFootprints
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
internal const val HAOHAO_EDITOR_KEY = "HaoHaoEditor"
internal const val HAOHAO_EDITOR_ACTION = "haohao_editor"

internal enum class HaoHaoToolboxAction(
    @param:StringRes val labelRes: Int,
    val actionToken: String,
    @param:DrawableRes val iconRes: Int,
) {
    Footprints(R.string.input_footprints_title, HAOHAO_INPUT_FOOTPRINTS_KEY, R.drawable.ic_baseline_book_24),
    Editor(R.string.haohao_toolbox_editor, HAOHAO_EDITOR_KEY, R.drawable.ic_baseline_edit_24),
    Clipboard(R.string.haohao_toolbox_clipboard, "clipboard_window", R.drawable.ic_clipboard_24),
    Emoji(R.string.haohao_toolbox_emoji, "liquid_keyboard_emoji", R.drawable.ic_haohao_emoji_24),
    Voice(R.string.haohao_toolbox_voice, "VOICE_ASSIST", R.drawable.ic_haohao_mic_24),
    Settings(R.string.haohao_toolbox_settings, "Settings", R.drawable.ic_baseline_settings_24),
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
            setEntry(label, action.iconRes)
            if (action == HaoHaoToolboxAction.Footprints) {
                this@HaoHaoToolboxWindow.countsText = this.label
                this.label.maxLines = 2
                updateCounts(0, 0)
            }
            root.setOnClickListener {
                actionListener.listener.onAction(KeyActionManager.getAction(action.actionToken))
            }
            if (action == HaoHaoToolboxAction.Footprints && !InputFootprints.isAvailable) {
                root.isEnabled = false
                root.alpha = 0.45f
            }
            if (
                action == HaoHaoToolboxAction.Clipboard &&
                (!ClipboardHelper.isAvailable || !CollectionHelper.isAvailable)
            ) {
                root.isEnabled = false
                root.alpha = 0.45f
            }
        }.root
    }

    private fun updateCounts(recent: Int, favorites: Int) {
        countsText?.text = buildString {
            append(context.getString(R.string.input_footprints_title))
            append('\n')
            append(context.getString(R.string.input_footprints_toolbox_summary_compact, recent, favorites))
        }
    }

    override fun onCreateView(): View = context.verticalLayout {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        HaoHaoToolboxAction.entries.chunked(3).forEach { rowActions ->
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
        val store = InputFootprints.storeOrNull ?: return
        countsJob = service.lifecycleScope.launch {
            store.counts.collect { counts ->
                updateCounts(counts.recent, counts.favorites)
            }
        }
    }

    override fun onDetached() {
        countsJob?.cancel()
        countsJob = null
        countsText = null
    }
}
