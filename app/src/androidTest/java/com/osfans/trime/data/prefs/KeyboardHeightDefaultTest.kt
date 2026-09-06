// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardHeightDefaultTest {
    @Test
    fun unsetHeightIsCompactWhileExplicitChoicesSurviveRecreation() {
        // Dedicated regression-only preferences: never touch the installed user's settings.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("height-default-regression", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            assertEquals(AppPrefs.Keyboard.KeyboardHeightMode.COMPACT, AppPrefs.Keyboard(preferences).heightMode.getValue())
            assertFalse(preferences.contains(AppPrefs.Keyboard.HEIGHT_MODE))
            for (mode in AppPrefs.Keyboard.KeyboardHeightMode.entries) {
                AppPrefs.Keyboard(preferences).heightMode.setValue(mode)
                assertEquals(mode, AppPrefs.Keyboard(preferences).heightMode.getValue())
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
