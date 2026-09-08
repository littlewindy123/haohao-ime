// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.footprints

/** Derived from valid completed tasks, so undo/clear cannot leave fictitious rewards behind. */
internal val learningMilestones = listOf(1, 3, 7, 14, 30, 100)
internal fun earnedLearningMilestones(checkins: Int) = learningMilestones.filter { it <= checkins.coerceAtLeast(0) }
internal fun nextLearningMilestone(checkins: Int) = learningMilestones.firstOrNull { it > checkins.coerceAtLeast(0) }
