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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.RecallRating
import com.osfans.trime.data.footprints.SavedWordEntity
import com.osfans.trime.data.footprints.WordReviewSession
import com.osfans.trime.data.footprints.displaySavedEnglish
import com.osfans.trime.data.footprints.normalizeSavedEnglish
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Private, explicit destination; no editor text or input connection is passed to it. */
class WordLearningActivity : AppCompatActivity() {
    private val store get() = InputFootprints.store
    private val learning get() = store.learning
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var busy = false
    private var showingReview = false
    private var pageEpoch = 0L
    private var learningScreen = "dashboard"
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
            setBackgroundColor(color(R.color.haohao_page_background))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (!InputFootprints.isAvailable) {
            page(R.string.words_detail)
            label(getString(R.string.words_unavailable))
            return
        }
        learningScreen = savedInstanceState?.getString("learningScreen") ?: "dashboard"
        savedInstanceState?.getLong("calendarMonth")?.let { calendarMonth.timeInMillis = it }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var day = com.osfans.trime.data.footprints.learningDay(System.currentTimeMillis())
                while (true) {
                    delay(30_000)
                    val next = com.osfans.trime.data.footprints.learningDay(System.currentTimeMillis())
                    if (next != day && !busy) {
                        day = next
                        if (!showingReview && intent.getStringExtra(EXTRA_MODE) != null) action {
                            when (learningScreen) {
                                "dashboard" -> renderPlan()
                                "calendar" -> renderCalendar()
                                "stats" -> renderStatistics()
                            }
                        }
                    }
                }
            }
        }
        action {
            when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_REVIEW -> {
                    if (savedInstanceState != null && !savedInstanceState.getBoolean("showingReview") && learningScreen in setOf("dashboard", "calendar", "stats", "settings")) {
                        when (learningScreen) {
                            "calendar" -> renderCalendar()
                            "stats" -> renderStatistics()
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
        outState.putLong("calendarMonth", calendarMonth.timeInMillis)
        outState.putBoolean("showingReview", showingReview)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from the word/sentence book, without rebuilding a review card.
        if (pageEpoch > 0 && InputFootprints.isAvailable && !showingReview) {
            action {
                when (learningScreen) {
                    "dashboard" -> if (intent.getStringExtra(EXTRA_MODE) != null) renderPlan()
                    "calendar" -> renderCalendar()
                    "stats" -> renderStatistics()
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
        pageEpoch++
        speech.clearBindings()
        root.removeAllViews()
        val toolbar = Toolbar(this).apply {
            setTitle(title)
            setTitleTextColor(color(R.color.haohao_cocoa))
            navigationIcon = DrawerArrowDrawable(this@WordLearningActivity).apply {
                progress = 1f
                color = color(R.color.haohao_cocoa)
            }
            navigationContentDescription = getString(R.string.words_back)
            setNavigationOnClickListener {
                if (learningScreen in setOf("calendar", "stats", "settings")) action { renderPlan() } else finish()
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
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
        val spelling = TextView(this).apply {
            text = phonetic.orEmpty(); textSize = 16f
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
            group.addView(TextView(this).apply {
                text = value.toString(); textSize = 34f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.haohao_cocoa))
            })
            group.addView(TextView(this).apply { setText(title); textSize = 13f; setTextColor(color(R.color.haohao_cocoa_secondary)) })
            row.addView(group, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(10) })
        }
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
    }

    private fun rewards(checkins: Int) {
        val earned = com.osfans.trime.data.footprints.earnedLearningMilestones(checkins)
        button(getString(R.string.journal_rewards) + "   ${earned.size} / 6") {
            val names = getString(R.string.journal_reward_names).split('|')
            showJournalSheet(SentenceSheet(this, getString(R.string.journal_rewards)).apply {
                com.osfans.trime.data.footprints.learningMilestones.chunked(2).forEach { pair ->
                    val row = LinearLayout(this@WordLearningActivity).apply { isBaselineAligned = false }
                    pair.forEachIndexed { column, threshold ->
                        val unlocked = threshold in earned
                        val name = names[com.osfans.trime.data.footprints.learningMilestones.indexOf(threshold)]
                        row.addView(TextView(this@WordLearningActivity).apply {
                            text = "${if (unlocked) "✦" else "○"}\n$name\n$threshold 天"
                            contentDescription = name + "，" + getString(if (unlocked) R.string.journal_reward_earned else R.string.journal_reward_locked, threshold)
                            textSize = 17f; gravity = Gravity.CENTER; minHeight = dp(112)
                            setPadding(dp(8), dp(12), dp(8), dp(12))
                            setTextColor(color(if (unlocked) R.color.haohao_on_honey else R.color.haohao_cocoa_secondary))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(24).toFloat()
                                setColor(color(if (unlocked) R.color.haohao_honey else R.color.haohao_segment_surface))
                            }
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (column > 0) marginStart = dp(10) })
                    }
                    body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                }
                description(getString(R.string.journal_reward_rule))
                action(getString(R.string.sentences_close), true)
            })
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
        val requestedSource = intent.getStringExtra(EXTRA_SOURCE).takeIf { it == "cloud" } ?: "offline"
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
            .setMessage("$english\n$chinese\n\n${getString(if (source == "cloud") R.string.words_source_cloud else R.string.words_source_offline)}\n\n${getString(R.string.words_save_notice)}")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> save() }.show()
    }

    private suspend fun renderPlan() {
        showingReview = false
        learningScreen = "dashboard"
        page(R.string.study_home)
        val settings = learning.settings()
        val summary = learning.summary()
        val dashboard = learning.progress.dashboard(System.currentTimeMillis())
        label(getString(R.string.study_today), 28f, true)
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
            group.addView(TextView(this).apply { text = count.toString(); textSize = 36f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.haohao_cocoa)) })
            group.addView(TextView(this).apply { setText(title); textSize = 14f; setTextColor(color(R.color.haohao_cocoa_secondary)) })
            numbers.addView(group, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(numbers, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        if (dashboard.task?.completed == true) label(getString(R.string.study_checked), 18f, true)
        else label(getString(R.string.study_progress, dashboard.done, dashboard.total.takeIf { dashboard.task != null } ?: (fresh + review)), 14f)
        card.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = dashboard.total.coerceAtLeast(1); progress = dashboard.done
            progressTintList = ColorStateList.valueOf(color(R.color.haohao_cocoa))
            progressBackgroundTintList = ColorStateList.valueOf(color(R.color.haohao_divider))
            contentDescription = getString(R.string.study_progress, dashboard.done, dashboard.total.takeIf { dashboard.task != null } ?: (fresh + review))
        }, LinearLayout.LayoutParams(-1, dp(4)).apply { bottomMargin = dp(12) })
        if (dashboard.targets.any { !it.excluded && it.pendingRepeat }) label(getString(R.string.study_pending), 13f)
        if (!settings.planEnabled) {
            label(getString(R.string.study_enable_hint))
            button(getString(R.string.words_plan_enable), true) {
                action { learning.saveSettings(true, settings.newLimit, settings.reviewLimit, settings.reverse); renderPlan() }
            }.minHeight = dp(56)
        } else if (summary.active != null || (dashboard.task == null && summary.plannedNew + summary.plannedDue > 0) || dashboard.total > dashboard.done) {
            button(if (summary.active != null) getString(R.string.words_resume, summary.active.cards.size) else getString(R.string.words_plan_start), true) {
                action { renderReview(learning.startSession(daily = true)) }
            }.minHeight = dp(56)
        } else label(getString(R.string.study_rest), 20f, true)
        content = container
        rewards(dashboard.checkins)
        val todayDone = dashboard.today.total
        if (todayDone > dashboard.targetAnswered) label(getString(R.string.study_extra, todayDone, (todayDone - dashboard.targetAnswered).coerceAtLeast(0)), 13f)
        if (summary.learningCount == 0) label(getString(R.string.study_empty))
        val shortcuts = mutableListOf<AppCompatButton>()
        shortcuts += button(getString(R.string.study_words)) {
            startActivity(Intent(this, com.osfans.trime.ui.main.MainActivity::class.java).setAction(Intent.ACTION_RUN)
                .putExtra(com.osfans.trime.ui.main.MainActivity.EXTRA_SETTINGS_ROUTE, com.osfans.trime.ui.main.NavigationRoute.InputFootprints))
        }
        shortcuts += button(getString(R.string.study_sentences)) { startActivity(Intent(this, SentenceBookActivity::class.java)) }
        shortcuts += button(getString(R.string.study_calendar)) { action { renderCalendar() } }
        shortcuts += button(getString(R.string.study_stats)) { action { renderStatistics() } }
        shortcuts.forEach(container::removeView)
        shortcuts.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { isBaselineAligned = false }
            pair.forEachIndexed { index, view ->
                view.minHeight = dp(64)
                row.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 1) marginStart = dp(10) })
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        button(getString(R.string.words_plan_settings)) { action { renderPlanSettings() } }
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
        val previous = button("‹") { calendarMonth.add(java.util.Calendar.MONTH, -1); action { renderCalendar() } }
        content.removeView(previous)
        previous.contentDescription = getString(R.string.study_previous_month)
        previous.textSize = 26f
        previous.setPadding(0, dp(8), 0, dp(8))
        navigation.addView(previous, LinearLayout.LayoutParams(dp(48), -2))
        navigation.addView(TextView(this).apply {
            text = getString(R.string.study_date, calendarMonth.get(java.util.Calendar.YEAR), calendarMonth.get(java.util.Calendar.MONTH) + 1)
            textSize = 21f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.haohao_cocoa))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val current = java.util.Calendar.getInstance()
        val next = button("›") { calendarMonth.add(java.util.Calendar.MONTH, 1); action { renderCalendar() } }
        content.removeView(next)
        next.contentDescription = getString(R.string.study_next_month); next.textSize = 26f
        next.isEnabled = calendarMonth.get(java.util.Calendar.YEAR) * 12 + calendarMonth.get(java.util.Calendar.MONTH) < current.get(java.util.Calendar.YEAR) * 12 + current.get(java.util.Calendar.MONTH)
        next.alpha = if (next.isEnabled) 1f else .3f
        next.setPadding(0, dp(8), 0, dp(8))
        navigation.addView(next, LinearLayout.LayoutParams(dp(48), -2))
        content.addView(navigation, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        label(getString(R.string.study_calendar_legend), 13f)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumWidth = dp(336) }
        content.addView(android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(grid, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        val weekdays = java.text.DateFormatSymbols.getInstance().shortWeekdays
        val heading = LinearLayout(this)
        (1..7).forEach { day -> heading.addView(TextView(this).apply {
            text = weekdays[day]; textSize = 13f; gravity = Gravity.CENTER; setTextColor(color(R.color.haohao_cocoa_secondary))
        }, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        grid.addView(heading)
        val first = calendarMonth.clone() as java.util.Calendar
        first.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val offset = first.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val count = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val stats = data.days.associateBy { it.day }
        for (week in 0 until (offset + count + 6) / 7) {
            // A completed day has two lines; baseline alignment otherwise pushes it
            // below the row bounds while one-line neighbours determine the baseline.
            val row = LinearLayout(this).apply { isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            grid.addView(row)
            for (column in 0..6) {
                val day = week * 7 + column - offset + 1
                if (day !in 1..count) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(64), 1f)); continue }
                val date = (first.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, day) }
                val key = com.osfans.trime.data.footprints.learningDay(date.timeInMillis)
                val stat = stats[key]
                val status = getString(if (stat?.completed == true) R.string.study_done_status else if ((stat?.total ?: 0) > 0) R.string.study_partial_status else R.string.study_none_status)
                row.addView(AppCompatButton(this).apply {
                    text = "$day" + if (stat?.completed == true) "\n✓" else if ((stat?.total ?: 0) > 0) "\n·" else ""
                    contentDescription = "$key，$status"
                    textSize = 16f; minWidth = 0; minimumWidth = 0; minHeight = dp(64)
                    stateListAnimator = null; elevation = 0f
                    setPadding(0, dp(4), 0, dp(4))
                    setTextColor(color(R.color.haohao_cocoa))
                    backgroundTintList = null
                    background = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(if (stat?.completed == true) color(R.color.haohao_selection_surface) else android.graphics.Color.TRANSPARENT)
                        if (key == data.day) setStroke(dp(1), color(R.color.haohao_cocoa_secondary))
                    }, dp(2))
                    setOnClickListener { showJournalSheet(SentenceSheet(this@WordLearningActivity, key).apply {
                        description(status + "\n" + getString(R.string.study_counts, stat?.fresh ?: 0, stat?.reviewed ?: 0))
                        action(getString(android.R.string.ok), true)
                    }) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        rewards(data.checkins)
        button(getString(R.string.journal_history_info)) {
            showJournalSheet(SentenceSheet(this, getString(R.string.journal_history_info)).apply {
                description(getString(R.string.study_history_note))
                action(getString(android.R.string.ok), true)
            })
        }
    }

    private suspend fun renderStatistics() {
        showingReview = false
        learningScreen = "stats"
        val data = learning.progress.dashboard(System.currentTimeMillis())
        val summary = learning.summary()
        page(R.string.study_stats)
        metrics(data.studyDays to R.string.journal_study_days, summary.dueCount to R.string.journal_due)
        label(getString(R.string.study_ratings), 22f, true)
        label(getString(R.string.journal_ratings_note), 13f)
        listOf(RecallRating.FORGOTTEN to R.string.words_forgotten, RecallRating.UNCERTAIN to R.string.words_uncertain, RecallRating.REMEMBERED to R.string.words_remembered).forEach { (rating, title) ->
            val count = data.ratings[rating.name] ?: 0
            label(getString(R.string.study_rating_count, getString(title), count), 16f)
            content.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = data.ratings.values.sum().coerceAtLeast(1); progress = count
                progressTintList = ColorStateList.valueOf(color(if (rating == RecallRating.REMEMBERED) R.color.haohao_honey else R.color.haohao_cocoa_secondary))
                progressBackgroundTintList = ColorStateList.valueOf(color(R.color.haohao_divider))
                contentDescription = getString(R.string.study_rating_count, getString(title), count)
            }, LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(20) })
        }
        label(getString(R.string.study_recent_days), 22f, true)
        if (data.studyDays == 0) label(getString(R.string.study_no_stats))
        data.days.filter { it.total > 0 }.take(30).forEach {
            label(it.day + "\n" + getString(R.string.study_counts, it.fresh, it.reviewed), 15f).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color(R.color.haohao_surface)) }
            }
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
        val reverse = SwitchCompat(this).apply {
            text = getString(R.string.words_plan_reverse)
            thumbTintList = ContextCompat.getColorStateList(this@WordLearningActivity, R.color.haohao_switch_thumb)
            trackTintList = ContextCompat.getColorStateList(this@WordLearningActivity, R.color.haohao_switch_track)
            isChecked = settings.reverse
            minHeight = dp(56)
            setTextColor(color(R.color.haohao_cocoa))
        }
        content.addView(reverse)
        button(getString(R.string.words_plan_save), true) {
            val fresh = newWords.text.toString().toIntOrNull()
            val due = dueWords.text.toString().toIntOrNull()
            if (fresh == null || fresh !in 1..50 || due == null || due !in 1..100) {
                toast(R.string.words_plan_limits)
            } else {
                action {
                    learning.saveSettings(enabled.isChecked, fresh, due, reverse.isChecked)
                    androidx.core.view.WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                    renderPlan()
                }
            }
        }
    }

    private suspend fun renderReview(initial: WordReviewSession) {
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
        page(if (session.daily) R.string.words_daily_plan else R.string.words_review_title)
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
        val pageContent = content
        val cardSurface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            tag = "learning.reading.surface"
        }
        pageContent.addView(cardSurface, LinearLayout.LayoutParams(-1, -2))
        content = cardSurface
        label(if (session.reverse) current.chinese else current.displayEnglish, 46f, true).apply {
            setTextColor(color(if (session.reverse) R.color.learning_meaning_ink else R.color.learning_word_ink))
        }
        var phoneticView: TextView? = null
        if (!session.reverse) {
            phoneticView = pronunciation(current.phonetic, current.displayEnglish)
        }
        val answer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardSurface.addView(answer, LinearLayout.LayoutParams(-1, -2))
        content = answer
        answer.setPadding(0, dp(8), 0, 0)
        label(if (session.reverse) current.displayEnglish else current.chinese, 24f, true).apply {
            setTextColor(color(if (session.reverse) R.color.learning_word_ink else R.color.learning_meaning_ink))
        }
        if (session.reverse) {
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
        if (resources.configuration.screenHeightDp < 480) {
            footer.setPadding(0, dp(8), 0, dp(12))
            pageContent.addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        else root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        content = footer
        val controls = android.widget.FrameLayout(this)
        footer.addView(controls, LinearLayout.LayoutParams(-1, -2))
        val ratings = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; minimumHeight = dp(56) }
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
            view.background = RippleDrawable(ColorStateList.valueOf(color(R.color.haohao_divider)), GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(color(fill))
            }, null)
            view.setTextColor(color(ink))
            ratings.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        lateinit var reveal: AppCompatButton
        fun updateAnswer(revealed: Boolean) {
            answer.visibility = if (revealed) View.VISIBLE else View.GONE
            ratings.visibility = if (revealed) View.VISIBLE else View.INVISIBLE
            reveal.visibility = if (revealed) View.INVISIBLE else View.VISIBLE
        }
        reveal = button(getString(R.string.words_reveal), true) {
            action {
                learning.reveal(card.token)?.let { updated ->
                    session = updated
                    updateAnswer(updated.answerVisible)
                }
            }
        }
        footer.removeView(reveal)
        reveal.minHeight = dp(56)
        reveal.minimumHeight = dp(56)
        reveal.background = RippleDrawable(ColorStateList.valueOf(color(R.color.haohao_divider)), GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(color(R.color.learning_recall_green))
        }, null)
        reveal.setTextColor(color(R.color.learning_on_recall_green))
        controls.addView(reveal, android.widget.FrameLayout.LayoutParams(-1, -2))
        controls.addView(ratings, android.widget.FrameLayout.LayoutParams(-1, -2))
        updateAnswer(session.answerVisible)
        undoButton()
    }

    private fun renderExample(example: com.osfans.trime.data.footprints.StudyExample, terms: List<String> = emptyList(), meaning: String = "") {
        val previous = content
        val pair = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0)
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
        label(highlighted(example.english, englishRanges), 20f).apply { setTextColor(color(R.color.haohao_cocoa)); setLineSpacing(dp(3).toFloat(), 1f) }
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
                    label(text, 18f).apply { setTextColor(color(R.color.haohao_cocoa)); setLineSpacing(dp(3).toFloat(), 1f) }
                }
            }
            meanings(entry.meanings.take(3))
            if (entry.meanings.size > 3) {
                val extra = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                target.addView(extra)
                val toggle = button(getString(R.string.learning_expand)) { extra.visibility = View.VISIBLE }
                content = extra
                meanings(entry.meanings.drop(3))
                content = target
                toggle.setOnClickListener { extra.visibility = View.VISIBLE; toggle.visibility = View.GONE }
            }
            if (entry.definition.isNotBlank()) {
                sectionHeading(getString(R.string.learning_english_definitions))
                label(entry.definition, 15f)
            }
            sectionHeading(getString(R.string.learning_examples))
            if (entry.examples.isEmpty()) label(getString(R.string.learning_missing))
            else entry.examples.forEach { renderExample(it, com.osfans.trime.data.footprints.studyEnglishTerms(entry.word, entry), intent.getStringExtra(EXTRA_CHINESE).orEmpty()) }
            sectionHeading(getString(R.string.learning_forms))
            val names = mapOf("p" to "过去式", "d" to "过去分词", "i" to "现在分词", "3" to "第三人称", "r" to "比较级", "t" to "最高级", "s" to "复数", "0" to "原形")
            val forms = entry.forms.mapNotNull { form -> val parts = form.split(':', limit = 2); names[parts[0]]?.let { "$it  ${parts[1]}" } }
            label(forms.joinToString("\n").ifBlank { getString(R.string.learning_missing) })
            readingAction("ECDICT · MIT · 资料来源") {
                val notice = assets.open("learning/NOTICE.txt").bufferedReader().use { it.readText() }
                val license = assets.open("learning/ECDICT-LICENSE.txt").bufferedReader().use { it.readText() }
                showJournalSheet(SentenceSheet(this, "ECDICT · MIT").apply {
                    description("$notice\n\n$license"); action(getString(R.string.sentences_close), true)
                })
            }.apply { textSize = 12f; setTextColor(color(R.color.haohao_cocoa_secondary)) }
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
