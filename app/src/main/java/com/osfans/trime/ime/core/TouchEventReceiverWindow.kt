/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow

internal fun canShowTouchReceiver(attached: Boolean, hasToken: Boolean, shown: Boolean, width: Int, height: Int): Boolean = attached && hasToken && shown && width > 0 && height > 0

class TouchEventReceiverWindow(
    private val contentView: View,
) {
    private val ctx = contentView.context

    private val window =
        PopupWindow(
            object : View(ctx) {
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean = contentView.dispatchTouchEvent(event)
            },
        ).apply {
            // disable animation
            animationStyle = 0
        }

    private val cachedLocation = intArrayOf(0, 0)
    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)
    private var requested = false
    private var followContentBounds = false
    private var explicitBounds: Bounds? = null
    private var displayedBounds: Bounds? = null
    private val updateOnAttach = Runnable { updateWindow() }

    init {
        contentView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                // StateFlow can replay composition before the IME has a window token.
                view.post(updateOnAttach)
            }
            override fun onViewDetachedFromWindow(view: View) {
                dismiss()
            }
        })
        contentView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWindow() }
    }

    fun showAt(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        requested = true
        followContentBounds = false
        explicitBounds = Bounds(x, y, w, h)
        updateWindow()
    }

    fun show() {
        requested = true
        followContentBounds = true
        updateWindow()
    }

    fun dismiss() {
        requested = false
        explicitBounds = null
        contentView.removeCallbacks(updateOnAttach)
        dismissWindow()
    }

    private fun dismissWindow() {
        displayedBounds = null
        try {
            window.dismiss()
        } catch (_: IllegalArgumentException) {
            // Its host can already have been removed by a configuration change.
        }
    }

    private fun updateWindow() {
        if (!requested) return
        val bounds = if (followContentBounds) {
            contentView.getLocationInWindow(cachedLocation)
            Bounds(cachedLocation[0], cachedLocation[1], contentView.width, contentView.height)
        } else {
            explicitBounds ?: return
        }
        if (!canShowTouchReceiver(contentView.isAttachedToWindow, contentView.windowToken != null, contentView.isShown, bounds.width, bounds.height)) {
            dismissWindow()
            return
        }
        if (window.isShowing && displayedBounds == bounds) return
        try {
            if (window.isShowing) {
                window.update(bounds.x, bounds.y, bounds.width, bounds.height)
            } else {
                window.width = bounds.width
                window.height = bounds.height
                window.showAtLocation(contentView, Gravity.TOP or Gravity.START, bounds.x, bounds.y)
            }
            displayedBounds = bounds
        } catch (_: WindowManager.BadTokenException) {
            dismissWindow()
        }
    }
}
