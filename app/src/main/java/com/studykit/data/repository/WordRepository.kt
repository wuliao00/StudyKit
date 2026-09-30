package com.studykit.data.repository

import com.studykit.data.dao.WordDao
import com.studykit.data.entity.Word
import com.studykit.srs.Confidence
import com.studykit.srs.Rating
import com.studykit.srs.ReviewLog
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class WordRepository(private val wordDao: WordDao) {

    fun observeAll(): Flow<List<Word>> = wordDao.observeAll()

    suspend fun getAll(): List<Word> = wordDao.getAll()

    /** 到期待复习且未掌握的单词（复习提醒用，SQL 下推过滤） */
    suspend fun getDueForReview(now: Long): List<Word> = wordDao.getDueForReview(now)

    fun observeByStatus(status: String): Flow<List<Word>> = wordDao.observeByStatus(status)

    fun observeCount(): Flow<Int> = wordDao.observeCount()

    suspend fun add(word: String, meaning: String, example: String): Long =
        wordDao.insert(Word(uuid = UUID.randomUUID().toString(), word = word, meaning = meaning, example = example))

    suspend fun update(word: Word) = wordDao.update(word)

    suspend fun remove(word: Word) = wordDao.delete(word)

    suspend fun updateStatus(id: Long, status: String, nextReviewAt: Long = 0L) =
        wordDao.updateStatus(id, status, nextReviewAt)

    /** FSRS 一次复习结果落库：记忆状态 + 展示档位 + 下次到期 + 自评信心 */
    suspend fun applyScheduling(
        id: Long,
        stability: Double,
        difficulty: Double,
        reps: Int,
        lapses: Int,
        lastReviewAt: Long,
        confidence: Int,
        status: String,
        nextReviewAt: Long,
    ) = wordDao.applyScheduling(
        id = id,
        stability = stability,
        difficulty = difficulty,
        reps = reps,
        lapses = lapses,
        lastReviewAt = lastReviewAt,
        confidence = confidence,
        status = status,
        nextReviewAt = nextReviewAt,
    )

    /** 已进入 FSRS 调度的单词（记忆看板与预测保留率用） */
    suspend fun getScheduled(): List<Word> = wordDao.getScheduled()

    /** 写入一次复习日志（评分 + 自评信心 + 稳定性快照，供后续参数拟合） */
    suspend fun recordReview(
        wordId: Long,
        rating: Rating,
        confidence: Confidence?,
        stabilityAfter: Double,
        reviewedAt: Long = System.currentTimeMillis(),
    ): Long = wordDao.insertReview(
        ReviewLog.wordReview(
            wordId = wordId,
            rating = rating,
            confidence = confidence,
            stabilityAfter = stabilityAfter,
            reviewedAt = reviewedAt,
        ),
    )
}
