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
     *
     * **v2.7 双内核双写**（spec §2.1）：`half_life_days` / `difficulty` 与 `fsrs_*` 四列
     * 在同一条 UPDATE 里落齐，活跃内核那一侧是原生值，另一侧是换算镜像（近似值）；
     * `kernel` 记这一次是**谁原生写的**（审计戳，不参与将来挑内核，见 `KernelHub`）。
     * 四个新形参默认 null 只为让既有调用点不必改就能编过：**排期路径必须逐条传齐**，
     * 不传等于把那一行的 FSRS 账清成 NULL。
     */
    @Query(
        "UPDATE words SET half_life_days = :halfLifeDays, difficulty = :difficulty, " +
            "status = :status, next_review_at = :nextReviewAt, last_review_at = :lastReviewAt, " +
            "fsrs_stability = :fsrsStability, fsrs_difficulty = :fsrsDifficulty, " +
            "fsrs_state = :fsrsState, kernel = :kernel, " +
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
        fsrsStability: Double? = null,
        fsrsDifficulty: Double? = null,
        fsrsState: Int? = null,
        kernel: String? = null,
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
     * 已排期那批词的「到期时刻 + 半衰期 + 上次复习 + FSRS 稳定性」投影，学习首页的
     * 「明天预计复习 N 词 · 到时候大约还记得 X%」用（v2.5 §2.3）。
     *
     * ## 为什么必须是一条 SQL 拿全，而不是复用上面两条各取一半
     * [observeScheduledTimestamps] 只有 `next_review_at`、[observeHalfLifeDays] 只有
     * `half_life_days`，两者都不 JOIN、也不保证同一批行。分两次查再在内存里按下标配对，
     * 拿到的是**两个不同快照**：两次挂起之间任何一次评分（复习页每答一张就写一次）都会让
     * 第 k 行的时刻配上第 k 行"另一批词"的半衰期。本仓在逐条失效那件事上真踩过一次
     * （`settleDue` 逐条 `await update()` ⇒ 观察者拿到半新半旧快照）。
     * 最坏的是这种错位**不报错**：N 是对的，X% 静默地数了另一批词，整行数字没意义。
     *
     * ## 为什么带上 `created_at`
     * `last_review_at` 可空是真实状态，不是脏数据：v4→v5 迁移给老库的 MASTERED 词统一
     * 排到了 4 天后（见 `AppDatabase.MIGRATION_4_5`），而那一列是当场新加的、全是 NULL。
     * 从没复习过的词拿加入那天当锚点，与 `StudyViewModel.gradeCard` 里
     * `word.lastReviewAt ?: word.createdAt` 同一个口径 —— 所以这些字段一条 SQL 取齐。
     *
     * WHERE 条件与 [observeScheduledTimestamps] 同为 `next_review_at > 0`："哪些词算排过期"
     * 两处必须一致，否则首页的 N 和记忆看板的未来柱又分叉成两套账。
     */
    @Query(
        "SELECT next_review_at AS nextReviewAt, half_life_days AS halfLifeDays, " +
            "last_review_at AS lastReviewAt, created_at AS createdAt, " +
            "fsrs_stability AS fsrsStability " +
            "FROM words WHERE next_review_at > 0",
    )
    fun observeScheduledMemoryRows(): Flow<List<ScheduledMemoryRow>>

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

    /**
     * 新词预测试的干扰项池（v2.5 §3.3）：除了这个词、除了这句释义之外的**其他释义**，
     * 按文本去重后随机取 [:limit] 条。
     *
     * ## 三条口径都收在这条 SQL 里，一条都不留给调用方
     * - **同词库优先，`source_list_id` 为 null 时退回全表**：
     *   `(:sourceListId IS NULL OR source_list_id = :sourceListId)` 一支写完，
     *   手工/粘贴/导入词（那一列是 null，见 `entity/Word.kt:32`）不会被这道条件筛成空池。
     *   为什么不写两条查询让调用方选：那会把"什么时候算同一本词库"这个决定摊到调用点上，
     *   而它错了不会报错，只会让用户看到一本毫不相干的释义。
     * - **按释义文本去重**（`GROUP BY meaning`）：同一个词库里有两词共用一句释义是常态，
     *   不去重就会给出两个一模一样的选项 —— 用户选哪个都"对一半"，这道题就废了。
     *   `RecallGate.buildRecallPretest` 那侧还会再按文本去一次（连空白一起掐掉），
     *   两处都要：SQL 那侧管捞得多不多，纯函数那侧管"不足 3 条就整轮跳过"的判定不能被
     *   一条脏数据绕过。
     * - **排除正确项本身**（`meaning != :excludeMeaning`）：同词库里另一个词写着同一句释义时，
     *   不排掉它就会有一项与正确项文字全等，于是这道题出现两个正确答案。
     *   `id != :wordId` 理论上是它的子集，留着是为了让"别把这个词自己捞回来"这条意图写在 SQL 里。
     *
     * `ORDER BY RANDOM()`：每次进这张卡换一批干扰项，否则同一本词库的顺序固定，
     * 用户背到第十个词就已经认识那三个选项了。
     *
     * 只取 `meaning` 一列、`LIMIT` 收住条数（调用方给的是个位数）：这一条每张新词卡都会走一次，
     * 不该把整行（单词、释义、例句）拉过 Binder。
     */
    @Query(
        "SELECT meaning FROM words " +
            "WHERE (:sourceListId IS NULL OR source_list_id = :sourceListId) " +
            "AND id != :wordId AND meaning != :excludeMeaning AND meaning != '' " +
            "GROUP BY meaning ORDER BY RANDOM() LIMIT :limit",
    )
    suspend fun getRecallPretestPool(
        sourceListId: Long?,
        wordId: Long,
        excludeMeaning: String,
        limit: Int,
    ): List<String>
}

/**
 * [WordDao.observeReviewGapAndResult] 的投影行。
 *
 * `gapDays` 可空：v2.3 之前的旧复习记录只有时间戳，没有"当时隔了多久"，
 * 那种行参与不了曲线计算（算法层会过滤掉），但**不能假装它们是 0 天**。
 */
data class ReviewGapRow(val gapDays: Double?, val correct: Boolean)

/**
 * [WordDao.observeScheduledMemoryRows] 的投影行：一条已排期的词，
 * 到期时刻、这条记忆的半衰期、以及 Δt 的锚点**同行取回**。
 *
 * 这些字段必须来自同一行、同一次查询，理由见那条 SQL 的 KDoc。
 * [anchorAt] 在这里（而不是 SQL 的 COALESCE 里）折算，是为了让"没复习过的词按加入那天算"
 * 这条锚点口径留在 Kotlin 里、留在能被单测钉住的地方，与 `StudyViewModel.gradeCard` 一致。
 *
 * [fsrsStability]（v2.7 双内核）是"这一行被 FSRS 原生写过吗"的唯一证据，所以同样
 * 得在那一条 SQL 里取齐：分两次查就会让保留率数的是另一批词（上面那段理由）。
 * null 时读数退回 [halfLifeDays] 那条镜像曲线，不许拿空状态算出一个假的低分
 * （见 `memory.recallForDisplay`）。
 */
data class ScheduledMemoryRow(
    /** 下一次到期的时刻（毫秒），恒 > 0 */
    val nextReviewAt: Long,
    /** 半衰期（天）。列本身 NOT NULL DEFAULT 0.5，所以拿不到 null；脏值由模型自己降级 */
    val halfLifeDays: Double,
    /** 上次复习的时刻；null = 从没复习过（迁移折算出来的老词就是这种） */
    val lastReviewAt: Long?,
    /** 加入学习的时刻，`lastReviewAt` 为 null 时的锢点 */
    val createdAt: Long,
    /** FSRS 稳定性（天）；null = FSRS 还没写过这一行（新词 / 迁移未回填的老行） */
    val fsrsStability: Double? = null,
) {
    /** 算 Δt 用的锚点 */
    val anchorAt: Long
        get() = lastReviewAt ?: createdAt
}
