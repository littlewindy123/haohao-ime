// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.footprints

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.osfans.trime.data.prefs.AppPrefs
import androidx.appcompat.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.footprints.*
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Only explicit local sentence records. No editor, clipboard, account or network access. */
class SentenceBookActivity : AppCompatActivity() {
    private val store get() = InputFootprints.store.sentences
    private var favorites = false
    private var query = ""
    private var values = emptyList<SavedSentenceEntity>()
    private val speech by lazy { WordSpeech(this) }
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private lateinit var search: AppCompatEditText
    private lateinit var recent: AppCompatButton
    private lateinit var saved: AppCompatButton
    private lateinit var countSummary: TextView
    private val adapter = SentenceAdapter()
    private var activeSheet: SentenceSheet? = null
    override fun onCreate(state: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
            AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        })
        super.onCreate(state)
        enableEdgeToEdge()
        favorites = state?.getBoolean("favorites") ?: false
        query = state?.getString("query").orEmpty()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.haohao_page_background)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        root.addView(Toolbar(this).apply {
            setTitle(R.string.sentences_title)
            setTitleTextColor(color(R.color.haohao_cocoa))
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationContentDescription(R.string.words_back)
            setNavigationOnClickListener { finish() }
            menu.add(R.string.journal_manage).apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    showSheet(SentenceSheet(this@SentenceBookActivity, getString(R.string.journal_manage)).apply {
                        action(getString(R.string.sentences_clear)) {
                            confirm { if (favorites) store.clearFavorites() else store.clear(false) }
                        }
                        action(getString(R.string.sentences_close), true)
                    })
                    true
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        if (!InputFootprints.isAvailable) { root.addView(text(getString(R.string.words_unavailable))); return }
        val automatic = SwitchCompat(this).apply {
            isEnabled = false
            setText(R.string.sentences_auto); minHeight = dp(48); setPadding(dp(20), 0, dp(20), 0)
            setTextColor(color(R.color.haohao_cocoa))
            thumbTintList = ContextCompat.getColorStateList(this@SentenceBookActivity, R.color.haohao_switch_thumb)
            trackTintList = ContextCompat.getColorStateList(this@SentenceBookActivity, R.color.haohao_switch_track)
        }
        countSummary = text("", 14f).apply { setTextColor(color(R.color.haohao_cocoa_secondary)) }
        root.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(8), dp(8))
            addView(countSummary, LinearLayout.LayoutParams(0, -2, 1f))
            automatic.textSize = 13f
            addView(automatic, LinearLayout.LayoutParams(-2, -2))
        })
        lifecycleScope.launch {
            automatic.isChecked = store.automatic()
            automatic.setOnClickListener {
                val enable = automatic.isChecked
                automatic.isChecked = !enable
                if (enable) showSheet(SentenceSheet(this@SentenceBookActivity, getString(R.string.sentences_auto_title)).apply {
                    description(getString(R.string.sentences_consent))
                    action(getString(R.string.sentences_enable), true) { changeMode(automatic, true) }
                    action(getString(R.string.sentences_not_now))
                })
                else changeMode(automatic, false)
            }
            automatic.isEnabled = true
        }
        val tabs = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        recent = button(R.string.sentences_recent) { favorites = false; render() }
        saved = button(R.string.sentences_favorites) { favorites = true; render() }
        tabs.addView(recent, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(saved, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        root.addView(tabs)
        search = AppCompatEditText(this).apply {
            setHint(R.string.sentences_search); setSingleLine(); setText(query)
            setTextColor(color(R.color.haohao_cocoa)); setPadding(dp(20), dp(8), dp(20), dp(8))
            textSize = 16f; minHeight = dp(52)
            backgroundTintList = null
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(color(R.color.haohao_segment_surface))
            }
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            doAfterTextChanged { query = it.toString(); render() }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })
        empty = text("").apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }
        root.addView(empty)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context); adapter = this@SentenceBookActivity.adapter; itemAnimator = null
            setPadding(dp(16), dp(8), dp(16), dp(16)); clipToPadding = false
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { store.rows.collect { values = it; render(); recent.isSelected = !favorites; saved.isSelected = favorites } }
        }
    }
    private fun changeMode(view: SwitchCompat, enabled: Boolean) {
        view.isEnabled = false
        lifecycleScope.launch {
            try { store.setAutomatic(enabled); view.isChecked = enabled }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toast(R.string.words_error) }
            finally { view.isEnabled = true }
        }
    }
    private fun render() {
        if (!::empty.isInitialized) return
        recent.isSelected = !favorites
        saved.isSelected = favorites
        adapter.rows = values.filter { it.favorite == favorites && (it.chinese.contains(query.trim(), true) || it.english.contains(query.trim(), true)) }
        adapter.notifyDataSetChanged()
        countSummary.text = getString(R.string.journal_sentence_count, adapter.rows.size)
        empty.visibility = if (adapter.rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(if (query.isNotBlank()) R.string.sentences_no_results else R.string.sentences_empty) + if (query.isBlank()) "\n" + getString(R.string.journal_sentence_hint) else ""
    }
    private fun showSentence(value: SavedSentenceEntity) {
        val detailBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(value.english, 23f)); addView(text(value.chinese, 18f))
            addView(speech.controls(compact = true) { value.english })
        }
        val dialog = SentenceSheet(this, getString(R.string.sentences_title)).apply {
            body.addView(detailBody)
            action(getString(if (value.favorite) R.string.sentences_remove_favorite else R.string.sentences_save), true) {
                lifecycleScope.launch {
                    try { store.favorite(value, !value.favorite) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { toast(R.string.words_error) }
                }
            }
            action(getString(R.string.delete)) { confirm { store.delete(value) } }
            action(getString(R.string.sentences_close))
        }
        showSheet(dialog)
    }
    private fun confirm(action: suspend () -> Unit) {
        showSheet(SentenceSheet(this, getString(R.string.sentences_delete_title)).apply {
            description(getString(R.string.sentences_delete_prompt))
            action(getString(android.R.string.cancel), true)
            action(getString(R.string.delete)) {
                lifecycleScope.launch {
                    try { action() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { toast(R.string.words_error) }
                }
            }
        })
    }
    private fun showSheet(sheet: SentenceSheet) {
        activeSheet?.dismiss()
        activeSheet = sheet
        sheet.setOnDismissListener { speech.clearBindings(); if (activeSheet === sheet) activeSheet = null }
        sheet.show()
    }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setTextColor(color(R.color.haohao_cocoa)); setPadding(0, dp(8), 0, dp(8)) }
    private fun button(label: Int, action: () -> Unit) = AppCompatButton(this).apply {
        setText(label); isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }
        setTextColor(color(R.color.haohao_cocoa)); setBackgroundResource(R.drawable.haohao_segment_background)
    }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("favorites", favorites); outState.putString("query", query); super.onSaveInstanceState(outState) }
    override fun onStop() { speech.stop(); super.onStop() }
    override fun onDestroy() { activeSheet?.dismiss(); speech.close(); super.onDestroy() }
    private inner class SentenceAdapter : RecyclerView.Adapter<SentenceHolder>() {
        var rows = emptyList<SavedSentenceEntity>()
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): SentenceHolder = SentenceHolder(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(16)); minimumHeight = dp(64)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat(); setColor(color(R.color.haohao_surface))
                setStroke(dp(1), color(R.color.haohao_divider))
            }
            addView(text("", 22f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text("", 16f).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(R.color.haohao_cocoa_secondary))
            })
        })
        override fun onBindViewHolder(holder: SentenceHolder, position: Int) {
            val value = rows[position]
            (holder.row.getChildAt(0) as TextView).text = value.english
            (holder.row.getChildAt(1) as TextView).text = value.chinese
            holder.row.setOnClickListener { showSentence(value) }
        }
    }
    private class SentenceHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row)
}
