/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import com.osfans.trime.data.prefs.AppPrefs.Keyboard.FeedbackPreset
import com.osfans.trime.data.prefs.AppPrefs.Keyboard.FeedbackSettings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyboardFeedbackPresetTest :
    StringSpec({
        "soft sound is the default profile" {
            FeedbackPreset.SOFT_SOUND.settings shouldBe FeedbackSettings(
                soundEnabled = true,
                soundVolume = 10,
                customSoundEnabled = false,
                vibrationEnabled = false,
                vibrateOnRelease = false,
                vibrateOnRepeat = false,
                vibrationDuration = 0,
                vibrationAmplitude = 0,
            )
        }

        "built-in profiles resolve deterministically" {
            FeedbackPreset.resolve(requireNotNull(FeedbackPreset.SILENT.settings)) shouldBe FeedbackPreset.SILENT
            FeedbackPreset.resolve(requireNotNull(FeedbackPreset.SOFT_SOUND.settings)) shouldBe FeedbackPreset.SOFT_SOUND
            FeedbackPreset.resolve(requireNotNull(FeedbackPreset.SOFT_HAPTIC.settings)) shouldBe FeedbackPreset.SOFT_HAPTIC
            FeedbackPreset.resolve(requireNotNull(FeedbackPreset.SOUND_HAPTIC.settings)) shouldBe FeedbackPreset.SOUND_HAPTIC
        }

        "advanced values resolve to custom" {
            FeedbackPreset.resolve(
                requireNotNull(FeedbackPreset.SOFT_SOUND.settings).copy(soundVolume = 35),
            ) shouldBe FeedbackPreset.CUSTOM
            FeedbackPreset.resolve(
                requireNotNull(FeedbackPreset.SOFT_HAPTIC.settings).copy(vibrationDuration = 12),
            ) shouldBe FeedbackPreset.CUSTOM
        }

        "disabled channels ignore dormant advanced values" {
            FeedbackPreset.resolve(
                FeedbackSettings(
                    soundEnabled = false,
                    soundVolume = 87,
                    customSoundEnabled = true,
                    vibrationEnabled = false,
                    vibrateOnRelease = true,
                    vibrateOnRepeat = true,
                    vibrationDuration = 44,
                    vibrationAmplitude = 200,
                ),
            ) shouldBe FeedbackPreset.SILENT
        }
    })
