/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ui.main.footprints

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.RecallRating
import com.osfans.trime.data.footprints.ReviewMode
import com.osfans.trime.data.footprints.SavedWordEntity
import com.osfans.trime.data.footprints.WordReviewSession
import com.osfans.trime.data.footprints.displaySavedEnglish
import com.osfans.trime.data.footprints.normalizeSavedEnglish
import com.osfans.trime.data.footprints.selectedMode
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Private, explicit destination; no editor text or input connection is passed to it. */
class WordLearningActivity : AppCompatActivity() {
    private val store get() = InputFootprints.store
    private var practiceReview = false
    private val learning get() = if (practiceReview) store.learning.practice else store.learning
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var busy = false
    private var showingReview = false
    private var pageEpoch = 0L
    private var learningGeneration = 0L
    private var learningScreen = "dashboard"
    private val weaknessPages = mutableMapOf<String, Int>()
    private var statisticsTab = "activity"
    private var statisticsDays = 7
    private var calendarMonth = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }
    private val speech by lazy { WordSpeech(this) }
    private var journalSheet: SentenceSheet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
                AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setBackgroundColor(color(R.color.haohao_page_background))
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) {
            if (InputFootprints.isAvailable && learningScreen in setOf("calendar", "stats", "settings", "profile", "review", "weakness")) action { renderPlan() } else finish()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            val editingInShortWindow = insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                resources.configuration.screenHeightDp < 480
            (root.getChildAt(0) as? Toolbar)?.visibility = if (editingInShortWindow) View.GONE else View.VISIBLE
            root.findViewWithTag<AppCompatEditText>("learning.spelling.input")?.setPadding(dp(14), dp(if (editingInShortWindow) 4 else 12), dp(14), dp(if (editingInShortWindow) 4 else 12))
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                view.post {
                    (currentFocus as? AppCompatEditText)?.let { input ->
                        input.requestRectangleOnScreen(android.graphics.Rect(0, 0, input.width, input.height), true)
                    }
                }
            }
            insets
        }
        if (!InputFootprints.isAvailable) {
            page(R.string.words_detail)
            label(getString(R.string.words_unavailable))
            return
        }
        learningGeneration = store.learning.generation
        learningScreen = savedInstanceState?.getString("learningScreen") ?: if (intent.getBooleanExtra("words.startSettings", false)) "settings" else "dashboard"
        com.osfans.trime.data.footprints.WeaknessKind.entries.forEach { weaknessPages[it.name] = savedInstanceState?.getInt("weakness.${it.name}") ?: 0 }
        practiceReview = savedInstanceState?.getBoolean("practiceReview") ?: false
        statisticsTab = savedInstanceState?.getString("statisticsTab") ?: "activity"
        statisticsDays = savedInstanceState?.getInt("statisticsDays", 7) ?: 7
        savedInstanceState?.getLong("calendarMonth")?.let { calendarMonth.timeInMillis = it }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var day = com.osfans.trime.data.footprints.learningDay(System.currentTimeMillis())
                while (true) {
                    delay(30_000)
                    val next = com.osfans.trime.data.footprints.learningDay(System.currentTimeMillis())
                    if (next != day && !busy) {
                        day = next
                        if (!showingReview && intent.getStringExtra(EXTRA_MODE) != null) {
                            action {
                                when (learningScreen) {
                                    "dashboard" -> renderPlan()
                                    "calendar" -> renderCalendar()
                                    "stats" -> renderStatistics()
                                    "profile" -> renderProfile()
                                }
                            }
                        }
                    }
                }
            }
        }
        action {
            if (savedInstanceState != null && learningScreen == "weakness") {
                renderWeaknesses()
                return@action
            }
            when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_REVIEW -> {
                    if (savedInstanceState != null && !savedInstanceState.getBoolean("showingReview") && learningScreen in setOf("dashboard", "calendar", "stats", "settings", "profile")) {
                        when (learningScreen) {
                            "calendar" -> renderCalendar()
                            "stats" -> renderStatistics()
                            "profile" -> renderProfile()
                            "settings" -> renderPlanSettings()
                            else -> renderPlan()
                        }
                        return@action
                    }
                    val previous = learning.session()
                    val restoreResult = savedInstanceState?.getBoolean("showingReview") == true ||
                        (learning.undoToken() != null && learning.summary().available == 0)
                    renderReview(if (restoreResult && previous != null) previous else learning.startSession(daily = false))
                }
                MODE_PLAN -> if (savedInstanceState?.getBoolean("showingReview") == true) {
                    renderReview(learning.session() ?: learning.startSession(daily = true))
                } else {
                    when (learningScreen) {
                        "calendar" -> renderCalendar()
                        "stats" -> renderStatistics()
                        "profile" -> renderProfile()
                        "settings" -> renderPlanSettings()
                        else -> renderPlan()
                    }
                }
                else -> renderMeaning()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("learningScreen", learningScreen)
        outState.putBoolean("practiceReview", practiceReview)
        weaknessPages.forEach { (kind, value) -> outState.putInt("weakness.$kind", value) }
        outState.putString("statisticsTab", statisticsTab)
        outState.putInt("statisticsDays", statisticsDays)
        outState.putLong("calendarMonth", calendarMonth.timeInMillis)
        outState.putBoolean("showingReview", showingReview)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (InputFootprints.isAvailable && learningGeneration != store.learning.generation) {
            journalSheet?.dismiss()
            learningGeneration = store.learning.generation
            action { renderPlan() }
            return
        }
        // Refresh after returning from the word/sentence book, without rebuilding a review card.
        if (pageEpoch > 0 && InputFootprints.isAvailable && !showingReview) {
            action {
                when (learningScreen) {
                    "dashboard" -> if (intent.getStringExtra(EXTRA_MODE) != null) renderPlan()
                    "calendar" -> renderCalendar()
                    "stats" -> renderStatistics()
                    "profile" -> renderProfile()
                    "weakness" -> renderWeaknesses()
                }
            }
        }
    }

    override fun onStop() {
        speech.stop()
        super.onStop()
    }

    override fun onDestroy() {
        journalSheet?.dismiss()
        speech.close()
        super.onDestroy()
    }

    private fun page(title: Int) {
        if (InputFootprints.isAvailable) learningGeneration = store.learning.generation
        pageEpoch++
        speech.clearBindings()
        root.removeAllViews()
        // Navigation/recreation restores the saved draft without letting editor focus
        // move the scroll position or reopen the IME underneath the next action.
        root.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        val toolbar = Toolbar(this).apply {
            setTitle(title)
            setTitleTextColor(color(R.color.haohao_cocoa))
            navigationIcon = DrawerArrowDrawable(this@WordLearningActivity).apply {
                progress = 1f
                color = color(R.color.haohao_cocoa)
            }
            navigationContentDescription = getString(R.string.words_back)
            setNavigationOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
        }
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
    }

    private fun label(value: CharSequence, size: Float = 15f, emphasis: Boolean = false, centered: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(if (emphasis) R.color.haohao_cocoa else R.color.haohao_cocoa_secondary))
        if (emphasis) setTypeface(typeface, Typeface.BOLD)
        if (centered) gravity = Gravity.CENTER
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    private fun button(title: String, primary: Boolean = false, clicked: () -> Unit): AppCompatButton = AppCompatButton(this).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48)
        minimumWidth = 0
        stateListAnimator = null
        elevation = 0f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = RippleDrawable(
            ColorStateList.valueOf(color(R.color.haohao_divider)),
            GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(color(if (primary) R.color.haohao_honey else R.color.haohao_segment_surface))
            },
            null,
        )
        ViewCompat.setBackgroundTintList(this, null)
        setTextColor(color(if (primary) R.color.haohao_on_honey else R.color.haohao_cocoa))
        setOnClickListener { if (!busy) clicked() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun mascot() {
        content.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.haohao_golden_foreground)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
                bottomMargin = dp(20)
            },
        )
    }

    /** A reading action, not another full-width card inside the definition. */
    private fun readingAction(title: String, clicked: () -> Unit): AppCompatButton = button(title, clicked = clicked).apply {
        background = null
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        setTextColor(color(R.color.learning_link_ink))
    }

    private fun pronunciation(phonetic: String?, english: String): TextView {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        val spelling = TextView(this).apply {
            text = phonetic.orEmpty()
            textSize = 16f
            setTextColor(color(R.color.haohao_cocoa_secondary))
        }
        row.addView(spelling, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(speech.controls(compact = true, flat = true) { english }, LinearLayout.LayoutParams(-2, -2))
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        return spelling
    }

    private fun sectionHeading(title: String) = label(title, 13f, true).apply {
        setTextColor(color(R.color.learning_word_ink))
        setPadding(0, dp(16), 0, 0)
    }

    private fun metrics(vararg values: Pair<Int, Int>) {
        val row = LinearLayout(this).apply { isBaselineAligned = false }
        values.forEachIndexed { index, (value, title) ->
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(12), dp(16))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(color(R.color.haohao_selection_surface))
                }
            }
            group.addView(
                TextView(this).apply {
                    text = value.toString()
                    textSize = 34f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.haohao_cocoa))
                },
            )
            group.addView(
                TextView(this).apply {
                    setText(title)
                    textSize = 13f
                    setTextColor(color(R.color.haohao_cocoa_secondary))
                },
            )
            row.addView(group, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(10) })
        }
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
    }

    private fun rewards(checkins: Int) {
        val earned = com.osfans.trime.data.footprints.earnedLearningMilestones(checkins)
        button(getString(R.string.journal_rewards) + "   ${earned.size} / 6") {
            val names = getString(R.string.journal_reward_names).split('|')
            showJournalSheet(
                SentenceSheet(this, getString(R.string.journal_rewards)).apply {
                    com.osfans.trime.data.footprints.learningMilestones.chunked(2).forEach { pair ->
                        val row = LinearLayout(this@WordLearningActivity).apply { isBaselineAligned = false }
                        pair.forEachIndexed { column, threshold ->
                            val unlocked = threshold in earned
                            val name = names[com.osfans.trime.data.footprints.learningMilestones.indexOf(threshold)]
                            row.addView(
                                TextView(this@WordLearningActivity).apply {
                                    text = "${if (unlocked) "✦" else "○"}\n$name\n$threshold 天"
                                    contentDescription = name + "，" + getString(if (unlocked) R.string.journal_reward_earned else R.string.journal_reward_locked, threshold)
                                    textSize = 17f
                                    gravity = Gravity.CENTER
                                    minHeight = dp(112)
                                    setPadding(dp(8), dp(12), dp(8), dp(12))
                                    setTextColor(color(if (unlocked) R.color.haohao_on_honey else R.color.haohao_cocoa_secondary))
                                    background = GradientDrawable().apply {
                                        cornerRadius = dp(24).toFloat()
                                        setColor(color(if (unlocked) R.color.haohao_honey else R.color.haohao_segment_surface))
                                    }
                                },
                                LinearLayout.LayoutParams(0, -2, 1f).apply { if (column > 0) marginStart = dp(10) },
                            )
                        }
                        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                    }
                    description(getString(R.string.journal_reward_rule))
                    action(getString(R.string.sentences_close), true)
                },
            )
        }
        val next = com.osfans.trime.data.footprints.nextLearningMilestone(checkins)
        label(if (next == null) getString(R.string.journal_reward_complete) else getString(R.string.journal_reward_progress, next - checkins), 13f)
    }

    private fun showJournalSheet(sheet: SentenceSheet) {
        journalSheet?.dismiss()
        journalSheet = sheet
        sheet.setOnDismissListener { if (journalSheet === sheet) journalSheet = null }
        sheet.show()
    }

    private suspend fun renderMeaning() {
        page(R.string.words_detail)
        val chinese = intent.getStringExtra(EXTRA_CHINESE).orEmpty().trim()
        val requestedEnglish = displaySavedEnglish(intent.getStringExtra(EXTRA_ENGLISH).orEmpty())
        val requestedSource = intent.getStringExtra(EXTRA_SOURCE).takeIf { it in setOf("cloud", "import") } ?: "offline"
        val saved = requestedEnglish?.let { learning.find(chinese, it) }
        val source = saved?.source ?: requestedSource
        val english = saved?.displayEnglish ?: requestedEnglish
        val phonetic = saved?.phonetic ?: intent.getStringExtra(EXTRA_PHONETIC)
        val legacyFavorite = store.isFavorite(chinese)
        label(english ?: chinese, 44f, emphasis = true).setTextColor(color(R.color.learning_word_ink))
        if (english == null) {
            label(getString(R.string.words_no_meaning), 20f, true)
            label(getString(R.string.words_no_meaning_hint))
            return
        }
        val phoneticLabel = pronunciation(phonetic, english)
        label(chinese, 24f, emphasis = true).setTextColor(color(R.color.learning_meaning_ink))
        label(
            getString(
                when (source) {
                    "cloud" -> R.string.words_source_cloud
                    "import" -> R.string.words_source_import
                    else -> R.string.words_source_offline
                },
            ),
            13f,
        )
        val references = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(references, LinearLayout.LayoutParams(-1, -2))
        val epoch = pageEpoch
        lifecycleScope.launch {
            val entry = com.osfans.trime.data.footprints.StudyLexicon.lookup(this@WordLearningActivity, english)
            if (epoch == pageEpoch && !isFinishing) {
                if (phonetic.isNullOrBlank()) phoneticLabel.text = entry?.phonetic.orEmpty()
                renderReferences(references, entry)
            }
        }
        if (saved != null) {
            button(getString(R.string.words_correct_case)) {
                val edit = AppCompatEditText(this).apply {
                    setText(english)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    setSelectAllOnFocus(true)
                }
                val dialog = AlertDialog.Builder(this).setTitle(R.string.words_correct_case)
                    .setMessage(R.string.words_correct_case_hint).setView(edit)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = edit.text.toString()
                        if (normalizeSavedEnglish(value) != saved.english) {
                            edit.error = getString(R.string.words_correct_case_hint)
                        } else {
                            action {
                                learning.correctCase(chinese, saved.english, value)
                                renderMeaning()
                            }
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
        }
        val favorite = saved?.favorite == true || (saved == null && legacyFavorite)
        button(getString(if (favorite) R.string.input_footprints_remove_favorite else R.string.input_footprints_add_favorite)) {
            val save = {
                action {
                    learning.saveMeaning(chinese, english, phonetic, source, favorite = !favorite)
                    if (favorite && legacyFavorite) store.setFavorite(chinese, false, System.currentTimeMillis())
                    toast(R.string.words_saved)
                    renderMeaning()
                }
            }
            if (favorite) save() else confirmMeaning(R.string.words_confirm_save, chinese, english, source, save)
        }
        val isLearning = saved?.learning == true
        button(getString(if (isLearning) R.string.words_remove_learning else R.string.words_add_learning), primary = !isLearning) {
            val save = {
                action {
                    learning.saveMeaning(chinese, english, phonetic, source, favorite = saved?.favorite ?: legacyFavorite, learning = !isLearning)
                    toast(R.string.words_saved)
                    renderMeaning()
                }
            }
            if (isLearning) {
                AlertDialog.Builder(this).setTitle(R.string.words_remove_learning).setMessage(R.string.words_pause_notice)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> save() }.show()
            } else {
                confirmMeaning(R.string.words_confirm_learning, chinese, english, source, save)
            }
        }
    }

    private fun confirmMeaning(title: Int, chinese: String, english: String, source: String, save: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(
                "$english\n$chinese\n\n${getString(
                    when (source) {
                        "cloud" -> R.string.words_source_cloud
                        "import" -> R.string.words_source_import
                        else -> R.string.words_source_offline
                    },
                )}\n\n${getString(R.string.words_save_notice)}",
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> save() }.show()
    }

    private fun modeTitle(mode: ReviewMode): String = getString(
        when (mode) {
            ReviewMode.ENGLISH -> R.string.study_mode_english
            ReviewMode.CHINESE -> R.string.study_mode_chinese
            ReviewMode.SPELLING -> R.string.study_mode_spelling
            ReviewMode.MIXED -> R.string.study_mode_mixed
        },
    )

    private fun modeHint(mode: ReviewMode): String = getString(
        when (mode) {
            ReviewMode.ENGLISH -> R.string.study_hint_english
            ReviewMode.CHINESE -> R.string.study_hint_chinese
            ReviewMode.SPELLING -> R.string.study_hint_spelling
            ReviewMode.MIXED -> R.string.study_hint_mixed
        },
    )

    private fun modePicker(selected: ReviewMode, choose: (ReviewMode) -> Unit) {
        val container = content
        val choices = mutableMapOf<ReviewMode, AppCompatButton>()
        lateinit var hint: TextView
        ReviewMode.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { isBaselineAligned = false }
            pair.forEachIndexed { index, mode ->
                val view = button(modeTitle(mode), mode == selected) {
                    choices.forEach { (choice, view) ->
                        view.isSelected = choice == mode
                        ViewCompat.setStateDescription(view, if (choice == mode) "已选择" else "未选择")
                        ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(color(if (choice == mode) R.color.haohao_honey else R.color.haohao_segment_surface)))
                        view.setTextColor(color(if (choice == mode) R.color.haohao_on_honey else R.color.haohao_cocoa))
                    }
                    hint.text = modeHint(mode)
                    choose(mode)
                }
                container.removeView(view)
                choices[mode] = view
                view.isSelected = mode == selected
                ViewCompat.setStateDescription(view, if (mode == selected) "已选择" else "未选择")
                row.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        hint = label(modeHint(selected), 14f)
    }

    private suspend fun renderProfile() {
        practiceReview = false
        learningScreen = "profile"
        showingReview = false
        page(R.string.study_profile)
        val data = learning.progress.dashboard(System.currentTimeMillis())
        val words = learning.savedWords()
        label(getString(R.string.study_profile_heading), 28f, true)
        label(getString(R.string.study_profile_note), 14f)
        metrics(data.streak to R.string.journal_streak, data.checkins to R.string.journal_checkins)
        metrics(data.studyDays to R.string.journal_study_days, data.ratings.values.sum() to R.string.study_total_feedback)
        sectionHeading(getString(R.string.study_words))
        label(getString(R.string.study_active_words) + "   ${words.count { it.learning }}", 19f, true)
        label(getString(R.string.study_saved_words) + "   ${words.count { it.favorite }}", 19f, true)
        label(getString(R.string.study_familiar_words) + "   ${words.count { it.learning && it.stage >= 4 }}", 19f, true)
        label(getString(R.string.study_familiar_note), 13f)
        rewards(data.checkins)
        button(getString(R.string.study_calendar)) { action { renderCalendar() } }
        button(getString(R.string.study_stats)) { action { renderStatistics() } }
        button(getString(R.string.words_plan_settings)) { action { renderPlanSettings() } }
        button(getString(R.string.learning_backup)) { startActivity(Intent(this, LearningBackupActivity::class.java)) }
    }

    private suspend fun renderWeaknesses() {
        practiceReview = false
        showingReview = false
        learningScreen = "weakness"
        page(R.string.learning_weak_words)
        label(getString(R.string.learning_practice_note), 16f)
        label(getString(R.string.learning_weak_note), 14f)
        label(getString(R.string.learning_weak_unknown), 13f)
        val active = store.learning.practice.session()
        if (active?.cards?.isNotEmpty() == true) {
            button(getString(R.string.learning_practice_resume), true) { action { renderReview(active) } }
        }
        com.osfans.trime.data.footprints.WeaknessKind.entries.forEach { kind ->
            sectionHeading(getString(if (kind == com.osfans.trime.data.footprints.WeaknessKind.FORGOTTEN) R.string.learning_often_forgotten else R.string.learning_often_misspelled))
            val words = store.learning.weaknesses(kind)
            label(getString(R.string.learning_weak_count, words.size), 14f)
            if (words.isEmpty()) {
                label(getString(R.string.learning_weak_empty), 17f)
            } else {
                button(getString(R.string.learning_practice_start), true) { action { renderReview(store.learning.practice.startPractice(kind)) } }
                val pageSize = 50
                val pageIndex = (weaknessPages[kind.name] ?: 0).coerceIn(0, (words.size - 1) / pageSize)
                weaknessPages[kind.name] = pageIndex
                val offset = pageIndex * pageSize
                if (words.size > pageSize) label(getString(R.string.learning_weak_page, offset + 1, minOf(offset + pageSize, words.size), words.size), 14f)
                words.drop(offset).take(pageSize).forEach { entry ->
                    label(entry.word.displayEnglish + " · " + entry.word.chinese, 19f, true)
                    label(getString(R.string.learning_weak_failures, entry.failures), 13f)
                }
                if (pageIndex > 0) {
                    button(getString(R.string.learning_weak_previous)) {
                        action {
                            weaknessPages[kind.name] = pageIndex - 1
                            renderWeaknesses()
                        }
                    }
                }
                if (offset + pageSize < words.size) {
                    button(getString(R.string.learning_weak_next)) {
                        action {
                            weaknessPages[kind.name] = pageIndex + 1
                            renderWeaknesses()
                        }
                    }
                }
            }
        }
    }

    private suspend fun renderPlan() {
        practiceReview = false
        showingReview = false
        learningScreen = "dashboard"
        page(R.string.study_home)
        val settings = learning.settings()
        val summary = learning.summary()
        val dashboard = learning.progress.dashboard(System.currentTimeMillis())
        label(getString(R.string.study_today), 28f, true)
        val savedCount = store.wordbooks.dao.counts(null, "", System.currentTimeMillis()).total
        val container = content
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color(R.color.haohao_selection_surface))
            }
        }
        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content = card
        val fresh = if (dashboard.task != null) dashboard.targets.count { !it.excluded && it.wasNew } else summary.plannedNew
        val review = if (dashboard.task != null) dashboard.targets.count { !it.excluded && !it.wasNew } else summary.plannedDue
        val numbers = LinearLayout(this).apply { isBaselineAligned = false }
        listOf(fresh to R.string.study_new_label, review to R.string.study_review_label).forEach { (count, title) ->
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            group.addView(
                TextView(this).apply {
                    text = count.toString()
                    textSize = 36f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.haohao_cocoa))
                },
            )
            group.addView(
                TextView(this).apply {
                    setText(title)
                    textSize = 14f
                    setTextColor(color(R.color.haohao_cocoa_secondary))
                },
            )
            numbers.addView(group, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(numbers, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        if (dashboard.task?.completed == true) {
            label(getString(R.string.study_checked), 18f, true)
        } else {
            label(getString(R.string.study_progress, dashboard.done, dashboard.total.takeIf { dashboard.task != null } ?: (fresh + review)), 14f)
        }
        card.addView(
            android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = dashboard.total.coerceAtLeast(1)
                progress = dashboard.done
                progressTintList = ColorStateList.valueOf(color(R.color.haohao_cocoa))
                progressBackgroundTintList = ColorStateList.valueOf(color(R.color.haohao_divider))
                contentDescription = getString(R.string.study_progress, dashboard.done, dashboard.total.takeIf { dashboard.task != null } ?: (fresh + review))
            },
            LinearLayout.LayoutParams(-1, dp(4)).apply { bottomMargin = dp(12) },
        )
        if (dashboard.targets.any { !it.excluded && it.pendingRepeat }) label(getString(R.string.study_pending), 13f)
        if (summary.active != null) {
            button(getString(R.string.words_resume, summary.active.cards.size), true) { action { renderReview(learning.startSession(daily = true)) } }.minHeight = dp(56)
        } else if (savedCount == 0) {
            label(getString(R.string.wordbooks_empty))
            button(getString(R.string.wordbooks_catalog), true) { startActivity(Intent(this, WordbookActivity::class.java).putExtra("catalog", true)) }
            button(getString(R.string.wordbooks_import)) { startActivity(Intent(this, WordbookActivity::class.java).putExtra("import", true)) }
        } else if (!settings.planEnabled) {
            label(getString(R.string.study_enable_hint))
            button(getString(R.string.wordbooks_plan), true) { action { renderPlanSettings() } }.minHeight = dp(56)
        } else if (summary.active != null || (dashboard.task == null && summary.plannedNew + summary.plannedDue > 0) || dashboard.total > dashboard.done) {
            button(if (summary.active != null) getString(R.string.words_resume, summary.active.cards.size) else getString(R.string.words_plan_start), true) {
                action { renderReview(learning.startSession(daily = true)) }
            }.minHeight = dp(56)
        } else {
            label(getString(R.string.study_rest), 20f, true)
        }
        content = container
        label(getString(R.string.wordbooks_mode, modeTitle(settings.selectedMode())), 17f)
        button(getString(R.string.wordbooks_change_mode)) { action { renderPlanSettings() } }
        if (summary.active != null) label(getString(R.string.study_mode_current, modeTitle(summary.active.mode ?: if (summary.active.reverse) ReviewMode.CHINESE else ReviewMode.ENGLISH)) + "\n" + getString(R.string.study_mode_next), 13f)
        val todayDone = dashboard.today.total
        if (todayDone > dashboard.targetAnswered) label(getString(R.string.study_extra, todayDone, (todayDone - dashboard.targetAnswered).coerceAtLeast(0)), 13f)
        if (savedCount > 0 && summary.learningCount == 0) {
            label(getString(R.string.wordbooks_join_hint))
        }
        val shortcuts = mutableListOf<AppCompatButton>()
        shortcuts += button(getString(R.string.wordbooks_title)) { startActivity(Intent(this, WordbookActivity::class.java)) }
        shortcuts += button(getString(R.string.learning_weak_words)) { action { renderWeaknesses() } }
        shortcuts += button(getString(R.string.study_stats)) { action { renderStatistics() } }
        shortcuts += button(getString(R.string.study_profile)) { action { renderProfile() } }
        shortcuts.forEach(container::removeView)
        shortcuts.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { isBaselineAligned = false }
            pair.forEachIndexed { index, view ->
                view.minHeight = dp(64)
                row.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 1) marginStart = dp(10) })
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        button(getString(R.string.study_sentences)) { startActivity(Intent(this, SentenceBookActivity::class.java)) }
        if (dashboard.task != null && dashboard.done == dashboard.total && summary.available > 0) {
            button(getString(R.string.words_continue, summary.available)) { action { renderReview(learning.startSession(true, extra = true)) } }
        }
    }

    private suspend fun renderCalendar() {
        showingReview = false
        learningScreen = "calendar"
        val data = learning.progress.dashboard(System.currentTimeMillis())
        page(R.string.study_calendar)
        // Seven 48dp touch columns fit on a 360dp phone without hiding Saturday.
        content.setPadding(dp(8), dp(16), dp(8), dp(28))
        metrics(data.checkins to R.string.journal_checkins, data.streak to R.string.journal_streak)
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val previous = button("‹") {
            calendarMonth.add(java.util.Calendar.MONTH, -1)
            action { renderCalendar() }
        }
        content.removeView(previous)
        previous.contentDescription = getString(R.string.study_previous_month)
        previous.textSize = 26f
        previous.setPadding(0, dp(8), 0, dp(8))
        navigation.addView(previous, LinearLayout.LayoutParams(dp(48), -2))
        navigation.addView(
            TextView(this).apply {
                text = getString(R.string.study_date, calendarMonth.get(java.util.Calendar.YEAR), calendarMonth.get(java.util.Calendar.MONTH) + 1)
                textSize = 21f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.haohao_cocoa))
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val current = java.util.Calendar.getInstance()
        val next = button("›") {
            calendarMonth.add(java.util.Calendar.MONTH, 1)
            action { renderCalendar() }
        }
        content.removeView(next)
        next.contentDescription = getString(R.string.study_next_month)
        next.textSize = 26f
        next.isEnabled = calendarMonth.get(java.util.Calendar.YEAR) * 12 + calendarMonth.get(java.util.Calendar.MONTH) < current.get(java.util.Calendar.YEAR) * 12 + current.get(java.util.Calendar.MONTH)
        next.alpha = if (next.isEnabled) 1f else .3f
        next.setPadding(0, dp(8), 0, dp(8))
        navigation.addView(next, LinearLayout.LayoutParams(dp(48), -2))
        content.addView(navigation, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        label(getString(R.string.study_calendar_legend), 13f)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(336)
        }
        content.addView(
            android.widget.HorizontalScrollView(this).apply {
                isFillViewport = true
                addView(grid, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val weekdays = java.text.DateFormatSymbols.getInstance().shortWeekdays
        val heading = LinearLayout(this)
        (1..7).forEach { day ->
            heading.addView(
                TextView(this).apply {
                    text = weekdays[day]
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.haohao_cocoa_secondary))
                },
                LinearLayout.LayoutParams(0, dp(48), 1f),
            )
        }
        grid.addView(heading)
        val first = calendarMonth.clone() as java.util.Calendar
        first.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val offset = first.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val count = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val stats = data.days.associateBy { it.day }
        for (week in 0 until (offset + count + 6) / 7) {
            // A completed day has two lines; baseline alignment otherwise pushes it
            // below the row bounds while one-line neighbours determine the baseline.
            val row = LinearLayout(this).apply {
                isBaselineAligned = false
                gravity = Gravity.CENTER_VERTICAL
            }
            grid.addView(row)
            for (column in 0..6) {
                val day = week * 7 + column - offset + 1
                if (day !in 1..count) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, dp(64), 1f))
                    continue
                }
                val date = (first.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, day) }
                val key = com.osfans.trime.data.footprints.learningDay(date.timeInMillis)
                val stat = stats[key]
                val status = getString(
                    if (stat?.completed == true) {
                        R.string.study_done_status
                    } else if ((stat?.total ?: 0) > 0) {
                        R.string.study_partial_status
                    } else {
                        R.string.study_none_status
                    },
                )
                row.addView(
                    AppCompatButton(this).apply {
                        text = "$day" + if (stat?.completed == true) {
                            "\n✓"
                        } else if ((stat?.total ?: 0) > 0) {
                            "\n·"
                        } else {
                            ""
                        }
                        contentDescription = "$key，$status"
                        textSize = 16f
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = dp(64)
                        stateListAnimator = null
                        elevation = 0f
                        setPadding(0, dp(4), 0, dp(4))
                        setTextColor(color(R.color.haohao_cocoa))
                        backgroundTintList = null
                        background = android.graphics.drawable.InsetDrawable(
                            GradientDrawable().apply {
                                cornerRadius = dp(18).toFloat()
                                setColor(if (stat?.completed == true) color(R.color.haohao_selection_surface) else android.graphics.Color.TRANSPARENT)
                                if (key == data.day) setStroke(dp(1), color(R.color.haohao_cocoa_secondary))
                            },
                            dp(2),
                        )
                        setOnClickListener {
                            showJournalSheet(
                                SentenceSheet(this@WordLearningActivity, key).apply {
                                    description(status + "\n" + getString(R.string.study_counts, stat?.fresh ?: 0, stat?.reviewed ?: 0))
                                    action(getString(android.R.string.ok), true)
                                },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
        }
        rewards(data.checkins)
        button(getString(R.string.journal_history_info)) {
            showJournalSheet(
                SentenceSheet(this, getString(R.string.journal_history_info)).apply {
                    description(getString(R.string.study_history_note))
                    action(getString(android.R.string.ok), true)
                },
            )
        }
    }

    private suspend fun renderStatistics() {
        showingReview = false
        learningScreen = "stats"
        val now = System.currentTimeMillis()
        val data = learning.progress.dashboard(now)
        val summary = learning.summary()
        page(R.string.study_stats)
        button(getString(R.string.study_calendar)) { action { renderCalendar() } }
        fun selector(options: List<Pair<String, String>>, selected: String, click: (String) -> Unit) {
            val row = LinearLayout(this).apply { isBaselineAligned = false }
            options.forEachIndexed { index, (key, title) ->
                val view = button(title, key == selected) { click(key) }
                content.removeView(view)
                view.isSelected = key == selected
                ViewCompat.setStateDescription(view, if (key == selected) "已选择" else "未选择")
                view.setPadding(dp(4), dp(10), dp(4), dp(10))
                row.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        }
        selector(listOf("activity" to getString(R.string.study_tab_activity), "retention" to getString(R.string.study_tab_retention), "schedule" to getString(R.string.study_tab_schedule)), statisticsTab) {
            statisticsTab = it
            action { renderStatistics() }
        }
        fun chart(labels: List<String>, values: List<Int?>, details: List<String>, percentages: Boolean = false) {
            content.addView(LearningChartView(this, labels, values, details.joinToString("；"), percentages), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            val rows = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            button(getString(R.string.study_chart_details)) { rows.visibility = if (rows.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            content.addView(rows, LinearLayout.LayoutParams(-1, -2))
            val parent = content
            content = rows
            details.forEach { label(it, 15f) }
            content = parent
        }
        val events = learning.progress.events()
        if (statisticsTab == "retention") {
            label(getString(R.string.study_retention_heading), 26f, true)
            label(getString(R.string.study_retention_note), 14f)
            val samples = com.osfans.trime.data.footprints.recallObservations(events)
            if (samples.all { it.attempts == 0 }) {
                label(getString(R.string.study_retention_empty), 18f)
            } else {
                val labels = listOf(R.string.study_interval_one, R.string.study_interval_three, R.string.study_interval_seven, R.string.study_interval_fourteen, R.string.study_interval_long).map { getString(it) }
                val details = samples.mapIndexed { i, sample -> getString(R.string.study_retention_sample, labels[i], sample.remembered, sample.attempts) }
                chart(labels, samples.map { if (it.attempts == 0) null else it.remembered * 100 / it.attempts }, details, true)
            }
            return
        }
        fun day(offset: Int): String = com.osfans.trime.data.footprints.learningDay(
            java.util.Calendar.getInstance().apply {
                timeInMillis = now
                add(java.util.Calendar.DAY_OF_MONTH, offset)
            }.timeInMillis,
        )
        if (statisticsTab == "schedule") {
            label(getString(R.string.study_schedule_heading), 26f, true)
            label(getString(R.string.study_current_due, summary.dueCount), 20f, true)
            label(getString(R.string.study_schedule_note), 14f)
            val words = learning.savedWords().filter { it.learning && it.reviewCount > 0 && (it.nextReviewAt ?: 0) > now }
            val days = (0..6).map(::day)
            val counts = days.map { date -> words.count { com.osfans.trime.data.footprints.learningDay(it.nextReviewAt!!) == date } }
            chart(days.map { it.substring(5) }, counts, days.mapIndexed { index, date -> date + "   " + getString(R.string.study_count_words, counts[index]) })
            return
        }
        selector(listOf("1" to getString(R.string.study_range_day), "7" to getString(R.string.study_range_week), "30" to getString(R.string.study_range_month)), statisticsDays.toString()) {
            statisticsDays = it.toInt()
            action { renderStatistics() }
        }
        val days = (1 - statisticsDays..0).map(::day)
        val selectedDays = data.days.filter { it.day in days }
        val ratings = events.filter { it.day in days }.groupingBy { it.rating }.eachCount()
        metrics(selectedDays.sumOf { it.fresh } to R.string.study_period_new, selectedDays.sumOf { it.reviewed } to R.string.study_period_review)
        label(getString(R.string.study_trend), 22f, true)
        label(getString(R.string.study_trend_note), 13f)
        val counts = days.map { date -> selectedDays.firstOrNull { it.day == date } }
        chart(days.map { it.substring(5) }, counts.map { it?.total ?: 0 }, days.mapIndexed { index, date -> date + "   " + getString(R.string.study_counts, counts[index]?.fresh ?: 0, counts[index]?.reviewed ?: 0) })
        if (selectedDays.sumOf { it.total } == 0) label(getString(R.string.study_no_stats))
        label(getString(R.string.study_ratings), 22f, true)
        label(getString(R.string.journal_ratings_note), 13f)
        listOf(RecallRating.FORGOTTEN to R.string.words_forgotten, RecallRating.UNCERTAIN to R.string.words_uncertain, RecallRating.REMEMBERED to R.string.words_remembered).forEach { (rating, title) ->
            val count = ratings[rating.name] ?: 0
            label(getString(R.string.study_rating_count, getString(title), count), 16f)
            content.addView(
                android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = ratings.values.sum().coerceAtLeast(1)
                    progress = count
                    progressTintList = ColorStateList.valueOf(color(if (rating == RecallRating.REMEMBERED) R.color.haohao_honey else R.color.haohao_cocoa_secondary))
                    progressBackgroundTintList = ColorStateList.valueOf(color(R.color.haohao_divider))
                    contentDescription = getString(R.string.study_rating_count, getString(title), count)
                },
                LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(20) },
            )
        }
    }

    private suspend fun renderPlanSettings() {
        learningScreen = "settings"
        showingReview = false
        page(R.string.words_plan_settings)
        val settings = learning.settings()
        label(getString(R.string.study_plan_heading), 26f, true)
        label(getString(R.string.words_plan_changes_next_round), 13f)
        val enabled = SwitchCompat(this).apply {
            text = getString(R.string.words_plan_enable)
            thumbTintList = ContextCompat.getColorStateList(this@WordLearningActivity, R.color.haohao_switch_thumb)
            trackTintList = ContextCompat.getColorStateList(this@WordLearningActivity, R.color.haohao_switch_track)
            isChecked = settings.planEnabled
            minHeight = dp(56)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setBackgroundResource(R.drawable.haohao_segment_background)
            setTextColor(color(R.color.haohao_cocoa))
        }
        content.addView(enabled, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        fun number(title: Int, value: Int): AppCompatEditText {
            val titleView = label(getString(title), emphasis = true)
            return AppCompatEditText(this).apply {
                id = View.generateViewId()
                titleView.labelFor = id
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setText(value.toString())
                textSize = 26f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundResource(R.drawable.haohao_segment_background)
                setSelectAllOnFocus(true)
                setTextColor(color(R.color.haohao_cocoa))
                minHeight = dp(56)
                content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            }
        }
        val newWords = number(R.string.words_plan_new, settings.newLimit)
        val dueWords = number(R.string.words_plan_due, settings.reviewLimit)
        var selectedMode = settings.selectedMode()
        label(getString(R.string.study_modes), 22f, true)
        modePicker(selectedMode) { selectedMode = it }
        button(getString(R.string.words_plan_save), true) {
            val fresh = newWords.text.toString().toIntOrNull()
            val due = dueWords.text.toString().toIntOrNull()
            if (fresh == null || fresh !in 1..50 || due == null || due !in 1..100) {
                toast(R.string.words_plan_limits)
            } else {
                action {
                    learning.saveSettings(enabled.isChecked, fresh, due, selectedMode == ReviewMode.CHINESE, selectedMode)
                    androidx.core.view.WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                    renderPlan()
                }
            }
        }
    }

    private suspend fun renderReview(initial: WordReviewSession) {
        practiceReview = initial.practiceKind != null
        learningScreen = "review"
        showingReview = true
        var session = initial
        var word: SavedWordEntity? = null
        while (session.cards.isNotEmpty()) {
            val card = session.cards.first()
            word = learning.find(card.chinese, card.english)?.takeIf { it.learning }
            if (word != null) break
            session = learning.skipRemoved(card.token) ?: session.copy(cards = emptyList())
        }
        page(
            if (practiceReview) {
                R.string.learning_practice
            } else if (session.daily) {
                R.string.words_daily_plan
            } else {
                R.string.words_review_title
            },
        )
        val undoToken = learning.undoToken()
        fun undoButton() {
            if (undoToken != null) {
                button(getString(R.string.words_undo)) {
                    action { learning.undoAnswer(undoToken)?.let { renderReview(it) } }
                }
            }
        }
        val card = session.cards.firstOrNull()
        if (card == null || word == null) {
            if (practiceReview) {
                label(getString(R.string.words_review_done), 26f, true)
                label(getString(R.string.learning_practice_note), 14f)
                label(getString(if (session.practiceMisses.isEmpty()) R.string.learning_practice_all_good else R.string.learning_practice_misses), 21f, true)
                session.practiceMisses.forEach { missed ->
                    learning.find(missed.chinese, missed.english)?.let { label(it.displayEnglish + " · " + it.chinese, 18f) }
                }
                undoButton()
                button(getString(R.string.learning_weak_words)) { action { renderWeaknesses() } }
                button(getString(R.string.study_home)) { action { renderPlan() } }
                return
            }
            mascot()
            label(getString(if (session.completed > 0) R.string.words_review_done else R.string.words_review_empty), 26f, true, true)
            label(if (session.completed > 0) getString(R.string.words_review_done_summary, session.completed) else getString(R.string.words_review_empty_hint), centered = true)
            val dashboard = learning.progress.dashboard(System.currentTimeMillis())
            if (dashboard.task?.completed == true) {
                label(getString(R.string.study_checked), 22f, true, true)
                rewards(dashboard.checkins)
            }
            button(getString(R.string.study_home)) { action { renderPlan() } }
            val summary = learning.summary()
            val remaining = summary.available
            if (remaining == 0) label(taskText(this, summary), centered = true)
            undoButton()
            if (remaining > 0) {
                button(getString(R.string.words_continue, remaining), true) {
                    action { renderReview(learning.startSession(daily = session.daily, extra = true)) }
                }
            }
            button(getString(R.string.words_back)) { finish() }
            return
        }
        val current = word
        label(getString(R.string.words_review_progress, session.completed, session.total), 14f)
        if (practiceReview) label(getString(R.string.learning_practice_note), 13f)
        val mode = session.currentMode
        val reverse = mode != ReviewMode.ENGLISH
        label(modeTitle(mode) + " · " + modeHint(mode), 14f)
        val pageContent = content
        val cardSurface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            tag = "learning.reading.surface"
        }
        pageContent.addView(cardSurface, LinearLayout.LayoutParams(-1, -2))
        content = cardSurface
        val question = if (reverse && !session.answerVisible) com.osfans.trime.data.footprints.recallMeaning(current.chinese, current.english) else current.chinese
        label(if (reverse) question else current.displayEnglish, if (reverse) 34f else 46f, true).apply {
            setTextColor(color(if (reverse) R.color.learning_meaning_ink else R.color.learning_word_ink))
        }
        var phoneticView: TextView? = null
        if (!reverse) {
            phoneticView = pronunciation(current.phonetic, current.displayEnglish)
        }
        var spellingInput: AppCompatEditText? = null
        if (mode == ReviewMode.SPELLING) {
            if (!session.answerVisible) {
                spellingInput = AppCompatEditText(this).apply {
                    tag = "learning.spelling.input"
                    hint = getString(R.string.study_spelling_input)
                    contentDescription = hint
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_FORCE_ASCII
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    filters = arrayOf(android.text.InputFilter.LengthFilter(128))
                    textSize = 24f
                    minHeight = dp(56)
                    setTextColor(color(R.color.haohao_cocoa))
                    setHintTextColor(color(R.color.haohao_cocoa_secondary))
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setBackgroundResource(R.drawable.haohao_segment_background)
                    setText(session.spellingDraft)
                    setSelection(text?.length ?: 0)
                    doAfterTextChanged { editable ->
                        val draft = editable.toString()
                        lifecycleScope.launch { learning.saveSpellingDraft(card.token, draft) }
                    }
                }
                cardSurface.addView(spellingInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            } else {
                label(
                    getString(
                        when {
                            session.spellingCorrect == true -> R.string.study_spelling_correct
                            session.spellingDraft.isBlank() -> R.string.study_spelling_shown
                            else -> R.string.study_spelling_wrong
                        },
                    ),
                    17f,
                    true,
                )
                if (session.spellingDraft.isNotBlank()) label(getString(R.string.study_spelling_attempt, session.spellingDraft), 17f)
                if (session.spellingCorrect != true) label(getString(R.string.study_spelling_rating_hint), 13f)
                if (session.spellingOutcome == com.osfans.trime.data.footprints.SpellingOutcome.WRONG) {
                    fun shown(value: String) = value.replace(" ", getString(R.string.learning_spelling_space))
                    val edits = com.osfans.trime.data.footprints.spellingEdits(session.spellingDraft, current.displayEnglish)
                    val feedback = edits.mapNotNull { edit ->
                        when (edit.kind) {
                            com.osfans.trime.data.footprints.SpellingEditKind.MISSING -> getString(R.string.learning_spelling_missing, shown(edit.expected))
                            com.osfans.trime.data.footprints.SpellingEditKind.EXTRA -> getString(R.string.learning_spelling_extra, shown(edit.actual))
                            com.osfans.trime.data.footprints.SpellingEditKind.REPLACE -> getString(R.string.learning_spelling_replace, shown(edit.actual), shown(edit.expected))
                            else -> null
                        }
                    }
                    label(feedback.joinToString("；"), 16f).setTextColor(color(R.color.learning_meaning_ink))
                }
            }
        }
        val answer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardSurface.addView(answer, LinearLayout.LayoutParams(-1, -2))
        content = answer
        answer.setPadding(0, dp(8), 0, 0)
        label(if (reverse) current.displayEnglish else current.chinese, 24f, true).apply {
            setTextColor(color(if (reverse) R.color.learning_word_ink else R.color.learning_meaning_ink))
        }
        if (reverse) {
            phoneticView = pronunciation(current.phonetic, current.displayEnglish)
        }
        val example = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        answer.addView(example, LinearLayout.LayoutParams(-1, -2))
        readingAction(getString(R.string.learning_other_meanings)) {
            openMeaning(this, current.chinese, current.displayEnglish, current.phonetic, current.source)
        }
        val epoch = pageEpoch
        lifecycleScope.launch {
            val entry = com.osfans.trime.data.footprints.StudyLexicon.lookup(this@WordLearningActivity, current.displayEnglish)
            if (epoch == pageEpoch && !isFinishing) {
                if (current.phonetic.isNullOrBlank()) phoneticView?.text = entry?.phonetic.orEmpty()
                val previous = content
                content = example
                entry?.examples?.firstOrNull()?.let { renderExample(it, com.osfans.trime.data.footprints.studyEnglishTerms(current.displayEnglish, entry), current.chinese) }
                content = previous
            }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        if (resources.configuration.screenHeightDp < 480 || resources.configuration.fontScale >= 1.8f || mode == ReviewMode.SPELLING) {
            footer.setPadding(0, dp(8), 0, dp(12))
            pageContent.addView(footer, LinearLayout.LayoutParams(-1, -2))
        } else {
            root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        content = footer
        val controls = android.widget.FrameLayout(this)
        footer.addView(controls, LinearLayout.LayoutParams(-1, -2))
        val ratings = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            minimumHeight = dp(56)
        }
        listOf(R.string.words_forgotten to RecallRating.FORGOTTEN, R.string.words_uncertain to RecallRating.UNCERTAIN, R.string.words_remembered to RecallRating.REMEMBERED).forEachIndexed { index, (title, rating) ->
            val view = button(getString(title), rating == RecallRating.REMEMBERED) {
                action { learning.answer(card.token, rating)?.let { renderReview(it) } }
            }
            footer.removeView(view)
            view.minHeight = dp(56)
            view.minimumHeight = dp(56)
            view.setPadding(dp(4), dp(8), dp(4), dp(8))
            val (fill, ink) = when (rating) {
                RecallRating.FORGOTTEN -> R.color.learning_recall_red to R.color.learning_on_recall_red
                RecallRating.UNCERTAIN -> R.color.learning_recall_amber to R.color.learning_on_recall_amber
                RecallRating.REMEMBERED -> R.color.learning_recall_green to R.color.learning_on_recall_green
            }
            view.background = RippleDrawable(
                ColorStateList.valueOf(color(R.color.haohao_divider)),
                GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(color(fill))
                },
                null,
            )
            view.setTextColor(color(ink))
            if (mode == ReviewMode.SPELLING && session.spellingCorrect != true && rating == RecallRating.REMEMBERED) {
                view.isEnabled = false
                view.alpha = .45f
            }
            ratings.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        lateinit var reveal: AppCompatButton
        fun updateAnswer(revealed: Boolean) {
            answer.visibility = if (revealed) View.VISIBLE else View.GONE
            ratings.visibility = if (revealed) View.VISIBLE else View.INVISIBLE
            reveal.visibility = if (revealed) View.INVISIBLE else View.VISIBLE
        }
        reveal = button(getString(if (mode == ReviewMode.SPELLING) R.string.study_spelling_check else R.string.words_reveal), true) {
            action {
                val updatedSession = if (mode == ReviewMode.SPELLING) learning.checkSpelling(card.token, spellingInput?.text.toString()) else learning.reveal(card.token)
                updatedSession?.let { updated ->
                    if (mode == ReviewMode.SPELLING) {
                        androidx.core.view.WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                        renderReview(updated)
                        return@let
                    }
                    session = updated
                    updateAnswer(updated.answerVisible)
                }
            }
        }
        footer.removeView(reveal)
        reveal.minHeight = dp(56)
        reveal.minimumHeight = dp(56)
        reveal.background = RippleDrawable(
            ColorStateList.valueOf(color(R.color.haohao_divider)),
            GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color(R.color.learning_recall_green))
            },
            null,
        )
        reveal.setTextColor(color(R.color.learning_on_recall_green))
        controls.addView(reveal, android.widget.FrameLayout.LayoutParams(-1, -2))
        controls.addView(ratings, android.widget.FrameLayout.LayoutParams(-1, -2))
        updateAnswer(session.answerVisible)
        spellingInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                reveal.performClick()
                true
            } else {
                false
            }
        }
        if (mode == ReviewMode.SPELLING && !session.answerVisible) {
            readingAction(getString(R.string.study_spelling_skip)) {
                action {
                    learning.reveal(card.token)?.let {
                        androidx.core.view.WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                        renderReview(it)
                    }
                }
            }
        }
        undoButton()
    }

    private fun renderExample(example: com.osfans.trime.data.footprints.StudyExample, terms: List<String> = emptyList(), meaning: String = "") {
        val previous = content
        val pair = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
            tag = "learning.reading.example"
        }
        previous.addView(pair, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content = pair
        fun highlighted(text: String, matches: List<IntRange>): CharSequence = android.text.SpannableString(text).apply {
            matches.forEach { range ->
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), range.first, range.last + 1, 0)
                setSpan(android.text.style.BackgroundColorSpan(color(R.color.haohao_honey)), range.first, range.last + 1, 0)
                setSpan(android.text.style.ForegroundColorSpan(color(R.color.haohao_on_honey)), range.first, range.last + 1, 0)
            }
        }
        val englishRanges = com.osfans.trime.data.footprints.studyHighlightRanges(example.english, terms)
        label(highlighted(example.english, englishRanges), 20f).apply {
            setTextColor(color(R.color.haohao_cocoa))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val chineseRanges = if (englishRanges.isNotEmpty()) com.osfans.trime.data.footprints.studyHighlightRanges(example.chinese, com.osfans.trime.data.footprints.studyChineseTerm(meaning), false) else emptyList()
        label(highlighted(example.chinese, chineseRanges), 17f).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        content = previous
        label("Tatoeba · ${example.englishAuthor} / ${example.chineseAuthor} · ${example.license}", 11f).apply {
            val links = android.text.SpannableString(text)
            val enStart = "Tatoeba · ".length
            val zhStart = enStart + example.englishAuthor.length + 3
            links.setSpan(android.text.style.URLSpan("https://tatoeba.org/en/sentences/show/${example.englishId}"), enStart, enStart + example.englishAuthor.length, 0)
            links.setSpan(android.text.style.URLSpan("https://tatoeba.org/en/sentences/show/${example.chineseId}"), zhStart, zhStart + example.chineseAuthor.length, 0)
            links.setSpan(android.text.style.URLSpan("https://creativecommons.org/licenses/by/2.0/fr/"), text.length - example.license.length, text.length, 0)
            text = links
            setLinkTextColor(color(R.color.haohao_cocoa_secondary))
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            minHeight = dp(48)
        }
    }

    private fun renderReferences(target: LinearLayout, entry: com.osfans.trime.data.footprints.StudyWord?) {
        val previous = content
        content = target
        sectionHeading(getString(R.string.learning_other_meanings))
        if (entry == null) {
            label(getString(R.string.learning_missing))
        } else {
            fun meanings(values: List<String>) {
                values.forEach { value ->
                    val text = android.text.SpannableString(value)
                    Regex("(?m)^(?:n|v|vt|vi|adj|adv|prep|pron|conj|interj|art)\\.").findAll(value).forEach { match ->
                        text.setSpan(android.text.style.ForegroundColorSpan(color(R.color.learning_word_ink)), match.range.first, match.range.last + 1, 0)
                        text.setSpan(android.text.style.StyleSpan(Typeface.BOLD), match.range.first, match.range.last + 1, 0)
                    }
                    label(text, 18f).apply {
                        setTextColor(color(R.color.haohao_cocoa))
                        setLineSpacing(dp(3).toFloat(), 1f)
                    }
                }
            }
            meanings(entry.meanings.take(3))
            if (entry.meanings.size > 3) {
                val extra = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }
                target.addView(extra)
                val toggle = button(getString(R.string.learning_expand)) { extra.visibility = View.VISIBLE }
                content = extra
                meanings(entry.meanings.drop(3))
                content = target
                toggle.setOnClickListener {
                    extra.visibility = View.VISIBLE
                    toggle.visibility = View.GONE
                }
            }
            if (entry.definition.isNotBlank()) {
                sectionHeading(getString(R.string.learning_english_definitions))
                label(entry.definition, 15f)
            }
            sectionHeading(getString(R.string.learning_examples))
            if (entry.examples.isEmpty()) {
                label(getString(R.string.learning_missing))
            } else {
                entry.examples.forEach { renderExample(it, com.osfans.trime.data.footprints.studyEnglishTerms(entry.word, entry), intent.getStringExtra(EXTRA_CHINESE).orEmpty()) }
            }
            sectionHeading(getString(R.string.learning_forms))
            val names = mapOf("p" to "过去式", "d" to "过去分词", "i" to "现在分词", "3" to "第三人称", "r" to "比较级", "t" to "最高级", "s" to "复数", "0" to "原形")
            val forms = entry.forms.mapNotNull { form ->
                val parts = form.split(':', limit = 2)
                names[parts[0]]?.let { "$it  ${parts[1]}" }
            }
            label(forms.joinToString("\n").ifBlank { getString(R.string.learning_missing) })
            readingAction("ECDICT · MIT · 资料来源") {
                val notice = assets.open("learning/NOTICE.txt").bufferedReader().use { it.readText() }
                val license = assets.open("learning/ECDICT-LICENSE.txt").bufferedReader().use { it.readText() }
                showJournalSheet(
                    SentenceSheet(this, "ECDICT · MIT").apply {
                        description("$notice\n\n$license")
                        action(getString(R.string.sentences_close), true)
                    },
                )
            }.apply {
                textSize = 12f
                setTextColor(color(R.color.haohao_cocoa_secondary))
            }
        }
        content = previous
    }

    private fun sessionDescription(session: WordReviewSession): String = getString(
        R.string.words_session_mode,
        getString(if (session.daily) R.string.words_daily_plan else R.string.words_review_title),
        getString(if (session.reverse) R.string.words_review_prompt_reverse else R.string.words_review_prompt),
    )

    private fun action(block: suspend () -> Unit) {
        if (busy) return
        if (learningGeneration != store.learning.generation) return
        busy = true
        lifecycleScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(R.string.words_error)
            } finally {
                busy = false
            }
        }
    }

    private fun speak(text: String) {
        speech.speak(text)
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal fun taskText(context: Context, summary: com.osfans.trime.data.footprints.WordTaskSummary): String = when {
            summary.active != null -> context.getString(R.string.words_resume, summary.active.cards.size)
            summary.quickCount > 0 -> context.getString(R.string.words_review_count, summary.quickCount)
            summary.learningCount == 0 -> context.getString(R.string.words_empty_learning_hint)
            summary.nextReviewAt != null -> context.getString(R.string.words_next_review, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(summary.nextReviewAt)))
            else -> context.getString(R.string.words_review_empty)
        }
        private const val EXTRA_MODE = "words.mode"
        private const val EXTRA_CHINESE = "words.chinese"
        private const val EXTRA_ENGLISH = "words.english"
        private const val EXTRA_PHONETIC = "words.phonetic"
        private const val EXTRA_SOURCE = "words.source"
        private const val MODE_REVIEW = "review"
        private const val MODE_PLAN = "plan"

        fun openMeaning(context: Context, chinese: String, english: String?, phonetic: String?, source: String) {
            context.startActivity(
                Intent(context, WordLearningActivity::class.java)
                    .putExtra(EXTRA_CHINESE, chinese).putExtra(EXTRA_ENGLISH, english)
                    .putExtra(EXTRA_PHONETIC, phonetic).putExtra(EXTRA_SOURCE, source)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun openReview(context: Context, daily: Boolean = false) {
            context.startActivity(Intent(context, WordLearningActivity::class.java).putExtra(EXTRA_MODE, if (daily) MODE_PLAN else MODE_REVIEW))
        }

        internal fun statusText(context: Context, word: SavedWordEntity, now: Long = System.currentTimeMillis()): String = when {
            word.reviewCount == 0 -> context.getString(R.string.words_new)
            (word.nextReviewAt ?: Long.MAX_VALUE) <= now -> context.getString(R.string.words_due)
            else -> context.getString(
                if (word.stage >= 4) R.string.words_familiar else R.string.words_next_review,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(word.nextReviewAt ?: now)),
            )
        }
    }
}
