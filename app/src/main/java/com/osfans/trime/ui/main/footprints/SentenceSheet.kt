// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.footprints

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.osfans.trime.R

/** One brand surface for sentence consent, preview and destructive confirmation. */
internal class SentenceSheet(private val owner: AppCompatActivity, title: String) : Dialog(owner) {
    private fun dp(value: Int) = (value * owner.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(owner, id)
    val body = LinearLayout(owner).apply {
        tag = "sentence-sheet"
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(24))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(color(R.color.haohao_page_background))
        }
    }
    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        body.addView(View(owner).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color(R.color.haohao_divider)) }
        }, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })
        body.addView(TextView(owner).apply {
            text = title; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.haohao_cocoa))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        setContentView(ScrollView(owner).apply { isFillViewport = false; addView(body) })
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setDimAmount(0.28f)
        }
    }
    override fun show() {
        // Size before the first frame, not after showing a narrow platform-dialog layout.
        val metrics = owner.resources.displayMetrics
        val width = minOf(metrics.widthPixels - dp(24), dp(560))
        val heightLimit = (metrics.heightPixels * 0.80f).toInt()
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        window?.setLayout(width, minOf(body.measuredHeight, heightLimit))
        super.show()
    }
    fun description(value: String) {
        body.addView(TextView(owner).apply {
            text = value; textSize = 16f; setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(color(R.color.haohao_cocoa_secondary))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    fun action(label: String, primary: Boolean = false, clicked: () -> Unit = {}) {
        body.addView(AppCompatButton(owner).apply {
            text = label; isAllCaps = false; textSize = 16f
            minHeight = dp(52); minimumWidth = 0
            setPadding(dp(16), dp(12), dp(16), dp(12))
            stateListAnimator = null
            background = RippleDrawable(ColorStateList.valueOf(color(R.color.haohao_divider)), GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color(if (primary) R.color.haohao_honey else R.color.haohao_segment_surface))
            }, null)
            backgroundTintList = null
            setTextColor(color(if (primary) R.color.haohao_on_honey else R.color.haohao_cocoa))
            setOnClickListener { isEnabled = false; dismiss(); clicked() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
}
