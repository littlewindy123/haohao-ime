package com.osfans.trime.data.footprints

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LearningPolicyTest :
    StringSpec({
        "recall observations exclude same day repeats undone and missing intervals" {
            fun event(token: String, day: Int, kind: String, rating: String = "REMEMBERED", undone: Boolean = false) = LearningReviewEvent(token, "学习", "learn", day * LEARNING_DAY_MS, "day$day", rating, kind, undone)
            val samples = recallObservations(listOf(event("a", 0, "new"), event("b", 1, "review", "FORGOTTEN"), event("c", 1, "repeat"), event("d", 2, "review", undone = true), event("e", 4, "review")))
            samples.first().attempts shouldBe 1
            samples.first().remembered shouldBe 0
            samples[1].attempts shouldBe 1
            samples[1].remembered shouldBe 1
            recallObservations(listOf(event("x", 9, "review"))).sumOf { it.attempts } shouldBe 0
        }
        "mixed review rotates all recall directions and stays stable on reveal" {
            val session = WordReviewSession(cards = listOf(ReviewCard("学习", "learn")), mode = ReviewMode.MIXED)
            (0..5).map { session.copy(completed = it).currentMode } shouldBe listOf(
                ReviewMode.ENGLISH,
                ReviewMode.CHINESE,
                ReviewMode.SPELLING,
                ReviewMode.ENGLISH,
                ReviewMode.CHINESE,
                ReviewMode.SPELLING,
            )
            session.copy(answerVisible = true).currentMode shouldBe session.currentMode
            WordReviewSession(reverse = true).currentMode shouldBe ReviewMode.CHINESE
        }
        "spelling accepts case spacing and apostrophe variants but not missing letters" {
            spellingMatches("  GO   home ", "go home") shouldBe true
            spellingMatches("don't", "don’t") shouldBe true
            spellingMatches("lern", "learn") shouldBe false
            spellingMatches("", "learn") shouldBe false
            spellingMatches("ice cream", "ice-cream") shouldBe false
        }
        "next review clears the spelling draft and result" {
            val session = WordReviewSession(cards = listOf(ReviewCard("学习", "learn")), spellingDraft = "lern", spellingCorrect = false, answerVisible = true)
            val next = advanceReview(session, RecallRating.FORGOTTEN)
            next.spellingDraft shouldBe ""
            next.spellingCorrect shouldBe null
            next.answerVisible shouldBe false
        }
        "remembered answers use all five intervals and keep the final interval" {
            var word = SavedWordEntity(chinese = "学习", english = "learn", source = "offline", createdAt = 1)
            val start = 100_000L
            listOf(1, 3, 7, 14, 30, 30).forEachIndexed { index, days ->
                word = scheduleWord(word, RecallRating.REMEMBERED, start + index)
                word.nextReviewAt shouldBe start + index + days * LEARNING_DAY_MS
            }
            word.stage shouldBe 5
            word.reviewCount shouldBe 6
        }
        "uncertain keeps the stage and forgotten resets it" {
            val word = SavedWordEntity("学习", "learn", source = "offline", createdAt = 1, stage = 3)
            scheduleWord(word, RecallRating.UNCERTAIN, 100).stage shouldBe 3
            scheduleWord(word, RecallRating.FORGOTTEN, 100).stage shouldBe 0
            scheduleWord(word, RecallRating.FORGOTTEN, 100).nextReviewAt shouldBe 100 + LEARNING_DAY_MS
        }
        "a backwards wall clock never places review before the last answer" {
            val word = SavedWordEntity("学习", "learn", source = "offline", createdAt = 1, lastReviewedAt = 50_000)
            scheduleWord(word, RecallRating.REMEMBERED, 100).nextReviewAt shouldBe 50_000 + LEARNING_DAY_MS
        }
        "light review selects due words before new words and ignores paused words" {
            val due = SavedWordEntity("学习", "learn", source = "offline", createdAt = 2, learning = true, nextReviewAt = 1, reviewCount = 1)
            val fresh = SavedWordEntity("明天", "tomorrow", source = "offline", createdAt = 1, learning = true)
            val paused = due.copy(chinese = "暂停", learning = false)
            selectReviewWords(listOf(fresh, due, paused), 10, 5, 5, 5).map { it.chinese } shouldBe listOf("学习", "明天")
        }
        "daily limits apply independently and future words are not tested early" {
            val words = (1..4).map { SavedWordEntity("词$it", "word", source = "offline", createdAt = it.toLong(), learning = true) }
            val future = words.first().copy(chinese = "未来", nextReviewAt = 500, reviewCount = 1)
            selectReviewWords(words + future, 100, 1, 10, 11).size shouldBe 1
        }
        "a forgotten word is repeated once after intervening cards" {
            val a = ReviewCard("学习", "learn", token = "a")
            val session = WordReviewSession(cards = listOf(a, ReviewCard("明天", "tomorrow", token = "b")))
            val after = advanceReview(session, RecallRating.FORGOTTEN)
            after.cards.map { it.chinese } shouldBe listOf("明天", "学习")
            after.cards.last().repeat shouldBe true
            val repeated = after.copy(cards = listOf(after.cards.last()))
            advanceReview(repeated, RecallRating.FORGOTTEN).cards.size shouldBe 0
            advanceReview(repeated, RecallRating.REMEMBERED).completed shouldBe repeated.completed
        }
        "saved meanings normalize without inventing a translation" {
            normalizeSavedEnglish("  Go   Home ") shouldBe "go home"
            displaySavedEnglish("  Go   Home ") shouldBe "Go Home"
            displaySavedEnglish("I") shouldBe "I"
            displaySavedEnglish("China") shouldBe "China"
            normalizeSavedEnglish("这是中文") shouldBe null
            normalizeSavedEnglish("...") shouldBe null
            normalizeSavedEnglish("") shouldBe null
        }
    })
