// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.setup

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import com.osfans.trime.R
import com.osfans.trime.util.InputMethodUtils

enum class SetupPage {
    Enable,
    Select,
    ;

    fun getStepText(context: Context) = context.getText(
        when (this) {
            Enable -> R.string.setup__step_one
            Select -> R.string.setup__step_two
        },
    )

    fun getHintText(context: Context) = context.getText(
        when (this) {
            Enable -> R.string.setup__enable_ime_hint
            Select -> R.string.setup__select_ime_hint
        },
    )

    fun getButtonText(context: Context) = context.getText(
        when (this) {
            Enable -> R.string.setup__enable_ime
            Select -> R.string.setup__select_ime
        },
    )

    @DrawableRes
    fun getIconRes() = when (this) {
        Enable -> R.drawable.ic_baseline_keyboard_24
        Select -> R.drawable.ic_input_box
    }

    fun getButtonAction(context: Context) {
        when (this) {
            Enable -> InputMethodUtils.showImeEnablerActivity(context)
            Select -> InputMethodUtils.showImePicker()
        }
    }

    fun isDone() = when (this) {
        Enable -> InputMethodUtils.checkIsTrimeEnabled()
        Select -> InputMethodUtils.checkIsTrimeSelected()
    }

    companion object {
        fun SetupPage.isLastPage() = this == entries.last()

        fun Int.isLastPage() = this == entries.size - 1

        fun hasUndonePage() = entries.any { !it.isDone() }

        fun firstUndonePage() = entries.firstOrNull { !it.isDone() }
    }
}

internal object SetupFlow {
    fun progressStep(currentIndex: Int) = currentIndex + 1

    fun firstUndoneIndex(doneStates: List<Boolean>) = doneStates.indexOfFirst { !it }.takeIf { it >= 0 }

    fun nextIndexAfterSync(
        currentIndex: Int,
        wasDone: Boolean,
        isDone: Boolean,
        doneStates: List<Boolean>,
    ): Int? {
        if (wasDone || !isDone || currentIndex !in doneStates.indices || currentIndex == doneStates.lastIndex) {
            return null
        }
        return ((currentIndex + 1)..doneStates.lastIndex).firstOrNull { !doneStates[it] }
            ?: doneStates.lastIndex
    }

    fun shouldAutoOpenPicker(
        currentIndex: Int,
        wasDone: Boolean,
        isDone: Boolean,
        doneStates: List<Boolean>,
    ): Boolean = currentIndex == SetupPage.Enable.ordinal &&
        !wasDone &&
        isDone &&
        doneStates.getOrNull(SetupPage.Select.ordinal) == false
}

internal object SetupLaunchPolicy {
    fun shouldOpenSetup(
        action: String?,
        hasTestInputRequest: Boolean,
        hasUndonePage: Boolean,
    ): Boolean = hasUndonePage && !hasTestInputRequest && action != Intent.ACTION_RUN
}
