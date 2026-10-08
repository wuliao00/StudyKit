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
 * 跨进程恢复时，暂停的起点该取哪一刻（评审 F1）。
 *
 * 库里那一列（`mock_sessions.paused_at`）就是为这一刻留的：没有它，恢复只能把**恢复的此刻**当暂停
 * 起点，`deadlineAfterResume` 于是算出 `pausedFor = 0`，截止时刻被 `.coerceAtLeast(nowTs)` 夹到
 * 「现在」—— 用户按下「继续作答」后的第一个 tick 就自动交卷，整段停表被悄悄吃掉。
 *
 * 只认**可信**的锚点，两种情形退回"此刻才暂停"（= 不补时）：
 * - 那一列是 null：这一行不是暂停中被杀的（作答中崩了，钟本来就一直在跑），白送时间等于允许
 *   「关掉 App 睡一觉再回来继续考」；
 * - 那一列在现在之后：设备时钟往回跳过，那段"暂停时长"是负的 —— [deadlineAfterResume] 自己也会
 *   把负当 0，这里先把锚点收回此刻，免得两个下限各说各话。
 *
 * 这一式只做"取哪个数"，**不写新的截止时刻算术**：补多少仍然只由 [deadlineAfterResume] 一处决定。
 */
internal fun pausedAnchorForRestore(rowPausedAt: Long?, nowTs: Long): Long =
    rowPausedAt?.takeIf { it in 1L..nowTs } ?: nowTs

/**
 * 「上次那卷还没交」那张卡上念的剩余时间（评审 F1 的界面那一半）。
 *
 * 用暂停那一刻的读数、而不是屏幕冻结着的那个此刻：默认暂停即停表，所以「恢复时手里还剩多少」
 * 与用户在外面待了多久**无关**（`deadline + (now - pausedAt) - now` 就是 `deadline - pausedAt`），
 * 提前按 [deadlineAfterResume] 演一遍恢复那一刻会算出的截止时刻，再叫同一个读数函数 ——
 * 于是卡上那个数和按下「继续作答」后表上那个数是同一份算术，不会出现"卡上写剩余 00:00、
 * 续上之后却还剩 5 分钟"这种两头骗的显示。
 *
 * 暂停也扣时（`examPauseCountsTime=true`）时 [deadlineAfterResume] 原样返回截止时刻，读数就是
 * 真实剩余 —— 已经跑到 00:00 也照实写 00:00：用户在这一格上看得见"这卷时间已经用完了"，
 * 「继续作答」按下去的后果不是秘密（见 [StudyViewModel.resumeMockExam]）。
 */
internal fun pausedRemainingLabel(
    nowTs: Long,
    pausedAtTs: Long?,
    deadlineTs: Long?,
    pauseCountsTime: Boolean,
): String? {
    val anchor = pausedAnchorForRestore(pausedAtTs, nowTs)
    return remainingLabel(nowTs, deadlineAfterResume(nowTs, anchor, deadlineTs, pauseCountsTime))
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

/**
 * 交卷摘要（写进 `mock_sessions.result` 那一列的字节）。
 *
 * ⛔ 这一列**今天没有任何读侧**（评审 F2 的裁定）：`StudyViewModel.submitMockExam` 把它写下去，
 * 而成绩页那一份分数/待批改/「时间到」全部来自**本次会话的内存状态**（`MockExamUiState`），
 * 恢复路径 [StudyViewModel.loadActiveMockSession] 只读 `question_ids` 与 `answers` —— DONE 的行
 * 会被 `observeActive()` 的 `state != 4` 滤掉，压根到不了屏幕上。所以这里只留写侧：
 * 那一列的值是给**将来的同步/备份**留的底稿（`uuid` + `syncStatus` 同一动机），
 * 不承诺任何"离开再回来还能看到这句标注"的界面行为 —— 以前那句承诺是假的（逐题判词的
 * `encodeResults`/`decodeResults` 与摘要的 `decodeExamResult` 三个函数写完之后无人调用，
 * 已随本轮删除；要恢复成绩页的历史清单，那时按新的读法一起补）。
 *
 * [autoSubmitted] 记的是"这一卷是钟替他按的交卷，不是他自己按的"（R3）。它在**这一次**会话里
 * 是真的有效读数（结果页那句「时间到，已自动交卷」从 `MockExamUiState` 读它），落库那一列只是
 * 顺手留档，别把它当成跨进程的保证。
 */
internal fun encodeExamResult(out: MockSubmission, autoSubmitted: Boolean = false): String =
    JSONObject()
        .put("correct", out.correctCount)
        .put("total", out.total)
        .put("graded", out.answeredCount)
        .put("toGrade", JSONArray().apply { out.toGrade.forEach { put(it) } })
        .put("auto", if (autoSubmitted) 1 else 0)
        .toString()
