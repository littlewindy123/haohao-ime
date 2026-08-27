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
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream

class WanxiangDictionaryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val generateTask =
            target.tasks.register<GenerateWanxiangDictionaryTask>(TASK_NAME) {
                sourceFile.set(target.layout.projectDirectory.file("dictionary/wanxiang/jichu.dict.yaml.gz"))
                metadataFile.set(target.layout.projectDirectory.file("dictionary/wanxiang/source.properties"))
                outputDirectory.set(target.layout.buildDirectory.dir("generated/assets/wanxiang"))
            }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants(selector().all()) { variant ->
                variant.sources.assets?.addGeneratedSourceDirectory(
                    generateTask,
                    GenerateWanxiangDictionaryTask::outputDirectory,
                )
            }
        }

        target.pluginManager.withPlugin("com.osfans.trime.data-checksums") {
            target.tasks.named<DataChecksumsPlugin.DataChecksumsTask>(DataChecksumsPlugin.TASK) {
                dependsOn(generateTask)
                generatedInputDirs.from(generateTask.flatMap(GenerateWanxiangDictionaryTask::outputDirectory))
            }
        }
    }

    companion object {
        const val TASK_NAME = "generateWanxiangDictionary"
    }
}

@CacheableTask
abstract class GenerateWanxiangDictionaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val metadata = Properties().apply { metadataFile.get().asFile.inputStream().use(::load) }
        val source = sourceFile.get().asFile
        val expectedCompressedHash = metadata.required("compressedSha256")
        val actualCompressedHash = source.inputStream().use(::sha256)
        check(actualCompressedHash == expectedCompressedHash) {
            "Wanxiang compressed source checksum mismatch: expected $expectedCompressedHash, got $actualCompressedHash"
        }

        val expectedHash = metadata.required("sha256")
        val actualHash = source.inputStream().use { compressed ->
            GZIPInputStream(compressed).use(::sha256)
        }
        check(actualHash == expectedHash) {
            "Wanxiang source checksum mismatch: expected $expectedHash, got $actualHash"
        }

        val expectedBytes = metadata.required("uncompressedBytes").toLong()
        val actualBytes = source.inputStream().use { compressed ->
            GZIPInputStream(compressed).use { it.copyTo(OutputCounter) }
        }
        check(actualBytes == expectedBytes) {
            "Wanxiang source size mismatch: expected $expectedBytes, got $actualBytes"
        }

        val release = metadata.required("release")
        val expectedEntries = metadata.required("entryCount").toInt()
        val expectedIgnoredEntries = metadata.required("ignoredEntryCount").toInt()
        val outputFile =
            outputDirectory
                .get()
                .asFile
                .resolve("shared")
                .apply { mkdirs() }
                .resolve(OUTPUT_FILE_NAME)
        val stats =
            source.inputStream().use { compressed ->
                GZIPInputStream(compressed).bufferedReader(Charsets.UTF_8).use { input ->
                    outputFile.bufferedWriter(Charsets.UTF_8).use { output ->
                        WanxiangDictionaryGenerator.generate(input, release, output)
                    }
                }
            }
        check(stats.generatedEntries == expectedEntries) {
            "Wanxiang generated entry count mismatch: ${stats.generatedEntries} != $expectedEntries"
        }
        check(stats.ignoredEntries == expectedIgnoredEntries) {
            "Wanxiang ignored entry count mismatch: ${stats.ignoredEntries} != $expectedIgnoredEntries"
        }
        check(stats.sourceEntries == expectedEntries + expectedIgnoredEntries) {
            "Wanxiang source entry count mismatch: ${stats.sourceEntries}"
        }
        check(outputFile.length() <= MAXIMUM_ASSET_BYTES) {
            "Generated Wanxiang dictionary exceeds 45 MiB: ${outputFile.length()} bytes"
        }
        logger.lifecycle(
            "Generated ${stats.generatedEntries} HaoHao Pinyin entries from Wanxiang $release " +
                "(${outputFile.length()} bytes, ${stats.ignoredEntries} pinned source row ignored)",
        )
    }

    private fun Properties.required(name: String): String = getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Missing Wanxiang metadata property: $name")

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private object OutputCounter : java.io.OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit
    }

    companion object {
        const val OUTPUT_FILE_NAME = "haohao_wanxiang_core.dict.yaml"
        const val MAXIMUM_ASSET_BYTES = 45L * 1024 * 1024
    }
}
