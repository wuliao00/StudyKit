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
