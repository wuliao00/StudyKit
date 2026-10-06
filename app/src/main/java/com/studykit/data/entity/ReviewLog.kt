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
 * 距上次多少天（[elapsedDays]）、**上次**排了多久（[scheduledDays]，后向口径，用于反推预测召回）、
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
    /**
     * 距该卡上次复习的真实经过天数（模型自变量 Δt）。锚点**按来源不同**：词是
     * `last_review_at ?: createdAt`（首评那次没有 `last_review_at`，退回加入学习那天，否则 Δt=0
     * 会让第一次评分的加固量归零）、书摘只有 `created_at`；练习与错题两路拿不到真实 Δt，记 0.0。
     *
     * ⚠️ 它与本行 [scheduledDays] 的锚点**不是同一个**：那一列的锚点只认「评分前那份行上的上次复习时刻」
     * （[ReviewLog.prevScheduledDays] 遇 null 直接回 0.0），所以词的**首评行**是
     * `elapsedDays > 0` 而 `scheduledDays = 0.0`——两个数各自讲的是"经过多久"与"上次排了多久"，
     * 首评那行本来就没有"上次"可言。
     */
    @ColumnInfo(name = "elapsed_days") val elapsedDays: Double,
    /**
     * **后向口径**：这次评分**之前**该卡被排出去的那段计划间隔（天）。
     *
     * 一句话定义：`到期时刻 − 上次复习时刻`，两个数都出自**评分前**那一份行快照。锚点是那一行的
     * 上次复习时刻（词 `last_review_at`、书摘只有 `created_at`），**没有** `?: created_at` 那层回退
     * ——回退只属于本行 [elapsedDays] 的锚点（词侧首评），见那条 KDoc。所以在词与书摘那些行上
     * `scheduledDays − elapsedDays` 恰好是「这次比计划早（正）/ 迟（负）几天」，
     * 事后反推预测召回 `recall(w, S, elapsed)` 时才拿得到对的 Δt（[com.studykit.data.memory.DueForecast]
     * 的朴素基线用的就是这一式）。等价说法：第 k 行的这一列 == 第 k−1 行那次评分刚排出去的间隔。
     * 词与书摘之外的两路（练习、错题）拿不到这两个时刻，`elapsedDays` 与这一列**成对**记 0.0。
     *
     * 与 py-fsrs 的 `review_log.scheduled_days`、[com.studykit.optimize.SynthLog] 同一条约定
     * （后者每行记的正是"促成这次复习的那个间隔"，见 `SynthLog.kt` 的 `tSched`）。
     *
     * **不要**写"这次评分刚排出去的间隔"（前向）。那会把整列相对该卡自己的历史串行一格，
     * 还会让同一行里 `elapsedDays`（出自评分前）与 `scheduledDays`（出自评分后）配错对。
     * 表是 append-only、没有回填路径，两种口径混在一列里就再也分不干净 —— v2.9 的词侧就犯过这个错，
     * 从此由 [ReviewLog.CURRENT_SCHEMA_VER]=2 划界（v1 老行是混合口径）。词与书摘两处评分点统一走
     * [ReviewLog.prevScheduledDays] 这一式（练习/错题拿不到这两个时刻，见上面那句成对记 0.0）。
     */
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
    /** 本行的日志结构版本，日后字段/语义演进时据此判别老行（当前 =[ReviewLog.CURRENT_SCHEMA_VER]=2） */
    @ColumnInfo(name = "log_schema_ver") val logSchemaVer: Int,
) {
    companion object {
        /** card_kind 取值域：与四处评分点（Task 6 双写）一一对应 */
        const val KIND_WORD = "word"
        const val KIND_PRACTICE = "practice"
        const val KIND_MISTAKE = "mistake"
        const val KIND_EXCERPT = "excerpt"

        private const val DAY_MS = 24L * 3_600_000L

        /**
         * 当前事件流 schema 版本 —— **改的是列的语义，不是列**：表结构没动（Room 仍 v8、零迁移），
         * 老行靠这一列认。
         *
         * v1 → v2（v3.0-A T2.5）：`scheduled_days` 从这一版起四处评分点**统一为后向口径**
         * （「这次评分之前该卡被排出去的计划间隔」，见 [ReviewLog.scheduledDays] 的 KDoc）。
         * v1 的老行是**混合口径**：词侧（`StudyViewModel.gradeCard`）记的是「这次刚排出去的间隔」
         * （前向，相对本卡历史串行一格）、书摘侧恒 0.0（间隔没从 `ExcerptReview` 回传）、
         * 练习与错题两侧恒 0.0（self-consistent，本就没这两维）、只有 [com.studykit.optimize.SynthLog]
         * 一直是后向。表 append-only、无回填路径，所以两版会长期共存。
         *
         * 消费侧规则：**只有 `logSchemaVer >= 2` 的行可以信 `scheduled_days`**（按卡回放、反推预测召回、
         * 负荷/到期基线都算）；v1 行要么把这一列当不可用，要么自己从前一行的到期重建。
         * `elapsed_days` / `rating` / `state` 两版同义，不受这次改口径影响。
         */
        const val CURRENT_SCHEMA_VER = 2

        /**
         * 把「评分前那一份行快照」折成 [ReviewLog.scheduledDays]（后向口径）。
         *
         * **只有两处**评分点走这一式——词（`StudyViewModel.gradeCard`，锚点 `word.lastReviewAt`）与书摘
         * （`ExcerptReview.schedule` → [com.studykit.data.memory.ExcerptReviewResult.prevScheduledDays]，
         * 锚点 `excerpt.createdAt`），
         * 免得两处各写一遍算出两个数（词侧当年的错就不是算错而是取错列）。
         * **练习与错题不走这里**：那两路既没有 `last_review_at` 也没有可用的到期偏移，`elapsed_days` 与
         * `scheduled_days` **成对**记 0.0（`MistakeViewModel.gradeRedo` / `StudyViewModel.selectOption`）——
         * 只把 scheduled 那一列单独补成"刚排出去的间隔"反而会把这一列串回前向。
         *
         * @param anchorAt 评分**前**那份行上的"上次复习时刻"：词是 `last_review_at`（首评为 null ⇒
         *                 本式回 0.0；这与同行 `elapsedDays` 的锚点不同，那一列首评退回 `created_at`，
         *                 见 [ReviewLog.elapsedDays] 的 KDoc），书摘只有 `created_at`
         *                 （表没有 last_review_at，`ExcerptReview` 的 KDoc 已登记这笔近似）。
         * @param dueAt    评分**前**行上的到期时刻（`next_review_at`）；`<= 0` 是"从没排过/未启用"哨兵。
         * @return 到期减锚点的天数；没有上次排期可言（首评、哨兵）与脏数据（到期早于锚点）一律 0.0，
         *         与 [com.studykit.optimize.SynthLog] 的首评行同口径，不凭空造间隔。
         */
        fun prevScheduledDays(anchorAt: Long?, dueAt: Long): Double =
            if (anchorAt == null || dueAt <= 0L) {
                0.0
            } else {
                (dueAt - anchorAt).coerceAtLeast(0L) / DAY_MS.toDouble()
            }
    }
}
