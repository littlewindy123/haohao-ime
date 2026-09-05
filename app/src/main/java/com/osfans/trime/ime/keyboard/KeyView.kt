/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.KeyEvent
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.haohao.HaoHaoGesturePolicy
import com.osfans.trime.ime.haohao.HaoHaoSlideDeleteController
import com.osfans.trime.ime.haohao.isPasswordInputType
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

private const val FUNCTION_KEY_DEPTH_ALPHA = 0.72f
private const val HIGHLIGHT_INSET_RATIO = 0.65f

internal data class HaoHaoModeLabel(
    val active: String,
    val inactive: String,
    val activeTextSizeSp: Float = 20f,
    val inactiveTextSizeSp: Float = 10f,
)

internal fun resolveHaoHaoModeLabel(asciiMode: Boolean): HaoHaoModeLabel = if (asciiMode) {
    HaoHaoModeLabel(active = "英", inactive = "中")
} else {
    HaoHaoModeLabel(active = "中", inactive = "英")
}

internal data class KeySurfaceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun offset(dx: Int, dy: Int) = copy(
        left = left + dx,
        top = top + dy,
        right = right + dx,
        bottom = bottom + dy,
    )

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

internal data class KeySurfaceGeometry(
    val logicalCell: KeySurfaceRect,
    val cap: KeySurfaceRect,
    val shadow: KeySurfaceRect?,
)

internal fun calculateKeySurfaceGeometry(
    width: Int,
    height: Int,
    paddingLeft: Int,
    paddingTop: Int,
    paddingRight: Int,
    paddingBottom: Int,
    shadowOffsetY: Int,
    pressOffsetX: Int,
    pressOffsetY: Int,
    pressed: Boolean,
): KeySurfaceGeometry {
    val logicalCell = KeySurfaceRect(0, 0, width, height)
    val safeLeft = paddingLeft.coerceIn(0, width)
    val safeTop = paddingTop.coerceIn(0, height)
    val capBase = KeySurfaceRect(
        left = safeLeft,
        top = safeTop,
        right = (width - paddingRight).coerceIn(safeLeft, width),
        bottom = (height - paddingBottom).coerceIn(safeTop, height),
    )
    val effectiveShadowOffset = shadowOffsetY.coerceIn(0, paddingBottom.coerceAtLeast(0))
    val layered = effectiveShadowOffset > 0
    val effectivePressOffsetX = pressOffsetX.coerceIn(-safeLeft, paddingRight.coerceAtLeast(0))
    val effectivePressOffsetY = pressOffsetY.coerceIn(-safeTop, paddingBottom.coerceAtLeast(0))
    val cap = if (layered && pressed) capBase.offset(effectivePressOffsetX, effectivePressOffsetY) else capBase
    val shadow = if (layered && !pressed) capBase.offset(0, effectiveShadowOffset) else null
    return KeySurfaceGeometry(logicalCell, cap, shadow)
}

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val rime get() = RimeDaemon.getFirstSessionOrNull()!!

    private val keyboardPrefs = AppPrefs.defaultInstance().keyboard

    private val slideDeleteController by lazy {
        HaoHaoSlideDeleteController(
            readPreviousCodePoint = ::readPreviousCodePoint,
            deletePreviousCodePoint = ::deletePreviousCodePoint,
            restoreText = { service.currentInputConnection?.commitText(it, 1) == true },
        )
    }

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val isHaoHaoTheme = ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID

    private var cachedIcon: IconicsDrawable? = null
    private var cachedIconName: String? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        setWillNotDraw(false)
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor == true && keyboardPrefs.spacebarSlideCursor.getValue()
        isSlideDelete = key.click?.isSlideDelete == true && keyboardPrefs.backspaceSlideDelete.getValue()
        if (isHaoHaoTheme) {
            slideStepDensity = resources.displayMetrics.density
        }
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK)
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK)
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()

        onPress = {
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) }
            showPopupPreview()
        }

        onRelease = { behavior, isFromLongPress ->
            if (isFromLongPress) {
                if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(KeyAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }
                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK,
                    KeyBehavior.SWIPE_UP, KeyBehavior.SWIPE_DOWN, KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT,
                    ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }
                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
            slideDeleteController.clear()
        }

        onSwipe = { direction ->
            setPressedState(true)
            showPopupPreview(direction)
        }

        onSlide = { delta, _, _ ->
            if (isSlideCursor) {
                if (HaoHaoGesturePolicy.canSlideCursor(rime.run { statusCached.isComposing })) {
                    val action = if (delta > 0) KeyAction("Right") else KeyAction("Left")
                    repeat(abs(delta)) {
                        keyboardActionListener.onAction(action)
                        provideSlideFeedback(action.code)
                    }
                }
            } else if (isSlideDelete) {
                val editorInfo = service.currentInputEditorInfo
                val allowed = HaoHaoGesturePolicy.canSlideDelete(
                    composing = rime.run { statusCached.isComposing },
                    password = editorInfo == null || isPasswordInputType(editorInfo.inputType),
                    selectionStart = service.currentSelectionStart,
                    selectionEnd = service.currentSelectionEnd,
                )
                if (allowed) {
                    repeat(slideDeleteController.slide(delta)) {
                        provideSlideFeedback(KeyEvent.KEYCODE_DEL)
                    }
                }
            }
        }

        onLongClick = {
            if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                key.getAction(KeyBehavior.LONG_CLICK)?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            slideDeleteController.clear()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    private fun readPreviousCodePoint(): String? {
        val inputConnection = service.currentInputConnection ?: return null
        val beforeCursor = inputConnection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (beforeCursor.isEmpty()) return null
        val codePoint = beforeCursor.codePointBefore(beforeCursor.length)
        return String(Character.toChars(codePoint))
    }

    private fun deletePreviousCodePoint(): Boolean {
        val inputConnection = service.currentInputConnection ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            inputConnection.deleteSurroundingTextInCodePoints(1, 0)
        ) {
            return true
        }
        val previous = readPreviousCodePoint() ?: return false
        return inputConnection.deleteSurroundingText(previous.length, 0)
    }

    private fun provideSlideFeedback(keyCode: Int) {
        InputFeedbackManager.keyPressSound(keyCode)
        InputFeedbackManager.keyPressVibrate(this)
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            invalidate()
        }
    }

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {

        if (action.isModifierKey) {
            val status = rime.run { statusCached }
            if (action.modifierKeyOnMask == KeyEvent.META_SHIFT_ON &&
                ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID &&
                status.isComposing
            ) {
                return
            }
            val requestedLock = action.isShiftLock xor (behavior == KeyBehavior.LONG_CLICK)
            keyboard.clickModifierKey(
                requestedLock && status.isAsciiMode,
                action.modifierKeyOnMask,
            )
            keyboardView.invalidateAllKeys()
            return
        }

        keyboardActionListener.onAction(action)

        val hookArrow = if (keyboardView.hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        if (!keyboardView.popupOnKeyPress) return
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackground(canvas, key)

        val label = key.getLabel().let {
            if (it == "enter_labels") keyboardView.labelEnter else it
        }

        if (isHaoHaoTheme && key.click?.toggle == "ascii_mode") {
            drawHaoHaoModeLabel(canvas, resolveHaoHaoModeLabel(rime.run { statusCached }.isAsciiMode))
        } else if (label.isNotEmpty()) {
            drawLabel(canvas, label)
        }

        val symbol = key.symbolLabel
        if (symbol.isNotEmpty()) {
            drawSymbol(canvas, symbol)
        }

        val hint = key.hint
        if (hint.isNotEmpty()) {
            drawSymbol(canvas, hint, isTop = false)
        }
    }

    private fun drawBackground(canvas: Canvas, k: Key) {
        val bg = k.getBackgroundDrawable() ?: return
        val cornerRadius = dp(k.roundCorner ?: keyboard.roundCorner)
        val geometry = calculateKeySurfaceGeometry(
            width = width,
            height = height,
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
            paddingRight = paddingRight,
            paddingBottom = paddingBottom,
            shadowOffsetY = dp(keyboard.keyShadowOffsetY).roundToInt(),
            pressOffsetX = sp(k.keyPressOffsetX).roundToInt(),
            pressOffsetY = sp(k.keyPressOffsetY).roundToInt(),
            pressed = k.isPressed,
        )
        val depthAlpha = if (k.click?.isFunctional == true) FUNCTION_KEY_DEPTH_ALPHA else 1f

        geometry.shadow?.let { shadow ->
            val shadowColor = runCatching { ColorManager.getColor("key_shadow_color") }.getOrDefault(Color.TRANSPARENT)
            if (Color.alpha(shadowColor) > 0) {
                shadowPaint.color = shadowColor
                shadowPaint.alpha = (Color.alpha(shadowColor) * depthAlpha).roundToInt()
                canvas.drawRoundRect(
                    shadow.left.toFloat(),
                    shadow.top.toFloat(),
                    shadow.right.toFloat(),
                    shadow.bottom.toFloat(),
                    cornerRadius,
                    cornerRadius,
                    shadowPaint,
                )
                shadowPaint.alpha = 255
            }
        }

        if (bg is GradientDrawable) {
            cornerRadius.takeIf { it > 0f }?.let { bg.cornerRadius = it }
            (k.keyBorder ?: keyboard.keyBorder).takeIf { it > 0 }?.let { bg.setStroke(dp(it), ColorManager.getColor("key_border_color")) }
        }

        bg.setBounds(
            geometry.cap.left,
            geometry.cap.top,
            geometry.cap.right,
            geometry.cap.bottom,
        )
        bg.draw(canvas)

        if (geometry.shadow != null) {
            val highlightColor = runCatching { ColorManager.getColor("key_highlight_color") }.getOrDefault(Color.TRANSPARENT)
            if (Color.alpha(highlightColor) > 0) {
                val horizontalInset = maxOf(dp(4).toFloat(), cornerRadius * HIGHLIGHT_INSET_RATIO)
                highlightPaint.color = highlightColor
                highlightPaint.alpha = (Color.alpha(highlightColor) * depthAlpha).roundToInt()
                canvas.drawLine(
                    geometry.cap.left + horizontalInset,
                    geometry.cap.top + dp(1).toFloat(),
                    geometry.cap.right - horizontalInset,
                    geometry.cap.top + dp(1).toFloat(),
                    highlightPaint,
                )
                highlightPaint.alpha = 255
            }
        }
    }

    private fun drawLabel(canvas: Canvas, label: String) {
        val textColor = key.getTextColor()
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: if (label.length > 1) keyboardView.keyLongTextSize else keyboardView.keyTextSize)

        if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = FontManager.getTypeface("key_font")
                clearShadowLayer()
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            val fontMetrics = textPaint.fontMetrics
            val adjustmentY = -(fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(label, centerX + sp(key.keyTextOffsetX), centerY + adjustmentY + sp(key.keyTextOffsetY), textPaint)
        }
    }

    private fun drawHaoHaoModeLabel(canvas: Canvas, label: HaoHaoModeLabel) {
        val typeface = FontManager.getTypeface("key_font")
        textPaint.apply {
            color = key.getTextColor()
            textSize = sp(label.activeTextSizeSp)
            this.typeface = typeface
            clearShadowLayer()
        }
        symbolPaint.apply {
            color = key.getSymbolColor()
            textSize = sp(label.inactiveTextSizeSp)
            this.typeface = typeface
            clearShadowLayer()
        }

        val secondary = "/${label.inactive}"
        val activeWidth = textPaint.measureText(label.active)
        val secondaryWidth = symbolPaint.measureText(secondary)
        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(key.keyTextOffsetX)
        val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(key.keyTextOffsetY)
        val groupLeft = centerX - (activeWidth + secondaryWidth) / 2f
        val activeBaseline = centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
        val secondaryBaseline = centerY - (symbolPaint.fontMetrics.ascent + symbolPaint.fontMetrics.descent) / 2f

        canvas.drawText(label.active, groupLeft + activeWidth / 2f, activeBaseline, textPaint)
        canvas.drawText(secondary, groupLeft + activeWidth + secondaryWidth / 2f, secondaryBaseline, symbolPaint)
    }

    private fun drawIcon(
        canvas: Canvas,
        iconName: String,
        size: Int,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        isTop: Boolean? = null,
    ) {
        val halfSize = size / 2

        val cmdName = iconName.toIconName()
        val icon = if (cachedIconName == cmdName) {
            cachedIcon!!
        } else {
            IconicsDrawable(context, cmdName).apply {
                sizeDp = size
            }.also {
                cachedIcon = it
                cachedIconName = cmdName
            }
        }

        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)

        val centerY = when (isTop) {
            true -> paddingTop + halfSize + sp(offsetY)
            false -> height - paddingBottom - size + sp(offsetY)
            null -> (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(offsetY)
        }

        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt(),
        )
        icon.draw(canvas)
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        if (isTop && keyboardView.hideKeySymbol) return
        if (!isTop && keyboardView.hideKeyHint) return

        val textColor = key.getSymbolColor()
        val textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize)
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = FontManager.getTypeface("symbol_font")
            }

            val lines = text.split("\n")
            val fontMetrics = symbolPaint.fontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)
            val startY = if (isTop) {
                paddingTop - fontMetrics.top + sp(offsetY) - (totalHeight - lineHeight) / 2
            } else {
                height - paddingBottom - fontMetrics.bottom + sp(offsetY) - (totalHeight - lineHeight) / 2
            }

            for (i in lines.indices) {
                val lineY = startY + lineHeight * i
                canvas.drawText(lines[i], centerX, lineY, symbolPaint)
            }
        }
    }
}
