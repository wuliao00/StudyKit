package com.studykit.data.repository

import com.studykit.data.dao.ReviewGapRow
import com.studykit.data.dao.RetentionRow
import com.studykit.data.dao.ScheduledMemoryRow
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
     *
     * 后四个形参是 v2.7 双内核的双写（spec §2.1，列口径见 `WordDao.applyReview`）：
     * 默认 null 只为让既有调用点不必改就能编过，排期路径逐条传齐。
     */
    suspend fun applyReview(
        wordId: Long,
        halfLifeDays: Double,
        difficulty: Double,
        status: String,
        nextReviewAt: Long,
        lastReviewAt: Long,
        lapseInc: Int,
        fsrsStability: Double? = null,
        fsrsDifficulty: Double? = null,
        fsrsState: Int? = null,
        kernel: String? = null,
    ) = wordDao.applyReview(
        wordId,
        halfLifeDays,
        difficulty,
        status,
        nextReviewAt,
        lastReviewAt,
        lapseInc,
        fsrsStability,
        fsrsDifficulty,
        fsrsState,
        kernel,
    )

    /**
     * 写入一次带档位的复习记录。
     *
     * `gapDays / pAtReview / hBefore / hAfter` 是校准的原料：没有它们，
     * 事后无法回答"模型给这个人的估计准不准"，只能继续猜参数。
     *
     * `confidence`（1=瞎猜 2=有点印象 3=非常确定）与 `fsrsRating`（FSRS 口径 1..4）是 v2.7 新列：
     * 前者是超纠正的判据（spec §2.3），后者让这一行以后能喂给 FSRS 参数优化器。
     * 默认 null —— UI 那侧开始采集信心之前（计划 B）就该是 null，拿 0 冒充"答『肯定不记得』"是假数据。
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
        confidence: Int? = null,
        fsrsRating: Int? = null,
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
            confidence = confidence,
            fsrsRating = fsrsRating,
        ),
    )

    /** 全部复习时间戳（连续学习天数/今日完成数用） */
    fun observeReviewTimestamps(): Flow<List<Long>> = wordDao.observeReviewTimestamps()

    /** 已排期的复习时刻，供首页/统计页算"未来 N 天要复习多少" */
    fun observeScheduledTimestamps(): Flow<List<Long>> = wordDao.observeScheduledTimestamps()

    /**
     * 已排期那批词的「到期时刻 + 半衰期 + 锚点」投影（学习首页的明日保留率用）。
     *
     * 一条 SQL 把这批词的字段拿全，不许由调用方拿 [observeScheduledTimestamps] 与
     * [observeHalfLifeDays] 拼 —— 理由见 `WordDao.observeScheduledMemoryRows` 的 KDoc。
     */
    fun observeScheduledMemoryRows(): Flow<List<ScheduledMemoryRow>> = wordDao.observeScheduledMemoryRows()

    /** 全库半衰期（记忆看板：持久度分布与模型曲线） */
    fun observeHalfLifeDays(): Flow<List<Double>> = wordDao.observeHalfLifeDays()

    /** 复习间隔 + 结果（记忆看板：实测遗忘曲线） */
    fun observeReviewGapAndResult(): Flow<List<ReviewGapRow>> = wordDao.observeReviewGapAndResult()

    /**
     * 复习间隔 + 评分档位 + 当时预测（记忆看板：「延迟后测」卡，v2.7 spec §9）。
     *
     * 与 [observeReviewGapAndResult] 是两条独立查询，不是"一条加了两个字段"：
     * 后者身后是 v2.3 起就钉死的遗忘曲线，动它的列等于动那张网的根。
     * 怎么分桶、哪些行算"没记评分"，一律在 `ui/stats/RetentionBuckets`，这里只转发。
     */
    fun observeRetentionRows(): Flow<List<RetentionRow>> = wordDao.observeRetentionRows()

    /**
     * 新词预测试的干扰项池：同词库（`source_list_id` 相同）的其他释义，
     * 词库为 null 时退回全表；按释义文本去重、排除正确项，随机取 [count] 条。
     *
     * 口径全在 `WordDao.getRecallPretestPool` 那条 SQL 里，这里只是转发 ——
     * **只读**：这道题的作答结果不落库（见 `StudyViewModel.loadRecallPretest` 的注释）。
     */
    suspend fun recallPretestPool(
        sourceListId: Long?,
        wordId: Long,
        excludeMeaning: String,
        count: Int,
    ): List<String> = wordDao.getRecallPretestPool(
        sourceListId = sourceListId,
        wordId = wordId,
        excludeMeaning = excludeMeaning,
        limit = count,
    )
}
