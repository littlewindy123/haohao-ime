// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.footprints

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
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
    private val adapter = SentenceAdapter()
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
        }, LinearLayout.LayoutParams(-1, dp(56)))
        if (!InputFootprints.isAvailable) { root.addView(text(getString(R.string.words_unavailable))); return }
        val automatic = SwitchCompat(this).apply {
            isEnabled = false
            setText(R.string.sentences_auto); minHeight = dp(48); setPadding(dp(20), 0, dp(20), 0)
            setTextColor(color(R.color.haohao_cocoa))
        }
        root.addView(automatic)
        lifecycleScope.launch {
            automatic.isChecked = store.automatic()
            automatic.setOnClickListener {
                val enable = automatic.isChecked
                automatic.isChecked = !enable
                if (enable) AlertDialog.Builder(this@SentenceBookActivity)
                    .setTitle(R.string.sentences_auto).setMessage(R.string.sentences_consent)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> changeMode(automatic, true) }.show()
                else changeMode(automatic, false)
            }
            automatic.isEnabled = true
        }
        val tabs = LinearLayout(this)
        recent = button(R.string.sentences_recent) { favorites = false; render() }
        saved = button(R.string.sentences_favorites) { favorites = true; render() }
        tabs.addView(recent, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(saved, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)
        search = AppCompatEditText(this).apply {
            setHint(R.string.sentences_search); setSingleLine(); setText(query)
            setTextColor(color(R.color.haohao_cocoa)); setPadding(dp(20), dp(8), dp(20), dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            doAfterTextChanged { query = it.toString(); render() }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        empty = text("").apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }
        root.addView(empty)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); adapter = this@SentenceBookActivity.adapter; itemAnimator = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button(R.string.sentences_clear) {
            confirm { if (favorites) store.clearFavorites() else store.clear(false) }
        }, LinearLayout.LayoutParams(-1, -2))
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
        empty.visibility = if (adapter.rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(if (query.isNotBlank()) R.string.sentences_no_results else R.string.sentences_empty) + if (query.isBlank()) "\n" + getString(R.string.sentences_empty_hint) else ""
    }
    private fun showSentence(value: SavedSentenceEntity) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(text(value.english, 23f)); addView(text(value.chinese, 18f))
            addView(speech.controls { value.english })
        }
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(if (value.favorite) R.string.sentences_remove_favorite else R.string.sentences_save) { _, _ ->
                lifecycleScope.launch {
                    try { store.favorite(value, !value.favorite) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { toast(R.string.words_error) }
                }
            }.setNegativeButton(R.string.delete) { _, _ -> confirm { store.delete(value) } }
            .setNeutralButton(R.string.words_back, null).create()
        dialog.setOnDismissListener { speech.clearBindings() }
        dialog.show()
    }
    private fun confirm(action: suspend () -> Unit) {
        AlertDialog.Builder(this).setMessage(R.string.sentences_delete_prompt)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    try { action() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { toast(R.string.words_error) }
                }
            }.show()
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
    override fun onDestroy() { speech.close(); super.onDestroy() }
    private inner class SentenceAdapter : RecyclerView.Adapter<SentenceHolder>() {
        var rows = emptyList<SavedSentenceEntity>()
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): SentenceHolder = SentenceHolder(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)); minimumHeight = dp(64)
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            addView(text("", 20f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text("").apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
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
