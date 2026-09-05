// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

class GeneralStyleTest :
    BehaviorSpec({
        fun decodeTheme(configId: String): Theme {
            val file = File("src/test/assets/$configId.yaml")
            return Theme.decode(requireNotNull(Yaml.parseToYamlNode(file.readText()).mapping))
        }

        Given("Correct trime.yaml") {
            When("loaded") {
                val generalStyle = decodeTheme("trime").generalStyle

                Then("it should not be null") {
                    generalStyle shouldNotBe null
                    generalStyle.autoCaps shouldBe false

                    generalStyle.candidateFont shouldBe emptyList()
                }
            }
        }

        Given("Empty trime.yaml") {
            When("loaded") {
                val generalStyle = decodeTheme("incorrect").generalStyle

                Then("with default value without exception") {
                    generalStyle.autoCaps shouldBe false
                    generalStyle.candidateBorder shouldBe 0
                    generalStyle.candidateFont shouldBe emptyList()
                    generalStyle.keyCapHeight shouldBe 0
                    generalStyle.commentPosition shouldBe GeneralStyle.CommentPosition.RIGHT
                    generalStyle.enterLabel shouldNotBe null
                    generalStyle.enterLabel.go shouldBe "go"
                }
            }
        }
    })
