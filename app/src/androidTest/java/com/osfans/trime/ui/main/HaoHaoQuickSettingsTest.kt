/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.view.View
import android.view.ViewGroup
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

            launchInputPreferences { fragment, _, activity ->
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
            launchInputPreferences { fragment, navHost, activity ->
                assertTrue(activity.findViewById<View>(R.id.mainToolbar).isVisible)

                fragment.requireView<AppCompatButton>(R.id.feedback_soft_haptic).performClick()
                assertEquals(AppPrefs.Keyboard.FeedbackPreset.SOFT_HAPTIC, keyboard.feedbackPreset.getValue())
                assertFalse(keyboard.soundOnKeyPress.getValue())
                assertTrue(keyboard.vibrateOnKeyPress.getValue())

                assertTrue(navHost.navController.popBackStack(NavigationRoute.Main, false))
                navHost.childFragmentManager.executePendingTransactions()
                assertTrue(navHost.childFragmentManager.primaryNavigationFragment is HaoHaoHomeFragment)
                assertFalse(activity.findViewById<View>(R.id.mainToolbar).isVisible)
                homeAction(navHost, R.string.home_try).performClick()
                assertTrue(activity.findViewById<TestInputPanel>(R.id.test_input_panel).isVisible)
                activity.findViewById<TestInputPanel>(R.id.test_input_panel).dismiss()

                homeAction(navHost, R.string.home_all_themes).performClick()
                assertTrue(navHost.navController.currentDestination?.hasRoute<NavigationRoute.Appearance>() == true)
                assertTrue(activity.findViewById<View>(R.id.mainToolbar).isVisible)
                navHost.navController.popBackStack()
                navHost.childFragmentManager.executePendingTransactions()

                homeAction(navHost, R.string.home_settings).performClick()
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

    @Test
    fun consumerSettingsKeepAdvancedOptionsAndCreditsReachable() {
        launchInputPreferences { _, navHost, activity ->
            fun open(route: NavigationRoute): androidx.preference.PreferenceScreen {
                navHost.navController.navigate(route)
                navHost.childFragmentManager.executePendingTransactions()
                return (navHost.childFragmentManager.primaryNavigationFragment as androidx.preference.PreferenceFragmentCompat).preferenceScreen
            }
            fun titles(group: androidx.preference.PreferenceGroup): List<String> = (0 until group.preferenceCount).flatMap { index ->
                val item = group.getPreference(index)
                if (item is androidx.preference.PreferenceGroup) titles(item) else listOf(item.title.toString())
            }
            val settings = titles(open(NavigationRoute.AllSettings))
            assertEquals(listOf(R.string.product_appearance, R.string.product_input, R.string.home_translation, R.string.product_learning, R.string.product_privacy_data, R.string.product_expert, R.string.about).map(activity::getString), settings)
            val appearance = open(NavigationRoute.Appearance)
            val mode = appearance.findPreference<androidx.preference.ListPreference>("appearance_ui_mode")!!
            assertEquals(AppPrefs.defaultInstance().advanced.uiMode.getValue().name, mode.value)
            assertEquals(3, mode.entries.size)
            val expert = titles(open(NavigationRoute.Expert))
            for (id in listOf(R.string.schemata, R.string.user_dictionary, R.string.profile, R.string.theme, R.string.developer, R.string.deploy)) assertTrue(expert.contains(activity.getString(id)))
            val about = titles(open(NavigationRoute.About))
            assertTrue(about.contains(activity.getString(R.string.open_source_licenses)))
            assertFalse(about.contains(activity.getString(R.string.librime_version)))
            assertFalse(about.contains(activity.getString(R.string.qq_group_1)))
            val privacy = open(NavigationRoute.PrivacyPolicy)
            assertEquals(activity.getString(R.string.haohao_privacy_policy), privacy.getPreference(0).summary)
            val licenses = titles(open(NavigationRoute.License))
            assertTrue(licenses.contains(activity.getString(R.string.product_credits)))
            assertTrue(licenses.contains(activity.getString(R.string.source_code)))
        }
    }

    @Test
    fun homeAndRecreationStayUsableWithoutNotificationPermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
            fun assertHome() {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue("The home window must receive focus without a permission dialog", activity.hasWindowFocus())
                    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    host.childFragmentManager.executePendingTransactions()
                    assertTrue(host.childFragmentManager.primaryNavigationFragment is HaoHaoHomeFragment)
                }
            }
            assertHome()
            scenario.recreate()
            assertHome()
        }
    }

    private fun launchInputPreferences(block: (MainFragment, NavHostFragment, MainActivity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.InputPreferences)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val navHost =
                    activity.supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHost.childFragmentManager.executePendingTransactions()
                assertTrue(navHost.navController.currentDestination?.hasRoute<NavigationRoute.InputPreferences>() == true)
                val fragment = navHost.childFragmentManager.primaryNavigationFragment as MainFragment
                block(fragment, navHost, activity)
            }
        }
    }

    private fun homeAction(navHost: NavHostFragment, label: Int): View {
        val home = navHost.childFragmentManager.primaryNavigationFragment as HaoHaoHomeFragment
        val text = home.getString(label)
        return descendants(home.requireView()).first {
            it.isClickable && ((it as? TextView)?.text == text || it.contentDescription == text)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private inline fun <reified T : View> MainFragment.requireView(id: Int): T = requireNotNull(requireView().findViewById<T>(id)) { "Missing view: $id" }
}
