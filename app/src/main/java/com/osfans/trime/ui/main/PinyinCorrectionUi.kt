/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.base.PinyinCorrectionSettings
import com.osfans.trime.data.base.applyManagedPinyinCorrectionConfig
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

internal suspend fun applyPinyinCorrectionSettings(
    prefs: AppPrefs,
    rime: RimeSession,
    settings: PinyinCorrectionSettings,
): Result<Unit> {
    val result = withContext(Dispatchers.IO) {
        applyManagedPinyinCorrectionConfig(
            configFile = DataManager.userDataDir.resolve(DataManager.SIMPLIFIED_SCHEMA_CUSTOM_FILE_NAME),
            storedManagedHash = prefs.internal.pinyinCorrectionConfigHash.getValue(),
            settings = settings,
        ) {
            withTimeoutOrNull(CONFIG_DEPLOY_TIMEOUT_MS) {
                confirmSuccessfulDeployment(
                    // updateConfig() marks the runtime PREPARING before starting librime, so the
                    // following runOnReady cannot observe the previous READY state.
                    startDeployment = {
                        rime.runOnReady { updateConfig() }
                    },
                    awaitRuntimeReady = {
                        rime.runOnReady {}
                    },
                )
            } ?: false
        }
    }
    result.onFailure { Timber.e(it, "Unable to apply Pinyin correction settings") }
    return result.map { hash ->
        prefs.pinyin.save(settings)
        prefs.internal.pinyinCorrectionConfigHash.setValue(hash)
    }
}

internal suspend fun confirmSuccessfulDeployment(
    startDeployment: suspend () -> Unit,
    awaitRuntimeReady: suspend () -> Unit,
): Boolean {
    startDeployment()
    awaitRuntimeReady()
    return true
}

private const val CONFIG_DEPLOY_TIMEOUT_MS = 20_000L
