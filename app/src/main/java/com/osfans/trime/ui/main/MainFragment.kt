/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.ClipData
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.core.RimeRuntimeSnapshot
import com.osfans.trime.core.RimeRuntimeState
import com.osfans.trime.core.statusTextRes
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.databinding.FragmentMainBinding
import com.osfans.trime.ime.candidates.compact.CompactTranslationMode
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim
import com.osfans.trime.util.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.systemservices.clipboardManager

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
    private val translationModeListener =
        PreferenceDelegate.OnChangeListener<CompactTranslationMode> { _, _ ->
            renderCandidateSettings(reschedulePreview = true)
        }
    private val learningHistoryListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        renderLearningSetting()
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
    private val spacebarSlideListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        renderErgonomicsSettings()
    }
    private val backspaceSlideListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        renderErgonomicsSettings()
    }
    private val heightModeListener =
        PreferenceDelegate.OnChangeListener<AppPrefs.Keyboard.KeyboardHeightMode> { _, _ ->
            renderErgonomicsSettings()
        }
    private val oneHandModeListener =
        PreferenceDelegate.OnChangeListener<AppPrefs.Keyboard.OneHandMode> { _, _ ->
            renderErgonomicsSettings()
        }
    private val themeListener = PreferenceDelegate.OnChangeListener<String> { _, _ ->
        renderThemeSetting()
        renderErgonomicsSettings()
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding = FragmentMainBinding.bind(view)
        setupInsets()
        setupHeader()
        setupEngineStatus()
        setupCandidateSettings()
        setupLearningSettings()
        setupErgonomicsSettings()
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
                binding.translationModeWord to CompactTranslationMode.WORD,
                binding.translationModeAdaptive to CompactTranslationMode.ADAPTIVE,
            ),
        ) { prefs.candidates.compactTranslationMode.setValue(it) }

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

    private fun setupEngineStatus() {
        binding.repairEngineButton.setOnClickListener { RimeDaemon.repairRime() }
        binding.copyDiagnosticsButton.setOnClickListener {
            clipboardManager.setPrimaryClip(
                ClipData.newPlainText(
                    getString(R.string.rime_runtime_diagnostics_label),
                    RimeDaemon.diagnosticText(),
                ),
            )
            requireContext().toast(R.string.rime_runtime_diagnostics_copied)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RimeDaemon.runtimeSnapshot.collect(::renderEngineStatus)
            }
        }
    }

    private fun renderEngineStatus(snapshot: RimeRuntimeSnapshot) {
        if (viewBinding == null) return
        val state = RimeDaemon.runtimeState.value
        when (state) {
            RimeRuntimeState.PREPARING -> {
                binding.engineStatusTitle.setText(R.string.rime_runtime_preparing)
                binding.engineStatusSummary.setText(snapshot.phase.statusTextRes)
                binding.repairEngineButton.isVisible = false
            }
            RimeRuntimeState.READY -> {
                binding.engineStatusTitle.setText(R.string.rime_runtime_ready)
                binding.engineStatusSummary.setText(R.string.rime_runtime_ready_summary)
                binding.repairEngineButton.isVisible = false
            }
            RimeRuntimeState.FAILED -> {
                binding.engineStatusTitle.setText(R.string.rime_runtime_failed)
                binding.engineStatusSummary.setText(R.string.rime_runtime_failed_summary)
                binding.repairEngineButton.isVisible = true
            }
        }
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

    private fun setupErgonomicsSettings() {
        binding.spacebarSlideCursorSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) prefs.keyboard.spacebarSlideCursor.setValue(checked)
        }
        binding.backspaceSlideDeleteSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) prefs.keyboard.backspaceSlideDelete.setValue(checked)
        }
        bindSegmentButtons(
            listOf(
                binding.heightCompact to AppPrefs.Keyboard.KeyboardHeightMode.COMPACT,
                binding.heightStandard to AppPrefs.Keyboard.KeyboardHeightMode.STANDARD,
                binding.heightRoomy to AppPrefs.Keyboard.KeyboardHeightMode.ROOMY,
            ),
        ) { prefs.keyboard.heightMode.setValue(it) }
        bindSegmentButtons(
            listOf(
                binding.oneHandOff to AppPrefs.Keyboard.OneHandMode.OFF,
                binding.oneHandLeft to AppPrefs.Keyboard.OneHandMode.LEFT,
                binding.oneHandRight to AppPrefs.Keyboard.OneHandMode.RIGHT,
            ),
        ) { prefs.keyboard.oneHandMode.setValue(it) }
    }

    private fun setupLearningSettings() {
        binding.learningHistorySwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) prefs.candidates.learningHistoryEnabled.setValue(checked)
        }
        val footprintStore = InputFootprints.storeOrNull
        binding.inputFootprintsDestination.isEnabled = footprintStore != null
        binding.inputFootprintsDestination.alpha = if (footprintStore != null) 1f else 0.45f
        binding.inputFootprintsDestination.setOnClickListener {
            if (footprintStore != null) findNavController().navigateWithAnim(NavigationRoute.InputFootprints)
        }
        if (footprintStore == null) {
            binding.inputFootprintsSummary.setText(R.string.optional_feature_unavailable)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                footprintStore.counts.collect { counts ->
                    binding.inputFootprintsSummary.text = getString(
                        R.string.input_footprints_summary,
                        counts.recent,
                        counts.favorites,
                    )
                }
            }
        }
    }

    private fun setupDestinations() {
        binding.cloudTranslationDestination.setOnClickListener {
            findNavController().navigateWithAnim(NavigationRoute.CloudTranslation)
        }
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
        prefs.candidates.compactTranslationMode.registerOnChangeListener(translationModeListener)
        prefs.candidates.learningHistoryEnabled.registerOnChangeListener(learningHistoryListener)
        prefs.candidates.compactCandidateCount.registerOnChangeListener(portraitCountListener)
        prefs.candidates.compactCandidateCountLandscape.registerOnChangeListener(landscapeCountListener)
        prefs.keyboard.feedbackPreset.registerOnChangeListener(feedbackListener)
        prefs.keyboard.spacebarSlideCursor.registerOnChangeListener(spacebarSlideListener)
        prefs.keyboard.backspaceSlideDelete.registerOnChangeListener(backspaceSlideListener)
        prefs.keyboard.heightMode.registerOnChangeListener(heightModeListener)
        prefs.keyboard.oneHandMode.registerOnChangeListener(oneHandModeListener)
        ThemeManager.prefs.selectedTheme.registerOnChangeListener(themeListener)
    }

    private fun unregisterPreferenceListeners() {
        prefs.candidates.bilingualTranslation.unregisterOnChangeListener(translationListener)
        prefs.candidates.bilingualTranslationDelay.unregisterOnChangeListener(delayListener)
        prefs.candidates.bilingualPhonetic.unregisterOnChangeListener(phoneticListener)
        prefs.candidates.compactTranslationMode.unregisterOnChangeListener(translationModeListener)
        prefs.candidates.learningHistoryEnabled.unregisterOnChangeListener(learningHistoryListener)
        prefs.candidates.compactCandidateCount.unregisterOnChangeListener(portraitCountListener)
        prefs.candidates.compactCandidateCountLandscape.unregisterOnChangeListener(landscapeCountListener)
        prefs.keyboard.feedbackPreset.unregisterOnChangeListener(feedbackListener)
        prefs.keyboard.spacebarSlideCursor.unregisterOnChangeListener(spacebarSlideListener)
        prefs.keyboard.backspaceSlideDelete.unregisterOnChangeListener(backspaceSlideListener)
        prefs.keyboard.heightMode.unregisterOnChangeListener(heightModeListener)
        prefs.keyboard.oneHandMode.unregisterOnChangeListener(oneHandModeListener)
        ThemeManager.prefs.selectedTheme.unregisterOnChangeListener(themeListener)
    }

    private fun renderAllSettings(reschedulePreview: Boolean) {
        if (viewBinding == null) return
        renderCandidateSettings(reschedulePreview)
        renderLearningSetting()
        renderErgonomicsSettings()
        renderFeedbackSetting()
        renderThemeSetting()
    }

    private fun renderCandidateSettings(reschedulePreview: Boolean) {
        if (viewBinding == null) return
        val candidates = prefs.candidates
        val translationEnabled = candidates.bilingualTranslation.getValue()
        val translationMode = candidates.compactTranslationMode.getValue()
        val delayMs = candidates.bilingualTranslationDelay.getValue()
        updatingUi = true
        binding.translationSwitch.isChecked = translationEnabled
        binding.phoneticSwitch.isChecked = candidates.bilingualPhonetic.getValue()
        binding.phoneticSwitch.isEnabled = translationEnabled
        binding.translationModeWord.isEnabled = translationEnabled
        binding.translationModeAdaptive.isEnabled = translationEnabled
        binding.translationDelaySlider.isEnabled = translationEnabled
        binding.translationDelayLabel.isEnabled = translationEnabled
        binding.translationDelayValue.isEnabled = translationEnabled
        binding.translationOptions.alpha = if (translationEnabled) ENABLED_ALPHA else DISABLED_ALPHA
        binding.translationDelaySlider.progress = delayMs / TRANSLATION_DELAY_STEP_MS
        binding.translationDelayValue.text = getString(R.string.quick_settings_delay_value, delayMs)
        selectSegment(
            listOf(
                binding.translationModeWord to CompactTranslationMode.WORD,
                binding.translationModeAdaptive to CompactTranslationMode.ADAPTIVE,
            ),
            translationMode,
        )
        val adaptive = translationMode == CompactTranslationMode.ADAPTIVE
        binding.portraitCandidateCountLabel.setText(
            if (adaptive) R.string.quick_settings_portrait_candidate_limit else R.string.quick_settings_portrait_candidate_count,
        )
        binding.landscapeCandidateCountLabel.setText(
            if (adaptive) R.string.quick_settings_landscape_candidate_limit else R.string.quick_settings_landscape_candidate_count,
        )
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

    private fun renderErgonomicsSettings() {
        if (viewBinding == null) return
        val keyboard = prefs.keyboard
        val controlsEnabled = ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID
        updatingUi = true
        binding.spacebarSlideCursorSwitch.isChecked = keyboard.spacebarSlideCursor.getValue()
        binding.backspaceSlideDeleteSwitch.isChecked = keyboard.backspaceSlideDelete.getValue()
        selectSegment(
            listOf(
                binding.heightCompact to AppPrefs.Keyboard.KeyboardHeightMode.COMPACT,
                binding.heightStandard to AppPrefs.Keyboard.KeyboardHeightMode.STANDARD,
                binding.heightRoomy to AppPrefs.Keyboard.KeyboardHeightMode.ROOMY,
            ),
            keyboard.heightMode.getValue(),
        )
        selectSegment(
            listOf(
                binding.oneHandOff to AppPrefs.Keyboard.OneHandMode.OFF,
                binding.oneHandLeft to AppPrefs.Keyboard.OneHandMode.LEFT,
                binding.oneHandRight to AppPrefs.Keyboard.OneHandMode.RIGHT,
            ),
            keyboard.oneHandMode.getValue(),
        )
        ergonomicsControls().forEach { it.isEnabled = controlsEnabled }
        binding.ergonomicsOptions.alpha = if (controlsEnabled) ENABLED_ALPHA else DISABLED_ALPHA
        updatingUi = false
    }

    private fun ergonomicsControls(): List<View> = listOf(
        binding.spacebarSlideCursorSwitch,
        binding.backspaceSlideDeleteSwitch,
        binding.heightCompact,
        binding.heightStandard,
        binding.heightRoomy,
        binding.oneHandOff,
        binding.oneHandLeft,
        binding.oneHandRight,
    )

    private fun renderLearningSetting() {
        if (viewBinding == null) return
        updatingUi = true
        binding.learningHistorySwitch.isChecked = prefs.candidates.learningHistoryEnabled.getValue()
        updatingUi = false
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
        val adaptive =
            prefs.candidates.compactTranslationMode.getValue() == CompactTranslationMode.ADAPTIVE
        binding.previewPinyin.setText(
            if (adaptive) R.string.quick_settings_preview_adaptive_pinyin else R.string.quick_settings_preview_pinyin,
        )
        binding.previewChinese.setText(
            if (adaptive) R.string.quick_settings_preview_adaptive_chinese else R.string.quick_settings_preview_chinese,
        )
        binding.previewEnglish.setText(
            if (adaptive) R.string.quick_settings_preview_adaptive_english else R.string.quick_settings_preview_english,
        )
        binding.previewIpa.setText(
            if (adaptive) R.string.quick_settings_preview_adaptive_ipa else R.string.quick_settings_preview_ipa,
        )
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
