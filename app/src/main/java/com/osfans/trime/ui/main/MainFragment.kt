/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.databinding.FragmentMainBinding
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

abstract class TopOptionsPreferenceFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }
}

class MainFragment : Fragment(R.layout.fragment_main) {
    private val prefs = AppPrefs.defaultInstance()
    private val viewModel: MainViewModel by activityViewModels()

    private var viewBinding: FragmentMainBinding? = null
    private val binding: FragmentMainBinding
        get() = requireNotNull(viewBinding)

    private var updatingUi = false
    private var previewJob: Job? = null

    private val translationListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        renderCandidateSettings(reschedulePreview = true)
    }
    private val delayListener = PreferenceDelegate.OnChangeListener<Int> { _, _ ->
        renderCandidateSettings(reschedulePreview = true)
    }
    private val phoneticListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        renderCandidateSettings(reschedulePreview = true)
    }
    private val portraitCountListener = PreferenceDelegate.OnChangeListener<Int> { _, _ ->
        renderCandidateSettings(reschedulePreview = false)
    }
    private val landscapeCountListener = PreferenceDelegate.OnChangeListener<Int> { _, _ ->
        renderCandidateSettings(reschedulePreview = false)
    }
    private val feedbackListener =
        PreferenceDelegate.OnChangeListener<AppPrefs.Keyboard.FeedbackPreset> { _, _ ->
            renderFeedbackSetting()
        }
    private val themeListener = PreferenceDelegate.OnChangeListener<String> { _, _ ->
        renderThemeSetting()
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding = FragmentMainBinding.bind(view)
        setupInsets()
        setupHeader()
        setupCandidateSettings()
        setupFeedbackSettings()
        setupDestinations()
        registerPreferenceListeners()
        renderAllSettings(reschedulePreview = true)
        viewModel.disableTopOptionsMenu()
    }

    override fun onStart() {
        super.onStart()
        renderAllSettings(reschedulePreview = true)
    }

    override fun onDestroyView() {
        previewJob?.cancel()
        unregisterPreferenceListeners()
        viewBinding?.brandHeader?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
        viewBinding = null
        super.onDestroyView()
    }

    private fun setupInsets() {
        val baseHeight = resources.getDimensionPixelSize(R.dimen.haohao_home_header_height)
        val baseTopPadding = binding.brandHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.brandHeader) { header, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            header.updatePadding(top = baseTopPadding + statusBar)
            header.updateLayoutParams { height = baseHeight + statusBar }
            insets
        }
        ViewCompat.requestApplyInsets(binding.brandHeader)
    }

    private fun setupHeader() {
        binding.tryKeyboardButton.setOnClickListener {
            (requireActivity() as MainActivity).showTestInputPanel()
        }
        binding.moreButton.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add(MENU_GROUP, MENU_DEPLOY, MENU_DEPLOY, R.string.deploy)
                menu.add(MENU_GROUP, MENU_DEVELOPER, MENU_DEVELOPER, R.string.developer)
                menu.add(MENU_GROUP, MENU_ABOUT, MENU_ABOUT, R.string.about)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        MENU_DEPLOY -> viewModel.rime.launchOnReady { it.deploy() }
                        MENU_DEVELOPER -> findNavController().navigateWithAnim(NavigationRoute.Developer)
                        MENU_ABOUT -> findNavController().navigateWithAnim(NavigationRoute.About)
                        else -> return@setOnMenuItemClickListener false
                    }
                    true
                }
                show()
            }
        }
    }

    private fun setupCandidateSettings() {
        binding.translationSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) prefs.candidates.bilingualTranslation.setValue(checked)
        }
        binding.phoneticSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) prefs.candidates.bilingualPhonetic.setValue(checked)
        }
        binding.translationDelaySlider.max = TRANSLATION_DELAY_STEPS
        binding.translationDelaySlider.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!updatingUi) {
                        prefs.candidates.bilingualTranslationDelay.setValue(progress * TRANSLATION_DELAY_STEP_MS)
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            },
        )

        bindSegmentButtons(
            listOf(
                binding.portraitCount3 to 3,
                binding.portraitCount4 to 4,
                binding.portraitCount5 to 5,
            ),
        ) { prefs.candidates.compactCandidateCount.setValue(it) }
        bindSegmentButtons(
            listOf(
                binding.landscapeCount5 to 5,
                binding.landscapeCount6 to 6,
                binding.landscapeCount7 to 7,
                binding.landscapeCount8 to 8,
            ),
        ) { prefs.candidates.compactCandidateCountLandscape.setValue(it) }
    }

    private fun setupFeedbackSettings() {
        bindSegmentButtons(
            listOf(
                binding.feedbackSilent to AppPrefs.Keyboard.FeedbackPreset.SILENT,
                binding.feedbackSoftSound to AppPrefs.Keyboard.FeedbackPreset.SOFT_SOUND,
                binding.feedbackSoftHaptic to AppPrefs.Keyboard.FeedbackPreset.SOFT_HAPTIC,
                binding.feedbackSoundHaptic to AppPrefs.Keyboard.FeedbackPreset.SOUND_HAPTIC,
            ),
        ) { preset ->
            prefs.keyboard.applyFeedbackPreset(preset)
            prefs.keyboard.feedbackPreset.setValue(preset)
        }
        binding.customFeedbackAction.setOnClickListener {
            findNavController().navigateWithAnim(NavigationRoute.VirtualKeyboard)
        }
    }

    private fun setupDestinations() {
        binding.themeDestination.setOnClickListener {
            findNavController().navigateWithAnim(NavigationRoute.Theme)
        }
        binding.allSettingsDestination.setOnClickListener {
            findNavController().navigateWithAnim(NavigationRoute.AllSettings)
        }
    }

    private fun registerPreferenceListeners() {
        prefs.candidates.bilingualTranslation.registerOnChangeListener(translationListener)
        prefs.candidates.bilingualTranslationDelay.registerOnChangeListener(delayListener)
        prefs.candidates.bilingualPhonetic.registerOnChangeListener(phoneticListener)
        prefs.candidates.compactCandidateCount.registerOnChangeListener(portraitCountListener)
        prefs.candidates.compactCandidateCountLandscape.registerOnChangeListener(landscapeCountListener)
        prefs.keyboard.feedbackPreset.registerOnChangeListener(feedbackListener)
        ThemeManager.prefs.selectedTheme.registerOnChangeListener(themeListener)
    }

    private fun unregisterPreferenceListeners() {
        prefs.candidates.bilingualTranslation.unregisterOnChangeListener(translationListener)
        prefs.candidates.bilingualTranslationDelay.unregisterOnChangeListener(delayListener)
        prefs.candidates.bilingualPhonetic.unregisterOnChangeListener(phoneticListener)
        prefs.candidates.compactCandidateCount.unregisterOnChangeListener(portraitCountListener)
        prefs.candidates.compactCandidateCountLandscape.unregisterOnChangeListener(landscapeCountListener)
        prefs.keyboard.feedbackPreset.unregisterOnChangeListener(feedbackListener)
        ThemeManager.prefs.selectedTheme.unregisterOnChangeListener(themeListener)
    }

    private fun renderAllSettings(reschedulePreview: Boolean) {
        if (viewBinding == null) return
        renderCandidateSettings(reschedulePreview)
        renderFeedbackSetting()
        renderThemeSetting()
    }

    private fun renderCandidateSettings(reschedulePreview: Boolean) {
        if (viewBinding == null) return
        val candidates = prefs.candidates
        val translationEnabled = candidates.bilingualTranslation.getValue()
        val delayMs = candidates.bilingualTranslationDelay.getValue()
        updatingUi = true
        binding.translationSwitch.isChecked = translationEnabled
        binding.phoneticSwitch.isChecked = candidates.bilingualPhonetic.getValue()
        binding.phoneticSwitch.isEnabled = translationEnabled
        binding.translationDelaySlider.isEnabled = translationEnabled
        binding.translationDelayLabel.isEnabled = translationEnabled
        binding.translationDelayValue.isEnabled = translationEnabled
        binding.translationOptions.alpha = if (translationEnabled) ENABLED_ALPHA else DISABLED_ALPHA
        binding.translationDelaySlider.progress = delayMs / TRANSLATION_DELAY_STEP_MS
        binding.translationDelayValue.text = getString(R.string.quick_settings_delay_value, delayMs)
        selectSegment(
            listOf(
                binding.portraitCount3 to 3,
                binding.portraitCount4 to 4,
                binding.portraitCount5 to 5,
            ),
            candidates.compactCandidateCount.getValue(),
        )
        selectSegment(
            listOf(
                binding.landscapeCount5 to 5,
                binding.landscapeCount6 to 6,
                binding.landscapeCount7 to 7,
                binding.landscapeCount8 to 8,
            ),
            candidates.compactCandidateCountLandscape.getValue(),
        )
        updatingUi = false
        if (reschedulePreview) schedulePreview()
    }

    private fun renderFeedbackSetting() {
        if (viewBinding == null) return
        val preset = prefs.keyboard.feedbackPreset.getValue()
        selectSegment(
            listOf(
                binding.feedbackSilent to AppPrefs.Keyboard.FeedbackPreset.SILENT,
                binding.feedbackSoftSound to AppPrefs.Keyboard.FeedbackPreset.SOFT_SOUND,
                binding.feedbackSoftHaptic to AppPrefs.Keyboard.FeedbackPreset.SOFT_HAPTIC,
                binding.feedbackSoundHaptic to AppPrefs.Keyboard.FeedbackPreset.SOUND_HAPTIC,
            ),
            preset,
        )
        binding.customFeedbackAction.isVisible = preset == AppPrefs.Keyboard.FeedbackPreset.CUSTOM
    }

    private fun renderThemeSetting() {
        if (viewBinding == null) return
        val selectedTheme = ThemeManager.prefs.selectedTheme.getValue()
        val displayName = if (selectedTheme == DEFAULT_THEME_ID) {
            getString(R.string.quick_settings_theme_haohao)
        } else {
            selectedTheme.removeSuffix(".trime")
        }
        binding.themeSummary.text = getString(R.string.quick_settings_theme_current, displayName)
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        binding.previewEnglish.isInvisible = true
        binding.previewIpa.isInvisible = true
        if (!prefs.candidates.bilingualTranslation.getValue()) return

        val delayMs = prefs.candidates.bilingualTranslationDelay.getValue().toLong()
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            val currentBinding = viewBinding ?: return@launch
            currentBinding.previewEnglish.isInvisible = false
            currentBinding.previewIpa.isInvisible = !prefs.candidates.bilingualPhonetic.getValue()
        }
    }

    private fun <T> bindSegmentButtons(
        choices: List<Pair<AppCompatButton, T>>,
        onSelected: (T) -> Unit,
    ) {
        choices.forEach { (button, value) ->
            button.setOnClickListener { onSelected(value) }
        }
    }

    private fun <T> selectSegment(
        choices: List<Pair<AppCompatButton, T>>,
        selected: T,
    ) {
        choices.forEach { (button, value) ->
            button.isSelected = value == selected
        }
    }

    private companion object {
        const val MENU_GROUP = 100
        const val MENU_DEPLOY = 101
        const val MENU_DEVELOPER = 102
        const val MENU_ABOUT = 103
        const val TRANSLATION_DELAY_STEP_MS = 100
        const val TRANSLATION_DELAY_STEPS = 10
        const val ENABLED_ALPHA = 1f
        const val DISABLED_ALPHA = 0.42f
    }
}

class AllSettingsFragment : TopOptionsPreferenceFragment() {

    private fun PreferenceGroup.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, icon = icon) {
            findNavController().navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addDestinationPreference(
                R.string.schemata,
                R.drawable.ic_round_view_list_24,
                NavigationRoute.SchemaList,
            )
            addDestinationPreference(
                R.string.user_dictionary,
                R.drawable.ic_baseline_book_24,
                NavigationRoute.UserDict,
            )
            addDestinationPreference(
                R.string.profile,
                R.drawable.ic_baseline_snippet_folder_24,
                NavigationRoute.Profile,
            )
            addCategory("") {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.general,
                    R.drawable.ic_baseline_tune_24,
                    NavigationRoute.General,
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    NavigationRoute.VirtualKeyboard,
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    NavigationRoute.CandidatesWindow,
                )
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_color_lens_24,
                    NavigationRoute.Theme,
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard_24,
                    NavigationRoute.Clipboard,
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    NavigationRoute.Advanced,
                )
            }
        }
    }
}
