package com.studykit.ui.study

import com.studykit.data.entity.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片会话「队列组成」纯逻辑单测（v2.7.0.1 修背不了单词）。
 *
 * 钉住三条会被用户直接察觉的性质，每条都对应真实导入词库暴露的那个 bug：
 *  1. **全新词库不再是空会话**：全是 `next_review_at=0` 的新词，到期队列为空时，
 *     会话必须从新词里补位到每日目标（而不是回「没有待复习的单词」）。
 *  2. **到期优先**：到期词排在新词前面，先清欠账再学新。
 *  3. **新词补位卡在剩余每日目标内**：`dailyWordGoal − todayReviewed` 决定补几条，
 *     不是一股脑把整本词库塞进一轮。
 *
 * 走纯函数而非 Robolectric，是因为这条性质长在"取哪些词"的算法上，与 Room/Android 无关；
 * 真机端到端由后续设备验证覆盖。
 */
class CardSessionQueueTest {

    private val now = 1_700_000_000_000L

    /** 新词：从没复习过、也没排期（真实导入路径落库就是这个形态）。 */
    private fun fresh(id: Long, created: Long = now) = Word(
        id = id,
        uuid = "w$id",
        word = "word$id",
        meaning = "释义$id",
        example = "",
        status = Word.STATUS_NEW,
        nextReviewAt = 0L,
        totalReviews = 0,
        createdAt = created,
    )

    /** 已排期且到期的复习词。 */
    private fun due(id: Long, dueAt: Long) = Word(
        id = id,
        uuid = "w$id",
        word = "due$id",
        meaning = "释义$id",
        example = "",
        status = Word.STATUS_LEARNING,
        nextReviewAt = dueAt,
        totalReviews = 3,
        createdAt = dueAt - 1000,
    )

    @Test
    fun `全新词库补位到每日目标而不是空会话`() {
        val words = (1L..100L).map { fresh(it) }
        val queue = CardSessionQueue.compose(
            words = words, now = now, dailyWordGoal = 20, todayReviewed = 0, dueCap = 10,
        )
        // 到期一支为空，全部来自新词补位，条数 = 剩余目标 20
        assertEquals(20, queue.size)
        assertTrue(queue.all { CardSessionQueue.isFresh(it) })
    }

    @Test
    fun `新词补位卡在剩余每日目标内`() {
        val words = (1L..100L).map { fresh(it) }
        // 今天已学 15，目标 20 → 只剩 5 个新词额度
        val queue = CardSessionQueue.compose(
            words = words, now = now, dailyWordGoal = 20, todayReviewed = 15, dueCap = 10,
        )
        assertEquals(5, queue.size)
        // 今日目标已满（goal<=todayReviewed）→ 不再补新词
        val full = CardSessionQueue.compose(
            words = words, now = now, dailyWordGoal = 20, todayReviewed = 20, dueCap = 10,
        )
        assertTrue(full.isEmpty())
    }

    @Test
    fun `到期优先于新词`() {
        val dueWords = listOf(due(1L, dueAt = now - 5), due(2L, dueAt = now - 1))
        val freshWords = listOf(fresh(100L), fresh(101L))
        val queue = CardSessionQueue.compose(
            words = freshWords + dueWords, now = now, dailyWordGoal = 20, todayReviewed = 0, dueCap = 10,
        )
        // 两条到期词在前，其后才是新词
        assertEquals(listOf(1L, 2L), queue.take(2).map { it.id })
        assertEquals(4, queue.size)
    }

    @Test
    fun `真没有词时才空会话`() {
        val queue = CardSessionQueue.compose(
            words = emptyList(), now = now, dailyWordGoal = 20, todayReviewed = 0, dueCap = 10,
        )
        assertTrue(queue.isEmpty())
    }
}
