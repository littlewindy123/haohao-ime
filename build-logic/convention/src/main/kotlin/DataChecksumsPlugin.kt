// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.task
import org.jetbrains.kotlin.com.google.common.hash.Hashing
import java.io.File
import java.security.MessageDigest
import kotlin.collections.set

/**
 * Add task generateDataChecksums
 */
class DataChecksumsPlugin : Plugin<Project> {
    companion object {
        const val TASK = "generateDataChecksums"
        const val CLEAN_TASK = "cleanDatacheksums"
        const val FILE_NAME = "checksums.json"
    }

    override fun apply(target: Project) {
        target.tasks.register<DataChecksumsTask>(TASK) {
            inputDir.set(target.assetsDir)
            outputFile.set(target.assetsDir.resolve(FILE_NAME))
        }
        target.tasks.configureEach {
            if (name.startsWith("lintAnalyze") || (name.contains("Lint") && name.endsWith("Model"))) dependsOn(TASK)
        }
        target.tasks.register<Delete>(CLEAN_TASK) {
            delete(target.assetsDir.resolve(FILE_NAME))
        }.also {
            target.tasks.findByName("clean")?.dependsOn(it)
        }
    }

    abstract class DataChecksumsTask : DefaultTask() {
        @Serializable
        data class DataChecksums(
            val sha256: String,
            val files: Map<String, String>,
        )

        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        @get:InputDirectory
        abstract val inputDir: DirectoryProperty

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val generatedInputDirs: ConfigurableFileCollection

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        private val file by lazy { outputFile.get().asFile }

        private fun serialize(files: Map<String, String>) {
            val checksums =
                DataChecksums(
                    Hashing
                        .sha256()
                        .hashString(
                            files.entries.joinToString { it.key + it.value },
                            Charsets.UTF_8,
                        ).toString(),
                    files,
                )
            file.writeText(json.encodeToString(checksums))
        }

        companion object {
            fun sha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
        }

        @TaskAction
        fun execute() {
            val map = sortedMapOf<String, String>()
            val roots =
                (listOf(inputDir.get().asFile) + generatedInputDirs.files)
                    .distinctBy(File::getAbsolutePath)
                    .sortedBy(File::getAbsolutePath)
            roots.forEach { root ->
                if (!root.exists()) return@forEach
                root.walkTopDown().forEach { candidate ->
                    if (candidate == root || candidate.absoluteFile == file.absoluteFile) return@forEach
                    val key = candidate.relativeTo(root).invariantSeparatorsPath
                    val value = if (candidate.isDirectory) "" else sha256(candidate)
                    val previous = map.putIfAbsent(key, value)
                    check(previous == null || previous == value) {
                        "Conflicting asset path across source directories: $key"
                    }
                }
            }
            serialize(map)
        }
    }
}
