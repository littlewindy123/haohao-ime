// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.translation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Properties
import kotlin.system.measureTimeMillis

/** Explicit opt-in only: no credentials or network calls in normal CI, unit tests or the APK. */
class InternalCloudLiveSmokeTest :
    StringSpec(
        smoke@{
            if (System.getenv("HAOHAO_RUN_LIVE_CLOUD_TESTS") != "true") return@smoke
            val secrets = Properties().apply {
                File(requireNotNull(System.getenv("HAOHAO_INTERNAL_CLOUD_SECRETS_FILE"))).inputStream().use(::load)
            }
            fun aliyun() = AliyunTranslationProvider(secrets.getProperty("ALIYUN_ACCESS_KEY_ID"), secrets.getProperty("ALIYUN_ACCESS_KEY_SECRET"))
            fun baidu() = BaiduTranslationProvider(secrets.getProperty("BAIDU_API_KEY"), secrets.getProperty("BAIDU_SECRET_KEY"))
            val providers = mapOf(
                "Aliyun" to aliyun(),
                "Baidu" to baidu(),
                "Dual" to DirectDualCloudTranslationProvider(aliyun(), baidu()),
            )
            for ((label, provider) in providers) {
                "$label accepts the authorized test credentials" {
                    var succeeded = false
                    var outcome = "not_started"
                    val elapsed = measureTimeMillis {
                        when (val result = executeTranslationRequest(provider, CloudTranslationRequest(listOf("你好"), TranslationPurpose.SENTENCE))) {
                            is CloudTranslationResult.Success -> {
                                succeeded = result.translations.singleOrNull()?.isNotBlank() == true
                                outcome = if (succeeded) "SUCCESS" else "EMPTY_RESULT"
                            }
                            is CloudTranslationResult.Failure -> outcome = result.kind.name
                        }
                    }
                    println("LIVE_CLOUD $label outcome=$outcome elapsedMs=$elapsed")
                    succeeded shouldBe true
                }
            }
        },
    )
