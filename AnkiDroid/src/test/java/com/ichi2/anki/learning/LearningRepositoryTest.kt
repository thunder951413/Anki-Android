// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.testutils.JvmTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LearningRepositoryTest : JvmTest() {
    private val exercise =
        """{"version":1,"steps":[{"id":"meaning","kind":"choice","prompt":"apple 是什么？",""" +
            """"options":["苹果","香蕉"],"answer":0,"explanation":"apple 表示苹果。"}]}"""

    @Before
    fun prepareLearningNoteType() {
        addStandardNoteType(
            NOTE_TYPE,
            arrayOf("Title", "Subject", "Topic", "Recognition", "Recall", "Application"),
            "{{Title}}",
            "{{Recognition}}",
        )
        col.config.set("fsrs", true)
    }

    private fun addGoal(
        title: String = "apple",
        recognition: String = exercise,
        noteType: String = NOTE_TYPE,
    ): Long = addNoteUsingNoteTypeName(noteType, title, "英语", "日常", recognition, "", "").cards(col).first().id

    private fun lessonCards(deckId: Long = col.decks.selected()) = LearningRepository(col).lesson(deckId).getJSONArray("cards")

    private fun answerFor(cardId: Long): JSONObject {
        val card = lessonCards().getJSONObject(0)
        assertEquals(cardId.toString(), card.getString("id"))
        return JSONObject()
            .put("cardId", card.getString("id"))
            .put("expectedReps", card.getInt("expectedReps"))
            .put("contentHash", card.getString("contentHash"))
            .put("token", card.getString("token"))
            .put("rating", 3)
            .put("attempts", 1)
            .put("durationMs", 1000)
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
        val error = assertFailsWith<LearningException> { LearningRepository(col).answer(answer) }
        assertTrue(error.stale)
        assertEquals(0, col.getCard(id).reps)
        assertEquals(0L, col.db.queryLongScalar("SELECT count(*) FROM revlog WHERE cid = ?", id))
    }

    @Test
    fun `ordinary Anki cards cannot be submitted as interactive reviews`() {
        val ordinary = addBasicNote("front", "back").cards(col).first()
        val goal = addGoal()
        val answer = answerFor(goal).put("cardId", ordinary.id.toString())
        val error = assertFailsWith<LearningException> { LearningRepository(col).answer(answer) }
        assertEquals(R.string.seal_learning_error_not_interactive, error.messageRes)
        assertEquals(0, col.getCard(ordinary.id).reps)
    }

    @Test
    fun `modified content cannot accept the old answer`() {
        val id = addGoal()
        val answer = answerFor(id).put("contentHash", "outdated-content")
        val error = assertFailsWith<LearningException> { LearningRepository(col).answer(answer) }
        assertTrue(error.stale)
        assertEquals(0, col.getCard(id).reps)
    }

    @Test
    fun `building a lesson leaves the selected deck alone`() {
        val other = addDeck("Other")
        val course = addDeck("English")
        col.decks.select(other)
        val note = col.getCard(addGoal()).note(col)
        note.moveToDeck("English")
        assertEquals(1, lessonCards(course).length())
        assertEquals(other, col.decks.selected())
    }

    @Test
    fun `courses are aggregated per deck without counting suspended cards`() {
        addDeck("English")
        repeat(3) { addGoal("word $it").let { id -> col.getCard(id).note(col).moveToDeck("English") } }
        val suspended = addGoal("suspended")
        col.getCard(suspended).note(col).moveToDeck("English")
        col.sched.suspendCards(listOf(suspended))
        addBasicNote("ordinary", "card").moveToDeck("English")

        val courses = LearningRepository(col).courses()
        assertEquals(1, courses.length())
        val course = courses.getJSONObject(0)
        assertEquals("English", course.getString("name"))
        assertEquals(3, course.getInt("total"))
        assertEquals(3, course.getInt("new"))
        assertEquals(0, course.getInt("due"))
    }

    @Test
    fun `a renamed copy of the note type from an import is still recognised`() {
        addStandardNoteType(
            "$NOTE_TYPE-a1b2c",
            arrayOf("Title", "Subject", "Topic", "Recognition", "Recall", "Application"),
            "{{Title}}",
            "{{Recognition}}",
        )
        val id = addGoal(noteType = "$NOTE_TYPE-a1b2c")
        assertEquals(id.toString(), lessonCards().getJSONObject(0).getString("id"))
        assertTrue(isLearningNotetype("$NOTE_TYPE-a1b2c"))
        assertFalse(isLearningNotetype("${NOTE_TYPE}2"))
    }

    @Test
    fun `one malformed card does not block the lesson`() {
        addGoal("broken", recognition = "{not json")
        val good = addGoal("good")
        val lesson = LearningRepository(col).lesson(col.decks.selected())
        assertEquals(1, lesson.getInt("skipped"))
        assertEquals(good.toString(), lesson.getJSONArray("cards").getJSONObject(0).getString("id"))
    }

    @Test
    fun `lessons require FSRS`() {
        col.config.set("fsrs", false)
        addGoal()
        val error = assertFailsWith<LearningException> { lessonCards() }
        assertEquals(R.string.seal_learning_error_fsrs_required, error.messageRes)
    }

    @Test
    fun `known_hosts validation rejects private keys and accepts host lines`() {
        assertTrue(SshSettingsActivity.looksLikeKnownHosts("# comment\nexample.org ssh-ed25519 AAAAC3Nza\n"))
        assertTrue(SshSettingsActivity.looksLikeKnownHosts("[example.org]:2222 ecdsa-sha2-nistp256 AAAAE2V"))
        assertTrue(SshSettingsActivity.looksLikeKnownHosts("@cert-authority *.example.org ssh-rsa AAAAB3"))
        assertFalse(SshSettingsActivity.looksLikeKnownHosts("-----BEGIN OPENSSH PRIVATE KEY-----\nabc"))
        assertFalse(SshSettingsActivity.looksLikeKnownHosts("hello world"))
    }
}
