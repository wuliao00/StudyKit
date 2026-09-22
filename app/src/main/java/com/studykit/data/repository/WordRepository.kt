package com.studykit.data.repository

import com.studykit.data.dao.ReviewGapRow
import com.studykit.data.dao.WordDao
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordReview
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

    /**
     * 批量入库，返回实际写入条数（只取 size，不依赖返回顺序，见 `WordDao.insertAll` 注释）。
     * 调用方负责两件事：先去重（`util/importer/dedupeWords`），以及把 `uuid` / `source_list_id` 填好。
     */
    suspend fun addAll(words: List<Word>): Int = wordDao.insertAll(words).size

    /** 全量词面，供导入前判重；词库量级到万级以下时可整表读进内存 */
    suspend fun wordTexts(): List<String> = wordDao.getWordTexts()

    suspend fun update(word: Word) = wordDao.update(word)

    suspend fun remove(word: Word) = wordDao.delete(word)

    suspend fun updateStatus(id: Long, status: String, nextReviewAt: Long = 0L) =
        wordDao.updateStatus(id, status, nextReviewAt)

    /**
     * 复习后写回模型状态（半衰期、难度、下一次时刻、计数）。
     *
     * 调用顺序必须是"先 [applyReview] 再 [recordGradedReview]"：万一中间被杀进程，
     * 后果是"这次复习没进历史"，下次复习时刻仍然正确，用户无感；
     * 反过来则会出现"历史里有一次复习，但词的半衰期没涨"，
     * 那条记录会在以后的校准里被当成一次真实评分去拟合，属于污染数据。
     */
    suspend fun applyReview(
        wordId: Long,
        halfLifeDays: Double,
        difficulty: Double,
        status: String,
        nextReviewAt: Long,
        lastReviewAt: Long,
        lapseInc: Int,
    ) = wordDao.applyReview(wordId, halfLifeDays, difficulty, status, nextReviewAt, lastReviewAt, lapseInc)

    /**
     * 写入一次带档位的复习记录。
     *
     * `gapDays / pAtReview / hBefore / hAfter` 是校准的原料：没有它们，
     * 事后无法回答"模型给这个人的估计准不准"，只能继续猜参数。
     */
    suspend fun recordGradedReview(
        wordId: Long,
        grade: Int,
        correct: Boolean,
        gapDays: Double,
        pAtReview: Double,
        hBefore: Double,
        hAfter: Double,
        reactionMs: Long?,
    ): Long = wordDao.insertReview(
        WordReview(
            uuid = UUID.randomUUID().toString(),
            wordId = wordId,
            correct = correct,
            gapDays = gapDays,
            pAtReview = pAtReview,
            grade = grade,
            hBefore = hBefore,
            hAfter = hAfter,
            reactionMs = reactionMs,
        ),
    )

    /** 全部复习时间戳（连续学习天数/今日完成数用） */
    fun observeReviewTimestamps(): Flow<List<Long>> = wordDao.observeReviewTimestamps()

    /** 已排期的复习时刻，供首页/统计页算"未来 N 天要复习多少" */
    fun observeScheduledTimestamps(): Flow<List<Long>> = wordDao.observeScheduledTimestamps()

    /** 全库半衰期（记忆看板：持久度分布与模型曲线） */
    fun observeHalfLifeDays(): Flow<List<Double>> = wordDao.observeHalfLifeDays()

    /** 复习间隔 + 结果（记忆看板：实测遗忘曲线） */
    fun observeReviewGapAndResult(): Flow<List<ReviewGapRow>> = wordDao.observeReviewGapAndResult()
}
