/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.haohao.usesLandscapeKeyboardMetrics
import com.osfans.trime.util.isLandscape

object KeyboardPrefs {
    private val prefs = AppPrefs.defaultInstance()

    private const val WIDE_SCREEN_WIDTH_DP = 600

    fun Context.isLandscapeMode(): Boolean = when (prefs.keyboard.landscapeMode.getValue()) {
        AppPrefs.Keyboard.LandscapeMode.WIDE -> resources.configuration.isLandscape() || isWideScreen()
        AppPrefs.Keyboard.LandscapeMode.LANDSCAPE -> resources.configuration.isLandscape()
        AppPrefs.Keyboard.LandscapeMode.ALWAYS -> true
        else -> false
    }

    fun Context.useLandscapeMetrics(): Boolean = usesLandscapeKeyboardMetrics(
        haoHaoTheme = ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID,
        landscapeScreen = resources.configuration.isLandscape(),
        landscapeLayout = isLandscapeMode(),
    )

    private fun Context.isWideScreen(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels / metrics.density > WIDE_SCREEN_WIDTH_DP
    }
}
