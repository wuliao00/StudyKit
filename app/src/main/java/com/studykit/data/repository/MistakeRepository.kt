package com.studykit.data.repository

import com.studykit.data.dao.MistakeDao
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.MistakeReview
import com.studykit.data.entity.Question
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class MistakeRepository(private val mistakeDao: MistakeDao) {

    fun observeAll(): Flow<List<Mistake>> = mistakeDao.observeAll()

    fun observeBySource(source: String): Flow<List<Mistake>> = mistakeDao.observeBySource(source)

    fun observeBySubject(subject: String): Flow<List<Mistake>> = mistakeDao.observeBySubject(subject)

    fun observeUnmastered(): Flow<List<Mistake>> = mistakeDao.observeUnmastered()

    fun observeById(id: Long): Flow<Mistake?> = mistakeDao.observeById(id)

    /** 按 id 单次查询（删除等操作前取最新记录用，不依赖 UI 缓存） */
    suspend fun getById(id: Long): Mistake? = mistakeDao.getById(id)

    /** 到期且未掌握的错题（复习提醒用） */
    suspend fun getDueForReview(now: Long): List<Mistake> = mistakeDao.getDueForReview(now)

    fun observeUnmasteredCount(): Flow<Int> = mistakeDao.observeUnmasteredCount()

    suspend fun add(
        source: String,
        subject: String,
        title: String,
        content: String,
        imagePath: String? = null,
        note: String = "",
    ): Long =
        mistakeDao.insert(
            Mistake(
                uuid = UUID.randomUUID().toString(),
                source = source,
                subject = subject,
                title = title,
                imagePath = imagePath,
                content = content,
                note = note,
            ),
        )

    suspend fun update(mistake: Mistake) = mistakeDao.update(mistake)

    suspend fun delete(id: Long) = mistakeDao.deleteById(id)

    /** 标记错题为已掌握（保留给历史入口；新流程的掌握状态由算法写回） */
    suspend fun markMastered(id: Long) = mistakeDao.markMastered(id)

    // ── 学习科学改造：重做式复习 / 错因 / 算法排期 ──────────────────────

    /** 今日到期队列（高置信答错优先、其次按到期时间） */
    suspend fun getDueSorted(now: Long): List<Mistake> = mistakeDao.getDueSorted(now)

    /** 只写错因标签，不碰其他列 */
    suspend fun updateCause(id: Long, cause: String) = mistakeDao.updateCause(id, cause)

    /** 写入下次复习时间；pinned=true 后算法不再覆盖该时间 */
    suspend fun schedule(id: Long, reviewAt: Long?, pinned: Boolean) =
        mistakeDao.schedule(id, reviewAt, pinned)

    /** 一次重做的完整结果落库：连对次数 + FSRS 状态 + 下次到期 + 掌握标记 */
    suspend fun applyRedo(
        id: Long,
        correctStreak: Int,
        lastGapDays: Int,
        stability: Double,
        difficulty: Double,
        reps: Int,
        lapses: Int,
        lastReviewAt: Long,
        highConfidenceError: Boolean,
        reviewAt: Long?,
        mastered: Boolean,
    ) = mistakeDao.applyRedo(
        id = id,
        correctStreak = correctStreak,
        lastGapDays = lastGapDays,
        stability = stability,
        difficulty = difficulty,
        reps = reps,
        lapses = lapses,
        lastReviewAt = lastReviewAt,
        highConfidenceError = highConfidenceError,
        reviewAt = reviewAt,
        mastered = mastered,
    )

    /** 写入一条重做记录（信心 / 提示档 / 隔天数都在记录里） */
    suspend fun recordRedo(
        mistakeId: Long,
        correct: Boolean,
        confidence: Int,
        hintLevel: Int,
        gapDays: Int,
        reviewedAt: Long,
    ): Long = mistakeDao.insertReview(
        MistakeReview(
            uuid = UUID.randomUUID().toString(),
            mistakeId = mistakeId,
            correct = correct,
            confidence = confidence,
            hintLevel = hintLevel,
            gapDays = gapDays,
            reviewedAt = reviewedAt,
        ),
    )

    /** 某道错题的重做历史（详情页展示） */
    fun observeReviews(mistakeId: Long): Flow<List<MistakeReview>> = mistakeDao.observeReviews(mistakeId)

    /** 上次重做时刻，gapDays 的基准；从未重做过返回 null */
    suspend fun lastRedoAt(mistakeId: Long): Long? = mistakeDao.lastRedoAt(mistakeId)

    /** 全量重做次数（列表页概览） */
    fun observeReviewCount(): Flow<Int> = mistakeDao.observeReviewCount()

    /**
     * 刷题答错的幂等收录：同一 questionId（qid 标记存在 note 里）已收录则直接返回 null。
     *
     * 同时写入 cause=""（待归因）与 highConfidenceError，并给一个初始复习时间，
     * 让新错题从录入起就进入自动排期而不是停留在队列外。
     */
    suspend fun capturePracticeMistake(
        question: Question,
        options: List<String>,
        highConfidenceError: Boolean,
        reviewAt: Long,
    ): Long? {
        val marker = practiceMarker(question.id)
        val exists = mistakeDao.observeAll().first().any {
            it.source == Mistake.SOURCE_PRACTICE && it.note == marker
        }
        if (exists) return null
        return mistakeDao.insert(
            Mistake(
                uuid = UUID.randomUUID().toString(),
                source = Mistake.SOURCE_PRACTICE,
                subject = question.subject,
                title = question.stem,
                content = practiceContent(question, options),
                note = marker,
                reviewAt = reviewAt,
                cause = "",
                highConfidenceError = highConfidenceError,
            ),
        )
    }

    companion object {
        /** 收录标记：与旧版刷题入错题本一致，幂等判重靠它 */
        fun practiceMarker(questionId: Long): String = "qid:$questionId"

        /** 题目→错题正文（保留选项与答案，重做时遮住答案行即可） */
        fun practiceContent(question: Question, options: List<String>): String = buildString {
            appendLine("题干：${question.stem}")
            options.forEachIndexed { i, option ->
                appendLine("${'A' + i}. $option")
            }
            appendLine("正确答案：${'A' + question.answerIndex}. ${options.getOrNull(question.answerIndex).orEmpty()}")
            append("解析：${question.explanation}")
        }
    }
}
