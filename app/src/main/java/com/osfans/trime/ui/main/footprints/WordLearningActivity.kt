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
    private var speech: WordSpeech? = null

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
        action {
            when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_REVIEW -> {
                    val previous = learning.session()
                    val restoreResult = savedInstanceState?.getBoolean("showingReview") == true ||
                        (learning.undoToken() != null && learning.summary().available == 0)
                    renderReview(if (restoreResult && previous != null) previous else learning.startSession(daily = false))
                }
                MODE_PLAN -> if (savedInstanceState?.getBoolean("showingReview") == true) {
                    renderReview(learning.session() ?: learning.startSession(daily = true))
                } else {
                    renderPlan()
                }
                else -> renderMeaning()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showingReview", showingReview)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        speech = WordSpeech(this)
    }

    override fun onStop() {
        speech?.close()
        speech = null
        super.onStop()
    }

    private fun page(title: Int) {
        root.removeAllViews()
        val toolbar = Toolbar(this).apply {
            setTitle(title)
            setTitleTextColor(color(R.color.haohao_cocoa))
            navigationIcon = DrawerArrowDrawable(this@WordLearningActivity).apply {
                progress = 1f
                color = color(R.color.haohao_cocoa)
            }
            navigationContentDescription = getString(R.string.words_back)
            setNavigationOnClickListener { finish() }
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

    private fun label(value: String, size: Float = 15f, emphasis: Boolean = false, centered: Boolean = false): TextView = TextView(this).apply {
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
        label(english ?: chinese, 34f, emphasis = true)
        if (english == null) {
            label(getString(R.string.words_no_meaning), 20f, true)
            label(getString(R.string.words_no_meaning_hint))
            return
        }
        label(chinese, 23f, emphasis = true)
        phonetic?.takeIf { it.isNotBlank() }?.let { label(it, 17f) }
        label(getString(if ((saved?.source ?: source) == "cloud") R.string.words_source_cloud else R.string.words_source_offline))
        label(getString(R.string.words_save_notice), 13f)
        saved?.takeIf { it.learning }?.let { label(statusText(this, it), 14f, true) }
        button(getString(R.string.input_footprints_speak)) { speak(english) }
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
        page(R.string.words_daily_plan)
        val settings = learning.settings()
        if (!settings.planEnabled) {
            label(getString(R.string.words_plan_hint), 20f)
            button(getString(R.string.words_plan_enable), true) {
                action {
                    learning.saveSettings(true, settings.newLimit, settings.reviewLimit, settings.reverse)
                    renderPlan()
                }
            }
        } else {
            val summary = learning.summary()
            label(getString(R.string.words_today), 26f, true)
            label(getString(R.string.words_plan_counts, summary.plannedNew, summary.plannedDue), 20f, true)
            label(getString(R.string.words_plan_remaining, summary.newCount, summary.dueCount))
            summary.active?.let { label(sessionDescription(it), 15f, true) }
            if (summary.active != null || summary.plannedNew + summary.plannedDue > 0) {
                button(if (summary.active != null) getString(R.string.words_resume, summary.active.cards.size) else getString(R.string.words_plan_start), true) {
                    action { renderReview(learning.startSession(daily = true)) }
                }
            } else {
                label(taskText(this, summary))
                if (summary.available > 0) {
                    button(getString(R.string.words_continue, summary.available), true) {
                        action { renderReview(learning.startSession(daily = true, extra = true)) }
                    }
                }
            }
            if (summary.active == null && learning.undoToken() != null) {
                button(getString(R.string.words_last_session)) {
                    action { learning.session()?.let { renderReview(it) } }
                }
            }
        }
        button(getString(R.string.words_plan_settings)) { action { renderPlanSettings() } }
    }

    private suspend fun renderPlanSettings() {
        showingReview = false
        page(R.string.words_daily_plan)
        val settings = learning.settings()
        label(getString(R.string.words_plan_hint))
        val enabled = SwitchCompat(this).apply {
            text = getString(R.string.words_plan_enable)
            thumbTintList = ContextCompat.getColorStateList(this@WordLearningActivity, R.color.haohao_switch_thumb)
            trackTintList = ContextCompat.getColorStateList(this@WordLearningActivity, R.color.haohao_switch_track)
            isChecked = settings.planEnabled
            minHeight = dp(56)
            setTextColor(color(R.color.haohao_cocoa))
        }
        content.addView(enabled)
        fun number(title: Int, value: Int): AppCompatEditText {
            val titleView = label(getString(title), emphasis = true)
            return AppCompatEditText(this).apply {
                id = View.generateViewId()
                titleView.labelFor = id
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setText(value.toString())
                textSize = 20f
                setTextColor(color(R.color.haohao_cocoa))
                minHeight = dp(48)
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
        label(sessionDescription(session), 13f)
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
        label(
            getString(
                if (card.repeat) {
                    R.string.words_review_repeat
                } else if (session.reverse) {
                    R.string.words_review_prompt_reverse
                } else {
                    R.string.words_review_prompt
                },
            ),
            20f,
            true,
        )
        label(getString(R.string.words_review_origin), 13f, centered = true).setPadding(0, dp(30), 0, 0)
        label(if (session.reverse) current.chinese else current.displayEnglish, 38f, true, true).setPadding(0, dp(14), 0, dp(16))
        if (session.answerVisible) {
            label(if (session.reverse) current.displayEnglish else current.chinese, 26f, true, true)
            current.phonetic?.let { label(it, 17f, centered = true) }
            label(getString(if (current.source == "cloud") R.string.words_source_cloud else R.string.words_source_offline), 13f, centered = true)
            button(getString(R.string.input_footprints_speak)) { speak(current.displayEnglish) }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val ratings = listOf(R.string.words_forgotten to RecallRating.FORGOTTEN, R.string.words_uncertain to RecallRating.UNCERTAIN, R.string.words_remembered to RecallRating.REMEMBERED)
            ratings.forEachIndexed { index, (title, rating) ->
                val view = button(getString(title), rating == RecallRating.REMEMBERED) {
                    action { learning.answer(card.token, rating)?.let { renderReview(it) } }
                }
                content.removeView(view)
                view.setPadding(dp(4), dp(10), dp(4), dp(10))
                view.textSize = 14f
                buttons.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            content.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        } else {
            button(getString(R.string.words_reveal), true) { action { learning.reveal(card.token)?.let { renderReview(it) } } }
        }
        label(getString(R.string.words_review_hint), 13f).setPadding(0, dp(20), 0, 0)
        undoButton()
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
        speech?.speak(text)
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
