/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyEvent
import com.osfans.trime.core.RimeRuntimeState
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.translation.CloudTranslationResult
import com.osfans.trime.data.translation.CloudTranslationRuntime
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.haohao.HAOHAO_EDITOR_ACTION
import com.osfans.trime.ime.haohao.HAOHAO_INPUT_FOOTPRINTS_ACTION
import com.osfans.trime.ime.haohao.HAOHAO_TRANSLATION_ACTION
import com.osfans.trime.ime.haohao.HaoHaoEditorWindow
import com.osfans.trime.ime.haohao.HaoHaoShiftPolicy
import com.osfans.trime.ime.haohao.HaoHaoToolboxAction
import com.osfans.trime.ime.haohao.HaoHaoToolboxWindow
import com.osfans.trime.ime.haohao.HaoHaoTranslationController
import com.osfans.trime.ime.haohao.resolveHaoHaoToolAvailability
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.symbol.LiquidData
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.SoundEffectPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.buildIntentFromAction
import com.osfans.trime.util.buildIntentFromArgument
import com.osfans.trime.util.customFormatDateTime
import com.osfans.trime.util.isAsciiPrintable
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

class CommonKeyboardActionListener {
    private val di = InputDependencyManager.getInstance().di

    private val context: Context by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private val rime: RimeSession by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val keyboardWindow: KeyboardWindow by di.instance()
    private val liquidWindow: LiquidWindow by di.instance()
    private val translationController: HaoHaoTranslationController by di.instance()

    private val prefs = AppPrefs.defaultInstance()

    private fun showDialog(dialog: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(dialog(api))
            }
        }
    }

    private fun showThemePicker() {
        showDialog {
            ThemePickerDialog.build(service.lifecycleScope, context) {
                service.postRimeJob { commitComposition() }
            }
        }
    }

    private fun showColorPicker() {
        showDialog {
            ColorPickerDialog.build(service.lifecycleScope, context) {
                service.postRimeJob { commitComposition() }
            }
        }
    }

    private fun showSoundEffectPicker() {
        showDialog {
            SoundEffectPickerDialog.build(service.lifecycleScope, context)
        }
    }

    private fun showEnabledSchemaPicker() {
        showDialog { api ->
            EnabledSchemaPickerDialog.build(api, service.lifecycleScope, context) {
                setNegativeButton(R.string.enable_schemata) { _, _ ->
                    AppUtils.launchMainToSchemaList(context)
                }
            }
        }
    }

    private fun expandActiveText(input: String): String = if (input.matches(PLACEHOLDER_PATTERN)) {
        input.format(
            service.getActiveText(1),
            service.getActiveText(2),
            service.getActiveText(3),
            service.getActiveText(4),
        )
    } else {
        input
    }

    val listener by lazy {
        object : KeyboardActionListener {
            override fun onPress(keyEventCode: Int) {
                InputFeedbackManager.run {
                    keyPressSound(keyEventCode)
                    keyPressSpeak(keyEventCode)
                }
            }

            override fun onAction(action: KeyAction) {
                if (handleHaoHaoTranslationEditing(action)) return
                if (handleHaoHaoSingleUppercase(action)) return
                val text = action.getText(KeyboardSwitcher.currentKeyboard)
                val shouldHandle = when {
                    action.commit.isNotEmpty() -> {
                        service.commitText(action.commit)
                        false
                    }
                    text.isNotEmpty() -> {
                        onText(text)
                        false
                    }
                    else -> true
                }

                if (shouldHandle) {
                    when (action.code) {
                        KeyEvent.KEYCODE_SWITCH_CHARSET -> handleSwitchCharset(action)
                        KeyEvent.KEYCODE_EISU -> keyboardWindow.switchKeyboard(action.select)
                        KeyEvent.KEYCODE_LANGUAGE_SWITCH -> handleLanguageSwitch(action)
                        KeyEvent.KEYCODE_FUNCTION -> handleFunctionCommand(action)
                        KeyEvent.KEYCODE_SETTINGS -> handleSettings(action)
                        KeyEvent.KEYCODE_PROG_RED -> showColorPicker()
                        KeyEvent.KEYCODE_MENU -> showEnabledSchemaPicker()
                        KeyEvent.KEYCODE_VOICE_ASSIST -> switchToVoiceInputMethod()
                        else -> handleDefaultKeyAction(action)
                    }
                }
            }

            private fun handleHaoHaoTranslationEditing(action: KeyAction): Boolean {
                if (!translationController.isActive) return false
                return when (action.code) {
                    KeyEvent.KEYCODE_DEL -> translationController.deleteLastCodePoint()
                    KeyEvent.KEYCODE_ENTER -> translationController.translateNow()
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        translationController.deactivate()
                        true
                    }
                    else -> false
                }
            }

            private fun handleHaoHaoSingleUppercase(action: KeyAction): Boolean {
                if (ThemeManager.prefs.selectedTheme.getValue() != DEFAULT_THEME_ID) return false
                val keyboard = KeyboardSwitcher.currentKeyboard
                val status = rime.run { statusCached }
                if (!HaoHaoShiftPolicy.shouldCommitSingleUppercase(
                        asciiMode = status.isAsciiMode,
                        composing = status.isComposing,
                        shifted = keyboard.isOnlyShiftOn,
                        keyCode = action.code,
                    )
                ) {
                    return false
                }
                service.commitText(HaoHaoShiftPolicy.uppercaseFor(action.code))
                return true
            }

            private fun handleSwitchCharset(action: KeyAction) {
                val option = action.toggle.ifEmpty { return }

                service.postRimeJob {
                    val isEnabled = getRuntimeOption(option)
                    val isComposing = statusCached.isComposing
                    setRuntimeOption(option, !isEnabled)
                    if (option == "ascii_mode" && isComposing) {
                        getRawInput().takeIf { it.isNotEmpty() }?.let {
                            service.commitText(it)
                            clearComposition()
                        }
                    }
                }
            }

            private fun handleLanguageSwitch(action: KeyAction) {
                when {
                    action.select == ".next" -> service.switchToNextIme()
                    action.select.isNotEmpty() -> service.switchToPrevIme()
                    else -> inputMethodManager.showInputMethodPicker()
                }
            }

            private fun handleFunctionCommand(action: KeyAction) {
                val arg = expandActiveText(action.option)

                when (action.command) {
                    "liquid_keyboard" -> handleLiquidKeyboard(arg)
                    "menu_keyboard" -> windowManager.attachWindow(SwitchOptionWindow())
                    "haohao_toolbox" -> windowManager.attachWindow(HaoHaoToolboxWindow())
                    HAOHAO_EDITOR_ACTION -> openHaoHaoEditor()
                    HAOHAO_INPUT_FOOTPRINTS_ACTION -> {
                        if (ensureHaoHaoToolAvailable(HaoHaoToolboxAction.Footprints)) {
                            AppUtils.launchMainToInputFootprints(service)
                        }
                    }
                    HAOHAO_TRANSLATION_ACTION -> openHaoHaoTranslation()
                    "clipboard_window" -> handleClipboardWindow(arg)
                    "set_color_scheme" -> handleColorScheme(arg)
                    "set_theme" -> handleTheme(arg)
                    "broadcast" -> service.sendBroadcast(Intent(arg))
                    "clipboard" -> handleClipboard()
                    "commit" -> service.commitText(arg)
                    "date" -> service.commitText(customFormatDateTime(arg))
                    "run" -> handleRunCommand(arg)
                    "apply" -> handleApplyCommand(arg)
                    "share_text" -> service.shareText()
                    "select_candidate" -> handleSelectCandidate(arg)
                    "switch_hide_key_symbol" -> switchHideKeySymbol()
                    "switch_hide_key_hint" -> switchHideKeyHint()
                    else -> handleIntentAction(action.command, arg)
                }
            }

            private fun currentHaoHaoToolAvailability(action: HaoHaoToolboxAction) = run {
                val runtimeState = RimeDaemon.runtimeState.value
                resolveHaoHaoToolAvailability(
                    action = action,
                    rimeState = runtimeState,
                    composing = if (runtimeState == RimeRuntimeState.READY) {
                        runCatching { rime.run { statusCached.isComposing } }.getOrDefault(false)
                    } else {
                        false
                    },
                    translationFailure = if (action == HaoHaoToolboxAction.Translation) {
                        runCatching {
                            val manager = CloudTranslationRuntime.manager
                            manager.configurationStatus()?.kind ?: manager.status()?.kind
                        }.getOrDefault(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
                    } else {
                        null
                    },
                    footprintsAvailable =
                    action != HaoHaoToolboxAction.Footprints || InputFootprints.isAvailable,
                    voiceAvailable =
                    action != HaoHaoToolboxAction.Voice || InputMethodUtils.firstVoiceInput() != null,
                )
            }

            private fun ensureHaoHaoToolAvailable(action: HaoHaoToolboxAction): Boolean {
                val availability = currentHaoHaoToolAvailability(action)
                if (availability.enabled) return true
                availability.reason?.let { service.toast(it.messageRes) }
                return false
            }

            private fun openHaoHaoEditor() {
                if (!ensureHaoHaoToolAvailable(HaoHaoToolboxAction.Editor)) return
                windowManager.attachWindow(HaoHaoEditorWindow())
            }

            private fun openHaoHaoTranslation() {
                if (!ensureHaoHaoToolAvailable(HaoHaoToolboxAction.Translation)) return
                when (val failure = CloudTranslationRuntime.manager.status()) {
                    null -> activateHaoHaoTranslation()
                    is CloudTranslationResult.Failure -> when (failure.kind) {
                        CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED -> showCloudTranslationConsent()
                        CloudTranslationResult.Failure.Kind.NOT_CONFIGURED ->
                            service.toast(R.string.haohao_translation_not_configured)
                        CloudTranslationResult.Failure.Kind.INVALID_REQUEST ->
                            service.toast(R.string.haohao_translation_sensitive_disabled)
                        else -> service.toast(R.string.cloud_translation_error_network)
                    }
                }
            }

            private fun showCloudTranslationConsent() {
                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.cloud_translation_consent_title)
                    .setMessage(R.string.cloud_translation_consent_message)
                    .setPositiveButton(R.string.cloud_translation_consent_confirm) { _, _ ->
                        prefs.cloudTranslation.consentGranted.setValue(true)
                        openHaoHaoTranslation()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                service.showDialog(dialog)
            }

            private fun activateHaoHaoTranslation() {
                val failure = translationController.activate() ?: return
                if (failure.kind == CloudTranslationResult.Failure.Kind.INVALID_REQUEST) {
                    service.toast(R.string.haohao_translation_sensitive_disabled)
                } else {
                    service.toast(R.string.haohao_translation_not_configured)
                }
            }

            private fun handleLiquidKeyboard(arg: String) {
                // for compatibility
                if (arg == "剪贴" || arg == "clipboard") {
                    windowManager.attachWindow(ClipboardWindow())
                    return
                }
                val liquidTagList = LiquidData.getTagList()
                val index = liquidTagList.indexOfFirst { tag ->
                    tag.label == arg || runCatching {
                        LiquidData.Type.valueOf(arg.uppercase())
                    }.getOrNull() == tag.type
                }

                if (index >= 0) {
                    windowManager.attachWindow(LiquidWindow)
                    liquidWindow.setDataByIndex(index)
                } else if (ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID) {
                    service.toast(R.string.optional_feature_unavailable)
                } else {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            private fun handleClipboardWindow(arg: String) {
                if (!ClipboardHelper.isAvailable || !CollectionHelper.isAvailable) {
                    service.toast(R.string.optional_feature_unavailable)
                    return
                }
                val tabIndex = arg.toIntOrNull()?.coerceIn(0, 1) ?: 0
                windowManager.attachWindow(ClipboardWindow(tabIndex))
            }

            private fun handleColorScheme(arg: String) {
                ThemeManager.activeTheme.colorSchemes
                    .find { it.id == arg }
                    ?.let { ColorManager.setColorScheme(it) }
            }

            private fun handleTheme(arg: String) {
                if (arg.isEmpty()) {
                    // 参数为空时，刷新当前主题
                    ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue())
                } else {
                    // 通过主题名称查找对应的配置ID并切换主题
                    ThemeManager.getAllThemes()
                        .find { it.name.equals(arg, ignoreCase = true) }?.let {
                            ThemeManager.selectTheme(it.configId)
                        }
                }
            }

            private fun handleClipboard() {
                clipboardManager.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(service)
                    ?.let { service.commitText(it.toString()) }
            }

            private fun handleRunCommand(arg: String) {
                buildIntentFromArgument(arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleApplyCommand(arg: String) {
                when (arg) {
                    "DEPLOY" -> {
                        Timber.i("try to start maintenance via command ...")
                        rime.launchOnReady { api -> api.deploy() }
                    }
                    "SYNC_USER_DATA" -> {
                        Timber.i("try to sync rime user data via command ...")
                        rime.launchOnReady { api -> api.syncUserData() }
                    }
                    "UPDATE_CONFIG" -> {
                        Timber.i("try to update rime config via command ...")
                        rime.launchOnReady { api ->
                            api.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    else -> Timber.w("Unknown apply method: $arg")
                }
            }

            private fun handleIntentAction(command: String, arg: String) {
                buildIntentFromAction(command, arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleSelectCandidate(arg: String) {
                val index = arg.toIntOrNull() ?: return
                service.selectCandidateFromCurrentPresentation(index, global = false)
            }

            private fun switchHideKeySymbol() {
                val preference = prefs.keyboard.hideKeySymbol
                preference.setValue(!preference.getValue())
            }

            private fun switchHideKeyHint() {
                val preference = prefs.keyboard.hideKeyHint
                preference.setValue(!preference.getValue())
            }

            private fun handleSettings(action: KeyAction) {
                when (action.option) {
                    "theme" -> showThemePicker()
                    "color" -> showColorPicker()
                    "schema" -> AppUtils.launchMainToSchemaList(context)
                    "sound" -> showSoundEffectPicker()
                    else -> AppUtils.launchMainActivity(service)
                }
            }

            private fun switchToVoiceInputMethod() {
                if (!ensureHaoHaoToolAvailable(HaoHaoToolboxAction.Voice)) return
                val pkgName = prefs.general.preferredVoiceInput.getValue()
                val voiceInputSubType = if (pkgName.isNotEmpty()) {
                    InputMethodUtils.voiceInputMethods().find {
                        it.first.packageName == pkgName
                    }?.let {
                        it.first.id to it.second
                    } ?: InputMethodUtils.firstVoiceInput()
                } else {
                    InputMethodUtils.firstVoiceInput()
                }
                if (voiceInputSubType != null) {
                    val (id, subType) = voiceInputSubType
                    InputMethodUtils.switchInputMethod(service, id, subType)
                } else {
                    service.toast(R.string.no_voice_input_installed)
                }
            }

            private fun handleDefaultKeyAction(action: KeyAction) {
                val shouldHookShiftKey = when {
                    prefs.keyboard.hookShiftSpace.getValue() && action.code == KeyEvent.KEYCODE_SPACE -> true
                    prefs.keyboard.hookShiftNum.getValue() && action.code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_SLASH -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in setOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD) -> true
                    else -> false
                }

                if (action.modifier == 0 && KeyboardSwitcher.currentKeyboard.isOnlyShiftOn && shouldHookShiftKey) {
                    onKey(action.code, 0)
                    return
                }

                val modifier = when {
                    action.modifier == 0 -> KeyboardSwitcher.currentKeyboard.modifier
                    (action.modifier and KeyEvent.META_CTRL_ON) != 0 && isNavigationKey(action.code) ->
                        action.modifier or KeyboardSwitcher.currentKeyboard.modifier
                    else -> action.modifier
                }

                onKey(action.code, modifier)
            }

            private fun isNavigationKey(keyCode: Int): Boolean = keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
                keyCode == KeyEvent.KEYCODE_MOVE_END

            override fun onKey(
                keyEventCode: Int,
                metaState: Int,
            ) {
                // An uppercase letter key (e.g. from `{x: A}`) is passed to
                // rime as the uppercase keysym with Shift, matching what a
                // physical keyboard reports via the unicode char, so that
                // rime commits the uppercase letter in ascii mode as well.
                // The generated reverse mapping would otherwise resolve e.g.
                // KEYCODE_A to the lowercase name "a" (XK_a).
                val value =
                    if (metaState and KeyEvent.META_SHIFT_ON != 0 &&
                        keyEventCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
                    ) {
                        'A'.code + (keyEventCode - KeyEvent.KEYCODE_A) // XK_A..XK_Z
                    } else {
                        val name = KeyCode.codeToKeyName(keyEventCode) ?: "VoidSymbol"
                        RimeKeyEvent.getKeycodeByName(name)
                    }
                val m = if (keyEventCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS) {
                    metaState or KeyEvent.META_NUM_LOCK_ON
                } else {
                    metaState
                }
                val modifiers = KeyModifiers.fromMetaState(m).modifiers
                service.postRimeKey {
                    if (service.hookKeyboard(keyEventCode, m)) {
                        Timber.d("handleKey: hook")
                        return@postRimeKey
                    }
                    if (processKeyDeferred(value, modifiers)) {
                        Timber.d("handleKey: processKey")
                        return@postRimeKey
                    }
                    if (AppUtils.launchKeyCategory(service, keyEventCode)) {
                        Timber.d("handleKey: openCategory")
                        return@postRimeKey
                    }
                    // other special cases
                    if (keyEventCode == KeyEvent.KEYCODE_BACK) {
                        service.requestHideSelf(0)
                    }
                }
            }

            override fun onText(input: String) {
                if (input.isEmpty()) return
                Timber.d("onText: $input")
                val status = rime.run { statusCached }
                if (!input[0].isAsciiPrintable() && status.isComposing) {
                    service.postRimeJob { commitComposition() }
                }

                val escaped = input.replace("{}", "{braceleft}{braceright}")
                var i = 0
                while (i < escaped.length) {
                    val value = when (val match = TEXT_INPUT_PATTERN.matchEntire(escaped.substring(i))) {
                        match if (match != null) -> match.groupValues[1]
                        else -> escaped[i].toString()
                    }

                    service.postRimeJob {
                        if (value.run { startsWith('{') && endsWith('}') }) {
                            val token = value.removeSurrounding("{", "}")
                            onAction(KeyActionManager.getAction(token))
                        } else if (!value[0].isAsciiPrintable()) {
                            service.commitText(value)
                        } else {
                            simulateKeySequence(value)
                        }
                    }

                    i += value.length
                }
            }
        }
    }

    companion object {
        /**
         * Regex for combined key events.
         * group(1) captures either:
         *   - a plain prefix (optionally preceded by {Escape}) from the left branch,
         *   - or a standalone {xxx} block from the right branch.
         * The trailing .* consumes the rest of the input without affecting group(1).
         */
        private val TEXT_INPUT_PATTERN = """^((?:\{Escape\})?[^{}]+|\{[^{}]+\}).*$""".toRegex()

        private val PLACEHOLDER_PATTERN = Regex(".*(%([1-4]\\$)?s).*")
    }
}
