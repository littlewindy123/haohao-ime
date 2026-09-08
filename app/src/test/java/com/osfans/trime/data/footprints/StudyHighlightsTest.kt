package com.osfans.trime.data.footprints

import io.kotest.core.spec.style.StringSpec
import org.junit.Assert.*

class StudyHighlightsTest : StringSpec({
    "whole words only with case and punctuation" {
        val s = "Learn, learn! relearn learning learner."
        assertEquals(listOf("Learn", "learn"), studyHighlightRanges(s, listOf("learn")).map { s.substring(it) })
    }
    "forms are explicit and longer phrases win" {
        val s = "Look up, looked up; look."
        assertEquals(listOf("Look up", "looked up", "look"), studyHighlightRanges(s, listOf("look", "look up", "looked up")).map { s.substring(it) })
    }
    "no substring in contractions or identifiers" {
        assertTrue(studyHighlightRanges("can't candy can2 _can", listOf("can")).isEmpty())
    }
    "Chinese meaning is not guessed from a definition" {
        assertEquals(listOf("学习"), studyChineseTerm("学习"))
        assertTrue(studyChineseTerm("学").isEmpty())
        assertTrue(studyChineseTerm("学习；了解").isEmpty())
        assertTrue(studyHighlightRanges("一起工作", studyChineseTerm("学习"), false).isEmpty())
    }
})
