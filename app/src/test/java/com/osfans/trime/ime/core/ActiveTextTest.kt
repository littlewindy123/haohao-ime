/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ActiveTextTest :
    StringSpec({
        "committed and composing text do not query the remote editor" {
            for ((type, expected) in listOf(1 to "committed", 2 to "preedit")) {
                resolveActiveText(
                    type,
                    "preedit",
                    "preview",
                    "committed",
                    { error("selection must not be read") },
                    { error("document must not be read") },
                    { error("document must not be read") },
                ) shouldBe expected
            }
        }

        "selected text wins without reading the surrounding document" {
            var reads = 0
            resolveActiveText(
                3,
                "preedit",
                "preview",
                "committed",
                {
                    reads++
                    "selected"
                },
                { error("before") },
                { error("after") },
            ) shouldBe "selected"
            reads shouldBe 1
        }

        "explicit preceding text wins without reading selection or following text" {
            val document = "long document ".repeat(10_000)
            resolveActiveText(
                4,
                "preedit",
                "preview",
                "committed",
                { error("selection") },
                { document },
                { error("after") },
            ) shouldBe document
        }

        "empty preferred values preserve the original fallback order" {
            for (preferred in 0..4) {
                val reads = mutableListOf<String>()
                resolveActiveText(
                    preferred,
                    "",
                    "",
                    "",
                    {
                        reads += "selected"
                        null
                    },
                    {
                        reads += "before"
                        ""
                    },
                    {
                        reads += "after"
                        "following"
                    },
                ) shouldBe "following"
                reads.count { it == "selected" } shouldBe 1
                reads.count { it == "before" } shouldBe 1
                reads.last() shouldBe "after"
            }
            resolveActiveText(
                2,
                "",
                "preview",
                "committed",
                { error("selection") },
                { error("before") },
                { error("after") },
            ) shouldBe "preview"
            resolveActiveText(
                2,
                "",
                "",
                "committed",
                { "selected" },
                { error("before") },
                { error("after") },
            ) shouldBe "selected"
            resolveActiveText(
                2,
                "",
                "",
                "committed",
                { null },
                { error("before") },
                { error("after") },
            ) shouldBe "committed"
        }

        "unavailable editor and empty input return empty text" {
            resolveActiveText(3, "", null, "", { null }, { null }, { null }) shouldBe ""
        }

        "only referenced format arguments are read and repeated arguments share one read" {
            val reads = mutableListOf<Int>()
            expandActiveTextArgument("%3\$s / %3\$s") {
                reads += it
                "selected"
            } shouldBe "selected / selected"
            reads shouldBe listOf(3)
            reads.clear()
            expandActiveTextArgument("%s") {
                reads += it
                "committed"
            } shouldBe "committed"
            reads shouldBe listOf(1)
        }

        "ordinary arguments and escaped placeholders never read editor text" {
            expandActiveTextArgument("clipboard") { error("unexpected read") } shouldBe "clipboard"
            expandActiveTextArgument("%%s") { error("unexpected read") } shouldBe "%s"
            expandActiveTextArgument("%%3\$s") { error("unexpected read") } shouldBe "%3\$s"
        }

        "multiline formats preserve positional ordinary and relative argument semantics" {
            expandActiveTextArgument("%3\$s\n%s %s %<s %%") { "text$it" } shouldBe
                "text3\ntext1 text2 text2 %"
        }

        "mixed general conversions retain String formatter behavior" {
            val format = "%1\$h %1\$s %2\$S %3\$b %4\$.3s"
            val values = arrayOf("committed", "preedit", "selected", "preceding")
            expandActiveTextArgument(format) { values[it - 1] } shouldBe format.format(*values)
        }
    })
