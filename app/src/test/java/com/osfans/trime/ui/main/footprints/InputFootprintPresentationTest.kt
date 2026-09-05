/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.footprints

import com.osfans.trime.data.footprints.InputFootprintEntity
import com.osfans.trime.ime.candidates.bilingual.CandidateTranslationEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class InputFootprintPresentationTest :
    FunSpec({
        val footprints = listOf(
            InputFootprintEntity("你好"),
            InputFootprintEntity("电脑"),
            InputFootprintEntity("损坏词"),
        )
        val entries = mapOf(
            "你好" to CandidateTranslationEntry("hello", "/həˈloʊ/"),
            "电脑" to CandidateTranslationEntry("computer", "/kəmˈpjuːtər/"),
        )

        test("searches Chinese and English without changing source order") {
            filterInputFootprints(footprints, "电脑", entries::get).map { it.footprint.text } shouldContainExactly listOf("电脑")
            filterInputFootprints(footprints, "hello", entries::get).map { it.footprint.text } shouldContainExactly listOf("你好")
        }

        test("missing meanings retain history without inventing an English answer") {
            filterInputFootprints(footprints, "", entries::get).map { it.footprint.text } shouldContainExactly listOf("你好", "电脑", "损坏词")
        }
    })
