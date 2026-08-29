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
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

class RimePrebuiltDataPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val verifyTask =
            target.tasks.register<VerifyRimePrebuiltDataTask>(TASK_NAME) {
                prebuiltDirectory.set(target.layout.projectDirectory.dir("dictionary/rime-prebuilt/files"))
                metadataFile.set(target.layout.projectDirectory.file("dictionary/rime-prebuilt/prebuilt.properties"))
                sharedInputDirectory.set(target.layout.projectDirectory.dir("src/main/assets/shared"))
                compileSharedDirectory.set(target.layout.projectDirectory.dir("dictionary/rime-prebuilt/compile-shared"))
                compileUserDirectory.set(target.layout.projectDirectory.dir("dictionary/rime-prebuilt/compile-user"))
                wanxiangSourceFile.set(target.layout.projectDirectory.file("dictionary/wanxiang/jichu.dict.yaml.gz"))
                wanxiangMetadataFile.set(target.layout.projectDirectory.file("dictionary/wanxiang/source.properties"))
                librimeCmakeFile.set(target.layout.projectDirectory.file("src/main/jni/librime/CMakeLists.txt"))
                outputDirectory.set(target.layout.buildDirectory.dir("generated/assets/rime-prebuilt"))
            }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants(selector().all()) { variant ->
                variant.sources.assets?.addGeneratedSourceDirectory(
                    verifyTask,
                    VerifyRimePrebuiltDataTask::outputDirectory,
                )
            }
        }

        target.pluginManager.withPlugin("com.osfans.trime.data-checksums") {
            target.tasks.named<DataChecksumsPlugin.DataChecksumsTask>(DataChecksumsPlugin.TASK) {
                dependsOn(verifyTask)
                generatedInputDirs.from(verifyTask.flatMap(VerifyRimePrebuiltDataTask::outputDirectory))
            }
        }
        target.pluginManager.withPlugin("com.osfans.trime.opencc-data") {
            verifyTask.configure { dependsOn(OpenCCDataPlugin.INSTALL_TASK) }
        }
    }

    companion object {
        const val TASK_NAME = "verifyRimePrebuiltData"
    }
}

@CacheableTask
abstract class VerifyRimePrebuiltDataTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prebuiltDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedInputDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compileSharedDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compileUserDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wanxiangSourceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wanxiangMetadataFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val librimeCmakeFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun verifyAndCopy() {
        val metadata = metadataFile.get().asFile.loadProperties()
        check(metadata.required("formatVersion") == FORMAT_VERSION)
        check(metadata.required("librimeVersion") == LIBRIME_VERSION)
        check(metadata.required("librimeCommit") == LIBRIME_COMMIT)
        check(librimeCmakeFile.get().asFile.readText().contains("set(rime_version $LIBRIME_VERSION)")) {
            "Native librime version no longer matches the prebuilt data"
        }
        check(metadata.required("wanxiangGeneratorVersion") == WANXIANG_GENERATOR_VERSION)
        val sourceTimestamp = metadata.required("sourceTimestampEpochSeconds").toLong()
        check(sourceTimestamp > 0) { "Invalid Rime prebuilt source timestamp" }

        val wanxiangMetadata = wanxiangMetadataFile.get().asFile.loadProperties()
        check(metadata.required("wanxiangRelease") == wanxiangMetadata.required("release"))
        check(metadata.required("wanxiangCommit") == wanxiangMetadata.required("commit"))
        check(metadata.required("wanxiangSourceSha256") == wanxiangMetadata.required("sha256"))
        check(metadata.required("wanxiangCompressedSha256") == wanxiangMetadata.required("compressedSha256"))

        val sourceHash = sha256(wanxiangSourceFile.get().asFile)
        check(sourceHash == metadata.required("wanxiangCompressedSha256")) {
            "Wanxiang compressed source checksum mismatch: $sourceHash"
        }

        val inputHash =
            compileInputSha256(
                sharedInputDirectory.get().asFile,
                compileSharedDirectory.get().asFile,
                compileUserDirectory.get().asFile,
                wanxiangSourceFile.get().asFile,
                wanxiangMetadataFile.get().asFile,
            )
        check(inputHash == metadata.required("compileInputSha256")) {
            "Rime prebuilt inputs changed: expected ${metadata.required("compileInputSha256")}, got $inputHash. " +
                "Regenerate the pinned prebuilt data before building."
        }

        val declaredFiles =
            (0 until metadata.required("fileCount").toInt()).map { index ->
                metadata.required("file.$index.name")
            }
        check(declaredFiles.toSet() == REQUIRED_FILES) {
            "Unexpected Rime prebuilt file set: $declaredFiles"
        }

        val compiledSchema = prebuiltDirectory.get().asFile.resolve("luna_pinyin_simp.schema.yaml").readText()
        val nonZeroTimestamps =
            compiledSchema.lineSequence()
                .dropWhile { it != "  timestamps:" }
                .drop(1)
                .takeWhile { it.startsWith("    ") }
                .map { it.substringAfter(':').trim().toLong() }
                .filter { it > 0 }
                .toSet()
        check(nonZeroTimestamps == setOf(sourceTimestamp)) {
            "Compiled schema timestamps do not match sourceTimestampEpochSeconds: $nonZeroTimestamps"
        }

        val destination = outputDirectory.get().asFile.resolve("shared/build")
        destination.deleteRecursively()
        destination.mkdirs()
        declaredFiles.sorted().forEach { name ->
            val source = prebuiltDirectory.get().asFile.resolve(name)
            check(source.isFile) { "Missing Rime prebuilt file: $name" }
            val expectedBytes = metadata.required("file.$name.bytes").toLong()
            val expectedHash = metadata.required("file.$name.sha256")
            check(source.length() == expectedBytes) {
                "Rime prebuilt size mismatch for $name: ${source.length()} != $expectedBytes"
            }
            val actualHash = sha256(source)
            check(actualHash == expectedHash) {
                "Rime prebuilt checksum mismatch for $name: $actualHash != $expectedHash"
            }
            source.copyTo(destination.resolve(name), overwrite = true)
        }
        metadataFile.get().asFile.copyTo(destination.resolve(RUNTIME_METADATA_FILE), overwrite = true)
        logger.lifecycle("Verified ${declaredFiles.size} librime $LIBRIME_VERSION prebuilt files")
    }

    companion object {
        const val FORMAT_VERSION = "1"
        const val LIBRIME_VERSION = "1.17.0"
        const val LIBRIME_COMMIT = "33e78140250125871856cdc5b42ddc6a5fcd3cd4"
        const val WANXIANG_GENERATOR_VERSION = "1"
        const val RUNTIME_METADATA_FILE = "haohao_prebuilt.properties"

        val REQUIRED_FILES =
            setOf(
                "haohao_pinyin.reverse.bin",
                "haohao_pinyin.table.bin",
                "luna_pinyin_simp.prism.bin",
                "luna_pinyin_simp.schema.yaml",
            )

        internal fun compileInputSha256(
            sharedInputDirectory: File,
            compileSharedDirectory: File,
            compileUserDirectory: File,
            wanxiangSourceFile: File,
            wanxiangMetadataFile: File,
        ): String {
            val entries = mutableListOf<Pair<String, File>>()
            entries += "wanxiang/${wanxiangSourceFile.name}" to wanxiangSourceFile
            entries += "wanxiang/${wanxiangMetadataFile.name}" to wanxiangMetadataFile
            entries += sourceEntries(sharedInputDirectory, "shared") { !it.name.endsWith(".trime.yaml") }
            entries += sourceEntries(compileSharedDirectory, "compile-shared") { true }
            entries += sourceEntries(compileUserDirectory, "compile-user") { true }

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("wanxiang-generator=$WANXIANG_GENERATOR_VERSION\n".toByteArray(Charsets.UTF_8))
            entries.sortedBy { it.first }.forEach { (path, file) ->
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update(0)
                file.inputStream().use { input -> updateDigest(digest, input) }
                digest.update(0)
            }
            return digest.hex()
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> updateDigest(digest, input) }
            return digest.hex()
        }

        private fun sourceEntries(
            root: File,
            prefix: String,
            include: (File) -> Boolean,
        ): List<Pair<String, File>> = root.walkTopDown()
            .filter { it.isFile && include(it) }
            .map { file -> "$prefix/${file.relativeTo(root).invariantSeparatorsPath}" to file }
            .toList()

        private fun updateDigest(
            digest: MessageDigest,
            input: InputStream,
        ) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

        private fun File.loadProperties(): Properties = Properties().apply { inputStream().use(::load) }

        private fun Properties.required(name: String): String = getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
            ?: error("Missing Rime prebuilt metadata property: $name")
    }
}
