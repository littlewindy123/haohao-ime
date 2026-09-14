/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import java.io.File

/** Guard the daily debug identity against accidentally shipping an unoptimized, text-logging engine. */
class NativeTypingBuildPolicyTest :
    StringSpec({
        "native debug builds optimize before adding dependency targets" {
            val cmake = File("src/main/jni/CMakeLists.txt").readText()
            cmake shouldContain "option(HAOHAO_OPTIMIZE_DEBUG_NATIVE"
            cmake shouldContain "Debug builds\" ON)"
            cmake shouldContain "<CONFIG:Debug>:-O2"
            cmake shouldContain "<CONFIG:Debug>:NDEBUG"
            cmake.indexOf("add_compile_options") shouldBeLessThan cmake.indexOf("add_subdirectory")
        }

        "native routine text logging is disabled before engine setup" {
            val native = File("src/main/jni/librime_jni/rime_jni.cc").readText()
            native shouldContain "trime_traits.min_log_level = 2;"
            native.indexOf("trime_traits.min_log_level = 2;") shouldBeLessThan native.indexOf("rime->setup(&trime_traits)")
        }
    })
