package com.osfans.trime.data.footprints

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

@Serializable internal data class BuiltinWordbook(val id: String, val name: String, val words: List<String>)

@Serializable internal data class BuiltinWordbooks(val version: Int, val sourceCommit: String, val words: List<ImportWord>, val books: List<BuiltinWordbook>) {
    fun entries(book: BuiltinWordbook): List<ImportWord> {
        val byEnglish = words.associateBy { it.english }
        return book.words.map { requireNotNull(byEnglish[it]) }
    }
    companion object {
        fun load(context: Context): BuiltinWordbooks {
            // .gz assets are transparently expanded/renamed by Android's asset merger.
            val bytes = context.assets.open("learning/wordbooks.bin").use { it.readBytes() }
            val manifest = context.assets.open("learning/wordbooks-manifest.json").bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(manifest["sha256"]?.jsonPrimitive?.content == digest) { "wordbook_resource" }
            return GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { Json.decodeFromString<BuiltinWordbooks>(it.readText()) }.also { require(it.version == 1) }
        }
    }
}
