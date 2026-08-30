/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.base.PinyinCorrectionSettings
import com.osfans.trime.data.base.applyManagedPinyinCorrectionConfig
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
                rime.runOnReady {
                    coroutineScope {
                        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                            messageFlow
                                .filterIsInstance<RimeMessage.DeployMessage>()
                                .first { it.data != RimeMessage.DeployMessage.State.Start }
                                .data == RimeMessage.DeployMessage.State.Success
                        }
                        updateConfig()
                        outcome.await()
                    }
                }
            } ?: false
        }
    }
    return result.map { hash ->
        prefs.pinyin.save(settings)
        prefs.internal.pinyinCorrectionConfigHash.setValue(hash)
    }
}

private const val CONFIG_DEPLOY_TIMEOUT_MS = 20_000L
