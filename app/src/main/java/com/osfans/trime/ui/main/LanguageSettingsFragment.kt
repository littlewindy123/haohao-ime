/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.data.speech.SpeechPlayback
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.navigateWithAnim

class LanguageSettingsFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            addPreference(
                Preference(context).apply {
                    setTitle(R.string.home_translation_settings)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        findNavController().navigateWithAnim(NavigationRoute.CloudTranslation)
                        true
                    }
                },
            )
            addPreference(
                PreferenceCategory(context).apply {
                    setTitle(R.string.home_speech)
                    isIconSpaceReserved = false
                },
            )
            addPreference(
                SwitchPreferenceCompat(context).apply {
                    setTitle(R.string.home_speech_network)
                    isIconSpaceReserved = false
                    isPersistent = false
                    isChecked = SpeechPlayback.consent(context)
                    setOnPreferenceChangeListener { _, value ->
                        if (value == true) {
                            AlertDialog.Builder(context).setTitle(R.string.speech_consent_title)
                                .setMessage(R.string.speech_consent_message)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.speech_allow) { _, _ ->
                                    SpeechPlayback.setConsent(context, true)
                                    isChecked = true
                                }
                                .show()
                            false
                        } else {
                            SpeechPlayback.setConsent(context, false)
                            true
                        }
                    }
                },
            )
            addPreference(
                Preference(context).apply {
                    setTitle(R.string.home_clear_audio)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        AlertDialog.Builder(context).setMessage(R.string.home_clear_audio_prompt)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.delete) { _, _ -> SpeechPlayback.clearCache(context) }.show()
                        true
                    }
                },
            )
        }
    }
}
