/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.base.PinyinCorrectionSettings
import com.osfans.trime.data.base.PinyinFuzzyPair
import com.osfans.trime.data.base.isManagedPinyinCorrectionConfig
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.databinding.FragmentPinyinFuzzyBinding
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch

class PinyinFuzzyFragment : Fragment(R.layout.fragment_pinyin_fuzzy) {
    private val prefs = AppPrefs.defaultInstance()
    private val viewModel: MainViewModel by activityViewModels()

    private var viewBinding: FragmentPinyinFuzzyBinding? = null
    private val binding get() = requireNotNull(viewBinding)
    private val pairSwitches = mutableMapOf<PinyinFuzzyPair, SwitchCompat>()
    private var draft = PinyinCorrectionSettings.DEFAULT
    private var updatingUi = false
    private var managedConfig = true
    private var applying = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding = FragmentPinyinFuzzyBinding.bind(view)
        draft = prefs.pinyin.settings()
        managedConfig = currentConfigIsManaged()
        createPairRows()
        binding.fuzzyMasterSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                draft = draft.copy(fuzzyEnabled = checked)
                render()
            }
        }
        binding.applyButton.setOnClickListener { applyDraft() }
        render()
        viewModel.disableTopOptionsMenu()
    }

    override fun onDestroyView() {
        pairSwitches.clear()
        viewBinding = null
        super.onDestroyView()
    }

    private fun createPairRows() {
        pairMetadata.forEach { metadata ->
            val pairSwitch = SwitchCompat(requireContext()).apply {
                minWidth = dp(48)
                minHeight = dp(48)
                gravity = Gravity.CENTER
                setOnCheckedChangeListener { _, checked ->
                    if (!updatingUi) {
                        draft = draft.copy(
                            fuzzyPairs = draft.fuzzyPairs.toMutableSet().apply {
                                if (checked) add(metadata.pair) else remove(metadata.pair)
                            },
                        )
                        render()
                    }
                }
            }
            pairSwitches[metadata.pair] = pairSwitch

            val labels = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        setText(metadata.title)
                        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_HaoHao_SettingTitle)
                    },
                )
                addView(
                    TextView(context).apply {
                        text = getString(R.string.pinyin_fuzzy_example, metadata.example)
                        setTextColor(ContextCompat.getColor(context, R.color.haohao_cocoa_secondary))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    },
                )
            }
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                isClickable = true
                isFocusable = true
                val selectable = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                setBackgroundResource(selectable.resourceId)
                contentDescription = getString(metadata.title)
                setOnClickListener { if (pairSwitch.isEnabled) pairSwitch.toggle() }
                addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(pairSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
            }
            binding.fuzzyOptions.addView(row)
            binding.fuzzyOptions.addView(
                View(requireContext()).apply {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.haohao_divider))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)),
            )
        }
    }

    private fun applyDraft() {
        if (applying || !managedConfig) return
        applying = true
        render()
        requireActivity().lifecycleScope.launch {
            try {
                applyPinyinCorrectionSettings(prefs, viewModel.rime, draft)
                    .onSuccess {
                        managedConfig = true
                        context?.toast(R.string.pinyin_correction_apply_success)
                    }.onFailure {
                        draft = prefs.pinyin.settings()
                        managedConfig = currentConfigIsManaged()
                        context?.toast(R.string.pinyin_correction_apply_failed)
                    }
            } finally {
                applying = false
                if (viewBinding != null) render()
            }
        }
    }

    private fun render() {
        if (viewBinding == null) return
        updatingUi = true
        binding.fuzzyMasterSwitch.isChecked = draft.fuzzyEnabled
        binding.fuzzyMasterSwitch.isEnabled = managedConfig && !applying
        pairSwitches.forEach { (pair, switch) ->
            switch.isChecked = pair in draft.fuzzyPairs
            switch.isEnabled = managedConfig && draft.fuzzyEnabled && !applying
        }
        binding.fuzzyOptions.alpha = if (draft.fuzzyEnabled && managedConfig) 1f else 0.42f
        binding.customConfigNotice.isVisible = !managedConfig
        binding.applyButton.isEnabled = managedConfig && !applying && draft != prefs.pinyin.settings()
        binding.applyButton.text = if (applying) {
            getString(R.string.pinyin_correction_applying)
        } else {
            getString(R.string.pinyin_fuzzy_apply)
        }
        updatingUi = false
    }

    private fun currentConfigIsManaged(): Boolean = isManagedPinyinCorrectionConfig(
        DataManager.userDataDir.resolve(DataManager.SIMPLIFIED_SCHEMA_CUSTOM_FILE_NAME),
        prefs.internal.pinyinCorrectionConfigHash.getValue(),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PairMetadata(
        val pair: PinyinFuzzyPair,
        val title: Int,
        val example: String,
    )

    private companion object {
        val pairMetadata = listOf(
            PairMetadata(PinyinFuzzyPair.S_SH, R.string.pinyin_fuzzy_s_sh, "si / shi"),
            PairMetadata(PinyinFuzzyPair.C_CH, R.string.pinyin_fuzzy_c_ch, "ci / chi"),
            PairMetadata(PinyinFuzzyPair.Z_ZH, R.string.pinyin_fuzzy_z_zh, "zi / zhi"),
            PairMetadata(PinyinFuzzyPair.L_N, R.string.pinyin_fuzzy_l_n, "lan / nan"),
            PairMetadata(PinyinFuzzyPair.F_H, R.string.pinyin_fuzzy_f_h, "fu / hu"),
            PairMetadata(PinyinFuzzyPair.R_L, R.string.pinyin_fuzzy_r_l, "ran / lan"),
            PairMetadata(PinyinFuzzyPair.AN_ANG, R.string.pinyin_fuzzy_an_ang, "fan / fang"),
            PairMetadata(PinyinFuzzyPair.EN_ENG, R.string.pinyin_fuzzy_en_eng, "fen / feng"),
            PairMetadata(PinyinFuzzyPair.IN_ING, R.string.pinyin_fuzzy_in_ing, "lin / ling"),
            PairMetadata(PinyinFuzzyPair.IAN_IANG, R.string.pinyin_fuzzy_ian_iang, "xian / xiang"),
            PairMetadata(PinyinFuzzyPair.UAN_UANG, R.string.pinyin_fuzzy_uan_uang, "guan / guang"),
        )
    }
}
