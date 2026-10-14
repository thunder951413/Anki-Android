// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.util.Base64
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import org.jsoup.Jsoup

/** Collection operations kept separate from WebView and lifecycle handling. */
internal class LearningRepository(private val collection: Collection) {
    fun courses(): JSONArray = collection.courses()
    fun lesson(deckId: Long): JSONArray = collection.lesson(deckId)
    fun answer(input: JSONObject): JSONObject = collection.saveAnswer(input)
}

private const val NOTE_TYPE = "小海豹通用学习"
private val abilities = listOf("Recognition", "Recall", "Application")
private val abilityLabels = listOf("识别与理解", "独立回忆", "应用与解题")

private fun Collection.courses(): JSONArray {
    val counts = findCards("note:\"$NOTE_TYPE\" -is:suspended -is:buried").map { getCard(it).did }.groupingBy { it }.eachCount()
    return JSONArray().apply {
        counts.entries.sortedBy { decks.name(it.key) }.forEach { (deckId, count) ->
            put(JSONObject().put("id", deckId.toString()).put("name", decks.name(deckId)).put("count", count))
        }
    }
}

private fun Collection.lesson(deckId: Long): JSONArray {
    require(decks.have(deckId)) { "牌组不存在。" }
    require(config.get<Boolean>("fsrs", false) == true) { "请先在复习设置中开启 FSRS，再开始基于遗忘曲线的短课。" }
    decks.select(deckId)
    val queue = backend.getQueuedCards(fetchLimit = 1000, intradayLearningOnly = false)
    return JSONArray().apply {
        queue.cardsList.forEach { queued ->
            val card = Card(queued.card)
            val note = card.note(this@lesson)
            if (note.notetype.name != NOTE_TYPE || card.ord !in abilities.indices) return@forEach
            val data = note.getItem(abilities[card.ord])
            if (data.isBlank()) return@forEach
            val decoded = if (data.startsWith("b64:")) String(Base64.decode(data.removePrefix("b64:"), Base64.DEFAULT), Charsets.UTF_8) else data
            val exercise = JSONObject(decoded)
            val metadata = runCatching { JSONObject(card.customData).optJSONObject("lp") }.getOrNull()
            val memory = if (card.reps > 0 && (card.memoryState == null || card.decay == null || card.desiredRetention == null)) computeMemoryState(card.id) else null
            put(JSONObject().apply {
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
                val lastReview = card.lastReviewTimeSecs ?: if (card.reps > 0) db.queryLongScalar("SELECT coalesce(max(id), 0) FROM revlog WHERE cid = ? AND ease BETWEEN 1 AND 4", card.id) / 1000 else 0L
                put("lastReview", if (lastReview > 0) lastReview else JSONObject.NULL)
                put("decay", card.decay ?: memory?.decay ?: JSONObject.NULL)
                put("retention", card.desiredRetention ?: memory?.desiredRetention ?: 0.9)
                put("lastRating", metadata?.optInt("r", 0) ?: 0)
                put("failures", metadata?.optInt("f", 0) ?: 0)
            })
        }
    }
}

/** The token is stored atomically in the new scheduling state, making retry safe. */
private fun Collection.saveAnswer(input: JSONObject): JSONObject {
    val card = getCard(input.getString("cardId").toLong())
    require(card.note(this).notetype.name == NOTE_TYPE) { "只能提交互动学习卡片。" }
    require(card.ord in abilities.indices) { "不支持这个卡片模板。" }
    val token = input.getString("token")
    require(token.length == 36 && UUID.fromString(token).toString() == token) { "无效提交 ID。" }
    val custom = if (card.customData.isBlank()) JSONObject() else JSONObject(card.customData)
    if (custom.optJSONObject("lp")?.optString("t") == token) return JSONObject().put("saved", true)
    require(card.reps == input.getInt("expectedReps")) { "STALE: 题目已在其他学习流程中更新，请返回课程刷新。" }
    require(contentHash(card.note(this).getItem(abilities[card.ord])) == input.getString("contentHash")) { "STALE: 卡片内容已修改，请返回课程刷新。" }
    val rating = input.getInt("rating")
    require(rating in 1..3) { "无效评分。" }
    require(input.getInt("attempts") in 1..60) { "无效练习次数。" }
    val previousFailures = custom.optJSONObject("lp")?.optInt("f", 0) ?: 0
    custom.put("lp", JSONObject().put("t", token).put("r", rating).put("f", if (rating == 1) (previousFailures + 1).coerceAtMost(5) else 0))
    require(custom.toString().toByteArray().size <= 255) { "卡片自定义数据已满，请先清理旧扩展数据。" }
    card.timerStarted = TimeManager.time.intTimeMS() - input.optLong("durationMs", 0).coerceIn(0, 3_600_000)
    val states = backend.getSchedulingStates(card.id)
    val answer = sched.buildAnswer(card, states, Rating.forNumber(rating - 1))
    backend.answerCard(answer.toBuilder().setNewState(answer.newState.toBuilder().setCustomData(custom.toString()).build()).build())
    return JSONObject().put("saved", true).put("cardId", card.id.toString())
}

private fun contentHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
