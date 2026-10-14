// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.testutils.JvmTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class LearningRepositoryTest : JvmTest() {
    @Before
    fun prepareLearningNoteType() {
        addStandardNoteType("小海豹通用学习", arrayOf("Title", "Subject", "Topic", "Recognition", "Recall", "Application"), "{{Title}}", "{{Recognition}}")
        col.config.set("fsrs", true)
    }

    private fun addGoal(): Long {
        val exercise = """{"version":1,"steps":[{"id":"meaning","kind":"choice","prompt":"apple 是什么？","options":["苹果","香蕉"],"answer":0,"explanation":"apple 表示苹果。"}]}"""
        return addNoteUsingNoteTypeName("小海豹通用学习", "apple", "英语", "日常", exercise, "", "").cards(col).first().id
    }

    private fun answerFor(cardId: Long): JSONObject {
        val card = LearningRepository(col).lesson(col.decks.selected()).getJSONObject(0)
        assertEquals(cardId.toString(), card.getString("id"))
        return JSONObject().put("cardId", card.getString("id")).put("expectedReps", card.getInt("expectedReps"))
            .put("contentHash", card.getString("contentHash")).put("token", card.getString("token")).put("rating", 3).put("attempts", 1).put("durationMs", 1000)
    }

    @Test
    fun `retrying the same submission creates exactly one review`() {
        val id = addGoal()
        val answer = answerFor(id)
        val repository = LearningRepository(col)
        repository.answer(answer)
        repository.answer(answer)
        assertEquals(1, col.getCard(id).reps)
        assertEquals(1L, col.db.queryLongScalar("SELECT count(*) FROM revlog WHERE cid = ?", id))
        assertEquals(answer.getString("token"), JSONObject(col.getCard(id).customData).getJSONObject("lp").getString("t"))
    }

    @Test
    fun `a stale submission cannot overwrite another review`() {
        val id = addGoal()
        val answer = answerFor(id)
        answer.put("expectedReps", 99)
        assertFailsWith<IllegalArgumentException> { LearningRepository(col).answer(answer) }
        assertEquals(0, col.getCard(id).reps)
        assertEquals(0L, col.db.queryLongScalar("SELECT count(*) FROM revlog WHERE cid = ?", id))
    }

    @Test
    fun `ordinary Anki cards cannot be submitted as interactive reviews`() {
        val ordinary = addBasicNote("front", "back").cards(col).first()
        val goal = addGoal()
        val answer = answerFor(goal).put("cardId", ordinary.id.toString())
        assertFailsWith<IllegalArgumentException> { LearningRepository(col).answer(answer) }
        assertEquals(0, col.getCard(ordinary.id).reps)
    }

    @Test
    fun `modified content cannot accept the old answer`() {
        val id = addGoal()
        val answer = answerFor(id).put("contentHash", "outdated-content")
        assertFailsWith<IllegalArgumentException> { LearningRepository(col).answer(answer) }
        assertEquals(0, col.getCard(id).reps)
    }
}
