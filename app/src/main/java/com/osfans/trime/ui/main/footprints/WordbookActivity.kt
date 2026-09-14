package com.osfans.trime.ui.main.footprints

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.osfans.trime.R
import com.osfans.trime.data.footprints.BuiltinWordbooks
import com.osfans.trime.data.footprints.ImportPreview
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.WordImport
import com.osfans.trime.data.footprints.WordbookEntity
import com.osfans.trime.data.footprints.normalizeSavedEnglish
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class WordbookActivity : AppCompatActivity() {
    internal class State : ViewModel() {
        var screen = "home"
        var bookId: String? = null
        var page = 0
        var search = ""
        var bookMode = WordbookViewMode.BROWSE
        var pendingDailyStart = false
        var busy = false
        var message = ""
        var generation = -1L
        var raw: String? = null
        var paste = ""
        var csv = true
        var swapped = false
        var preview: ImportPreview? = null
        var existing = emptySet<Pair<String, String>>()
        var catalog: BuiltinWordbooks? = null
        var draftId = UUID.randomUUID().toString()
        val selected = linkedSetOf<Pair<String, String>>()
        val changes = MutableStateFlow(0)
    }
    private val state by lazy { ViewModelProvider(this)[State::class.java] }
    private val store get() = InputFootprints.store.wordbooks
    private val draft get() = File(cacheDir, "word-import-${state.draftId}.csv")
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var renderJob: kotlinx.coroutines.Job? = null
    private var dailyLaunchInFlight = false
    private var selectionSheet: SentenceSheet? = null
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            task {
                state.preview = null
                state.raw = null
                draft.delete()
                val raw = requireNotNull(contentResolver.openInputStream(uri)).use(WordImport::read)
                state.csv = true
                state.raw = raw
                draft.writeText(raw, Charsets.UTF_8)
                preparePreview()
            }
        }
    }
    private val template = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            task {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write("english,chinese,phonetic\r\nhello,你好,\r\nworld,世界,\r\n") }
                state.message = getString(R.string.wordbooks_done)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
                AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
        super.onCreate(savedInstanceState)
        if (!InputFootprints.isAvailable) {
            finish()
            return
        }
        if (state.generation < 0) {
            state.generation = savedInstanceState?.getLong("generation", store.generation) ?: store.generation
            savedInstanceState?.let {
                state.screen = it.getString("screen", "home")
                state.bookId = it.getString("book")
                state.page = it.getInt("page")
                state.search = it.getString("search", "")
                state.bookMode = wordbookViewMode(it.getString("bookMode"))
                state.pendingDailyStart = it.getBoolean("pendingDailyStart")
                val chinese = it.getStringArrayList("selectedChinese").orEmpty()
                val english = it.getStringArrayList("selectedEnglish").orEmpty()
                if (state.bookMode != WordbookViewMode.BROWSE && chinese.size == english.size) {
                    state.selected.addAll(chinese.zip(english))
                }
                state.draftId = it.getString("draft", state.draftId)
                state.csv = it.getBoolean("csv", true)
                state.swapped = it.getBoolean("swapped")
            }
            if (savedInstanceState == null && intent.getBooleanExtra("import", false)) state.screen = "import"
            if (savedInstanceState == null && intent.getBooleanExtra("catalog", false)) state.screen = "catalog"
        }
        enableEdgeToEdge()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setBackgroundColor(color(R.color.haohao_page_background))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this) { back() }
        lifecycleScope.launch { state.changes.collect { render() } }
        if (state.preview == null && draft.isFile && state.screen in setOf("preview", "errors")) {
            task {
                state.raw = draft.inputStream().use(WordImport::read)
                preparePreview()
            }
        }
    }
    override fun onResume() {
        super.onResume()
        dailyLaunchInFlight = false
        if (InputFootprints.isAvailable && state.generation >= 0 && state.generation != store.generation && !state.busy) {
            state.generation = store.generation
            state.screen = "home"
            state.bookId = null
            state.selected.clear()
            state.bookMode = WordbookViewMode.BROWSE
            state.pendingDailyStart = false
            state.raw = null
            state.preview = null
            draft.delete()
            state.message = getString(R.string.wordbooks_stale)
            state.changes.value++
        } else if (::root.isInitialized) {
            // Details can change favourite/learning flags without a restore generation change.
            render()
        }
    }
    override fun onPostResume() {
        super.onPostResume()
        // Lifecycle's ON_RESUME may be dispatched after this callback on API 29+.
        // onPostResume itself guarantees the host resumed; do not check that state again.
        consumeDailyStart()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("screen", state.screen)
        out.putString("book", state.bookId)
        out.putInt("page", state.page)
        out.putString("search", state.search)
        out.putString("bookMode", state.bookMode.name)
        out.putBoolean("pendingDailyStart", state.pendingDailyStart)
        out.putStringArrayList("selectedChinese", ArrayList(state.selected.map { it.first }))
        out.putStringArrayList("selectedEnglish", ArrayList(state.selected.map { it.second }))
        out.putString("draft", state.draftId)
        out.putBoolean("csv", state.csv)
        out.putBoolean("swapped", state.swapped)
        out.putLong("generation", state.generation)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        selectionSheet?.dismiss()
        if (isFinishing && !state.busy) draft.delete()
        super.onDestroy()
    }
    private fun back() {
        if (state.busy) return
        if (state.screen == "book" && state.bookMode != WordbookViewMode.BROWSE) {
            changeBookMode(WordbookViewMode.BROWSE)
            return
        }
        if (state.screen == "home") finish() else show(if (state.screen == "errors") "preview" else "home")
    }
    private fun show(screen: String, book: String? = state.bookId) {
        state.screen = screen
        state.bookId = book
        state.page = 0
        state.search = ""
        state.selected.clear()
        state.bookMode = WordbookViewMode.BROWSE
        state.message = ""
        render()
    }
    private fun render() {
        if (!::root.isInitialized) return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) consumeDailyStart()
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            root.removeAllViews()
            root.requestFocus()
            androidx.core.view.WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
            val title = when (state.screen) {
                "catalog" -> R.string.wordbooks_catalog
                "import", "preview", "errors" -> R.string.wordbooks_import
                else -> R.string.wordbooks_title
            }
            root.addView(
                Toolbar(this@WordbookActivity).apply {
                    setTitle(title)
                    setTitleTextColor(color(R.color.haohao_cocoa))
                    navigationIcon = DrawerArrowDrawable(this@WordbookActivity).apply {
                        progress = 1f
                        color = color(R.color.haohao_cocoa)
                    }
                    navigationContentDescription = getString(R.string.words_back)
                    setNavigationOnClickListener { back() }
                },
                LinearLayout.LayoutParams(-1, dp(56)),
            )
            content = LinearLayout(this@WordbookActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(28))
            }
            root.addView(ScrollView(this@WordbookActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
            if (state.message.isNotBlank()) label(state.message, 16f).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (state.busy) {
                label(getString(R.string.wordbooks_busy))
                return@launch
            }
            try {
                when (state.screen) {
                    "catalog" -> catalog()
                    "book" -> book()
                    "import" -> importPage()
                    "preview", "errors" -> preview()
                    else -> home()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                label(getString(R.string.wordbooks_failed))
                button(R.string.wordbooks_home) { show("home") }
            }
        }
    }
    private suspend fun home() {
        label(getString(R.string.wordbooks_added), 24f)
        val books = store.dao.books()
        if (books.isEmpty()) label(getString(R.string.wordbooks_empty))
        books.forEach { b -> button(b.name + "\n" + counts(b.id)) { show("book", b.id) } }
        button(R.string.wordbooks_ungrouped) { show("book", "") }
        button(R.string.wordbooks_create) { nameDialog(null) }
        button(R.string.wordbooks_catalog, true) { show("catalog") }
        button(R.string.wordbooks_import) { show("import") }
        button(R.string.wordbooks_home) { finish() }
    }
    private suspend fun catalog() {
        label(getString(R.string.wordbooks_catalog_note))
        val catalog = state.catalog ?: withContext(Dispatchers.IO) { BuiltinWordbooks.load(applicationContext) }.also { state.catalog = it }
        catalog.books.forEach { b ->
            label(b.name, 24f)
            label(getString(R.string.wordbooks_catalog_info, b.words.size, catalog.sourceCommit.take(8)), 14f)
            val id = "builtin-${b.id}"
            if (store.dao.find(id) != null) {
                button(R.string.wordbooks_already_added) { show("book", id) }
            } else {
                button(R.string.wordbooks_add_catalog, true) {
                    task {
                        store.importWords(id, catalog.entries(b), WordbookEntity(id, b.name, System.currentTimeMillis(), b.id), state.generation)
                        state.screen = "book"
                        state.bookId = id
                        state.page = 0
                        state.bookMode = WordbookViewMode.BROWSE
                    }
                }
            }
        }
    }
    private suspend fun counts(id: String?): String {
        val c = store.dao.counts(id, "", System.currentTimeMillis())
        return getString(R.string.wordbooks_counts, c.total, c.learning, c.fresh, c.due)
    }
    private suspend fun book() {
        val id = state.bookId ?: return
        val b = if (id.isEmpty()) null else store.dao.find(id)
        if (id.isNotEmpty() && b == null) {
            state.screen = "home"
            home()
            return
        }
        label(b?.name ?: getString(R.string.wordbooks_ungrouped), 26f)
        label(counts(id), 15f)
        if (state.bookMode == WordbookViewMode.BROWSE) {
            val summary = InputFootprints.store.learning.summary()
            val start = wordbookStartAction(summary.active != null, summary.learningCount)
            button(
                when (start) {
                    WordbookStartAction.SELECT -> R.string.wordbooks_select_start
                    WordbookStartAction.START -> R.string.wordbooks_start_today
                    WordbookStartAction.RESUME -> R.string.wordbooks_continue_today
                },
                true,
            ) {
                if (start == WordbookStartAction.SELECT) changeBookMode(WordbookViewMode.SELECT) else openDailyLearning()
            }.tag = "wordbooks.primary"
            val shortcuts = LinearLayout(this).apply { isBaselineAligned = false }
            val choices = buildList {
                if (start != WordbookStartAction.SELECT) add(R.string.wordbooks_select_learning to WordbookViewMode.SELECT)
                add(R.string.wordbooks_manage to WordbookViewMode.MANAGE)
            }
            choices.forEachIndexed { index, (title, mode) ->
                val view = button(title) { changeBookMode(mode) }
                content.removeView(view)
                shortcuts.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            content.addView(shortcuts, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        } else {
            button(R.string.wordbooks_finish_selection) { changeBookMode(WordbookViewMode.BROWSE) }
        }
        val search = input(getString(R.string.wordbooks_search), state.search, false)
        button(R.string.wordbooks_search_action) {
            state.search = search.text.toString().trim()
            state.page = 0
            state.selected.clear()
            render()
        }
        val total = store.dao.counts(id, state.search, System.currentTimeMillis()).total
        state.page = state.page.coerceAtMost(((total - 1).coerceAtLeast(0)) / 50)
        val rows = store.dao.page(id, state.search, 50, state.page * 50)
        if (rows.isEmpty()) label(getString(R.string.wordbooks_no_words))
        val selecting = state.bookMode != WordbookViewMode.BROWSE
        val selectedCount = if (selecting) label(getString(R.string.wordbooks_selected, state.selected.size), 14f) else null
        val selectedStart = if (state.bookMode == WordbookViewMode.SELECT) {
            button(getString(R.string.wordbooks_selected_start, state.selected.size), true) { confirmSelectedStart() }.apply {
                tag = "wordbooks.selected.start"
                isEnabled = state.selected.isNotEmpty()
            }
        } else null
        rows.forEach { w ->
            val key = w.chinese to w.english
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            if (selecting) row.addView(
                CheckBox(this).apply {
                    contentDescription = getString(R.string.wordbooks_select_word, w.displayEnglish)
                    minHeight = dp(48)
                    isChecked = key in state.selected
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) state.selected.add(key) else state.selected.remove(key)
                        selectedCount?.text = getString(R.string.wordbooks_selected, state.selected.size)
                        selectedStart?.apply {
                            text = getString(R.string.wordbooks_selected_start, state.selected.size)
                            isEnabled = state.selected.isNotEmpty()
                        }
                    }
                },
                LinearLayout.LayoutParams(dp(48), -2),
            )
            row.addView(
                TextView(this).apply {
                    text = "${w.displayEnglish.ifBlank { w.english }} · ${w.chinese}"
                    textSize = 17f
                    minHeight = dp(48)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setTextColor(color(R.color.haohao_cocoa))
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    contentDescription = getString(R.string.wordbooks_word_detail, w.displayEnglish, w.chinese)
                    setOnClickListener { WordLearningActivity.openMeaning(this@WordbookActivity, w.chinese, w.displayEnglish, w.phonetic, w.source) }
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        pager(total)
        if (!selecting) return
        button(R.string.wordbooks_select_page) {
            state.selected.addAll(rows.map { it.chinese to it.english })
            render()
        }
        button(R.string.wordbooks_clear_selection) {
            state.selected.clear()
            render()
        }
        if (state.bookMode == WordbookViewMode.SELECT) return
        button(R.string.wordbooks_learn, true) { batchLearning(true, false) }
        button(R.string.wordbooks_pause) { batchLearning(false, false) }
        button(R.string.wordbooks_copy) { destination(false) }
        button(R.string.wordbooks_move) { destination(true) }
        button(R.string.wordbooks_learn_all) { batchLearning(true, true) }
        button(R.string.wordbooks_plan) { startActivity(Intent(this, WordLearningActivity::class.java).putExtra("words.mode", "plan").putExtra("words.startSettings", true)) }
        if (id.isNotEmpty()) {
            button(R.string.wordbooks_remove) {
                task {
                    store.transfer(state.selected.toList(), id, null, true, state.generation)
                    state.selected.clear()
                }
            }
            button(R.string.wordbooks_import) { show("import", id) }
            button(R.string.wordbooks_rename) { nameDialog(b) }
            button(R.string.wordbooks_delete) {
                confirm(R.string.wordbooks_delete, R.string.wordbooks_delete_note) {
                    task {
                        store.delete(id, state.generation)
                        state.screen = "home"
                        state.bookId = null
                    }
                }
            }
        }
    }

    private fun changeBookMode(mode: WordbookViewMode) {
        state.bookMode = mode
        state.selected.clear()
        render()
    }

    private fun openDailyLearning() {
        if (dailyLaunchInFlight || state.generation != store.generation) return
        dailyLaunchInFlight = true
        WordLearningActivity.startDaily(this)
    }

    private fun consumeDailyStart() {
        if (!state.pendingDailyStart || state.busy) return
        state.pendingDailyStart = false
        openDailyLearning()
    }

    private fun confirmSelectedStart() {
        if (state.selected.isEmpty() || selectionSheet?.isShowing == true) return
        // Snapshot the explicit choice; late UI changes must not enroll a different set.
        val keys = state.selected.toList()
        val generation = state.generation
        selectionSheet = SentenceSheet(this, getString(R.string.wordbooks_selected_start, keys.size)).apply {
            description(getString(R.string.wordbooks_selected_start_note, keys.size))
            action(getString(R.string.learning_backup_confirm), true) {
                task {
                    store.setLearning(keys, true, generation)
                    state.selected.clear()
                    state.bookMode = WordbookViewMode.BROWSE
                    state.pendingDailyStart = true
                }
            }
            action(getString(R.string.learning_backup_cancel))
            setOnDismissListener { selectionSheet = null }
            show()
        }
    }
    private fun batchLearning(active: Boolean, all: Boolean) {
        confirm(
            if (active) R.string.wordbooks_learn else R.string.wordbooks_pause,
            if (all) {
                R.string.wordbooks_learn_all_note
            } else if (active) {
                R.string.wordbooks_learn_note
            } else {
                R.string.wordbooks_pause_note
            },
        ) {
            task {
                val keys = if (all) store.dao.page(state.bookId, "", 100001, 0).map { it.chinese to it.english } else state.selected.toList()
                store.setLearning(keys, active, state.generation)
                state.selected.clear()
                state.message = getString(R.string.wordbooks_done)
            }
        }
    }
    private fun destination(move: Boolean) {
        lifecycleScope.launch {
            val books = store.dao.books().filter { it.id != state.bookId }
            if (books.isEmpty()) {
                nameDialog(null)
                return@launch
            }
            AlertDialog.Builder(this@WordbookActivity).setTitle(R.string.wordbooks_choose_target).setItems(books.map { it.name }.toTypedArray()) { _, index ->
                task {
                    store.transfer(state.selected.toList(), state.bookId, books[index].id, move, state.generation)
                    state.selected.clear()
                }
            }.setNegativeButton(R.string.learning_backup_cancel, null).show()
        }
    }
    private suspend fun importPage() {
        label(getString(R.string.wordbooks_import_note), 15f)
        val target = state.bookId?.let { store.dao.find(it) }
        label(if (target == null) getString(R.string.wordbooks_need_target) else getString(R.string.wordbooks_import_target, target.name), 20f)
        button(R.string.wordbooks_choose_target) { chooseTarget() }
        button(R.string.wordbooks_create) { nameDialog(null) }
        if (target == null) return
        button(R.string.wordbooks_pick_csv, true) { pick.launch(arrayOf("text/csv", "text/plain", "application/octet-stream", "application/vnd.ms-excel")) }
        button(R.string.wordbooks_template) { template.launch("haohao-words-template.csv") }
        button(if (state.swapped) R.string.wordbooks_order_zh else R.string.wordbooks_order_en) {
            state.swapped = !state.swapped
            render()
        }
        val paste = input(getString(R.string.wordbooks_paste_hint), state.paste, true)
        paste.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                state.paste = s.toString()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        button(R.string.wordbooks_paste_preview) {
            task {
                state.csv = false
                state.raw = state.paste
                draft.writeText(state.paste, Charsets.UTF_8)
                preparePreview()
            }
        }
    }
    private fun chooseTarget() {
        lifecycleScope.launch {
            val books = store.dao.books()
            if (books.isEmpty()) {
                nameDialog(null)
                return@launch
            }
            AlertDialog.Builder(this@WordbookActivity).setTitle(R.string.wordbooks_choose_target).setItems(books.map { it.name }.toTypedArray()) { _, index ->
                state.bookId = books[index].id
                render()
            }.setNegativeButton(R.string.learning_backup_cancel, null).show()
        }
    }
    private suspend fun preparePreview() {
        state.preview = null
        state.preview = WordImport.preview(requireNotNull(state.raw), if (state.csv) ',' else '\t', state.swapped)
        state.existing = InputFootprints.store.learning.savedWords().map { it.chinese to it.english }.toSet()
        state.screen = "preview"
        state.page = 0
    }
    private suspend fun preview() {
        val p = state.preview ?: run {
            state.screen = "import"
            importPage()
            return
        }
        val target = state.bookId?.let { store.dao.find(it) } ?: run {
            state.screen = "import"
            importPage()
            return
        }
        label(getString(R.string.wordbooks_import_target, target.name), 20f)
        if (state.screen == "errors") {
            label(getString(R.string.wordbooks_invalid), 24f)
            p.issues.drop(state.page * 50).take(50).forEach { issue ->
                val id = when (issue.reason) {
                    "english" -> R.string.wordbooks_error_english
                    "chinese" -> R.string.wordbooks_error_chinese
                    "phonetic" -> R.string.wordbooks_error_phonetic
                    else -> R.string.wordbooks_error_columns
                }
                label(getString(R.string.wordbooks_invalid_line, issue.line, getString(id)), 15f)
            }
            pager(p.issues.size)
            button(R.string.wordbooks_preview) { show("preview") }
            return
        }
        val existing = p.words.count { (it.chinese to normalizeSavedEnglish(it.english)) in state.existing }
        label(getString(R.string.wordbooks_preview), 24f)
        label(getString(R.string.wordbooks_preview_counts, p.words.size - existing, existing, p.duplicates, p.issues.size))
        button(if (state.swapped) R.string.wordbooks_order_zh else R.string.wordbooks_order_en) {
            task {
                state.swapped = !state.swapped
                preparePreview()
            }
        }
        p.words.drop(state.page * 50).take(50).forEach { row ->
            label("${row.english} · ${row.chinese}", 17f)
            if ((row.chinese to normalizeSavedEnglish(row.english)) in state.existing) label(getString(R.string.wordbooks_existing), 13f)
        }
        pager(p.words.size)
        if (p.issues.isNotEmpty()) button(R.string.wordbooks_invalid) { show("errors") }
        if (p.words.isNotEmpty()) {
            button(R.string.wordbooks_confirm_import, true) {
                confirm(R.string.wordbooks_confirm_import, R.string.wordbooks_import_confirm_note) {
                    task {
                        val added = store.importWords(target.id, p.words, generation = state.generation)
                        state.raw = null
                        state.preview = null
                        state.paste = ""
                        draft.delete()
                        state.screen = "book"
                        state.page = 0
                        state.bookMode = WordbookViewMode.BROWSE
                        state.message = getString(R.string.wordbooks_import_done, added)
                    }
                }
            }
        }
        button(R.string.learning_backup_cancel) {
            state.preview = null
            state.raw = null
            draft.delete()
            show("import")
        }
    }
    private fun pager(total: Int) {
        label(getString(R.string.wordbooks_page, state.page + 1, total), 14f)
        if (state.page > 0) {
            button(R.string.learning_weak_previous) {
                state.page--
                state.selected.clear()
                render()
            }
        }
        if ((state.page + 1) * 50 < total) {
            button(R.string.learning_weak_next) {
                state.page++
                state.selected.clear()
                render()
            }
        }
    }
    private fun nameDialog(book: WordbookEntity?) {
        val input = EditText(this).apply {
            hint = getString(R.string.wordbooks_name)
            setText(book?.name.orEmpty())
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (book == null) R.string.wordbooks_create else R.string.wordbooks_rename).setView(input).setNegativeButton(R.string.learning_backup_cancel, null).setPositiveButton(R.string.learning_backup_confirm, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.length > 40 || name.any(Char::isISOControl)) {
                    input.error = getString(R.string.wordbooks_name_error)
                    return@setOnClickListener
                }
                dialog.dismiss()
                task {
                    if (book == null) {
                        state.bookId = store.create(name, state.generation).id
                        state.bookMode = WordbookViewMode.BROWSE
                        state.selected.clear()
                    } else {
                        store.rename(book.id, name, state.generation)
                    }
                    if (state.screen != "import") state.screen = "book"
                    state.page = 0
                }
            }
        }
        dialog.show()
    }
    private fun confirm(title: Int, note: Int, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(note).setNegativeButton(R.string.learning_backup_cancel, null).setPositiveButton(R.string.learning_backup_confirm) { _, _ -> action() }.show()
    }
    private fun task(block: suspend () -> Unit) {
        if (state.busy) return
        state.busy = true
        render()
        state.viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.message = getString(
                    when {
                        e.message == "import_limit" -> R.string.wordbooks_limit
                        e.message == "stale_learning_write" -> R.string.wordbooks_stale
                        e.message?.startsWith("import_syntax") == true || e is java.nio.charset.CharacterCodingException -> R.string.wordbooks_syntax
                        else -> R.string.wordbooks_failed
                    },
                )
                if (state.preview == null && state.screen in setOf("preview", "errors")) state.screen = "import"
            } finally {
                state.busy = false
                state.changes.value++
            }
        }
    }
    private fun input(hint: String, text: String, multiline: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(text)
        textSize = 16f
        setTextColor(color(R.color.haohao_cocoa))
        setHintTextColor(color(R.color.haohao_cocoa_secondary))
        isSingleLine = !multiline
        minHeight = dp(56)
        if (multiline) minLines = 3
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun label(text: String, size: Float = 17f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color(R.color.haohao_cocoa))
        setLineSpacing(dp(3).toFloat(), 1f)
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun button(id: Int, primary: Boolean = false, action: () -> Unit) = button(getString(id), primary, action)
    private fun button(text: String, primary: Boolean = false, action: () -> Unit): AppCompatButton {
        val view = AppCompatButton(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            minHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(color(if (primary) R.color.haohao_on_honey else R.color.haohao_cocoa))
            setBackgroundResource(R.drawable.haohao_segment_background)
            if (primary) backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.haohao_honey))
            stateListAnimator = null
            elevation = 0f
            setOnClickListener { if (!state.busy) action() }
        }
        content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return view
    }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
