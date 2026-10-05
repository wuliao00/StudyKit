package com.studykit.ui.stats

import com.studykit.data.dao.ScheduledMemoryRow
import com.studykit.data.entity.Excerpt
import com.studykit.data.entity.Mistake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 5（v3.0-A）装配层的单测：纯 JVM、无 runner、不碰 Room —— [ForecastCards.of] 收的是
 * 三份已经 `.first()` 出来的列表，所以这里直接喂内存对象就能覆盖全部映射规则。
 *
 * 断言的期望值一律是**手算字面值**（`now - 2*day` 就是 2.0 天），不拿被测函数给自己出题。
 *
 * 四条真实坑，每条都有对应的测试（口径详见 [ForecastCards] 的 KDoc）：
 *  1. `Mistake` **没有 `last_review_at` 列** —— 锚点只能用 `created_at`，于是错题的 elapsed
 *     是「建卡至今」；这不是精确值，代码不许把它说成「距上次复习」。
 *  2. `Excerpt.stability` **可空** —— 空就是「这本书摘还没被 FSRS 写过」，丢掉而不是当 0。
 *  3. `questions` 表**压根没有记忆态列** —— 所以它不参与预测，签名里没有它不是漏了。
 *  4. 脏 S（null / <=0 / 非有限）在**装配这一层**就丢掉 —— 留给 `DueForecast` 丢也行，但那样
 *     看板上的「14 天负荷」和「共 N 张卡」数的就不是同一批卡（本仓在概率与词数不同批这件事上
 *     写过整条 [com.studykit.ui.study.TomorrowLoad.sampledCount] 的防线，同源不另造口径）。
 */
class ForecastCardsTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `脏稳定度的卡被丢掉`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = listOf(mistake(stability = null), mistake(stability = 0.0), mistake(stability = 3.0)),
            excerpts = emptyList(),
            now = now,
        )
        assertEquals(1, cards.size)
        assertEquals(3.0, cards.single().stabilityDays, 1e-9)
    }

    @Test
    fun `错题没有 last_review_at 时用 createdAt 当锚点`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            // reviewAt 故意设成「今天到期」：装配层若错拿它当锚点（它不是 last_review_at，
            // 是 v2.7 起的「手动覆盖」列），elapsed 会算成 0.0 而不是 2.0。
            mistakes = listOf(mistake(stability = 5.0, createdAt = now - 2 * day, reviewAt = now)),
            excerpts = emptyList(),
            now = now,
        )
        assertEquals(2.0, cards.single().elapsedDays, 0.01)
    }

    @Test
    fun `书摘按 stability 进队列且 kind 可区分`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = emptyList(),
            excerpts = listOf(excerpt(stability = 8.0)),
            now = now,
        )
        assertEquals("excerpt", cards.single().kind)
        assertEquals(8.0, cards.single().stabilityDays, 1e-9)
    }

    @Test
    fun `elapsed 永不为负`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = listOf(mistake(stability = 5.0, createdAt = now + 10 * day)),
            excerpts = emptyList(),
            now = now,
        )
        assertTrue(cards.single().elapsedDays >= 0.0)
    }

    @Test
    fun `书摘 stability 为空时整条丢掉，不当成 0 天`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = emptyList(),
            excerpts = listOf(excerpt(stability = null), excerpt(stability = 6.0)),
            now = now,
        )
        assertEquals(1, cards.size)
        assertEquals(6.0, cards.single().stabilityDays, 1e-9)
    }

    /**
     * 词卡是三张表里唯一有 `last_review_at` 的那一张，所以它是唯一能给出真「距上次复习」的。
     * 两行各断一个锚点：有复习史走 `lastReviewAt`，迁移折算出来的老行（那一列全 NULL）走 `createdAt`。
     */
    @Test
    fun `词卡的锚点是 lastReviewAt，从没复习过的退回 createdAt`() {
        val cards = ForecastCards.of(
            words = listOf(
                row(lastReviewAt = now - 3 * day, createdAt = now - 30 * day),
                row(lastReviewAt = null, createdAt = now - 4 * day),
            ),
            mistakes = emptyList(),
            excerpts = emptyList(),
            now = now,
        )
        assertEquals("word", cards.first().kind)
        assertEquals(3.0, cards[0].elapsedDays, 0.01)
        assertEquals(4.0, cards[1].elapsedDays, 0.01)
    }

    /**
     * kind 的词表就这三张表。写成"恰好这三种"而不是"包含"，是为了让第四种（比如有人给
     * `questions` 补了个 `fsrs_stability` 就顺手接进来）必须改这条断言才能过 —— 那是个
     * 该被拦住的改动，不是顺手加的。
     */
    @Test
    fun `三张表的 kind 只有三种，题目表不参与`() {
        val cards = ForecastCards.of(
            words = listOf(row(lastReviewAt = now - day, createdAt = now - day)),
            mistakes = listOf(mistake(stability = 4.0)),
            excerpts = listOf(excerpt(stability = 6.0)),
            now = now,
        )
        assertEquals(setOf("word", "mistake", "excerpt"), cards.map { it.kind }.toSet())
    }

    /**
     * `toForecastCards()` 不重新过滤一遍脏 S：能走到这一步的卡已经在 `of` 里筛过，
     * 所以这里的条数必须与 `of` 的条数逐条相等（两处各筛一次迟早分叉成两套账）。
     */
    @Test
    fun `映射到预测输入的条数与内容都不加不减`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = listOf(mistake(stability = null), mistake(stability = 2.5)),
            excerpts = listOf(excerpt(stability = null), excerpt(stability = 9.0)),
            now = now,
        )
        val forecast = cards.toForecastCards()
        assertEquals(2, forecast.size)
        assertEquals(2.5, forecast[0].stabilityDays, 1e-9)
        assertEquals(9.0, forecast[1].stabilityDays, 1e-9)
        // 两个 helper 的 createdAt 默认都是 now，所以 elapsed 是 0.0 天（不是"缺省值"，是同一个 now）
        assertEquals(0.0, forecast[0].elapsedDays, 1e-9)
        assertEquals(0.0, forecast[1].elapsedDays, 1e-9)
    }

    private fun mistake(stability: Double?, createdAt: Long = now, reviewAt: Long? = null) = Mistake(
        uuid = "u",
        // `Mistake.source` 是 NOT NULL 且**无默认值**（entity/Mistake.kt:16），计划里的 helper 漏了它
        source = Mistake.SOURCE_WORD,
        subject = "数学",
        title = "t",
        content = "c",
        fsrsStability = stability,
        createdAt = createdAt,
        reviewAt = reviewAt,
    )

    private fun excerpt(stability: Double?) = Excerpt(
        uuid = "u",
        bookId = 1L,
        content = "x",
        stability = stability,
        createdAt = now,
    )

    private fun row(lastReviewAt: Long?, createdAt: Long) = ScheduledMemoryRow(
        nextReviewAt = now + day,
        halfLifeDays = 2.0,
        lastReviewAt = lastReviewAt,
        createdAt = createdAt,
        fsrsStability = 10.0,
    )
}
