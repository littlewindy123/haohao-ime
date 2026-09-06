// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.candidates.compact

import android.content.res.ColorStateList
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.transition.Transition
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.translation.CloudCandidateTranslationController
import com.osfans.trime.data.translation.SentenceCandidateState
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import org.kodein.di.instance
import splitties.dimensions.dp

/** Uses the existing keyboard board bounds; no dialog, editor read, clipboard or commit action. */
internal class SentenceTranslationPreviewWindow(private val snapshot: SentenceCandidateState) : BoardWindow.NoBarBoardWindow() {
    private val manager: BoardWindowManager by di.instance()
    private val controller: CloudCandidateTranslationController by di.instance()
    private lateinit var body: TextView
    private lateinit var speech: com.osfans.trime.ui.main.footprints.WordSpeech
    private val listener: () -> Unit = {
        if (controller.sentenceState != snapshot) {
            speech.stop()
            body.text = ""
            body.post { if (manager.isAttached(this)) manager.attachWindow(KeyboardWindow) }
        }
    }
    override fun enterAnimation(lastWindow: BoardWindow): Transition? = null
    override fun exitAnimation(nextWindow: BoardWindow): Transition? = null
    override fun onCreateView(): View = LinearLayout(context).apply {
        speech = com.osfans.trime.ui.main.footprints.WordSpeech(context)
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(12), 0, context.dp(12), context.dp(8))
        setBackgroundColor(ColorManager.getColor("back_color"))
        addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_baseline_arrow_back_24)
                imageTintList = ColorStateList.valueOf(ColorManager.getColor("candidate_text_color"))
                background = null
                setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
                contentDescription = context.getString(R.string.sentence_translation_return)
                setOnClickListener { manager.attachWindow(KeyboardWindow) }
            },
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)),
        )
        body = TextView(context).apply {
            text = sentencePreviewText(snapshot)
            textSize = 17f
            setTextColor(ColorManager.getColor("candidate_text_color"))
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(12))
        }
        addView(
            ScrollView(context).apply {
                addView(body, FrameLayout.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        addView(speech.controls { snapshot.translation.takeIf { controller.sentenceState == snapshot } }, LinearLayout.LayoutParams(-1, context.dp(48)))
    }
    override fun onAttached() {
        controller.addSentenceListener(listener)
        listener()
    }
    override fun onDetached() {
        speech.close()
        controller.removeSentenceListener(listener)
        body.text = ""
    }
}
