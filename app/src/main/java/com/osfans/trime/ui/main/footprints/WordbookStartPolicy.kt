// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.footprints

internal enum class WordbookViewMode { BROWSE, SELECT, MANAGE }

internal fun wordbookViewMode(value: String?): WordbookViewMode =
    WordbookViewMode.entries.firstOrNull { it.name == value } ?: WordbookViewMode.BROWSE

internal enum class WordbookStartAction { SELECT, START, RESUME }

internal fun wordbookStartAction(hasSession: Boolean, learningCount: Int): WordbookStartAction = when {
    hasSession -> WordbookStartAction.RESUME
    learningCount > 0 -> WordbookStartAction.START
    else -> WordbookStartAction.SELECT
}

internal enum class DailyStartDestination { SETTINGS, REVIEW, HOME }

/** Unsaved form state only; rotating a confirmation screen must not accept its settings. */
internal data class DailyPlanDraft(val enabled: Boolean, val fresh: String, val reviews: String, val modeName: String)

/** Navigation only: a book never replaces the shared daily task or starts extra learning. */
internal fun dailyStartDestination(
    planEnabled: Boolean,
    hasSession: Boolean,
    hasTask: Boolean,
    remainingTargets: Int,
    plannedWords: Int,
): DailyStartDestination = when {
    !planEnabled -> DailyStartDestination.SETTINGS
    hasSession -> DailyStartDestination.REVIEW
    hasTask -> if (remainingTargets > 0) DailyStartDestination.REVIEW else DailyStartDestination.HOME
    plannedWords > 0 -> DailyStartDestination.REVIEW
    else -> DailyStartDestination.HOME
}
