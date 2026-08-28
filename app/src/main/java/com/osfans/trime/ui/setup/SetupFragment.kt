// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.osfans.trime.R
import com.osfans.trime.databinding.FragmentSetupBinding
import com.osfans.trime.ui.setup.SetupPage.Companion.isLastPage
import com.osfans.trime.util.serializable

class SetupFragment : Fragment() {
    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable("page")!! }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSetupBinding.inflate(inflater, container, false)
        sync()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    fun sync() {
        if (!::binding.isInitialized) return
        val isDone = page.isDone()
        val isFinalSuccess = isDone && page.isLastPage()
        with(binding) {
            stepIcon.setImageResource(page.getIconRes())
            stepText.text = page.getStepText(requireContext())
            hintText.text = page.getHintText(requireContext())
            doneState.isVisible = isDone
            doneText.setText(
                if (isFinalSuccess) {
                    R.string.setup__all_done
                } else {
                    R.string.setup__step_done
                },
            )
            actionButton.isVisible = !isDone || isFinalSuccess
            actionButton.text =
                if (isFinalSuccess) {
                    getText(R.string.setup__start_typing)
                } else {
                    page.getButtonText(requireContext())
                }
            actionButton.setOnClickListener {
                if (isFinalSuccess) {
                    (requireActivity() as SetupActivity).startTyping()
                } else {
                    page.getButtonAction(requireContext())
                }
            }
        }
    }
}
