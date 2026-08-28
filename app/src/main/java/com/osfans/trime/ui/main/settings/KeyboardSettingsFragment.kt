/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import kotlinx.coroutines.launch

class KeyboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().keyboard) {
    private val prefs = AppPrefs.defaultInstance().keyboard

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.bindFeedbackPreset(prefs)

        ADVANCED_FEEDBACK_KEYS.forEach { key ->
            screen.findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                prefs.feedbackPreset.setValue(AppPrefs.Keyboard.FeedbackPreset.CUSTOM)
                true
            }
        }

        screen.findPreference<Preference>("custom_sound_effect_name")?.apply {
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    SoundEffectPickerDialog.build(lifecycleScope, requireContext())
                        .show()
                }
                true
            }
        }
    }

    private companion object {
        val ADVANCED_FEEDBACK_KEYS = listOf(
            AppPrefs.Keyboard.SOUND_ON_KEYPRESS,
            AppPrefs.Keyboard.KEY_SOUND_VOLUME,
            AppPrefs.Keyboard.USE_CUSTOM_SOUND_EFFECT,
            AppPrefs.Keyboard.VIBRATE_ON_KEY_PRESS,
            AppPrefs.Keyboard.VIBRATE_ON_KEY_RELEASE,
            AppPrefs.Keyboard.VIBRATE_ON_KEY_REPEAT,
            AppPrefs.Keyboard.VIBRATION_DURATION,
            AppPrefs.Keyboard.VIBRATION_AMPLITUDE,
        )
    }
}

internal fun PreferenceScreen.bindFeedbackPreset(prefs: AppPrefs.Keyboard) {
    findPreference<ListPreference>(AppPrefs.Keyboard.FEEDBACK_PRESET)?.apply {
        setOnPreferenceChangeListener { _, newValue ->
            val preset = runCatching {
                AppPrefs.Keyboard.FeedbackPreset.valueOf(newValue.toString().uppercase())
            }.getOrNull() ?: return@setOnPreferenceChangeListener false
            prefs.applyFeedbackPreset(preset)
            true
        }
    }
}
