/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HaoHaoQuickSettingsTest {
    @Test
    fun quickSettingsReuseStoredValuesAndToggleTranslationDependencies() {
        val candidates = AppPrefs.defaultInstance().candidates
        val originalTranslation = candidates.bilingualTranslation.getValue()
        val originalDelay = candidates.bilingualTranslationDelay.getValue()
        val originalPhonetic = candidates.bilingualPhonetic.getValue()
        val originalPortraitCount = candidates.compactCandidateCount.getValue()
        val originalLandscapeCount = candidates.compactCandidateCountLandscape.getValue()

        try {
            candidates.bilingualTranslation.setValue(true)
            candidates.bilingualTranslationDelay.setValue(700)
            candidates.bilingualPhonetic.setValue(true)
            candidates.compactCandidateCount.setValue(3)
            candidates.compactCandidateCountLandscape.setValue(7)

            launchMain { fragment, _, activity ->
                val translation = fragment.requireView<SwitchCompat>(R.id.translation_switch)
                val delay = fragment.requireView<AppCompatSeekBar>(R.id.translation_delay_slider)
                val phonetic = fragment.requireView<SwitchCompat>(R.id.phonetic_switch)

                assertTrue(translation.isChecked)
                assertEquals(7, delay.progress)
                assertEquals(
                    activity.getString(R.string.quick_settings_delay_value, 700),
                    fragment.requireView<TextView>(R.id.translation_delay_value).text.toString(),
                )
                assertTrue(fragment.requireView<AppCompatButton>(R.id.portrait_count_3).isSelected)
                assertTrue(fragment.requireView<AppCompatButton>(R.id.landscape_count_7).isSelected)

                translation.performClick()
                assertFalse(candidates.bilingualTranslation.getValue())
                assertFalse(delay.isEnabled)
                assertFalse(phonetic.isEnabled)
                assertEquals(700, candidates.bilingualTranslationDelay.getValue())
                assertTrue(fragment.requireView<TextView>(R.id.preview_english).isInvisible)

                translation.performClick()
                assertTrue(delay.isEnabled)
                assertTrue(phonetic.isEnabled)
            }
        } finally {
            candidates.bilingualTranslation.setValue(originalTranslation)
            candidates.bilingualTranslationDelay.setValue(originalDelay)
            candidates.bilingualPhonetic.setValue(originalPhonetic)
            candidates.compactCandidateCount.setValue(originalPortraitCount)
            candidates.compactCandidateCountLandscape.setValue(originalLandscapeCount)
        }
    }

    @Test
    fun quickSettingsApplyFeedbackPresetAndNavigateToExistingPages() {
        val keyboard = AppPrefs.defaultInstance().keyboard
        val originalPreset = keyboard.feedbackPreset.getValue()
        val originalSound = keyboard.soundOnKeyPress.getValue()
        val originalVolume = keyboard.soundVolume.getValue()
        val originalCustomSound = keyboard.useCustomSoundEffect.getValue()
        val originalVibrate = keyboard.vibrateOnKeyPress.getValue()
        val originalVibrateRelease = keyboard.vibrateOnKeyRelease.getValue()
        val originalVibrateRepeat = keyboard.vibrateOnKeyRepeat.getValue()
        val originalDuration = keyboard.vibrationDuration.getValue()
        val originalAmplitude = keyboard.vibrationAmplitude.getValue()

        try {
            launchMain { fragment, navHost, activity ->
                assertFalse(activity.findViewById<View>(R.id.mainToolbar).isVisible)

                fragment.requireView<AppCompatButton>(R.id.feedback_soft_haptic).performClick()
                assertEquals(AppPrefs.Keyboard.FeedbackPreset.SOFT_HAPTIC, keyboard.feedbackPreset.getValue())
                assertFalse(keyboard.soundOnKeyPress.getValue())
                assertTrue(keyboard.vibrateOnKeyPress.getValue())

                fragment.requireView<View>(R.id.try_keyboard_button).performClick()
                assertTrue(activity.findViewById<TestInputPanel>(R.id.test_input_panel).isVisible)
                activity.findViewById<TestInputPanel>(R.id.test_input_panel).dismiss()

                fragment.requireView<View>(R.id.theme_destination).performClick()
                assertTrue(navHost.navController.currentDestination?.hasRoute<NavigationRoute.Theme>() == true)
                assertTrue(activity.findViewById<View>(R.id.mainToolbar).isVisible)
                navHost.navController.popBackStack()
                navHost.childFragmentManager.executePendingTransactions()

                val current = navHost.childFragmentManager.primaryNavigationFragment as MainFragment
                current.requireView<View>(R.id.all_settings_destination).performClick()
                assertTrue(navHost.navController.currentDestination?.hasRoute<NavigationRoute.AllSettings>() == true)
            }
        } finally {
            keyboard.feedbackPreset.setValue(originalPreset)
            keyboard.soundOnKeyPress.setValue(originalSound)
            keyboard.soundVolume.setValue(originalVolume)
            keyboard.useCustomSoundEffect.setValue(originalCustomSound)
            keyboard.vibrateOnKeyPress.setValue(originalVibrate)
            keyboard.vibrateOnKeyRelease.setValue(originalVibrateRelease)
            keyboard.vibrateOnKeyRepeat.setValue(originalVibrateRepeat)
            keyboard.vibrationDuration.setValue(originalDuration)
            keyboard.vibrationAmplitude.setValue(originalAmplitude)
        }
    }

    private fun launchMain(block: (MainFragment, NavHostFragment, MainActivity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val navHost =
                    activity.supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHost.childFragmentManager.executePendingTransactions()
                val fragment = navHost.childFragmentManager.primaryNavigationFragment as MainFragment
                block(fragment, navHost, activity)
            }
        }
    }

    private inline fun <reified T : View> MainFragment.requireView(id: Int): T = requireNotNull(requireView().findViewById<T>(id)) { "Missing view: $id" }
}
