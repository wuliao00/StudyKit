package com.studykit.data.repository

import com.studykit.data.dao.ReviewLogDao
import com.studykit.data.entity.ReviewLog

/**
 * `review_log` 事件流的写入侧（v2.9 计划 Task 6）。
 *
 * 四处评分点（背词 [com.studykit.ui.study.StudyViewModel.gradeCard]、练习提交
 * [com.studykit.ui.study.StudyViewModel.selectOption]、错题重做
 * [com.studykit.ui.mistake.MistakeViewModel.gradeRedo]、书摘自评
 * [com.studykit.ui.book.BookViewModel.gradeExcerptReview]）在**各自的既有状态写之后**
 * 收敛成对本方法的一次调用——先状态后历史那条纪律（同 `WordRepository.applyReview →
 * recordGradedReview`、`MistakeScheduling.grade → insertRedo`）：万一日志写之前进程被杀，
 * 后果只是"这一次评分没进事件流"，卡自己的状态仍是对的；反过来先写日志、状态没落，
 * 会留下一条"事件流里有一次评分、可卡根本没被排过"的脏样本，那条会被 Task 7 的
 * LogPacker 当成真实评分去拟合，属于污染校准。
 *
 * 这里只负责"按 [ReviewLog] 的字段顺序组装一行并 append"，不做任何调度算术：
 * 每个调用点能拿到什么、缺什么默认成什么（0 / 每会话 id），都在调用点注释里逐条登记，
 * 语义（rating 1..4、self_conf=Confidence.ordinal+1 或 null、state=0New/1Learn/2Review/3Relearn）
 * 在这一层由 [ReviewLogWriteTest] 钉死。[ocrConf] 暂留 NULL（spec §阶段8 预留列，OCR 入卡置信属后续增强）。
 */
class ReviewLogRepository(private val reviewLogDao: ReviewLogDao) {

    /**
     * 追加一次"已评分复习"事件。参数顺序即 [ReviewLog] 构造子的字段顺序（ts 由本方法默认取当前时刻）。
     * 返回自增行 id。
     */
    suspend fun recordGradedReview(
        cardKind: String,
        cardId: Long,
        rating: Int,
        elapsedDays: Double,
        scheduledDays: Double,
        state: Int,
        durationMs: Long,
        origin: String,
        selfConf: Int?,
        sessionId: String,
        ocrConf: Double? = null,
        isMakeup: Int = 0,
        ts: Long = System.currentTimeMillis(),
        logSchemaVer: Int = ReviewLog.CURRENT_SCHEMA_VER,
    ): Long = reviewLogDao.insert(
        ReviewLog(
            cardKind = cardKind,
            cardId = cardId,
            ts = ts,
            rating = rating,
            elapsedDays = elapsedDays,
            scheduledDays = scheduledDays,
            state = state,
            durationMs = durationMs,
            origin = origin,
            ocrConf = ocrConf,
            selfConf = selfConf,
            sessionId = sessionId,
            isMakeup = isMakeup,
            logSchemaVer = logSchemaVer,
        ),
    )

    /**
     * 全量按 `(card_kind, card_id, ts)` 升序读回（Task 7 LogPacker 消费口径的透传口）。
     * DAO 本身已按此排序，这里只是给优化器一个不落 DAO 类型的读入口。
     */
    suspend fun getAllSince(): List<ReviewLog> = reviewLogDao.getAllSince()
}
