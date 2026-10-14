// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.util.Base64
import androidx.annotation.StringRes
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID

/**
 * Collection operations kept separate from WebView and lifecycle handling.
 *
 * None of these calls change the user's selected deck or other global state: the only write is
 * [answer], which records one review exactly like the reviewer would.
 */
internal class LearningRepository(
    private val collection: Collection,
) {
    fun courses(): JSONArray = collection.courses()

    fun lesson(deckId: DeckId): JSONObject = collection.lesson(deckId)

    fun answer(input: JSONObject): JSONObject = collection.saveAnswer(input)
}

internal const val NOTE_TYPE = "小海豹通用学习"
private val abilities = listOf("Recognition", "Recall", "Application")
private val abilityLabels = listOf("识别与理解", "独立回忆", "应用与解题")

/** Upper bounds that keep a lesson request fast even in collections with thousands of cards. */
private const val MAX_DUE_CANDIDATES = 60
private const val MAX_NEW_CANDIDATES = 12

/** Card queues, see rslib/src/card/mod.rs. Negative queues are suspended or buried. */
private const val QUEUE_NEW = 0
private const val QUEUE_LEARN = 1
private const val QUEUE_REVIEW = 2
private const val QUEUE_DAY_LEARN = 3

/**
 * Note types this feature handles. Anki renames a note type on import conflicts
 * (`小海豹通用学习-a1b2c`), so those copies are accepted as well.
 */
internal fun isLearningNotetype(name: String) = name == NOTE_TYPE || name.startsWith("$NOTE_TYPE-")

private fun Collection.learningNotetypeIds(): List<Long> =
    notetypes
        .allNamesAndIds()
        .filter {
            isLearningNotetype(it.name)
        }.map { it.id }
        .toList()

/** SQL condition for "due now" that matches the scheduler's cut-offs. */
private fun Collection.dueCondition(): Pair<String, Array<Any>> {
    val today = sched.today
    val learnCutoff = TimeManager.time.intTime() + sched.learnAheadSeconds()
    return "((c.queue IN ($QUEUE_REVIEW, $QUEUE_DAY_LEARN) AND c.due <= ?) OR (c.queue = $QUEUE_LEARN AND c.due <= ?))" to
        arrayOf<Any>(today, learnCutoff)
}

/**
 * One aggregated query instead of loading every card: O(decks) objects regardless of collection size.
 * Counts are raw (deck daily limits are applied when a lesson is built).
 */
private fun Collection.courses(): JSONArray {
    val result = JSONArray()
    val notetypeIds = learningNotetypeIds()
    if (notetypeIds.isEmpty()) return result
    val (due, dueArgs) = dueCondition()
    val names = decks.allNamesAndIds().associate { it.id to it.name }
    val rows = mutableListOf<JSONObject>()
    db
        .query(
            """
            SELECT c.did, count(*),
                   sum(CASE WHEN $due THEN 1 ELSE 0 END),
                   sum(CASE WHEN c.queue = $QUEUE_NEW THEN 1 ELSE 0 END)
            FROM cards c JOIN notes n ON n.id = c.nid
            WHERE n.mid IN (${notetypeIds.joinToString(",")}) AND c.queue >= 0 AND c.ord < ${abilities.size}
            GROUP BY c.did
            """.trimIndent(),
            *dueArgs,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val deckId = cursor.getLong(0)
                rows +=
                    JSONObject()
                        .put("id", deckId.toString())
                        .put("name", names[deckId] ?: decks.name(deckId))
                        .put("total", cursor.getInt(1))
                        .put("due", cursor.getInt(2))
                        .put("new", cursor.getInt(3))
            }
        }
    rows.sortedBy { it.getString("name") }.forEach { result.put(it) }
    return result
}

/**
 * Candidate cards for one lesson in [deckId] and its subdecks.
 *
 * Unlike the reviewer queue this does not need the deck to be selected, so the user's current deck
 * is left alone. The deck's daily new/review limits (as shown in the deck list) cap the candidates,
 * and the JS planner then picks the most urgent few.
 */
private fun Collection.lesson(deckId: DeckId): JSONObject {
    ensure(decks.have(deckId), R.string.seal_learning_error_deck_missing)
    ensure(config.get<Boolean>("fsrs", false) == true, R.string.seal_learning_error_fsrs_required)
    val notetypeIds = learningNotetypeIds()
    val cards = JSONArray()
    var skipped = 0
    if (notetypeIds.isNotEmpty()) {
        val limits = sched.deckDueTree(deckId)
        val dueLimit = ((limits?.reviewCount ?: MAX_DUE_CANDIDATES) + (limits?.learnCount ?: 0)).coerceIn(0, MAX_DUE_CANDIDATES)
        val newLimit = (limits?.newCount ?: MAX_NEW_CANDIDATES).coerceIn(0, MAX_NEW_CANDIDATES)
        val deckIds = decks.deckAndChildIds(deckId).joinToString(",")
        val base = "FROM cards c JOIN notes n ON n.id = c.nid WHERE n.mid IN (${notetypeIds.joinToString(
            ",",
        )}) AND c.did IN ($deckIds) AND c.ord < ${abilities.size}"
        val (due, dueArgs) = dueCondition()
        val ids =
            db.queryLongList("SELECT c.id $base AND $due ORDER BY c.queue = $QUEUE_LEARN DESC, c.due LIMIT $dueLimit", *dueArgs) +
                db.queryLongList("SELECT c.id $base AND c.queue = $QUEUE_NEW ORDER BY c.due, c.ord LIMIT $newLimit")
        for (id in ids) {
            try {
                lessonCard(id)?.let { cards.put(it) }
            } catch (error: Exception) {
                // One malformed card must not block the whole course.
                Timber.w(error, "learning: skipping card %d", id)
                skipped++
            }
        }
    }
    return JSONObject().put("cards", cards).put("skipped", skipped)
}

private fun Collection.lessonCard(cardId: CardId): JSONObject? {
    val card = getCard(cardId)
    val note = card.note(this)
    if (!isLearningNotetype(note.notetype.name) || card.ord !in abilities.indices) return null
    val data = note.getItem(abilities[card.ord])
    if (data.isBlank()) return null
    val decoded = if (data.startsWith("b64:")) String(Base64.decode(data.removePrefix("b64:"), Base64.DEFAULT), Charsets.UTF_8) else data
    val exercise = JSONObject(decoded)
    val metadata = runCatching { JSONObject(card.customData).optJSONObject("lp") }.getOrNull()
    // Memory state is normally stored on the card; computing it is only a fallback for older reviews.
    val memory =
        if (card.reps > 0 && (card.memoryState == null || card.decay == null || card.desiredRetention == null)) {
            computeMemoryState(card.id)
        } else {
            null
        }
    val lastReview =
        card.lastReviewTimeSecs
            ?: if (card.reps > 0) {
                db.queryLongScalar("SELECT coalesce(max(id), 0) FROM revlog WHERE cid = ? AND ease BETWEEN 1 AND 4", card.id) / 1000
            } else {
                0L
            }
    return JSONObject().apply {
        put("id", card.id.toString())
        put("noteId", card.nid.toString())
        put("title", Jsoup.parse(note.getItem("Title")).text())
        put("subject", Jsoup.parse(note.getItem("Subject")).text())
        put("ability", abilityLabels[card.ord])
        put("exercise", exercise)
        put("expectedReps", card.reps)
        put("contentHash", contentHash(data))
        put("token", UUID.randomUUID().toString())
        put("startedAt", TimeManager.time.intTimeMS())
        put("isNew", card.reps == 0)
        put("stability", card.memoryState?.stability ?: memory?.stability ?: JSONObject.NULL)
        put("lastReview", if (lastReview > 0) lastReview else JSONObject.NULL)
        put("decay", card.decay ?: memory?.decay ?: JSONObject.NULL)
        put("retention", card.desiredRetention ?: memory?.desiredRetention ?: 0.9)
        put("lastRating", metadata?.optInt("r", 0) ?: 0)
        put("failures", metadata?.optInt("f", 0) ?: 0)
    }
}

/** The token is stored atomically in the new scheduling state, making retry safe. */
private fun Collection.saveAnswer(input: JSONObject): JSONObject {
    val card = getCard(input.getString("cardId").toLong())
    ensure(isLearningNotetype(card.note(this).notetype.name), R.string.seal_learning_error_not_interactive)
    ensure(card.ord in abilities.indices, R.string.seal_learning_error_unsupported_template)
    val token = input.getString("token")
    ensure(
        token.length == 36 &&
            runCatching {
                UUID.fromString(token).toString() == token
            }.getOrDefault(false),
        R.string.seal_learning_error_invalid_submission,
    )
    val custom = if (card.customData.isBlank()) JSONObject() else JSONObject(card.customData)
    if (custom.optJSONObject("lp")?.optString("t") == token) return JSONObject().put("saved", true)
    ensure(card.reps == input.getInt("expectedReps"), R.string.seal_learning_error_stale_reviewed, stale = true)
    ensure(
        contentHash(card.note(this).getItem(abilities[card.ord])) == input.getString("contentHash"),
        R.string.seal_learning_error_stale_edited,
        stale = true,
    )
    val rating = input.getInt("rating")
    ensure(rating in 1..3, R.string.seal_learning_error_invalid_submission)
    ensure(input.getInt("attempts") in 1..60, R.string.seal_learning_error_invalid_submission)
    val previousFailures = custom.optJSONObject("lp")?.optInt("f", 0) ?: 0
    custom.put("lp", JSONObject().put("t", token).put("r", rating).put("f", if (rating == 1) (previousFailures + 1).coerceAtMost(5) else 0))
    ensure(custom.toString().toByteArray().size <= 255, R.string.seal_learning_error_custom_data_full)
    card.timerStarted = TimeManager.time.intTimeMS() - input.optLong("durationMs", 0).coerceIn(0, 3_600_000)
    val states = backend.getSchedulingStates(card.id)
    val answer = sched.buildAnswer(card, states, Rating.forNumber(rating - 1))
    backend.answerCard(
        answer
            .toBuilder()
            .setNewState(
                answer.newState
                    .toBuilder()
                    .setCustomData(custom.toString())
                    .build(),
            ).build(),
    )
    return JSONObject().put("saved", true).put("cardId", card.id.toString())
}

/**
 * A user-facing failure. [stale] means another review or an edit got there first; the web UI then
 * drops the pending answer instead of retrying it.
 */
internal class LearningException(
    @StringRes val messageRes: Int,
    val stale: Boolean = false,
) : IllegalArgumentException("learning error $messageRes")

private fun ensure(
    condition: Boolean,
    @StringRes messageRes: Int,
    stale: Boolean = false,
) {
    if (!condition) throw LearningException(messageRes, stale)
}

private fun contentHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
