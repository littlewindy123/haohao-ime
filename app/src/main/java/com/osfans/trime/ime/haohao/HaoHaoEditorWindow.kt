/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.haohao

import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.util.toast
import org.kodein.di.instance
import splitties.dimensions.dp

internal const val HAOHAO_EDITOR_REPEAT_INITIAL_DELAY_MS = 350L
internal const val HAOHAO_EDITOR_REPEAT_INTERVAL_MS = 50L

internal enum class HaoHaoEditorAction(
    val repeatable: Boolean = false,
) {
    MoveUp(repeatable = true),
    MoveDown(repeatable = true),
    MoveLeft(repeatable = true),
    MoveRight(repeatable = true),
    ToggleSelection,
    SelectAll,
    Paste,
    Cut,
    Copy,
    Undo,
    Redo,
    Backspace(repeatable = true),
    ForwardDelete(repeatable = true),
}

internal data class HaoHaoEditorState(
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val selectionMode: Boolean = false,
    val passwordEditor: Boolean = false,
) {
    val hasSelection: Boolean
        get() = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd

    val canCutOrCopy: Boolean
        get() = hasSelection && !passwordEditor

    fun withSelection(start: Int, end: Int) = copy(selectionStart = start, selectionEnd = end)

    fun resetForEditor(passwordEditor: Boolean) = HaoHaoEditorState(passwordEditor = passwordEditor)

    fun afterSuccessfulAction(action: HaoHaoEditorAction): HaoHaoEditorState = when (action) {
        HaoHaoEditorAction.SelectAll -> copy(selectionMode = true)
        HaoHaoEditorAction.Copy -> copy(selectionMode = false)
        HaoHaoEditorAction.Cut,
        HaoHaoEditorAction.Paste,
        HaoHaoEditorAction.Backspace,
        HaoHaoEditorAction.ForwardDelete,
        -> copy(selectionStart = selectionEnd, selectionMode = false)
        else -> this
    }
}

internal class HaoHaoEditorActionExecutor(
    private val sendKey: (keyCode: Int, shift: Boolean) -> Boolean,
    private val performMenuAction: (menuAction: Int, fallbackKeyCode: Int, shift: Boolean) -> Boolean,
) {
    fun execute(action: HaoHaoEditorAction, state: HaoHaoEditorState): Boolean = when (action) {
        HaoHaoEditorAction.MoveUp -> sendKey(KeyEvent.KEYCODE_DPAD_UP, state.selectionMode)
        HaoHaoEditorAction.MoveDown -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, state.selectionMode)
        HaoHaoEditorAction.MoveLeft -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, state.selectionMode)
        HaoHaoEditorAction.MoveRight -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, state.selectionMode)
        HaoHaoEditorAction.SelectAll -> performMenuAction(android.R.id.selectAll, KeyEvent.KEYCODE_A, false)
        HaoHaoEditorAction.Paste -> performMenuAction(android.R.id.paste, KeyEvent.KEYCODE_V, false)
        HaoHaoEditorAction.Cut ->
            state.canCutOrCopy &&
                performMenuAction(android.R.id.cut, KeyEvent.KEYCODE_X, false)
        HaoHaoEditorAction.Copy ->
            state.canCutOrCopy &&
                performMenuAction(android.R.id.copy, KeyEvent.KEYCODE_C, false)
        HaoHaoEditorAction.Undo -> performMenuAction(android.R.id.undo, KeyEvent.KEYCODE_Z, false)
        HaoHaoEditorAction.Redo -> performMenuAction(android.R.id.redo, KeyEvent.KEYCODE_Z, true)
        HaoHaoEditorAction.Backspace -> sendKey(KeyEvent.KEYCODE_DEL, false)
        HaoHaoEditorAction.ForwardDelete -> sendKey(KeyEvent.KEYCODE_FORWARD_DEL, false)
        HaoHaoEditorAction.ToggleSelection -> true
    }
}

internal class HaoHaoEditorRepeatController(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallback: (Runnable) -> Unit,
    private val perform: (HaoHaoEditorAction) -> Boolean,
) {
    private var action: HaoHaoEditorAction? = null
    private val runnable = object : Runnable {
        override fun run() {
            val current = action ?: return
            if (perform(current)) {
                postDelayed(this, HAOHAO_EDITOR_REPEAT_INTERVAL_MS)
            } else {
                stop()
            }
        }
    }

    val isRunning: Boolean
        get() = action != null

    fun start(action: HaoHaoEditorAction): Boolean {
        stop()
        this.action = action
        return if (perform(action)) {
            postDelayed(runnable, HAOHAO_EDITOR_REPEAT_INITIAL_DELAY_MS)
            true
        } else {
            stop()
            false
        }
    }

    fun stop() {
        removeCallback(runnable)
        action = null
    }
}

class HaoHaoEditorWindow :
    BoardWindow.BarBoardWindow(),
    InputBroadcastReceiver {
    private val theme: Theme by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private val buttons = mutableMapOf<HaoHaoEditorAction, TextView>()

    private var state = HaoHaoEditorState()
    private var repeatedView: View? = null
    private val actionExecutor = HaoHaoEditorActionExecutor(::sendKey, ::performMenuAction)
    private val repeatController = HaoHaoEditorRepeatController(
        postDelayed = handler::postDelayed,
        removeCallback = handler::removeCallbacks,
        perform = ::performAction,
    )

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        renderState()
    }

    override val title: String
        get() = context.getString(R.string.haohao_editor_title)

    private fun createButton(
        action: HaoHaoEditorAction,
        label: String,
        @StringRes description: Int? = null,
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (label.length <= 1) 24f else 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        minWidth = dp(48)
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        contentDescription = description?.let(context::getString) ?: label
        buttons[action] = this
        if (action.repeatable) {
            setOnClickListener { }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!view.isEnabled) return@setOnTouchListener false
                        startRepeating(view, action)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        stopRepeating()
                        view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        stopRepeating()
                        true
                    }
                    else -> true
                }
            }
        } else {
            setOnClickListener {
                provideFeedback(this)
                performAction(action)
            }
        }
        applyButtonStyle(active = false)
    }

    private fun TextView.applyButtonStyle(active: Boolean) {
        val content = ColorManager.getDecorDrawable(
            if (active) "on_key_back_color" else "key_back_color",
            if (active) "on_key_back_color" else "key_border_color",
            dp(theme.generalStyle.keyBorder),
            dp(theme.generalStyle.roundCorner),
        )
        background = RippleDrawable(
            ColorStateList.valueOf(ColorManager.getColor("hilited_key_back_color")),
            content,
            null,
        )
        setTextColor(ColorManager.getColor(if (active) "on_key_text_color" else "key_text_color"))
    }

    private fun cellParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        1f,
    ).apply {
        val margin = context.dp(3)
        setMargins(margin, margin, margin, margin)
    }

    private fun rowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f,
    )

    private fun panelParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        1f,
    )

    private fun createRow(vararg children: View?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEach { child ->
            addView(child ?: View(context), cellParams())
        }
    }

    private fun createDirectionPad(): LinearLayout {
        val up = createButton(HaoHaoEditorAction.MoveUp, "↑")
        val down = createButton(HaoHaoEditorAction.MoveDown, "↓")
        val left = createButton(HaoHaoEditorAction.MoveLeft, "←")
        val right = createButton(HaoHaoEditorAction.MoveRight, "→")
        val select = createButton(
            HaoHaoEditorAction.ToggleSelection,
            context.getString(R.string.haohao_editor_select),
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(createRow(null, up, null), rowParams())
            addView(createRow(left, select, right), rowParams())
            addView(createRow(null, down, null), rowParams())
        }
    }

    private fun createActionGrid(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            createRow(
                createButton(
                    HaoHaoEditorAction.SelectAll,
                    context.getString(R.string.haohao_editor_select_all),
                ),
                createButton(
                    HaoHaoEditorAction.Paste,
                    context.getString(R.string.haohao_editor_paste),
                ),
            ),
            rowParams(),
        )
        addView(
            createRow(
                createButton(HaoHaoEditorAction.Cut, context.getString(R.string.haohao_editor_cut)),
                createButton(HaoHaoEditorAction.Copy, context.getString(R.string.haohao_editor_copy)),
            ),
            rowParams(),
        )
        addView(
            createRow(
                createButton(HaoHaoEditorAction.Undo, context.getString(R.string.haohao_editor_undo)),
                createButton(HaoHaoEditorAction.Redo, context.getString(R.string.haohao_editor_redo)),
            ),
            rowParams(),
        )
        addView(
            createRow(
                createButton(HaoHaoEditorAction.Backspace, "⌫", R.string.haohao_editor_backspace),
                createButton(HaoHaoEditorAction.ForwardDelete, "⌦", R.string.haohao_editor_forward_delete),
            ),
            rowParams(),
        )
    }

    override fun onCreateView(): View = object : LinearLayout(context) {
        override fun onDetachedFromWindow() {
            stopRepeating()
            state = state.copy(selectionMode = false)
            super.onDetachedFromWindow()
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(createDirectionPad(), panelParams())
        addView(createActionGrid(), panelParams())
        renderState()
    }

    override fun onAttached() {
        state = state
            .resetForEditor(isPasswordInputType(service.currentInputEditorInfo.inputType))
            .withSelection(service.currentSelectionStart, service.currentSelectionEnd)
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        renderState()
    }

    override fun onDetached() {
        stopRepeating()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        state = HaoHaoEditorState()
        buttons.clear()
    }

    override fun onStartInput(info: EditorInfo) {
        stopRepeating()
        state = state
            .resetForEditor(isPasswordInputType(info.inputType))
            .withSelection(info.initialSelStart, info.initialSelEnd)
        renderState()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        state = state.withSelection(start, end)
        renderState()
    }

    private fun startRepeating(view: View, action: HaoHaoEditorAction) {
        stopRepeating()
        repeatedView = view.apply { isPressed = true }
        provideFeedback(view)
        repeatController.start(action)
    }

    private fun stopRepeating() {
        repeatController.stop()
        repeatedView?.isPressed = false
        repeatedView = null
    }

    private fun provideFeedback(view: View) {
        InputFeedbackManager.keyPressSound()
        InputFeedbackManager.keyPressVibrate(view)
    }

    private fun performAction(action: HaoHaoEditorAction): Boolean {
        if (action == HaoHaoEditorAction.ToggleSelection) {
            state = state.copy(selectionMode = !state.selectionMode)
            renderState()
            return true
        }
        val success = actionExecutor.execute(action, state)
        if (success) {
            state = state.afterSuccessfulAction(action)
            renderState()
        } else {
            context.toast(R.string.haohao_editor_unsupported)
        }
        return success
    }

    private fun sendKey(keyCode: Int, shift: Boolean = false): Boolean = service.sendDownUpKeyEvent(keyCode, service.meta(shift = shift))

    private fun performMenuAction(
        menuAction: Int,
        fallbackKeyCode: Int,
        shift: Boolean = false,
    ): Boolean {
        val connection = service.currentInputConnection ?: return false
        if (menuAction != 0 && connection.performContextMenuAction(menuAction)) return true
        return service.sendDownUpKeyEvent(
            fallbackKeyCode,
            service.meta(ctrl = true, shift = shift),
        )
    }

    private fun renderState() {
        val selectButton = buttons[HaoHaoEditorAction.ToggleSelection]
        val selectLabel = context.getString(
            if (state.selectionMode) R.string.haohao_editor_selecting else R.string.haohao_editor_select,
        )
        selectButton?.text = selectLabel
        selectButton?.contentDescription = selectLabel
        selectButton?.applyButtonStyle(active = state.selectionMode)

        val canCutOrCopy = state.canCutOrCopy
        buttons[HaoHaoEditorAction.Cut]?.isEnabled = canCutOrCopy
        buttons[HaoHaoEditorAction.Copy]?.isEnabled = canCutOrCopy
        buttons[HaoHaoEditorAction.Paste]?.isEnabled = clipboardManager.hasPrimaryClip()
        buttons.values.forEach { button ->
            button.alpha = if (button.isEnabled) 1f else 0.38f
        }
    }
}
