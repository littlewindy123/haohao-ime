/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.Reader

internal fun interface CandidateTranslationRepository {
    fun lookup(text: String): String?
}

internal class TsvCandidateTranslationRepository private constructor(
    private val translations: Map<String, String>,
) : CandidateTranslationRepository {
    override fun lookup(text: String): String? = translations[text]

    companion object {
        fun load(
            onFailure: (Throwable) -> Unit = {},
            readerProvider: () -> Reader,
        ): TsvCandidateTranslationRepository = runCatching {
            val translations = linkedMapOf<String, String>()
            readerProvider().buffered().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith('#')) return@forEach

                    val columns = line.split('\t', limit = 2)
                    if (columns.size != 2) return@forEach

                    val source = columns[0].trim()
                    val translation = columns[1].trim()
                    if (source.isEmpty() || translation.isEmpty()) return@forEach

                    translations[source] = translation
                }
            }
            TsvCandidateTranslationRepository(translations)
        }.getOrElse { error ->
            onFailure(error)
            TsvCandidateTranslationRepository(emptyMap())
        }
    }
}

internal object DemoCandidateTranslationRepository : CandidateTranslationRepository {
    private const val ASSET_PATH = "bilingual_demo_zh_en.tsv"

    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TsvCandidateTranslationRepository.load(
            readerProvider = {
                appContext.assets.open(ASSET_PATH).reader(Charsets.UTF_8)
            },
            onFailure = { error ->
                Timber.e(error, "Failed to load bilingual candidate demo dictionary")
            },
        )
    }

    override fun lookup(text: String): String? = delegate.lookup(text)
}
