/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("UnstableApiUsage")

import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Properties

plugins {
    id("com.osfans.trime.app-convention")
    id("com.osfans.trime.native-app-convention")
    id("com.osfans.trime.data-checksums")
    id("com.osfans.trime.native-cache-hash")
    id("com.osfans.trime.opencc-data")
    id("com.osfans.trime.cedict-dictionary")
    id("com.osfans.trime.wanxiang-dictionary")
    id("com.osfans.trime.rime-prebuilt-data")
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

val embedInternalCloudSecrets =
    providers.gradleProperty("embedInternalCloudSecrets").orElse("false").get().toBoolean()
val publicDistribution =
    providers.gradleProperty("publicDistribution").orElse("false").get().toBooleanStrict()
// Internal APKs may contain disposable test credentials, but must never enter public distribution.
val internalTestDistribution =
    providers.gradleProperty("internalTestDistribution").orElse("false").get().toBooleanStrict()
// Local-only upgrades for devices still on the original September test identity.
// Never relax the current public channel's pinned certificate.
val legacyInternalSigning =
    providers.gradleProperty("legacyInternalSigning").orElse("false").get().toBooleanStrict()
require(!legacyInternalSigning || (internalTestDistribution && !publicDistribution)) {
    "Legacy signing is restricted to private internal upgrades"
}
require(!(publicDistribution && internalTestDistribution)) { "Choose public OR internal test distribution" }
require(!internalTestDistribution || embedInternalCloudSecrets) { "Internal test distribution requires explicit cloud embedding" }
require(!embedInternalCloudSecrets || internalTestDistribution) { "Embedded credentials are restricted to internal test distribution" }
val fixedSigningDistribution = publicDistribution || internalTestDistribution
val publicSigningPolicy = Properties().apply {
    rootProject.file("public-signing.properties").inputStream().use(::load)
}
val publicSigningFile = file(
    providers.environmentVariable("HAOHAO_PUBLIC_KEYSTORE").orElse(
        "${System.getProperty("user.home")}/.haohao-ime/signing/public-test.keystore",
    ).get(),
)
// Use the pinned test identity. Keep this private file outside Git and public hosting.
val publicStorePassword = providers.environmentVariable("HAOHAO_PUBLIC_STORE_PASSWORD").orElse("android").get()
val publicKeyPassword = providers.environmentVariable("HAOHAO_PUBLIC_KEY_PASSWORD").orElse("android").get()
val publicKeyAlias = publicSigningPolicy.getProperty("keyAlias")
if (publicDistribution) {
    require(!embedInternalCloudSecrets) { "Public distribution must not embed shared cloud credentials" }
}
if (fixedSigningDistribution) {
    require(publicSigningFile.isFile) {
        "Fixed public signing key is missing. Restore the backup or set HAOHAO_PUBLIC_KEYSTORE; do not generate a replacement."
    }
    val keyStore = KeyStore.getInstance(publicSigningFile, publicStorePassword.toCharArray())
    val certificate = requireNotNull(keyStore.getCertificate(publicKeyAlias)) {
        "The public signing key alias is missing"
    }
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }
    val expectedFingerprint = if (legacyInternalSigning) {
        requireNotNull(publicSigningPolicy.getProperty("certificateSha256.20260921")) {
            "The historical internal signing identity is not pinned"
        }
    } else {
        publicSigningPolicy.getProperty("certificateSha256")
    }
    require(fingerprint == expectedFingerprint) {
        "Public signing certificate changed; refusing to produce an incompatible update. Restore the fixed key."
    }
    require(keyStore.getKey(publicKeyAlias, publicKeyPassword.toCharArray()) is PrivateKey) {
        "The public signing identity must include its private key"
    }
}
val internalCloudSecrets = Properties()
val internalCloudSecretsFile = providers.environmentVariable("HAOHAO_INTERNAL_CLOUD_SECRETS_FILE")
    .orNull?.let(::file) ?: rootProject.file("internal-cloud-secrets.properties")
val internalCloudSecretKeys = listOf(
    "ALIYUN_ACCESS_KEY_ID",
    "ALIYUN_ACCESS_KEY_SECRET",
    "BAIDU_API_KEY",
    "BAIDU_SECRET_KEY",
    "TEST_CLOUD_EXPIRES_AT",
)

if (embedInternalCloudSecrets) {
    require(internalCloudSecretsFile.isFile) {
        "Missing internal-cloud-secrets.properties; refusing to build a cloud-enabled APK"
    }
    internalCloudSecretsFile.inputStream().use(internalCloudSecrets::load)
    internalCloudSecretKeys.forEach { key ->
        require(!internalCloudSecrets.getProperty(key).isNullOrBlank()) {
            "Missing $key in internal-cloud-secrets.properties"
        }
    }
    val expiry = try {
        LocalDate.parse(internalCloudSecrets.getProperty("TEST_CLOUD_EXPIRES_AT").trim())
    } catch (_: DateTimeParseException) {
        error("TEST_CLOUD_EXPIRES_AT must use YYYY-MM-DD")
    }
    require(expiry == LocalDate.of(2026, 9, 30)) {
        "This temporary internal test is approved only through 2026-09-30"
    }
    require(!expiry.isBefore(LocalDate.now(ZoneOffset.UTC))) {
        "TEST_CLOUD_EXPIRES_AT has expired; refusing to embed cloud credentials"
    }
}

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

// Speech credentials are a revocable gateway token, never a Tencent Cloud access key.
val internalSpeech = Properties().apply {
    if (internalTestDistribution) {
        providers.environmentVariable("HAOHAO_INTERNAL_SPEECH_CONFIG_FILE").orNull?.let { path ->
            file(path).inputStream().use(::load)
        }
    }
}
val speechEndpoint = internalSpeech.getProperty("SPEECH_ENDPOINT", "")
val speechToken = internalSpeech.getProperty("SPEECH_CLIENT_TOKEN", "")
if (speechEndpoint.isNotEmpty() || speechToken.isNotEmpty()) {
    require(internalTestDistribution && speechEndpoint == "https://124.221.187.214/api/v1/speech" && speechToken.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
        "Speech requires the approved HTTPS gateway and a restricted internal token"
    }
}

android {
    namespace = "com.osfans.trime"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.osfans.trime"
        minSdk = 21
        targetSdk = 36
        versionCode = 20260927
        versionName = "3.3.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        multiDexEnabled = true
        buildConfigField("String", "BUILDER", "\"${project.builder}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", project.buildTimestamp)
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${project.buildCommitHash}\"")
        buildConfigField("String", "BUILD_GIT_REPO", "\"${project.buildGitRepo}\"")
        buildConfigField("String", "BUILD_VERSION_NAME", "\"${project.buildVersionName}\"")
        val translationBaseUrl = providers.gradleProperty("haohaoTranslationBaseUrl").orElse("").get()
        buildConfigField("String", "HAOHAO_TRANSLATION_BASE_URL", "\"$translationBaseUrl\"")
        buildConfigField("boolean", "INTERNAL_CLOUD_ENABLED", "false")
        buildConfigField("String", "INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_ID", "\"\"")
        buildConfigField("String", "INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_SECRET", "\"\"")
        buildConfigField("String", "INTERNAL_CLOUD_BAIDU_API_KEY", "\"\"")
        buildConfigField("String", "INTERNAL_CLOUD_BAIDU_SECRET_KEY", "\"\"")
        buildConfigField("String", "INTERNAL_CLOUD_EXPIRES_AT", "\"\"")
        buildConfigField("String", "HAOHAO_SPEECH_ENDPOINT", "\"\"")
        buildConfigField("String", "INTERNAL_SPEECH_CLIENT_TOKEN", "\"\"")
    }

    base {
        // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
        archivesName = "${android.defaultConfig.applicationId}-$buildVersionName"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            // Production always inherits empty shared credentials from defaultConfig.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                project.signKeyFile?.let {
                    signingConfigs.create("release") {
                        storeFile = it
                        storePassword = project.signKeyStorePwd
                        keyAlias = project.signKeyAlias
                        keyPassword = project.signKeyPwd
                    }
                }

            resValue("string", "trime_app_name", "@string/app_name_release")
        }
        debug {
            applicationIdSuffix = ".debug"
            if (internalTestDistribution) {
                buildConfigField("String", "HAOHAO_SPEECH_ENDPOINT", buildConfigString(speechEndpoint))
                buildConfigField("String", "INTERNAL_SPEECH_CLIENT_TOKEN", buildConfigString(speechToken))
            }

            if (fixedSigningDistribution) {
                require("${defaultConfig.applicationId}$applicationIdSuffix" == publicSigningPolicy.getProperty("applicationId")) {
                    "Public application ID changed; refusing an incompatible update"
                }
                signingConfig = signingConfigs.create("haohaoPublic") {
                    storeFile = publicSigningFile
                    storePassword = publicStorePassword
                    keyAlias = publicKeyAlias
                    keyPassword = publicKeyPassword
                }
            }

            if (embedInternalCloudSecrets) {
                buildConfigField("boolean", "INTERNAL_CLOUD_ENABLED", "true")
                buildConfigField("String", "INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_ID", buildConfigString(internalCloudSecrets.getProperty("ALIYUN_ACCESS_KEY_ID").trim()))
                buildConfigField("String", "INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_SECRET", buildConfigString(internalCloudSecrets.getProperty("ALIYUN_ACCESS_KEY_SECRET").trim()))
                buildConfigField("String", "INTERNAL_CLOUD_BAIDU_API_KEY", buildConfigString(internalCloudSecrets.getProperty("BAIDU_API_KEY").trim()))
                buildConfigField("String", "INTERNAL_CLOUD_BAIDU_SECRET_KEY", buildConfigString(internalCloudSecrets.getProperty("BAIDU_SECRET_KEY").trim()))
                buildConfigField("String", "INTERNAL_CLOUD_EXPIRES_AT", buildConfigString(internalCloudSecrets.getProperty("TEST_CLOUD_EXPIRES_AT").trim()))
            }

            resValue("string", "trime_app_name", "@string/app_name_debug")
        }
        create("regression") {
            initWith(getByName("debug"))
            resValue("string", "trime_app_name", "好好输入法（隔离测试）")
            buildConfigField("String", "HAOHAO_SPEECH_ENDPOINT", "\"\"")
            buildConfigField("String", "INTERNAL_SPEECH_CLIENT_TOKEN", "\"\"")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".regression"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "INTERNAL_CLOUD_ENABLED", "false")
            buildConfigField("String", "INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_ID", "\"\"")
            buildConfigField("String", "INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_SECRET", "\"\"")
            buildConfigField("String", "INTERNAL_CLOUD_BAIDU_API_KEY", "\"\"")
            buildConfigField("String", "INTERNAL_CLOUD_BAIDU_SECRET_KEY", "\"\"")
            buildConfigField("String", "INTERNAL_CLOUD_EXPIRES_AT", "\"\"")
        }
        all {
            // remove META-INF/version-control-info.textproto
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
        }
    }

    testBuildType = "regression"

    sourceSets {
        getByName("androidTest").assets.directories.add("dictionary/regression")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // hack workaround lint gradle 8.0.2
    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module", // cannot be excluded actually
                    "/META-INF/androidx/**",
                    "/DebugProbesKt.bin",
                    "/kotlin-tooling-metadata.json",
                )
        }
    }

    androidResources {
        noCompress += "hhdict"
    }
}

aboutLibraries {
    collect {
        configPath.set(file("licenses").takeIf { it.exists() })
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.set(
            setOf("generated", "developers", "organization", "scm", "funding", "content"),
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    val runLiveCloudTests = providers.gradleProperty("runLiveCloudTests").orElse("false").get().toBooleanStrict()
    environment("HAOHAO_RUN_LIVE_CLOUD_TESTS", runLiveCloudTests.toString())
    if (runLiveCloudTests) {
        environment("HAOHAO_INTERNAL_CLOUD_SECRETS_FILE", internalCloudSecretsFile.absolutePath)
    }
}

dependencies {
    ksp(project(":codegen"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.flexbox)
    implementation(libs.bravh)
    implementation(libs.timber)
    implementation(libs.xxpermissions)
    implementation(libs.kodein.di)
    implementation(libs.snakeyaml)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.iconics.core)
    implementation(libs.community.material.typeface) {
        artifact { type = "aar" }
    }

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
}
