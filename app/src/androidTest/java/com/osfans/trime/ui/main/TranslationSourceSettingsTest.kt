// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.translation.CandidateTranslationSourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSourceSettingsTest {
    @Test
    fun sourceModesAreFirstVisibleAndSurviveRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".regression"))
        val prefs = AppPrefs.defaultInstance().cloudTranslation
        val consent = prefs.consentGranted.getValue()
        val source = prefs.candidateSource.getValue()
        try {
            prefs.consentGranted.setValue(true) // isolated test app only; no network requested
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
                .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.CloudTranslation)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                for ((id, mode) in listOf(
                    R.id.candidate_source_local to CandidateTranslationSourceMode.LOCAL_ONLY,
                    R.id.candidate_source_cloud to CandidateTranslationSourceMode.CLOUD_ONLY,
                    R.id.candidate_source_hybrid to CandidateTranslationSourceMode.LOCAL_THEN_CLOUD,
                )) {
                    scenario.onActivity { activity ->
                        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                        host.childFragmentManager.executePendingTransactions()
                        val view = host.childFragmentManager.primaryNavigationFragment!!.requireView()
                        val section = view.findViewById<View>(R.id.candidate_source_section)
                        assertEquals(0, (section.parent as ViewGroup).indexOfChild(section))
                        assertTrue(view.findViewById<View>(id).performClick())
                        assertEquals(mode, prefs.candidateSource.getValue())
                    }
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertTrue(activity.findViewById<View>(id).isSelected)
                    }
                }
            }
        } finally {
            prefs.candidateSource.setValue(source)
            prefs.consentGranted.setValue(consent)
        }
    }
}
