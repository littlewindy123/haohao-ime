// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version embeddedKotlinVersion
    `java-gradle-plugin`
}

group = "com.osfans.trime.build_logic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("androidAppConvention") {
            id = "com.osfans.trime.app-convention"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("dataChecksums") {
            id = "com.osfans.trime.data-checksums"
            implementationClass = "DataChecksumsPlugin"
        }
        register("cedictDictionary") {
            id = "com.osfans.trime.cedict-dictionary"
            implementationClass = "CedictDictionaryPlugin"
        }
        register("nativeAppConvention") {
            id = "com.osfans.trime.native-app-convention"
            implementationClass = "NativeAppConventionPlugin"
        }
        register("nativeCacheHash") {
            id = "com.osfans.trime.native-cache-hash"
            implementationClass = "NativeCacheHashPlugin"
        }
        register("openccData") {
            id = "com.osfans.trime.opencc-data"
            implementationClass = "OpenCCDataPlugin"
        }
    }
}
