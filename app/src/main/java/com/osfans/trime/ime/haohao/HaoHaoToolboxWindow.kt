/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeRuntimeState
import com.osfans.trime.core.StatusProto
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.HAOHAO_TOOLBAR_ACTIONS
import com.osfans.trime.data.theme.model.replaceHaoHaoToolbarAction
import com.osfans.trime.data.theme.model.resolveHaoHaoToolbarActions
import com.osfans.trime.data.translation.CloudTranslationResult
import com.osfans.trime.data.translation.CloudTranslationRuntime
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.bilingual.BILINGUAL_TRANSLATION_DELAY_MAX_MS
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.util.InputMethodUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.resources.styledColor

internal const val HAOHAO_TOOLBOX_KEY = "HaoHaoToolbox"
internal const val HAOHAO_TOOLBOX_BUTTON_WIDTH_DP = 48
internal const val HAOHAO_INPUT_FOOTPRINTS_KEY = "HaoHaoInputFootprints"
internal const val HAOHAO_INPUT_FOOTPRINTS_ACTION = "haohao_input_footprints"
internal const val HAOHAO_EDITOR_KEY = "HaoHaoEditor"
internal const val HAOHAO_EDITOR_ACTION = "haohao_editor"
internal const val HAOHAO_TRANSLATION_KEY = "HaoHaoTranslation"

internal enum class HaoHaoToolboxAction(
    @param:StringRes val labelRes: Int,
    @param:StringRes val summaryRes: Int,
    val actionToken: String,
    @param:DrawableRes val iconRes: Int,
) {
    Clipboard(R.string.haohao_toolbox_clipboard, R.string.haohao_clipboard_summary, "clipboard_window", R.drawable.ic_clipboard_24),
    Phrases(R.string.haohao_phrases, R.string.haohao_phrases_summary, "HaoHaoPhrases", R.drawable.ic_baseline_star_24),
    QuickSettings(R.string.haohao_quick_settings, R.string.haohao_quick_settings_summary, "", R.drawable.ic_baseline_settings_24),
    Toolbar(R.string.haohao_customize_toolbar, R.string.haohao_toolbar_summary, "", R.drawable.ic_baseline_edit_24),
    Editor(
        R.string.haohao_toolbox_editor,
        R.string.haohao_toolbox_editor_summary,
        HAOHAO_EDITOR_KEY,
        R.drawable.ic_baseline_edit_24,
    ),
    Translation(
        R.string.haohao_translation_tool,
        R.string.haohao_toolbox_translation_summary,
        HAOHAO_TRANSLATION_KEY,
        R.drawable.ic_baseline_text_fields_24,
    ),
    Footprints(
        R.string.input_footprints_title,
        R.string.input_footprints_toolbox_summary,
        HAOHAO_INPUT_FOOTPRINTS_KEY,
        R.drawable.ic_baseline_book_24,
    ),
    Voice(
        R.string.haohao_toolbox_voice,
        R.string.haohao_toolbox_voice_summary,
        "VOICE_ASSIST",
        R.drawable.ic_haohao_mic_24,
    ),
    Settings(
        R.string.haohao_toolbox_settings,
        R.string.haohao_toolbox_settings_summary,
        "Settings",
        R.drawable.ic_baseline_settings_24,
    ),
}

internal enum class HaoHaoToolUnavailableReason(
    @param:StringRes val messageRes: Int,
) {
    RIME_PREPARING(R.string.haohao_tool_unavailable_preparing),
    RIME_FAILED(R.string.haohao_tool_unavailable_failed),
    COMPOSING(R.string.haohao_tool_unavailable_composing),
    NOT_CONFIGURED(R.string.haohao_tool_unavailable_not_configured),
    CONSENT_REQUIRED(R.string.haohao_tool_consent_required),
    UNSUPPORTED(R.string.haohao_tool_unavailable_unsupported),
    LOCAL_DATA_UNAVAILABLE(R.string.haohao_tool_unavailable_local_data),
}

internal data class HaoHaoToolAvailability(
    val enabled: Boolean,
    val reason: HaoHaoToolUnavailableReason? = null,
)

internal fun resolveHaoHaoToolAvailability(
    action: HaoHaoToolboxAction,
    rimeState: RimeRuntimeState,
    composing: Boolean,
    translationFailure: CloudTranslationResult.Failure.Kind?,
    footprintsAvailable: Boolean,
    voiceAvailable: Boolean,
): HaoHaoToolAvailability = when (action) {
    HaoHaoToolboxAction.Editor,
    HaoHaoToolboxAction.Translation,
    -> when {
        rimeState == RimeRuntimeState.PREPARING ->
            HaoHaoToolAvailability(false, HaoHaoToolUnavailableReason.RIME_PREPARING)
        rimeState == RimeRuntimeState.FAILED ->
            HaoHaoToolAvailability(false, HaoHaoToolUnavailableReason.RIME_FAILED)
        composing -> HaoHaoToolAvailability(false, HaoHaoToolUnavailableReason.COMPOSING)
        action == HaoHaoToolboxAction.Translation -> when (translationFailure) {
            null -> HaoHaoToolAvailability(true)
            CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED ->
                HaoHaoToolAvailability(true, HaoHaoToolUnavailableReason.CONSENT_REQUIRED)
            CloudTranslationResult.Failure.Kind.UNSUPPORTED_DEVICE ->
                HaoHaoToolAvailability(false, HaoHaoToolUnavailableReason.UNSUPPORTED)
            else -> HaoHaoToolAvailability(false, HaoHaoToolUnavailableReason.NOT_CONFIGURED)
        }
        else -> HaoHaoToolAvailability(true)
    }
    HaoHaoToolboxAction.Footprints -> if (footprintsAvailable) {
        HaoHaoToolAvailability(true)
    } else {
        HaoHaoToolAvailability(false, HaoHaoToolUnavailableReason.LOCAL_DATA_UNAVAILABLE)
    }
    HaoHaoToolboxAction.Voice -> if (voiceAvailable) {
        HaoHaoToolAvailability(true)
    } else {
        HaoHaoToolAvailability(false, HaoHaoToolUnavailableReason.UNSUPPORTED)
    }
    HaoHaoToolboxAction.Settings,
    HaoHaoToolboxAction.Clipboard,
    HaoHaoToolboxAction.Phrases,
    HaoHaoToolboxAction.QuickSettings,
    HaoHaoToolboxAction.Toolbar,
    -> HaoHaoToolAvailability(true)
}

class HaoHaoToolboxWindow :
    BoardWindow.BarBoardWindow(),
    InputBroadcastReceiver {
    override fun enterAnimation(lastWindow: BoardWindow): androidx.transition.Transition? = null
    override fun exitAnimation(nextWindow: BoardWindow): androidx.transition.Transition? = null

    private data class ToolTile(
        val root: LinearLayout,
        val summary: TextView,
    )

    private val theme: Theme by di.instance()
    private val actionListener: CommonKeyboardActionListener by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private val rime: RimeSession by di.instance()

    private val tiles = mutableMapOf<HaoHaoToolboxAction, ToolTile>()
    private var runtimeState = RimeDaemon.runtimeState.value
    private var composing = false
    private var translationFailure: CloudTranslationResult.Failure.Kind? = null
    private var footprintsAvailable = false
    private var voiceAvailable = false
    private var recentCount = 0
    private var favoriteCount = 0
    private var runtimeJob: Job? = null
    private var countsJob: Job? = null

    override val title: String
        get() = context.getString(R.string.haohao_toolbox_title)

    private fun createTile(action: HaoHaoToolboxAction): ToolTile {
        val icon = ImageView(context).apply {
            setImageResource(action.iconRes)
            imageTintList = ColorStateList.valueOf(ColorManager.getColor("key_text_color"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = TextView(context).apply {
            text = context.getString(action.labelRes)
            setTextColor(ColorManager.getColor("key_text_color"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.generalStyle.keyLongTextSize)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val summary = TextView(context).apply {
            setTextColor(ColorManager.getColor("comment_text_color"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.generalStyle.commentTextSize)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                title,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                summary,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val content = ColorManager.getDecorDrawable(
            "key_back_color",
            "key_border_color",
            context.dp(theme.generalStyle.keyBorder),
            context.dp(theme.generalStyle.roundCorner),
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = RippleDrawable(
                ColorStateList.valueOf(ColorManager.getColor("hilited_key_back_color")),
                content,
                null,
            )
            addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
            addView(
                labels,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            setOnClickListener {
                when (action) {
                    HaoHaoToolboxAction.Toolbar -> showToolbarSettings()
                    HaoHaoToolboxAction.QuickSettings -> showQuickSettings()
                    else -> actionListener.listener.onAction(KeyActionManager.getAction(action.actionToken))
                }
            }
        }
        return ToolTile(root, summary).also { tiles[action] = it }
    }

    private fun cellParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        1f,
    ).apply {
        val margin = context.dp(3)
        setMargins(margin, margin, margin, margin)
    }

    private fun row(vararg actions: HaoHaoToolboxAction): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        actions.forEach { action -> addView(createTile(action).root, cellParams()) }
    }

    private fun rowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        context.dp(76 * context.resources.configuration.fontScale.coerceAtLeast(1f)).toInt(),
    )

    override fun onCreateView(): View = ScrollView(context).apply {
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
                addView(row(HaoHaoToolboxAction.Clipboard, HaoHaoToolboxAction.Phrases), rowParams())
                addView(row(HaoHaoToolboxAction.QuickSettings, HaoHaoToolboxAction.Toolbar), rowParams())
                addView(row(HaoHaoToolboxAction.Editor, HaoHaoToolboxAction.Translation), rowParams())
                addView(row(HaoHaoToolboxAction.Footprints, HaoHaoToolboxAction.Voice), rowParams())
                addView(row(HaoHaoToolboxAction.Settings), rowParams())
            },
        )
        refreshStateSnapshot()
    }

    private fun toolbarLabel(action: String): String = context.getString(
        when (action) {
            "clipboard_window" -> R.string.haohao_toolbox_clipboard
            "HaoHaoTranslation" -> R.string.haohao_translation_tool
            "HaoHaoKeyboardMenu" -> R.string.virtual_keyboard
            "HaoHaoPhrases" -> R.string.haohao_phrases
            "HaoHaoEditor" -> R.string.haohao_toolbox_editor
            "HaoHaoInputFootprints" -> R.string.input_footprints_title
            else -> R.string.haohao_toolbox_emoji
        },
    )

    private fun showToolbarSettings() {
        val pref = AppPrefs.defaultInstance().internal.toolbarActions
        val actions = resolveHaoHaoToolbarActions(pref.getValue())
        val labels = actions.mapIndexed { index, action -> "${index + 1}. ${toolbarLabel(action)}" }.toTypedArray()
        service.showDialog(
            AlertDialog.Builder(context)
                .setTitle(R.string.haohao_customize_toolbar)
                .setItems(labels) { _, slot ->
                    service.showDialog(
                        AlertDialog.Builder(context)
                            .setTitle(labels[slot])
                            .setSingleChoiceItems(HAOHAO_TOOLBAR_ACTIONS.map(::toolbarLabel).toTypedArray(), HAOHAO_TOOLBAR_ACTIONS.indexOf(actions[slot])) { dialog, index ->
                                pref.setValue(replaceHaoHaoToolbarAction(pref.getValue(), slot, HAOHAO_TOOLBAR_ACTIONS[index]))
                                dialog.dismiss()
                                showToolbarSettings()
                            }
                            .setNegativeButton(R.string.cancel, null).create(),
                    )
                }
                .setNeutralButton(R.string.haohao_toolbar_reset) { _, _ -> pref.setValue("") }
                .setPositiveButton(R.string.ok, null).create(),
        )
    }

    private fun showQuickSettings() {
        val prefs = AppPrefs.defaultInstance()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun toggle(label: Int, value: Boolean, save: (Boolean) -> Unit) {
            content.addView(
                Switch(context).apply {
                    setText(label)
                    minHeight = dp(48)
                    isChecked = value
                    setOnCheckedChangeListener { _, checked -> save(checked) }
                },
            )
        }
        toggle(R.string.bilingual_candidate_phonetic, prefs.candidates.bilingualPhonetic.getValue()) { prefs.candidates.bilingualPhonetic.setValue(it) }
        fun delayControl(label: Int, value: Int, maxValue: Int, save: (Int) -> Unit) {
            val title = TextView(context).apply { setTextColor(context.styledColor(android.R.attr.textColorPrimary)) }
            fun update(value: Int) {
                title.text = "${context.getString(label)} · $value ms"
            }
            update(value)
            content.addView(title)
            content.addView(
                SeekBar(context).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(48))
                    max = maxValue / 100
                    progress = value.coerceIn(0, maxValue) / 100
                    minimumHeight = dp(48)
                    contentDescription = context.getString(label)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            update(progress * 100)
                            if (fromUser) save(progress * 100)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar) {}
                        override fun onStopTrackingTouch(bar: SeekBar) {}
                    })
                },
            )
        }
        delayControl(R.string.bilingual_candidate_translation_delay, prefs.candidates.bilingualTranslationDelay.getValue(), BILINGUAL_TRANSLATION_DELAY_MAX_MS) { prefs.candidates.bilingualTranslationDelay.setValue(it) }
        delayControl(R.string.cloud_candidate_translation_delay, prefs.candidates.cloudTranslationDelay.getValue(), 2000) { prefs.candidates.cloudTranslationDelay.setValue(it) }
        content.addView(
            TextView(context).apply {
                setText(R.string.cloud_candidate_translation_delay_summary)
                setTextColor(context.styledColor(android.R.attr.textColorPrimary))
            },
        )
        val modes = AppPrefs.Keyboard.OneHandMode.entries
        content.addView(
            Button(context).apply {
                isAllCaps = false
                text = "${context.getString(R.string.one_hand_mode)} · ${context.getString(prefs.keyboard.oneHandMode.getValue().stringRes)}"
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setOnClickListener {
                    service.showDialog(
                        AlertDialog.Builder(context).setTitle(R.string.one_hand_mode)
                            .setSingleChoiceItems(modes.map { context.getString(it.stringRes) }.toTypedArray(), modes.indexOf(prefs.keyboard.oneHandMode.getValue())) { dialog, index ->
                                prefs.keyboard.oneHandMode.setValue(modes[index])
                                text = "${context.getString(R.string.one_hand_mode)} · ${context.getString(modes[index].stringRes)}"
                                dialog.dismiss()
                            }.setNegativeButton(R.string.cancel, null).create(),
                    )
                }
            },
        )
        service.showDialog(
            AlertDialog.Builder(context)
                .setTitle(R.string.haohao_quick_settings)
                .setView(ScrollView(context).apply { addView(content) })
                .setNeutralButton(R.string.virtual_keyboard) { _, _ ->
                    actionListener.listener.onAction(KeyActionManager.getAction("HaoHaoKeyboardMenu"))
                }
                .setPositiveButton(R.string.ok, null).create(),
        )
    }

    private fun currentTranslationFailure(): CloudTranslationResult.Failure.Kind? = runCatching {
        val manager = CloudTranslationRuntime.manager
        manager.configurationStatus()?.kind ?: manager.status()?.kind
    }.getOrDefault(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)

    private fun refreshStateSnapshot() {
        runtimeState = RimeDaemon.runtimeState.value
        composing = if (runtimeState == RimeRuntimeState.READY) {
            runCatching { rime.run { statusCached.isComposing } }.getOrDefault(false)
        } else {
            false
        }
        translationFailure = currentTranslationFailure()
        footprintsAvailable = InputFootprints.isAvailable
        voiceAvailable = InputMethodUtils.firstVoiceInput() != null
        renderTiles()
    }

    private fun renderTiles() {
        HaoHaoToolboxAction.entries.forEach { action ->
            val tile = tiles[action] ?: return@forEach
            val availability = resolveHaoHaoToolAvailability(
                action = action,
                rimeState = runtimeState,
                composing = composing,
                translationFailure = translationFailure,
                footprintsAvailable = footprintsAvailable,
                voiceAvailable = voiceAvailable,
            )
            tile.root.isEnabled = availability.enabled
            tile.root.alpha = if (availability.enabled) 1f else 0.45f
            tile.summary.text = when {
                availability.reason != null -> context.getString(availability.reason.messageRes)
                action == HaoHaoToolboxAction.Footprints -> context.getString(
                    R.string.input_footprints_toolbox_summary_compact,
                    recentCount,
                    favoriteCount,
                )
                else -> context.getString(action.summaryRes)
            }
            tile.root.contentDescription = buildString {
                append(context.getString(action.labelRes))
                append('，')
                append(tile.summary.text)
            }
        }
    }

    override fun onAttached() {
        refreshStateSnapshot()
        runtimeJob = service.lifecycleScope.launch {
            RimeDaemon.runtimeState.collect { state ->
                runtimeState = state
                if (state != RimeRuntimeState.READY) composing = false
                renderTiles()
            }
        }
        val store = InputFootprints.storeOrNull
        if (store != null) {
            countsJob = service.lifecycleScope.launch {
                store.counts.collect { counts ->
                    recentCount = counts.recent
                    favoriteCount = counts.favorites
                    renderTiles()
                }
            }
        }
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        composing = data.length > 0
        renderTiles()
    }

    override fun onInputStatusUpdate(value: StatusProto) {
        composing = value.isComposing
        renderTiles()
    }

    override fun onDetached() {
        runtimeJob?.cancel()
        runtimeJob = null
        countsJob?.cancel()
        countsJob = null
        tiles.clear()
    }
}
