// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.footprints
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LearningRewardsTest : StringSpec({
    "rewards require real completed days and work at exact boundaries" {
        earnedLearningMilestones(0) shouldBe emptyList()
        earnedLearningMilestones(7) shouldBe listOf(1, 3, 7)
        nextLearningMilestone(7) shouldBe 14
        nextLearningMilestone(100) shouldBe null
    }
    "undo and clearing recompute rewards without persistent phantom unlocks" {
        earnedLearningMilestones(6) shouldBe listOf(1, 3)
        earnedLearningMilestones(-1) shouldBe emptyList()
        nextLearningMilestone(0) shouldBe 1
    }
})
