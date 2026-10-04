package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 端侧 FSRS 优化器的**事件流**（v2.9 计划 Task 4，Room v8 新表）。
 *
 * 与各卡自己那张"当前状态"表（`word_reviews` 等）不同，这张表记录每一次评分动作的**完整快照**：
 * 评了什么卡（[cardKind]+[cardId]）、什么时候（[ts]）、打了什么分（[rating] 1..4）、
 * 距上次多少天（[elapsedDays]）、模型上次排了多久（[scheduledDays]、用于反推预测召回）、
 * 当时处于什么状态（[state] 0New/1Learn/2Review/3Relearn）、作答耗时（[durationMs]）……
 * 优化器（Task 7 LogPacker→Task 9 FsrsLearner）只读这张表，按 `(card_kind, card_id, ts)`
 * 分组升序重放每个人的记忆曲线。
 *
 * **append-only**：只追加、不改写、不删除——复习历史是校准的本钱，一旦允许原地改，
 * 时间线会被后写的状态污染（同 [com.studykit.data.memory.Confidence] 那套"先状态后历史"纪律）。
 * 因此 DAO 只给 insert + 只读 query（见 [com.studykit.data.dao.ReviewLogDao] 与 `ReviewLogDaoTest`）。
 *
 * 列可空性刻意对齐"没记过就是没记过"：[ocrConf]/[selfConf] 可空，拿 0 冒充"用户答『肯定不记得』"
 * 会触发 FSRS 的超纠正（spec §2.3 同口径）；其余写死非空，是每条评分动作都必然知道的量。
 *
 * 索引建在 `(card_kind, card_id, ts)` —— 正是优化器分组扫描的键，一次索引扫全表有序。
 */
@Entity(
    tableName = "review_log",
    indices = [
        Index(
            name = "index_review_log_card_kind_card_id_ts",
            value = ["card_kind", "card_id", "ts"],
        ),
    ],
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 卡类型：word / practice / mistake / excerpt（见 KIND_* 常量） */
    @ColumnInfo(name = "card_kind") val cardKind: String,
    /** 指向该类型主表的行 id（不带外键：append-only 事件流要能在原卡删除后继续留存供回放） */
    @ColumnInfo(name = "card_id") val cardId: Long,
    /** 评分发生的 epoch 毫秒 */
    val ts: Long,
    /** FSRS 口径评分 1..4（Again/Hard/Good/Easy） */
    val rating: Int,
    /** 距该卡上次复习的真实经过天数（模型自变量 Δt） */
    @ColumnInfo(name = "elapsed_days") val elapsedDays: Double,
    /** 评分那一刻模型为该卡排的计划间隔天数（用于事后反推预测召回率） */
    @ColumnInfo(name = "scheduled_days") val scheduledDays: Double,
    /** 评分前该卡所处的 FSRS 状态：0New 1Learn 2Review 3Relearn */
    val state: Int,
    /** 从看到卡片到按下评分的毫秒数 */
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** 事件来源，便于区分同一 kind 下的不同写入路径（如 word 手动 vs OCR 入卡） */
    val origin: String,
    /** 端侧 OCR 识别置信度 0..1；NULL=该卡并非 OCR 产生（spec §阶段8 预留列，暂 NULL） */
    @ColumnInfo(name = "ocr_conf") val ocrConf: Double? = null,
    /** 用户作答前的自评信心（Confidence ordinal+1）；NULL=跳过自评，不可拿 0 冒充"肯定不记得" */
    @ColumnInfo(name = "self_conf") val selfConf: Int? = null,
    /** 同一次学习会话的分组键（去重、统计会话长度用） */
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** 是否补打卡/改期写入：0 正常，1 补录（Lally 2010：补录不断签但单独标识） */
    @ColumnInfo(name = "is_makeup") val isMakeup: Int,
    /** 本行的日志结构版本，日后字段演进时据此判别老行（当前 =1） */
    @ColumnInfo(name = "log_schema_ver") val logSchemaVer: Int,
) {
    companion object {
        /** card_kind 取值域：与四处评分点（Task 6 双写）一一对应 */
        const val KIND_WORD = "word"
        const val KIND_PRACTICE = "practice"
        const val KIND_MISTAKE = "mistake"
        const val KIND_EXCERPT = "excerpt"

        /** 当前事件流 schema 版本 */
        const val CURRENT_SCHEMA_VER = 1
    }
}
