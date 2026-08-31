/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.translation.AliyunTranslationCredentials
import com.osfans.trime.data.translation.AliyunTranslationProvider
import com.osfans.trime.data.translation.CloudTranslationConfigStore
import com.osfans.trime.data.translation.CloudTranslationProvider
import com.osfans.trime.data.translation.CloudTranslationProviderType
import com.osfans.trime.data.translation.CloudTranslationRequest
import com.osfans.trime.data.translation.CloudTranslationResult
import com.osfans.trime.data.translation.CustomTranslationCredentials
import com.osfans.trime.data.translation.CustomTranslationProvider
import com.osfans.trime.data.translation.HaoHaoTranslationProvider
import com.osfans.trime.data.translation.TRANSLATION_REQUEST_TIMEOUT_MS
import com.osfans.trime.data.translation.TranslationPurpose
import com.osfans.trime.data.translation.isAllowedTranslationEndpoint
import com.osfans.trime.databinding.FragmentCloudTranslationSettingsBinding
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class CloudTranslationSettingsFragment : Fragment(R.layout.fragment_cloud_translation_settings) {
    private val prefs = AppPrefs.defaultInstance()
    private val config = CloudTranslationConfigStore()
    private var viewBinding: FragmentCloudTranslationSettingsBinding? = null
    private val binding: FragmentCloudTranslationSettingsBinding
        get() = requireNotNull(viewBinding)
    private var selectedProvider = CloudTranslationProviderType.HAOHAO
    private var testing = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding = FragmentCloudTranslationSettingsBinding.bind(view)
        selectedProvider = config.activeProvider()
        binding.customEndpoint.setText(config.custom()?.endpoint.orEmpty())
        binding.aliyunAccessKeyId.setText(config.aliyun()?.accessKeyId.orEmpty())
        binding.candidateFallbackSwitch.isChecked = prefs.cloudTranslation.candidateFallback.getValue()
        binding.providerPublic.setOnClickListener { selectProvider(CloudTranslationProviderType.HAOHAO) }
        binding.providerAliyun.setOnClickListener { selectProvider(CloudTranslationProviderType.ALIYUN) }
        binding.providerCustom.setOnClickListener { selectProvider(CloudTranslationProviderType.CUSTOM) }
        binding.testApplyButton.setOnClickListener { ensureConsent(::testAndApply) }
        binding.candidateFallbackSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!enabled) {
                prefs.cloudTranslation.candidateFallback.setValue(false)
            } else if (prefs.cloudTranslation.consentGranted.getValue()) {
                prefs.cloudTranslation.candidateFallback.setValue(true)
            } else {
                binding.candidateFallbackSwitch.isChecked = false
                ensureConsent {
                    prefs.cloudTranslation.candidateFallback.setValue(true)
                    binding.candidateFallbackSwitch.isChecked = true
                }
            }
        }
        renderProvider()
    }

    override fun onDestroyView() {
        viewBinding = null
        super.onDestroyView()
    }

    private fun selectProvider(provider: CloudTranslationProviderType) {
        if (testing || selectedProvider == provider) return
        selectedProvider = provider
        renderProvider()
    }

    private fun renderProvider() {
        if (viewBinding == null) return
        binding.providerPublic.isSelected = selectedProvider == CloudTranslationProviderType.HAOHAO
        binding.providerAliyun.isSelected = selectedProvider == CloudTranslationProviderType.ALIYUN
        binding.providerCustom.isSelected = selectedProvider == CloudTranslationProviderType.CUSTOM
        binding.publicSection.isVisible = selectedProvider == CloudTranslationProviderType.HAOHAO
        binding.aliyunSection.isVisible = selectedProvider == CloudTranslationProviderType.ALIYUN
        binding.customSection.isVisible = selectedProvider == CloudTranslationProviderType.CUSTOM
        binding.testApplyButton.isEnabled = !testing && (
            selectedProvider != CloudTranslationProviderType.HAOHAO ||
                BuildConfig.HAOHAO_TRANSLATION_BASE_URL.isNotBlank()
            )
        binding.statusText.setText(
            when {
                testing -> R.string.cloud_translation_status_testing
                selectedProvider == CloudTranslationProviderType.HAOHAO &&
                    BuildConfig.HAOHAO_TRANSLATION_BASE_URL.isBlank() -> R.string.cloud_translation_status_preparing
                selectedProvider == config.activeProvider() && providerIsConfigured(selectedProvider) ->
                    R.string.cloud_translation_status_configured
                else -> R.string.cloud_translation_status_not_configured
            },
        )
    }

    private fun providerIsConfigured(provider: CloudTranslationProviderType): Boolean = when (provider) {
        CloudTranslationProviderType.HAOHAO -> BuildConfig.HAOHAO_TRANSLATION_BASE_URL.isNotBlank()
        CloudTranslationProviderType.ALIYUN -> config.aliyun() != null
        CloudTranslationProviderType.CUSTOM -> config.custom() != null
    }

    private fun ensureConsent(action: () -> Unit) {
        if (prefs.cloudTranslation.consentGranted.getValue()) {
            action()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cloud_translation_consent_title)
            .setMessage(R.string.cloud_translation_consent_message)
            .setPositiveButton(R.string.cloud_translation_consent_confirm) { _, _ ->
                prefs.cloudTranslation.consentGranted.setValue(true)
                action()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testAndApply() {
        val candidate = buildCandidateProvider() ?: return
        testing = true
        setFormEnabled(false)
        renderProvider()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                withTimeout(TRANSLATION_REQUEST_TIMEOUT_MS.toLong()) {
                    candidate.provider.translate(
                        CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK, "timeout")
            }
            if (viewBinding == null) return@launch
            testing = false
            setFormEnabled(true)
            when (result) {
                is CloudTranslationResult.Success -> {
                    candidate.apply()
                    binding.statusText.setText(R.string.cloud_translation_status_success)
                }
                is CloudTranslationResult.Failure -> {
                    binding.statusText.setText(errorMessage(result.kind))
                }
            }
            renderSelectionOnly()
        }
    }

    private fun buildCandidateProvider(): CandidateProvider? = when (selectedProvider) {
        CloudTranslationProviderType.HAOHAO -> {
            val url = BuildConfig.HAOHAO_TRANSLATION_BASE_URL
            if (url.isBlank()) {
                binding.statusText.setText(R.string.cloud_translation_status_preparing)
                null
            } else {
                CandidateProvider(
                    provider = HaoHaoTranslationProvider(url, config.installId()),
                    apply = config::selectPublic,
                )
            }
        }
        CloudTranslationProviderType.ALIYUN -> {
            val id = binding.aliyunAccessKeyId.text?.toString()?.trim().orEmpty()
            val secret = binding.aliyunAccessKeySecret.text?.toString()?.trim().orEmpty()
            val existing = config.aliyun()
            val credentials = when {
                id.isEmpty() && secret.isEmpty() -> existing
                id.isNotEmpty() && secret.isNotEmpty() -> AliyunTranslationCredentials(id, secret)
                else -> null
            }
            if (credentials == null) {
                binding.statusText.setText(R.string.cloud_translation_required_fields)
                null
            } else {
                CandidateProvider(
                    provider = AliyunTranslationProvider(credentials.accessKeyId, credentials.accessKeySecret),
                    apply = {
                        config.saveAliyun(credentials)
                        binding.aliyunAccessKeySecret.text?.clear()
                    },
                )
            }
        }
        CloudTranslationProviderType.CUSTOM -> {
            val endpoint = binding.customEndpoint.text?.toString()?.trim().orEmpty()
            if (!isAllowedTranslationEndpoint(endpoint, BuildConfig.DEBUG)) {
                binding.statusText.setText(
                    if (endpoint.startsWith("http://", ignoreCase = true)) {
                        R.string.cloud_translation_https_required
                    } else {
                        R.string.cloud_translation_required_fields
                    },
                )
                null
            } else {
                val enteredToken = binding.customToken.text?.toString()?.trim().orEmpty()
                val existing = config.custom()
                val token = enteredToken.takeIf(String::isNotEmpty)
                    ?: existing?.bearerToken.takeIf { existing?.endpoint == endpoint }
                val credentials = CustomTranslationCredentials(endpoint, token)
                CandidateProvider(
                    provider = CustomTranslationProvider(endpoint, token),
                    apply = {
                        config.saveCustom(credentials)
                        binding.customToken.text?.clear()
                    },
                )
            }
        }
    }

    private fun setFormEnabled(enabled: Boolean) {
        listOf(
            binding.providerPublic,
            binding.providerAliyun,
            binding.providerCustom,
            binding.aliyunAccessKeyId,
            binding.aliyunAccessKeySecret,
            binding.customEndpoint,
            binding.customToken,
            binding.candidateFallbackSwitch,
        ).forEach { it.isEnabled = enabled }
        binding.testApplyButton.isEnabled = enabled
    }

    private fun renderSelectionOnly() {
        binding.providerPublic.isSelected = selectedProvider == CloudTranslationProviderType.HAOHAO
        binding.providerAliyun.isSelected = selectedProvider == CloudTranslationProviderType.ALIYUN
        binding.providerCustom.isSelected = selectedProvider == CloudTranslationProviderType.CUSTOM
        binding.testApplyButton.isEnabled = selectedProvider != CloudTranslationProviderType.HAOHAO ||
            BuildConfig.HAOHAO_TRANSLATION_BASE_URL.isNotBlank()
    }

    private fun errorMessage(kind: CloudTranslationResult.Failure.Kind): Int = when (kind) {
        CloudTranslationResult.Failure.Kind.AUTHENTICATION -> R.string.cloud_translation_error_auth
        CloudTranslationResult.Failure.Kind.RATE_LIMITED -> R.string.cloud_translation_error_rate
        CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED -> R.string.cloud_translation_error_quota
        CloudTranslationResult.Failure.Kind.INVALID_RESPONSE -> R.string.cloud_translation_error_response
        CloudTranslationResult.Failure.Kind.NOT_CONFIGURED,
        CloudTranslationResult.Failure.Kind.INVALID_REQUEST,
        -> R.string.cloud_translation_required_fields
        else -> R.string.cloud_translation_error_network
    }

    private data class CandidateProvider(
        val provider: CloudTranslationProvider,
        val apply: () -> Unit,
    )
}
