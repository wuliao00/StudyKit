package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordReview
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {

    @Query("SELECT * FROM words ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Word>>

    @Query("SELECT * FROM words ORDER BY created_at DESC")
    suspend fun getAll(): List<Word>

    /**
     * 到期待复习的单词（复习提醒用，WHERE 条件下推 SQL，避免全量内存过滤）。
     *
     * **不再按 `status != 'MASTERED'` 过滤**：`next_review_at > 0` 才是"有排期"的真判据。
     * 旧规则下一个词答对两次就被永久请出队列，而半衰期模型的意义恰恰是
     * "掌握了也要在快要忘的时候回来一次"（h=30 天的词照样会在 4 天后到期）。
     * `status` 从此只作展示标签。
     */
    @Query(
        "SELECT * FROM words WHERE next_review_at > 0 AND next_review_at <= :now " +
            "ORDER BY next_review_at ASC",
    )
    suspend fun getDueForReview(now: Long): List<Word>

    @Query("SELECT * FROM words WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: String): Flow<List<Word>>

    @Query("SELECT COUNT(*) FROM words")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(word: Word): Long

    /** 批量入库；返回自增 id 列表，实践上与入参同序，但 Room 未承诺 —— 勿依赖顺序，只当入库计数用 */
    @Insert
    suspend fun insertAll(words: List<Word>): List<Long>

    /** 全量词面，仅用于导入前去重（词表万级以内可接受；M2 不做索引优化） */
    @Query("SELECT word FROM words")
    suspend fun getWordTexts(): List<String>

    @Query("DELETE FROM words WHERE source_list_id = :sourceListId")
    suspend fun deleteByList(sourceListId: Long): Int

    @Query("SELECT COUNT(*) FROM words WHERE source_list_id = :sourceListId")
    suspend fun countByList(sourceListId: Long): Int

    /**
     * 清除学习数据用（设置页「数据管理」）。两张表必须一起清：
     * `word_reviews` 只有 `word_id` 这一个线索，留着它就是"复习记录挂在已经不存在的词上"，
     * 热力图与"已学天数"会跟着虚高。
     */
    @Query("DELETE FROM word_reviews")
    suspend fun deleteAllReviews()

    @Query("DELETE FROM words")
    suspend fun deleteAll()

    @Update
    suspend fun update(word: Word)

    @Delete
    suspend fun delete(word: Word)

    @Query("UPDATE words SET status = :status, next_review_at = :nextReviewAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, nextReviewAt: Long = 0L)

    /**
     * 复习后一次性写回模型状态。
     *
     * 合成一条 UPDATE 而不是"先 updateStatus 再 updateMemory"：两次写之间如果进程被杀，
     * 会出现"状态推进了但半衰期没涨"的撕裂行，而这条行的表现是"这个词突然变得很笨"，
     * 事后从数据里几乎查不出来。
     */
    @Query(
        "UPDATE words SET half_life_days = :halfLifeDays, difficulty = :difficulty, " +
            "status = :status, next_review_at = :nextReviewAt, last_review_at = :lastReviewAt, " +
            "total_reviews = total_reviews + 1, lapses = lapses + :lapseInc WHERE id = :id",
    )
    suspend fun applyReview(
        id: Long,
        halfLifeDays: Double,
        difficulty: Double,
        status: String,
        nextReviewAt: Long,
        lastReviewAt: Long,
        lapseInc: Int,
    )

    @Insert
    suspend fun insertReview(review: WordReview): Long

    /** 学习活跃日统计用：全部复习时间戳 */
    @Query("SELECT reviewed_at FROM word_reviews")
    fun observeReviewTimestamps(): Flow<List<Long>>

    /**
     * 已排期的复习时刻（未掌握的词）。
     *
     * 分桶交给 Kotlin 按 `LocalDate` 做，不写在 SQL 里：`date()` 不吃时区，
     * 换过时区或跨零点的用户会看到"明天的量"莫名多/少一天。
     */
    @Query("SELECT next_review_at FROM words WHERE next_review_at > 0")
    fun observeScheduledTimestamps(): Flow<List<Long>>

    /**
     * 全库半衰期（记忆看板用）。
     *
     * 只取一列而不是 `observeAll()`：看板每改一次评分就会重算，
     * 把一千多行整行（单词、释义、例句）拉过 Binder 是纯浪费。
     */
    @Query("SELECT half_life_days FROM words")
    fun observeHalfLifeDays(): Flow<List<Double>>

    /** 复习时的间隔与结果 —— 实测遗忘曲线的唯一原料 */
    @Query("SELECT gap_days AS gapDays, correct FROM word_reviews")
    fun observeReviewGapAndResult(): Flow<List<ReviewGapRow>>
}

/**
 * [WordDao.observeReviewGapAndResult] 的投影行。
 *
 * `gapDays` 可空：v2.3 之前的旧复习记录只有时间戳，没有"当时隔了多久"，
 * 那种行参与不了曲线计算（算法层会过滤掉），但**不能假装它们是 0 天**。
 */
data class ReviewGapRow(val gapDays: Double?, val correct: Boolean)
