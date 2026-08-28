// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.zip.GZIPInputStream

class CedictDictionaryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val generateTask =
            target.tasks.register<GenerateCedictDictionaryTask>(TASK_NAME) {
                sourceFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"))
                metadataFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/source.properties"))
                overridesFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/common_overrides_zh_en.tsv"))
                pronunciationsFile.set(target.layout.projectDirectory.file("dictionary/ipa-dict/en_US.txt.gz"))
                pronunciationsMetadataFile.set(target.layout.projectDirectory.file("dictionary/ipa-dict/source.properties"))
                outputDirectory.set(target.layout.buildDirectory.dir("generated/assets/cedict"))
            }
        val qualityTask = target.tasks.register<VerifyTranslationQualityTask>(QUALITY_TASK_NAME) {
            dependsOn(generateTask)
            generatedDictionaryFile.set(
                generateTask.flatMap { task ->
                    task.outputDirectory.file(GenerateCedictDictionaryTask.OUTPUT_FILE_NAME)
                },
            )
            wanxiangSourceFile.set(target.layout.projectDirectory.file("dictionary/wanxiang/jichu.dict.yaml.gz"))
            regressionFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/translation_quality_zh_en.tsv"))
            overridesFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/common_overrides_zh_en.tsv"))
            pronunciationsFile.set(target.layout.projectDirectory.file("dictionary/ipa-dict/en_US.txt.gz"))
            baselineFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/translation_quality_baseline.properties"))
            outputDirectory.set(target.layout.buildDirectory.dir("reports/translation-quality"))
            group = "verification"
            description = "Checks 200 curated translations and the top 5000 Wanxiang headwords."
        }
        target.pluginManager.withPlugin("com.osfans.trime.wanxiang-dictionary") {
            qualityTask.configure { dependsOn(WanxiangDictionaryPlugin.TASK_NAME) }
        }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants(selector().all()) { variant ->
                variant.sources.assets?.addGeneratedSourceDirectory(
                    generateTask,
                    GenerateCedictDictionaryTask::outputDirectory,
                )
            }
        }
    }

    companion object {
        const val TASK_NAME = "generateCedictDictionary"
        const val QUALITY_TASK_NAME = "verifyTranslationQuality"
    }
}

@CacheableTask
abstract class VerifyTranslationQualityTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedDictionaryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wanxiangSourceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val regressionFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val overridesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pronunciationsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baselineFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val regression =
            regressionFile.get().asFile.reader(Charsets.UTF_8).use(TranslationQuality::parseRegression)
        val overrides =
            overridesFile.get().asFile.reader(Charsets.UTF_8).use(TranslationQuality::parseOverrides)
        val generated = CedictDictionaryGenerator.readForTesting(generatedDictionaryFile.get().asFile.readBytes())
        val dictionary = generated.entries.associateBy(CedictDictionaryGenerator.GeneratedEntry::sourceText)
        val topHeadwords =
            wanxiangSourceFile.get().asFile.inputStream().use { compressed ->
                GZIPInputStream(compressed).bufferedReader(Charsets.UTF_8).use { source ->
                    TranslationQuality.selectTopWanxiang(source, TOP_HEADWORD_COUNT)
                }
            }
        val pronunciations =
            pronunciationsFile.get().asFile.inputStream().use(CedictDictionaryGenerator::readPronunciations)
        val report =
            TranslationQuality.scan(topHeadwords, dictionary) { translation ->
                CedictDictionaryGenerator.findPronunciation(translation, pronunciations)
            }
        val mismatches =
            regression.mapNotNull { case ->
                val actual = dictionary[case.text]?.translation
                if (actual == case.translation) null else Triple(case.text, case.translation, actual)
            }

        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        outputDir.resolve("summary.properties").writeText(
            buildString {
                appendLine("topHeadwordCount=${report.totalCount}")
                appendLine("coveredCount=${report.coveredCount}")
                appendLine("coveragePercent=${String.format(Locale.ROOT, "%.2f", report.coveredCount * 100.0 / report.totalCount)}")
                appendLine("missingCount=${report.missing.size}")
                appendLine("hardIssueCount=${report.hardIssues.size}")
                appendLine("manualMismatchCount=${mismatches.size}")
            },
            Charsets.UTF_8,
        )
        outputDir.resolve("missing.tsv").bufferedWriter(Charsets.UTF_8).use { output ->
            output.appendLine("# Chinese<TAB>Wanxiang weight")
            report.missing.forEach { output.append(it.text).append('\t').append(it.weight.toString()).appendLine() }
        }
        outputDir.resolve("hard-issues.tsv").bufferedWriter(Charsets.UTF_8).use { output ->
            output.appendLine("# Chinese<TAB>translation<TAB>issues")
            report.hardIssues.forEach { issue ->
                output.append(issue.text).append('\t').append(issue.translation).append('\t')
                    .append(issue.reasons.joinToString("; ")).appendLine()
            }
        }
        outputDir.resolve("manual-mismatches.tsv").bufferedWriter(Charsets.UTF_8).use { output ->
            output.appendLine("# Chinese<TAB>expected<TAB>actual")
            mismatches.forEach { (text, expected, actual) ->
                output.append(text).append('\t').append(expected ?: "-").append('\t').append(actual ?: "-").appendLine()
            }
        }

        val baseline = Properties().apply { baselineFile.get().asFile.inputStream().use(::load) }
        val expectedQuotas = mapOf("single" to 50, "word" to 70, "phrase" to 35, "modern" to 25, "risk" to 20)
        val failures = buildList {
            if (regression.size != 200) add("manual regression count is ${regression.size}, expected 200")
            if (regression.groupingBy { it.category }.eachCount() != expectedQuotas) add("manual category quotas changed")
            if (regression.map { it.text }.distinct().size != regression.size) add("manual regression contains duplicate Chinese keys")
            val untrackedOverrides = overrides.keys - regression.mapTo(hashSetOf()) { it.text }
            if (untrackedOverrides.isNotEmpty()) add("overrides missing regression cases: ${untrackedOverrides.joinToString()}")
            if (mismatches.isNotEmpty()) add("${mismatches.size} manual translations differ from the curated baseline")
            val minimumCoveredCount = baseline.required("minimumCoveredCount").toInt()
            val maximumHardIssues = baseline.required("maximumHardIssues").toInt()
            val baselineTopHeadwordCount = baseline.required("topHeadwordCount").toInt()
            if (baselineTopHeadwordCount != TOP_HEADWORD_COUNT) {
                add("baseline top-headword count is $baselineTopHeadwordCount, expected $TOP_HEADWORD_COUNT")
            }
            if (report.coveredCount < minimumCoveredCount) {
                add("top-5000 coverage regressed: ${report.coveredCount} < $minimumCoveredCount")
            }
            if (report.hardIssues.size > maximumHardIssues) {
                add("top-5000 hard issues increased: ${report.hardIssues.size} > $maximumHardIssues")
            }
        }
        check(failures.isEmpty()) {
            failures.joinToString(prefix = "Translation quality verification failed:\n- ", separator = "\n- ") +
                "\nReports: ${outputDir.absolutePath}"
        }
        logger.lifecycle(
            "Translation quality: ${report.coveredCount}/${report.totalCount} covered, " +
                "${report.hardIssues.size} hard issues, ${report.missing.size} queued for review",
        )
    }

    private fun Properties.required(name: String): String = getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Missing translation quality baseline property: $name")

    companion object {
        const val TOP_HEADWORD_COUNT = 5_000
    }
}

@CacheableTask
abstract class GenerateCedictDictionaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val overridesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pronunciationsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pronunciationsMetadataFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val metadata = Properties().apply { metadataFile.get().asFile.inputStream().use(::load) }
        val source = sourceFile.get().asFile
        val expectedHash = metadata.required("sha256")
        val actualHash = source.inputStream().use(::sha256)
        check(actualHash == expectedHash) {
            "CC-CEDICT source checksum mismatch: expected $expectedHash, got $actualHash"
        }

        val pronunciationMetadata = Properties().apply {
            pronunciationsMetadataFile.get().asFile.inputStream().use(::load)
        }
        val pronunciationSource = pronunciationsFile.get().asFile
        val expectedPronunciationHash = pronunciationMetadata.required("compressedSha256")
        val actualPronunciationHash = pronunciationSource.inputStream().use(::sha256)
        check(actualPronunciationHash == expectedPronunciationHash) {
            "IPA source checksum mismatch: expected $expectedPronunciationHash, got $actualPronunciationHash"
        }

        val release = metadata.required("release")
        val expectedEntries = metadata.required("entryCount").toInt()
        val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
        val outputFile = outputDir.resolve(OUTPUT_FILE_NAME)
        val stats =
            source.inputStream().use { compressed ->
                overridesFile.get().asFile.reader(Charsets.UTF_8).use { overrides ->
                    pronunciationSource.inputStream().use { pronunciations ->
                        outputFile.outputStream().buffered().use { output ->
                            CedictDictionaryGenerator.generate(
                                compressed,
                                overrides,
                                pronunciations,
                                release,
                                output,
                            )
                        }
                    }
                }
            }
        check(stats.sourceEntries == expectedEntries) {
            "CC-CEDICT source entry count mismatch: ${stats.sourceEntries} != $expectedEntries"
        }
        check(stats.uniqueSimplifiedHeadwords >= MINIMUM_UNIQUE_HEADWORDS) {
            "CC-CEDICT source has too few unique simplified headwords: ${stats.uniqueSimplifiedHeadwords}"
        }
        check(outputFile.length() <= MAXIMUM_ASSET_BYTES) {
            "Generated dictionary exceeds 8 MiB: ${outputFile.length()} bytes"
        }
        logger.lifecycle(
            "Generated ${stats.generatedTranslations} bilingual entries with optional en-US IPA from " +
                "${stats.uniqueSimplifiedHeadwords} unique CC-CEDICT $release headwords (${outputFile.length()} bytes)",
        )
    }

    private fun Properties.required(name: String): String = getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Missing CC-CEDICT metadata property: $name")

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val OUTPUT_FILE_NAME = "bilingual_zh_en.hhdict"
        const val MINIMUM_UNIQUE_HEADWORDS = 100_000
        const val MAXIMUM_ASSET_BYTES = 8L * 1024 * 1024
    }
}
