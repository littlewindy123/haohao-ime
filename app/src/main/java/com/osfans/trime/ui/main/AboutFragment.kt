/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.navigation.fragment.findNavController
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.Const
import com.osfans.trime.util.addPreference

class AboutFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.trime_app_name, R.string.product_about_summary)
            addPreference(R.string.current_version, "${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
            addPreference(R.string.product_feedback, R.string.product_feedback_summary) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Const.PRODUCT_REPOSITORY}/issues")))
            }
            addPreference(R.string.product_help) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Const.PRODUCT_REPOSITORY}#readme")))
            }
            addPreference(R.string.privacy_policy) {
                findNavController().navigate(NavigationRoute.PrivacyPolicy)
            }
            addPreference(R.string.open_source_licenses) {
                findNavController().navigate(NavigationRoute.License)
            }
        }
    }
}
