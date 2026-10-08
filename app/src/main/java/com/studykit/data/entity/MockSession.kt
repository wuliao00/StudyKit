package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 一次套卷（模考）的**持久化**会话（v3.0-B）。
 *
 * 之前模考全住在 `MockExamUiState` 里，进程一死整卷作废 —— 而模考恰恰是最长、最不该白做的那种会话。
 *
 * `question_ids` / `answers` / `result` 三段都是 JSON 文本（`org.json`，与 `questions.options_json`
 * 同一手法）：套卷的顺序、作答、判分结果是**整份读写**的，没有人按题目 id 查它们，
 * 拆成子表只会多三次 join 和一轮外键生命周期。`state` 用整数，序号与
 * `MockSessionMachine.MockPhase` 严格对齐（0 sched 1 run 2 paused 3 grading 4 done，文档 L1861）。
 *
 * 今天真正被读回的只有前两段：恢复路径读 `question_ids` 与 `answers`（交卷的行被
 * `observeActive()` 的 `state != 4` 滤掉，压根到不了屏幕）。`result` 是**只写不读**的一列 ——
 * 成绩页那份分数与「时间到」标注来自本次会话的内存状态，不来自这里；留着它的理由是给将来的
 * 同步/备份当底稿（与 `uuid` + `syncStatus` 同一动机），不承诺任何"离开再回来还看得见"的界面行为
 * （评审 F2：那套无人调用的读侧解码已删）。
 *
 * 没有 `exam_id` 外键：考试清单住在设置里而不是表（计划 D2），这里只能按名字引用，
 * 改名后旧会话显示「未关联考试」。`uuid` + `syncStatus` 跟着 `mistakes`/`practice_records` 的
 * 既有形状走，为将来的 CRDT 同步留位。
 */
@Entity(tableName = "mock_sessions", indices = [Index(value = ["created_at"]), Index(value = ["state"])])
data class MockSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val subject: String,
    @ColumnInfo(name = "exam_name") val examName: String = "",
    @ColumnInfo(name = "question_ids") val questionIds: String = "[]",
    val answers: String = "{}",
    val cursor: Int = 0,
    @ColumnInfo(name = "start_ts") val startTs: Long = 0L,
    /** 交卷截止时刻；null = 不限时（设置的 `examLimitMinutes` 为 0 时就是这个） */
    @ColumnInfo(name = "deadline_ts") val deadlineTs: Long? = null,
    /**
     * 这一次暂停的起点（`System.currentTimeMillis()`）；null = 没在暂停中。
     *
     * 只有 `state=PAUSED` 的行才该有值：暂停时写入、继续与交卷时清回 null（写侧全在
     * `StudyViewModel` 那三处）。它存在的唯一理由是**跨进程恢复补不回停表那一段** ——
     * 没有这一列，恢复时只能把"恢复的那一刻"当暂停起点，于是暂停期间真实流逝的时间
     * 全被算成还在作答：10:00 暂停、11:00 回来按下「继续作答」，截止时刻被夹到 11:00，
     * 第一个 tick 就自动交卷（评审 F1）。有这一列，起点是 10:00，整段暂停照数补回。
     *
     * null 与 0 是两回事：0 会被读成「1970 年就暂停了」，等于凭空白送 55 年。
     * 可加列、无 DEFAULT（Room 对可空列的既有约定，以 9.json 为准）。
     */
    @ColumnInfo(name = "paused_at") val pausedAt: Long? = null,
    /**
     * 开卷那一刻这张卷子跑的是哪个限时档（分钟，0 = 不限时）——**只写不读**的审计痕迹。
     *
     * 留着它的理由不是"以后可能要读"，而是**旧行不该被新设置重新解释**：截止时刻 `deadline_ts`
     * 是从这一档限时推出来的，用户后来把限时从 90 改成 30，历史那一行仍然属于"在 90 分钟档下作答"
     * 的那一场；没了这一列，那份记录就只剩下一个无法说明自己是怎么来的 `deadline_ts`。
     * schema 冻在 v9，这一列不删也不动。
     */
    @ColumnInfo(name = "limit_min") val limitMin: Int = 0,
    val state: Int = 0,
    val result: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATE_SCHED = 0
        const val STATE_RUN = 1
        const val STATE_PAUSED = 2
        const val STATE_GRADING = 3
        const val STATE_DONE = 4
    }
}
