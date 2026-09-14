// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class KeyboardWindow :
    BoardWindow.NoBarBoardWindow(),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by di.instance()
    private val theme: Theme by di.instance()
    private val rime: RimeSession by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val popup: PopupDelegate by di.instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by di.instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val presetKeyboardIds = theme.presetKeyboards.keys.toList()
    private var currentKeyboardId = ""
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private val editorAsciiModePolicy = EditorAsciiModePolicy()
    private var editorRequiresAscii = false
    @Volatile private var editorGeneration = 0L
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val currentKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    private val keyboardActionListener = commonKeyboardActionListener.listener
    private val nineKeySyllables by lazy {
        context.assets.open("haohao/nine_key_syllables.txt").bufferedReader().use { it.readLines() }
    }

    private fun updateNineKeySpellings() {
        if (currentKeyboardId != NINE_KEY_SCHEMA_ID) return
        rime.launchOnReady { api ->
            val input = api.getNineKeyInput()
            val spellings = nineKeySpellings(input, nineKeySyllables)
            service.lifecycleScope.launch {
                if (currentKeyboardId == NINE_KEY_SCHEMA_ID) {
                    currentKeyboardView?.showNineKeySpellings(input, spellings) { syllable, expected ->
                        service.postRimeJob { filterNineKeySyllable(syllable, expected) }
                    }
                }
            }
        }
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        updateNineKeySpellings()
    }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachKeyboard(evalKeyboard(".default"))
        return keyboardView
    }

    override fun enterAnimation(lastWindow: com.osfans.trime.ime.window.BoardWindow): androidx.transition.Transition? = if (ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID) null else super.enterAnimation(lastWindow)

    override fun exitAnimation(nextWindow: com.osfans.trime.ime.window.BoardWindow): androidx.transition.Transition? = if (ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID) null else super.exitAnimation(nextWindow)

    private fun detachCurrentView(rememberAsciiMode: Boolean) {
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        if (rememberAsciiMode) {
            currentKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
        }
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? {
        val config = theme.presetKeyboards[name] ?: theme.presetKeyboards["default"]
        val importPreset = config?.importPreset
        if (!importPreset.isNullOrEmpty()) {
            return selectKeyboardConfig(importPreset)
        }
        return config
    }

    private fun attachKeyboard(target: String, applyAsciiMode: Boolean = true) {
        currentKeyboardId = target
        lastKeyboardId = target

        val config = selectKeyboardConfig(target)
        val keyboard = currentKeyboard ?: Keyboard(context, theme, config)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        if (currentKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        keyboard.also {
            _currentKeyboardHeight.tryEmit(it.keyboardHeight)
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            if (applyAsciiMode) {
                val targetMode = keyboardAsciiMode(
                    editorRequiresAscii,
                    if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode,
                )
                service.postRimeJob {
                    if (getRuntimeOption("ascii_mode") != targetMode) commitComposition()
                    setRuntimeOption("ascii_mode", targetMode)
                }
            }

            // TODO：为避免过量重构，这里暂时将 currentKeyboard 同步到 KeyboardSwitcher
            KeyboardSwitcher.currentKeyboard = it
        }

        view.let {
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                add(it, lParams(matchParent, matchParent))
            }
        }
    }

    private fun smartMatchKeyboard(): String {
        // 主题的布局中包含方案id，直接采用
        val currentSchema = rime.run { statusCached }.schemaId
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        val alphabet = rime.run { schemaCached }.alphabet
        val layout =
            when {
                alphabet.all { it.isLetter() } -> "qwerty" // 包含 26 个字母
                alphabet.all { it.isLetter() || ",./;".any(it::equals) } -> "qwerty_" // 包含 26 个字母和,./;
                alphabet.all { it.isLetterOrDigit() } -> "qwerty0" // 包含 26 个字母和数字键
                else -> "default"
            }
        return if (presetKeyboardIds.contains(layout)) layout else "default"
    }

    private fun evalKeyboard(id: String): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (id) {
                ".default" -> smartMatchKeyboard()
                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId
                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId
                ".last" -> lastKeyboardId
                ".last_lock" -> lastLockKeyboardId
                ".ascii" -> resolveAsciiKeyboard(
                    currentKeyboard?.asciiKeyboard,
                    selectKeyboardConfig(lastLockKeyboardId)?.asciiKeyboard,
                    lastLockKeyboardId,
                    currentKeyboardId,
                    presetKeyboardIds,
                )
                else -> {
                    id.ifEmpty {
                        if (currentKeyboard?.isLock == true) currentKeyboardId else lastLockKeyboardId
                    }
                }
            }
        var final = dot.ifEmpty { smartMatchKeyboard() }

        // 切换到横屏布局
        if (service.isLandscapeMode()) {
            val landscape =
                theme.presetKeyboards[final]?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        }
        return final
    }

    fun switchKeyboard(to: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            switchKeyboardOnMain(to)
        } else {
            val generation = editorGeneration
            ContextCompat.getMainExecutor(service).execute {
                if (generation == editorGeneration) switchKeyboardOnMain(to)
            }
        }
    }

    private fun switchKeyboardOnMain(
        to: String,
        applyAsciiMode: Boolean = true,
        rememberAsciiMode: Boolean = applyAsciiMode && !editorRequiresAscii,
    ): Boolean {
        val target = evalKeyboard(to)
        if (target == currentKeyboardId && target in cachedKeyboards) return false
        // Never store a temporary forced mode as the layout's ordinary mode.
        detachCurrentView(rememberAsciiMode)
        attachKeyboard(target, applyAsciiMode)
        updateNineKeySpellings()
        Timber.d("Switched to keyboard: $target")
        return true
    }

    private fun syncAsciiKeyboard(asciiMode: Boolean) {
        if ("haohao_english" in presetKeyboardIds &&
            currentKeyboardId in setOf("default", "qwerty", "haohao_english", NINE_KEY_SCHEMA_ID)
        ) {
            // The engine already owns this mode. Mounting its layout must not enqueue a reset.
            switchKeyboardOnMain(if (asciiMode) "haohao_english" else ".default", applyAsciiMode = false)
        }
    }

    override fun onStartInput(info: EditorInfo) {
        if (currentKeyboard?.clearTransientShift() == true) {
            currentKeyboardView?.invalidateAllKeys()
        }
        val generation = ++editorGeneration
        val targetKeyboard = editorKeyboardTarget(info.inputType, info.imeOptions)
        val requireAscii = targetKeyboard == ".ascii" || targetKeyboard == "number"
        val wasForcedAscii = editorRequiresAscii
        editorRequiresAscii = requireAscii
        // onStartInput is main-thread: mount now, then enqueue exactly one editor mode barrier.
        val layoutChanged = switchKeyboardOnMain(
            targetKeyboard,
            applyAsciiMode = false,
            rememberAsciiMode = !wasForcedAscii,
        )
        val normalMode = currentKeyboard?.takeIf {
            layoutChanged || theme.generalStyle.resetAsciiModeOnFocusChange
        }?.let { if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode }
        service.postRimeJob {
            val targetMode = editorAsciiModePolicy.onStartInput(
                requireAscii,
                getRuntimeOption("ascii_mode"),
                normalMode,
            )
            if (targetMode != null) {
                // Do not use statusCached to skip this required force/restoration write.
                setRuntimeOption("ascii_mode", targetMode)
                if (generation == editorGeneration && targetKeyboard != "number") {
                    syncAsciiKeyboard(targetMode)
                }
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.run { statusCached }
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.generalStyle.autoCaps && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.run { statusCached }.isAsciiMode) {
            currentKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            currentKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        switchKeyboard(".default")
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        when {
            option == "ascii_mode" -> {
                if (editorRequiresAscii && !value.value) {
                    val generation = editorGeneration
                    service.postRimeJob {
                        if (generation == editorGeneration) setRuntimeOption("ascii_mode", true)
                    }
                }
                syncAsciiKeyboard(editorRequiresAscii || value.value)
            }
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }
            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(KeyActionManager.getAction(what))
                }
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
        updateNineKeySpellings()
    }

    override fun onDetached() {
        currentKeyboardView?.onDetach()
    }
}
