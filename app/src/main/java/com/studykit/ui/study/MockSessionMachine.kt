package com.studykit.ui.study

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 套卷（模考）的状态机与时限算术（v3.0-B T10，文档 L1883/L1889）。
 *
 * 零 Android、零 Room、零时钟 —— `nowTs` 一律由调用方注入，测试给假时钟即可钉死超时与暂停。
 * 判分**不在这里重写**：那份算术已经在 [MockExamState] 里被 6 条单测钉住，这里只叫它，
 * 然后加它没有的四件事：阶段、截止时刻、暂停是否扣时、未答进待批改。
 *
 * 阶段序号（[MockPhase]）与 `mock_sessions.state` 列的整数**严格对齐**文档 L1861
 * （0 sched 1 run 2 paused 3 grading 4 done）。库里读到对齐不上的整数退回 [MockPhase.SCHED]，
 * 绝不退回 DONE —— 那等于凭空伪造一份"已交卷"的历史（见测试）。
 *
 * 依赖纪律：本文件只许 `org.json` + stdlib + [MockExamState]。`MockSession` 那张 Room 表归
 * ViewModel 侧用，这里连它的常量都不引用 —— 一旦纯层开始读实体，`state` 的口径就有了两处真值。
 */
internal enum class MockPhase { SCHED, RUN, PAUSED, GRADING, DONE }

/** 与 `mock_sessions.state` 列的整数一一对应（文档 L1861） */
internal fun encodePhase(phase: MockPhase): Int = when (phase) {
    MockPhase.SCHED -> 0
    MockPhase.RUN -> 1
    MockPhase.PAUSED -> 2
    MockPhase.GRADING -> 3
    MockPhase.DONE -> 4
}

internal fun decodePhase(raw: Int): MockPhase = when (raw) {
    0 -> MockPhase.SCHED
    1 -> MockPhase.RUN
    2 -> MockPhase.PAUSED
    3 -> MockPhase.GRADING
    4 -> MockPhase.DONE
    else -> MockPhase.SCHED
}

/** 到点即到期（`now >= deadline`）。`deadlineTs == null` = 不限时，永不到期 */
internal fun isExpired(nowTs: Long, deadlineTs: Long?): Boolean = deadlineTs != null && nowTs >= deadlineTs

/**
 * 恢复时该把截止时刻定在哪。
 *
 * `pauseCountsTime = true`：暂停也扣时，截止不动（原样返回）。
 * `false`（默认，真实考场的"pause 即停表"）：把暂停掉的那段加回去。
 *
 * 两处下限：暂停时长取负当 0（设备时钟往回跳过不能白送时间）；推出来的截止不能早于现在
 * （早在过去 = 恢复瞬间就"时间到"，那是把用户的剩余时间吃掉而不是停表）。
 */
internal fun deadlineAfterResume(
    nowTs: Long,
    pausedAt: Long,
    deadlineTs: Long?,
    pauseCountsTime: Boolean,
): Long? {
    if (deadlineTs == null) return null
    if (pauseCountsTime) return deadlineTs
    val pausedFor = (nowTs - pausedAt).coerceAtLeast(0L)
    return (deadlineTs + pausedFor).coerceAtLeast(nowTs)
}

internal fun remainingMs(nowTs: Long, deadlineTs: Long?): Long? =
    deadlineTs?.let { (it - nowTs).coerceAtLeast(0L) }

/** 屏幕上那串 `mm:ss`；不限时不给文案（页面就不画那一格） */
internal fun remainingLabel(nowTs: Long, deadlineTs: Long?): String? {
    val ms = remainingMs(nowTs, deadlineTs) ?: return null
    val totalSec = (ms / 1_000L).toInt()
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * 一次交卷的产出。
 *
 * - [graded]：答过的那些题的判词（出自 [MockExamState]，与练习/背词同一套算术）；
 * - [toGrade]：**没答**的题（文档 L1883「未答入 grading 队列」）—— 判不了，因为没人给出过选择，
 *   把它们当"答错"是在用 0 冒充一个没发生的事实（`MockExamState` 自己会那么记，这里把那些项摘出去）；
 * - [correctCount] 只统计 [graded]，于是页面上的分母是「判了 N 题」而不是整卷题数。
 */
internal data class MockSubmission(
    val graded: Map<Long, MockExamState.Answer>,
    val toGrade: List<Long>,
    val correctCount: Int,
    val answeredCount: Int,
    val total: Int,
)

internal fun submitOutcome(
    questionIds: List<Long>,
    selections: Map<Long, Int>,
    answerKey: Map<Long, Int>,
    answerCount: Int,
): MockSubmission {
    val machine = MockExamState(questionIds = questionIds, answerCount = answerCount, answerKey = answerKey)
    selections.forEach { (id, selected) -> machine.answer(id, selected) }
    val all = machine.submit()
    val toGrade = questionIds.filterNot { selections.containsKey(it) }
    val graded = all.filterKeys { it !in toGrade.toSet() }
    return MockSubmission(
        graded = graded,
        toGrade = toGrade,
        correctCount = graded.values.count { it.correct },
        answeredCount = graded.size,
        total = questionIds.size,
    )
}

// ── `mock_sessions` 那几个 JSON 列的编解码 ───────────────────────────────
// 读侧一律容错：脏串 -> 空表，而不是抛。这一列可能来自旧版本、也可能来自用户手改的恢复包，
// 与 `AppSettings.fromMap` 同一条纪律。写侧只写自己认得的形状。

internal fun encodeQuestionIds(ids: List<Long>): String {
    val arr = JSONArray()
    ids.forEach { arr.put(it) }
    return arr.toString()
}

internal fun decodeQuestionIds(raw: String): List<Long> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val v = if (arr.get(i) is Number) arr.getLong(i) else null
                if (v != null) add(v)
            }
        }
    } catch (e: JSONException) {
        emptyList()
    }
}

internal fun encodeAnswers(answers: Map<Long, Int>): String {
    val o = JSONObject()
    answers.forEach { (id, selected) -> o.put(id.toString(), selected) }
    return o.toString()
}

internal fun decodeAnswers(raw: String): Map<Long, Int> {
    if (raw.isBlank()) return emptyMap()
    return try {
        val o = JSONObject(raw)
        buildMap {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val id = k.toLongOrNull() ?: continue
                val v = if (o.get(k) is Number) o.getInt(k) else null
                if (v != null) put(id, v)
            }
        }
    } catch (e: JSONException) {
        emptyMap()
    }
}

internal fun encodeResults(results: Map<Long, MockExamState.Answer>): String {
    val o = JSONObject()
    results.forEach { (id, a) ->
        o.put(id.toString(), JSONObject().put("s", a.selected ?: JSONObject.NULL).put("c", if (a.correct) 1 else 0))
    }
    return o.toString()
}

internal fun decodeResults(raw: String?): Map<Long, MockExamState.Answer> {
    if (raw.isNullOrEmpty() || raw == "{}") return emptyMap()
    return try {
        val o = JSONObject(raw)
        buildMap {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val id = k.toLongOrNull() ?: continue
                val v = o.optJSONObject(k) ?: continue
                val selected = if (v.isNull("s")) null else v.optInt("s", -1).takeIf { it >= 0 }
                put(id, MockExamState.Answer(selected = selected, correct = v.optInt("c") == 1))
            }
        }
    } catch (e: JSONException) {
        emptyMap()
    }
}

/**
 * 交卷摘要（`mock_sessions.result` 那一列的可读形状）。
 *
 * 只有分数与待批改清单，逐题判词住在 [encodeResults] 写的那一列：结果页要重画整卷时读后者，
 * 而"这卷考了多少分"这种一句话读数才值得单独留着。
 *
 * [autoSubmitted] 记的是"这一卷是钟替他按的交卷，不是他自己按的"（R3）。它必须落在行里而不是
 * 只落在界面上：结果页那句「时间到，已自动交卷」是**成绩的一部分**，一条只靠 toast 的标注在用户
 * 离开再回来时就没影了，而那时屏幕上仍然印着那个分数。
 */
internal data class MockSummary(
    val correctCount: Int,
    val total: Int,
    val toGrade: List<Long>,
    val autoSubmitted: Boolean = false,
)

internal fun encodeExamResult(out: MockSubmission, autoSubmitted: Boolean = false): String =
    JSONObject()
        .put("correct", out.correctCount)
        .put("total", out.total)
        .put("graded", out.answeredCount)
        .put("toGrade", JSONArray().apply { out.toGrade.forEach { put(it) } })
        .put("auto", if (autoSubmitted) 1 else 0)
        .toString()

/** 摘要读不通返回 null（宁缺一条分数，不显示一个 0 分的假成绩） */
internal fun decodeExamResult(raw: String?): MockSummary? {
    if (raw.isNullOrEmpty()) return null
    val o = try { JSONObject(raw) } catch (e: JSONException) { return null }
    if (!o.has("total")) return null
    return MockSummary(
        correctCount = o.optInt("correct"),
        total = o.optInt("total"),
        toGrade = o.optJSONArray("toGrade")?.let { arr ->
            buildList { for (i in 0 until arr.length()) arr.optLong(i).takeIf { it > 0L }?.let { add(it) } }
        } ?: emptyList(),
        // 缺键（旧版本写下的行）读成 false：没有证据说那是自动交卷，就不能凭空标注「时间到」
        autoSubmitted = o.optInt("auto") == 1,
    )
}
