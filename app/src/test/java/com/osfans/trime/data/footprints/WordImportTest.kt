package com.osfans.trime.data.footprints

import io.kotest.core.spec.style.StringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class WordImportTest :
    StringSpec({
        "csv preserves quoted punctuation and multiline meanings" {
            val p = WordImport.preview("\uFEFFenglish,chinese,phonetic\r\nhello,\"你好,朋友\n问候\",həˈləʊ\r\nworld,\"世界\"\"天地\",\r\n")
            assertEquals(2, p.words.size)
            assertEquals("你好,朋友\n问候", p.words[0].chinese)
            assertEquals("世界\"天地", p.words[1].chinese)
        }
        "normalised duplicates and invalid lines are explicit" {
            val p = WordImport.preview("hello,你好\nHELLO,你好\n123,错误\nworld,\n")
            assertEquals(1, p.words.size)
            assertEquals(1, p.duplicates)
            assertEquals(listOf(3, 4), p.issues.map { it.line })
        }
        "paste order and long meanings respect learning limits" {
            val p = WordImport.preview("中文\t英文\n${"长".repeat(512)}\thello", '\t', true)
            assertEquals(1, p.words.size)
            assertEquals(1, WordImport.preview("hello,${"长".repeat(513)}").issues.size)
            assertEquals(1, WordImport.preview("${"a".repeat(33)},词").issues.size)
        }
        "malformed quotes and invalid UTF8 fail the whole file" {
            for (s in listOf("hello,\"你好", "he\"llo,你好", "hello,\"你好\"x")) assertTrue(runCatching { WordImport.preview(s) }.isFailure)
            assertTrue(runCatching { WordImport.read(byteArrayOf(0xc3.toByte(), 0x28).inputStream()) }.isFailure)
        }
        "record and byte limits fail before import" {
            assertTrue(runCatching { WordImport.preview("a,甲\n".repeat(100001)) }.isFailure)
            assertTrue(runCatching { WordImport.read(ByteArray(LEARNING_BACKUP_LIMIT + 1).inputStream()) }.isFailure)
        }
        "reverse questions mask an embedded answer without changing saved definitions" {
            assertEquals("第一个字母 ＿; 一个", recallMeaning("第一个字母 A; 一个", "a"))
            assertEquals("打招呼 ＿; shell", recallMeaning("打招呼 HELLO; shell", "hello"))
            assertEquals("homework", recallMeaning("homework", "home"))
        }
        "v2 backup preserves shared memberships and long imported definitions" {
            val words = listOf(SavedWordEntity("中".repeat(512), "hello", source = "import", createdAt = 1))
            val books = listOf(WordbookEntity("one", "一", 1), WordbookEntity("two", "二", 2))
            val b = LearningBackup(exportedAt = 1, words = words, sentences = emptyList(), settings = WordLearningStateEntity(), days = emptyList(), tasks = emptyList(), events = emptyList(), books = books, memberships = books.map { WordbookMember(it.id, words[0].chinese, "hello") })
            assertEquals(b, LearningBackupCodec.read(LearningBackupCodec.encode(b).inputStream()))
            for (bad in listOf(b.copy(version = 3), b.copy(memberships = b.memberships + b.memberships), b.copy(books = listOf(books[0])), b.copy(version = 1))) {
                assertTrue(runCatching { LearningBackupCodec.encode(bad) }.isFailure)
            }
            val old = b.copy(version = 1, books = emptyList(), memberships = emptyList())
            assertEquals(old, LearningBackupCodec.read(LearningBackupCodec.encode(old).inputStream()))
        }
    })
