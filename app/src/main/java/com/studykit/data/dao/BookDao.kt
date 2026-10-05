package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.ChapterTest
import com.studykit.data.entity.Excerpt
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY started_at DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("UPDATE books SET current_page = :page WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int)

    @Query("UPDATE books SET status = 'finished', finished_at = :finishedAt, current_page = total_pages WHERE id = :id")
    suspend fun markFinished(id: Long, finishedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM excerpts")
    fun observeExcerptCount(): Flow<Int>

    // ── 主指标「检索练习次数」（v2.7 计划 B Task 18）────────────────────────
    // 一条 UNION ALL：完成的检索动作总和 = 书摘侧各条 review_count(>0) 之和 + 章节自测行数。
    // 纯计数、不编造：只数已经真实发生过的自评与自测行；外层 COALESCE 兜住两表皆空时 SUM 返回 NULL。
    @Query(
        "SELECT COALESCE(SUM(n), 0) FROM (" +
            "SELECT COALESCE(SUM(review_count), 0) AS n FROM excerpts WHERE review_count > 0 " +
            "UNION ALL " +
            "SELECT COUNT(*) AS n FROM chapter_tests"
        + ")",
    )
    fun observeRetrievalCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(n), 0) FROM (" +
            "SELECT COALESCE(SUM(review_count), 0) AS n FROM excerpts WHERE review_count > 0 " +
            "UNION ALL " +
            "SELECT COUNT(*) AS n FROM chapter_tests"
        + ")",
    )
    suspend fun countRetrievalActions(): Int

    // ── 书摘复习队列（v2.7 spec §7.2）───────────────────────────────────────
    // next_review_at=0 是「未启用」哨兵（每摘 opt-out：不动 review_count，只把 next_review_at 归 0）；
    // 队列只收已启用且到期的那些。
    @Query(
        "SELECT * FROM excerpts WHERE next_review_at > 0 AND next_review_at <= :now " +
            "ORDER BY next_review_at ASC",
    )
    fun observeDueExcerpts(now: Long): Flow<List<Excerpt>>

    /**
     * 全部**已排期**的书摘（不只是"现在到期的"那批），负荷预测与稳定性全景的取数口（v3.0-A Task 5）。
     *
     * 判据与 [observeDueExcerpts] 的 `next_review_at > 0` 逐字相同，也和
     * `WordDao.observeScheduledMemoryRows` 相同 —— "哪些行算排过期"这条判据三处必须一致，
     * 否则看板的「共 N 张卡」和首页的「明天 N 词」会变成两套账。
     *
     * ## 为什么另开一条，而不是 `observeDueExcerpts(Long.MAX_VALUE)`
     * 结果集完全一样，但后者要把"我想看全部排过期的"这个意图藏在 `MAX_VALUE` 这个参数技巧里：
     * 读的人得先在脑子里证明"`<= MAX` 等于不设上界"才敢改它，而 `next_review_at > 0` 这支
     * 哨兵条件（0 = 未启用，见 [Excerpt.nextReviewAt]）还容易在改参数时被误当成上界一起动。
     * 语义写在名字里，比写在传参里便宜。
     *
     * ## 只投影两列，不返回整行 [Excerpt]
     * 与 [WordDao.observeScheduledMemoryRows] 同一个口径：消费方（`ForecastCards.of`）只读
     * `stability` 与 `created_at`，而 `content` 是书摘正文、一行可以几百到几千字。这条 Flow 被
     * 看板每次评分重算一遍，整行过 Binder 是纯浪费（[WordDao.observeHalfLifeDays] 的 KDoc 写的
     * 就是这个理由）。投影类的字段即这条查询的契约——加列会在这里被看见，而不是静默发生。
     *
     * 与 [observeDueExcerpts] 同为 `ORDER BY next_review_at ASC`：本层不依赖顺序（装配是逐条映射），
     * 留着只是为了和队列那一侧读到的是同一份排法，别给人"两条查询顺序不一样"的错觉。
     */
    @Query(
        "SELECT stability AS stability, created_at AS createdAt FROM excerpts " +
            "WHERE next_review_at > 0 ORDER BY next_review_at ASC",
    )
    fun observeScheduledExcerpts(): Flow<List<ScheduledExcerptRow>>

    /** 「只有划线没有检索」那条 RECALL_NOTES 贴士的判据：:cutoff 之前建的摘且 review_count==0 的条数 */
    @Query("SELECT COUNT(*) FROM excerpts WHERE created_at < :cutoff AND review_count = 0")
    fun observeExcerptsStaleWithoutRecall(cutoff: Long): Flow<Int>

    // ── 章节自测（v2.7 spec §3.2）───────────────────────────────────────────
    @Insert
    suspend fun insertChapterTest(chapterTest: ChapterTest): Long

    @Update
    suspend fun updateChapterTest(chapterTest: ChapterTest)

    @Query("SELECT * FROM chapter_tests WHERE book_id = :bookId ORDER BY tested_at DESC")
    fun observeChapterTests(bookId: Long): Flow<List<ChapterTest>>

    @Query("SELECT * FROM chapter_tests WHERE id = :id")
    suspend fun getChapterTest(id: Long): ChapterTest?

    @Query("SELECT COUNT(*) FROM chapter_tests WHERE book_id = :bookId")
    suspend fun countChapterTests(bookId: Long): Int

    @Query("SELECT * FROM excerpts WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeExcerpts(bookId: Long): Flow<List<Excerpt>>

    @Query("SELECT * FROM excerpts WHERE id = :id")
    fun observeExcerpt(id: Long): Flow<Excerpt?>

    @Query("SELECT * FROM excerpts WHERE id = :id")
    suspend fun getExcerpt(id: Long): Excerpt?

    @Insert
    suspend fun insertExcerpt(excerpt: Excerpt): Long

    @Update
    suspend fun updateExcerpt(excerpt: Excerpt)

    @Delete
    suspend fun deleteExcerpt(excerpt: Excerpt)

    @Query("SELECT * FROM book_reviews WHERE book_id = :bookId ORDER BY at DESC")
    fun observeReviews(bookId: Long): Flow<List<BookReview>>

    @Query("SELECT * FROM book_reviews WHERE id = :id")
    fun observeReview(id: Long): Flow<BookReview?>

    @Query("SELECT * FROM book_reviews WHERE id = :id")
    suspend fun getReview(id: Long): BookReview?

    @Insert
    suspend fun insertReview(review: BookReview): Long

    @Update
    suspend fun updateReview(review: BookReview)

    // ── 清除学习数据用（设置页「数据管理」）。三张表一起清：
    //    单删 books 会留下 excerpts / book_reviews 指向不存在书籍的悬空行。
    @Query("DELETE FROM book_reviews")
    suspend fun deleteAllReviews()

    @Query("DELETE FROM excerpts")
    suspend fun deleteAllExcerpts()

    @Query("DELETE FROM books")
    suspend fun deleteAllBooks()
}

/**
 * [BookDao.observeScheduledExcerpts] 的投影行：负荷预测只需要「稳定度」与「Δt 的锚点」两列，
 * 所以只取这两列（理由见那条 SQL 的 KDoc「只投影两列」一节）。
 *
 * 放在 `data/dao` 而不是 `data/entity`：它得被 Room 的编译期列校验覆盖一次才谈得上
 * "列名不会打错"，而且它压根不是一行书摘，只是那条查询的形状。
 */
data class ScheduledExcerptRow(
    /** FSRS 稳定性（天）；null = 这本书摘还没被 FSRS 写过，装配层整条丢弃（不当 0 天） */
    val stability: Double?,
    /** 建摘时刻，Δt 的锚点。书摘侧没有"上次复习"的时间戳列，口径见 [com.studykit.ui.stats.ForecastCards] */
    val createdAt: Long,
)
