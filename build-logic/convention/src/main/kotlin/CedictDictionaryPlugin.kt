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
import java.util.Properties

class CedictDictionaryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val generateTask =
            target.tasks.register<GenerateCedictDictionaryTask>(TASK_NAME) {
                sourceFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"))
                metadataFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/source.properties"))
                overridesFile.set(target.layout.projectDirectory.file("dictionary/cc-cedict/common_overrides_zh_en.tsv"))
                outputDirectory.set(target.layout.buildDirectory.dir("generated/assets/cedict"))
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

        val release = metadata.required("release")
        val expectedEntries = metadata.required("entryCount").toInt()
        val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
        val outputFile = outputDir.resolve(OUTPUT_FILE_NAME)
        val stats =
            source.inputStream().use { compressed ->
                overridesFile.get().asFile.reader(Charsets.UTF_8).use { overrides ->
                    outputFile.outputStream().buffered().use { output ->
                        CedictDictionaryGenerator.generate(compressed, overrides, release, output)
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
            "Generated dictionary exceeds 10 MiB: ${outputFile.length()} bytes"
        }
        logger.lifecycle(
            "Generated ${stats.generatedTranslations} bilingual entries from " +
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
        const val MAXIMUM_ASSET_BYTES = 10L * 1024 * 1024
    }
}
