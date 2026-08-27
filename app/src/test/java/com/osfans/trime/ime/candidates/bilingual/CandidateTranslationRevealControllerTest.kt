/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class CandidateTranslationRevealControllerTest :
    StringSpec({
        "waits for stable candidates and ignores a stale delayed task" {
            var enabled = true
            var delayMillis = 300
            var lookups = 0
            val scheduler = FakeDelayScheduler()
            val controller = controller(scheduler, { enabled }, { delayMillis })
            val states = mutableListOf<CandidateTranslationRevealState>()
            controller.addListener { states += it }
            val presenter =
                BilingualCandidatePresenter(
                    repository = CandidateTranslationRepository {
                        lookups += 1
                        CandidateTranslationEntry("hello", null)
                    },
                    isEnabled = { enabled },
                    revealState = { controller.state },
                )

            controller.onCandidateListUpdate(candidates("你"))

            controller.state shouldBe CandidateTranslationRevealState.PENDING
            scheduler.tasks.single().delayMillis shouldBe 300L
            presenter.present(candidate("你")).translation shouldBe null
            lookups shouldBe 0

            controller.onCandidateListUpdate(candidates("你好"))
            scheduler.tasks[0].cancelled shouldBe true
            scheduler.run(index = 0, includeCancelled = true)
            controller.state shouldBe CandidateTranslationRevealState.PENDING

            scheduler.run(index = 1)
            controller.state shouldBe CandidateTranslationRevealState.READY
            presenter.present(candidate("你好")).translation shouldBe "hello"
            lookups shouldBe 1
            states.shouldContainExactly(
                CandidateTranslationRevealState.PENDING,
                CandidateTranslationRevealState.PENDING,
                CandidateTranslationRevealState.READY,
            )
        }

        "zero delay reveals immediately and maximum delay stays configurable" {
            var delayMillis = 0
            val scheduler = FakeDelayScheduler()
            val controller = controller(scheduler, { true }, { delayMillis })

            controller.onCandidateListUpdate(candidates("你好"))

            controller.state shouldBe CandidateTranslationRevealState.READY
            scheduler.tasks.size shouldBe 0

            delayMillis = BILINGUAL_TRANSLATION_DELAY_MAX_MS
            controller.onCandidateListUpdate(candidates("中国"))

            controller.state shouldBe CandidateTranslationRevealState.PENDING
            scheduler.tasks.single().delayMillis shouldBe 1_000L
        }

        "disabling translations or clearing candidates cancels pending reveal" {
            var enabled = true
            val scheduler = FakeDelayScheduler()
            val controller = controller(scheduler, { enabled }, { 300 })

            controller.onCandidateListUpdate(candidates("你好"))
            enabled = false
            controller.onPreferencesChanged()

            controller.state shouldBe CandidateTranslationRevealState.HIDDEN
            scheduler.tasks.single().cancelled shouldBe true
            scheduler.run(index = 0, includeCancelled = true)
            controller.state shouldBe CandidateTranslationRevealState.HIDDEN

            enabled = true
            controller.onCandidateListUpdate(candidates("中国"))
            controller.onCandidateListUpdate(Candidates.Bulk(total = 0))

            controller.state shouldBe CandidateTranslationRevealState.HIDDEN
            scheduler.tasks.last().cancelled shouldBe true
        }

        "delay preference defaults to 300 milliseconds in 100 millisecond steps" {
            BILINGUAL_TRANSLATION_DELAY_MIN_MS shouldBe 0
            BILINGUAL_TRANSLATION_DELAY_DEFAULT_MS shouldBe 300
            BILINGUAL_TRANSLATION_DELAY_MAX_MS shouldBe 1_000
            BILINGUAL_TRANSLATION_DELAY_STEP_MS shouldBe 100
        }
    }) {
    private class FakeDelayScheduler : CandidateTranslationDelayScheduler {
        data class Task(
            val delayMillis: Long,
            val block: () -> Unit,
            var cancelled: Boolean = false,
        )

        val tasks = mutableListOf<Task>()

        override fun schedule(
            delayMillis: Long,
            block: () -> Unit,
        ): CandidateTranslationDelayTask {
            val task = Task(delayMillis, block)
            tasks += task
            return CandidateTranslationDelayTask { task.cancelled = true }
        }

        fun run(
            index: Int,
            includeCancelled: Boolean = false,
        ) {
            val task = tasks[index]
            if (includeCancelled || !task.cancelled) task.block()
        }
    }

    companion object {
        private fun controller(
            scheduler: CandidateTranslationDelayScheduler,
            enabled: () -> Boolean,
            delayMillis: () -> Int,
        ) = CandidateTranslationRevealController(
            isTranslationEnabled = enabled,
            delayMillis = delayMillis,
            scheduler = scheduler,
        )

        private fun candidates(vararg text: String) = Candidates.Bulk(
            total = text.size,
            candidates = text.map(::candidate).toTypedArray(),
        )

        private fun candidate(text: String) = CandidateProto(text = text, comment = "", label = "")
    }
}
