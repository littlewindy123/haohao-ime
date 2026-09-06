/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.haohao

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import splitties.dimensions.dp

/** Two shortcuts in the existing bottom spacer. Popovers never extend above the keyboard. */
class HaoHaoBottomActions(
    private val service: TrimeInputMethodService,
    private val context: Context,
    private val keyboard: () -> View,
) {
    private var popup: PopupWindow? = null
    private var menu: PopupMenu? = null
    private var recordsJob: Job? = null
    private val textColor get() = ColorManager.getColor("key_text_color")
    private val accent get() = ColorManager.getColor("hilited_candidate_text_color")
    private val surface get() = ColorManager.getColor("key_back_color")

    val view = LinearLayout(context).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        addView(icon(R.drawable.ic_baseline_keyboard_24, R.string.ime_switch_keyboard) { showInputMethods() })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(icon(R.drawable.ic_clipboard_24, R.string.ime_clips_phrases) { showClipboard() })
    }

    private fun icon(drawable: Int, label: Int, action: () -> Unit) = ImageButton(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT)
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(textColor)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        background = ripple()
        contentDescription = context.getString(label)
        setOnClickListener { action() }
    }

    private fun ripple() = RippleDrawable(
        ColorStateList.valueOf(accent and 0x00ffffff or 0x22000000),
        null,
        GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = context.dp(12).toFloat()
        },
    )

    private fun label(text: CharSequence, action: (() -> Unit)? = null) = TextView(context).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(this@HaoHaoBottomActions.textColor)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        if (action != null) {
            background = ripple()
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun open(content: View, alignEnd: Boolean = true, requestedHeight: Int = context.dp(320)) {
        val board = keyboard()
        if (!board.isAttachedToWindow || board.width == 0 || board.height == 0) return
        val margin = context.dp(8)
        val location = IntArray(2)
        val footerLocation = IntArray(2)
        // A popup is positioned relative to its parent IME window, not the physical screen.
        board.getLocationInWindow(location)
        view.getLocationInWindow(footerLocation)
        val size = bottomPopoverSize(board.width, footerLocation[1] - location[1] + view.height, view.height, margin, context.dp(336), requestedHeight)
        if (size.width <= 0 || size.height <= 0) return
        popup = PopupWindow(content, size.width, size.height, true).apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = context.dp(18).toFloat()
                },
            )
            elevation = context.dp(8).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener {
                recordsJob?.cancel()
                recordsJob = null
                menu?.dismiss()
                menu = null
                popup = null
            }
            showAtLocation(
                board,
                Gravity.TOP or Gravity.LEFT,
                location[0] + if (alignEnd) board.width - size.width - margin else margin,
                footerLocation[1] - size.height - margin,
            )
        }
    }

    fun dismiss() {
        menu?.dismiss()
        menu = null
        recordsJob?.cancel()
        recordsJob = null
        popup?.dismiss()
        popup = null
    }

    private fun showInputMethods() {
        dismiss()
        val manager = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val body = column()
        val entries = column()
        manager.enabledInputMethodList.forEach { info ->
            entries.addView(
                label(if (info.packageName == service.packageName) context.getString(R.string.app_name_release) else info.loadLabel(service.packageManager)) {
                    dismiss()
                    runCatching { service.switchInputMethod(info.id) }.onFailure { manager.showInputMethodPicker() }
                }.apply {
                    if (info.packageName == service.packageName) {
                        setTextColor(accent)
                        isSelected = true
                    }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        body.addView(ScrollView(context).apply { addView(entries) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(
            label(context.getString(R.string.ime_keyboard_settings)) {
                dismiss()
                AppUtils.launchMainToDest(service, NavigationRoute.InputPreferences)
            },
        )
        open(body, alignEnd = false, requestedHeight = context.dp((manager.enabledInputMethodList.size + 1) * 52))
    }

    private fun showClipboard() {
        dismiss()
        val editor = service.currentInputEditorInfo
        val body = column()
        val tabs = LinearLayout(context)
        val list = column()
        val footer = LinearLayout(context)
        val scroll = ScrollView(context).apply { addView(list) }
        var selectedTab = 0
        val tabLabels = listOf(R.string.clipboard, R.string.ime_common_phrases)
        fun showTab(tab: Int) {
            selectedTab = tab
            recordsJob?.cancel()
            list.removeAllViews()
            scroll.scrollTo(0, 0)
            for (i in 0 until tabs.childCount) {
                (tabs.getChildAt(i) as TextView).apply {
                    setTextColor(if (i == tab) accent else this@HaoHaoBottomActions.textColor)
                    setTypeface(typeface, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
                    isSelected = i == tab
                }
            }
            footer.removeAllViews()
            footer.addView(
                label(context.getString(if (tab == 0) R.string.ime_clip_settings else R.string.ime_edit_phrases)) {
                    dismiss()
                    AppUtils.launchMainToDest(service, if (tab == 0) NavigationRoute.Clipboard else NavigationRoute.CommonPhrases)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            if (tab == 0) footer.addView(label(context.getString(R.string.ime_clip_clear)) { confirmClear() })
            val available = if (tab == 0) ClipboardHelper.isAvailable else CollectionHelper.isAvailable
            if (!available) {
                list.addView(label(context.getString(R.string.ime_clip_unavailable)))
                return
            }
            recordsJob = service.lifecycleScope.launch {
                val flow = if (tab == 0) ClipboardHelper.observeBeans() else CollectionHelper.observeBeans()
                flow.catch {
                    list.removeAllViews()
                    list.addView(label(context.getString(R.string.ime_clip_unavailable)))
                }.collect { records ->
                    list.removeAllViews()
                    if (records.isEmpty()) {
                        val emptyLabel = when {
                            tab == 1 -> R.string.ime_phrase_empty
                            !AppPrefs.defaultInstance().clipboard.clipboardListening.getValue() -> R.string.ime_clip_paused
                            else -> R.string.ime_clip_empty
                        }
                        list.addView(label(context.getString(emptyLabel)))
                        if (tab == 1) {
                            list.addView(
                                label(context.getString(R.string.ime_add_phrase)) {
                                    dismiss()
                                    AppUtils.launchClipEdit(service, -1, ClipEditActivity.FROM_COLLECTION)
                                },
                            )
                        }
                    }
                    records.forEach { bean ->
                        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                        row.addView(
                            label(bean.text.orEmpty()) {
                                if (service.currentInputEditorInfo === editor && view.isAttachedToWindow) {
                                    service.commitText(bean.text.orEmpty())
                                }
                                dismiss()
                            }.apply {
                                maxLines = 3
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                        row.addView(
                            icon(R.drawable.ic_haohao_more_vert_24, R.string.ime_item_actions) {}.apply {
                                layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
                                setOnClickListener { showItemMenu(this, bean, selectedTab) }
                            },
                        )
                        list.addView(row)
                    }
                }
            }
        }
        tabLabels.forEachIndexed { index, res ->
            tabs.addView(label(context.getString(res)) { showTab(index) }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(tabs)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(footer)
        // Both tabs share stable geometry so their hit targets never move after loading.
        open(body, requestedHeight = context.dp(224))
        if (popup != null) showTab(0)
    }

    private fun showItemMenu(anchor: View, bean: DatabaseBean, tab: Int) {
        menu?.dismiss()
        menu = PopupMenu(android.view.ContextThemeWrapper(context, R.style.Theme_DialogTheme), anchor).apply {
            menu.add(R.string.edit).setOnMenuItemClickListener {
                dismiss()
                this@HaoHaoBottomActions.dismiss()
                AppUtils.launchClipEdit(service, bean.id, if (tab == 0) ClipEditActivity.FROM_CLIPBOARD else ClipEditActivity.FROM_COLLECTION)
                true
            }
            if (tab == 0) {
                menu.add(R.string.ime_save_phrase).setOnMenuItemClickListener {
                    if (CollectionHelper.isAvailable) CollectionHelper.addNewBean(bean.text.orEmpty())
                    true
                }
                menu.add(if (bean.pinned) R.string.simple_key_unpin else R.string.simple_key_pin).setOnMenuItemClickListener {
                    service.lifecycleScope.launch { if (bean.pinned) ClipboardHelper.unpin(bean.id) else ClipboardHelper.pin(bean.id) }
                    true
                }
            }
            menu.add(R.string.delete).setOnMenuItemClickListener {
                service.lifecycleScope.launch { if (tab == 0) ClipboardHelper.delete(bean.id) else CollectionHelper.delete(bean.id) }
                true
            }
            show()
        }
    }

    private fun confirmClear() {
        if (!ClipboardHelper.isAvailable) return
        // Keep destructive confirmation inside the same bounded popover.
        val confirmation = column().apply {
            addView(label(context.getString(R.string.ime_clip_clear_prompt)), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(label(context.getString(R.string.cancel)) { showClipboard() })
            addView(
                label(context.getString(R.string.ime_clip_clear)) {
                    service.lifecycleScope.launch {
                        ClipboardHelper.deleteAll(false)
                        showClipboard()
                    }
                },
            )
        }
        dismiss()
        open(confirmation, requestedHeight = context.dp(200))
    }
}

internal data class BottomPopoverSize(val width: Int, val height: Int)

internal fun bottomPopoverSize(boardWidth: Int, boardHeight: Int, footerHeight: Int, margin: Int, preferredWidth: Int, preferredHeight: Int) = BottomPopoverSize(minOf(preferredWidth, (boardWidth - 2 * margin).coerceAtLeast(0)), minOf(preferredHeight, (boardHeight - footerHeight - 2 * margin).coerceAtLeast(0)))
