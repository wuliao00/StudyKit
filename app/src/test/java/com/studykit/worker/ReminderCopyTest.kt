package com.studykit.worker

import com.studykit.data.entity.Word
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复习提醒通知正文（本轮 B 段）：把「有几个词到期」升级成「顺便还记得多少」。
 *
 * 三个刻意的设计，都在这里钉住：
 *  - **保留率复用仓内既有口径**（[com.studykit.data.memory.MemoryModel.recallProbability]
 *    + 锚点 `lastReviewAt ?: createdAt` + 整批做分母），与首页
 *    `TomorrowForecast` 同一套曲线。自己再画一条，迟早会在边界情形上跟第一条说出不同的数，
 *    而这两个数用户会在同一屏上下看到。
 *  - **取不到就不印**：整批词的半衰期都不可信时返回 null，正文里整段不提保留率。
 *    拿 0% 顶上去是凭空的坏消息（`recallProbability` 对 h ≤ 0 返回 0，那是"当作全忘了"
 *    的降级，不是实测）。
 *  - **保留率只归给单词**：错题没有半衰期字段（`Mistake` 只有 `reviewAt`），
 *    所以文案写「这些单词…」，不许读起来像整个通知的平均。
 *
 * 一条 SQL 取齐：单词来自 `WordDao.getDueForReview`（`SELECT *`，同一行里就有
 * `half_life_days` / `last_review_at` / `created_at`），所以结构上不存在
 * "两次查询再内存配对"那种错位（本仓有过一次这样的返工）。
 */
class ReminderCopyTest {

    private val dayMs: Long = TimeUnit.DAYS.toMillis(1)
    private val now: Long = 1_760_000_000_000L

    /** 只给到影响保留率的字段，其余走实体默认值 */
    private fun word(
        halfLifeDays: Double,
        lastReviewAt: Long?,
        createdAt: Long = now - 30 * dayMs,
    ): Word = Word(
        uuid = "w-$halfLifeDays-$lastReviewAt-$createdAt",
        word = "example",
        meaning = "示例",
        example = "",
        halfLifeDays = halfLifeDays,
        lastReviewAt = lastReviewAt,
        createdAt = createdAt,
    )

    // ── 保留率：与既有曲线同一口径 ──────────────────────────────

    /** Δt = 2 天、h = 2 天 ⇒ 2^(-1) = 50%，一个词时就是它自己 */
    @Test
    fun `到期词的预测保留率按半衰期曲线算`() {
        assertEquals(50, dueRetentionPercent(listOf(word(2.0, now - 2 * dayMs)), now))
    }

    /** Δt = 1 天、h = 2 天 ⇒ 2^(-0.5) = 0.7071 ⇒ 71%：四舍五入，不是截断 */
    @Test
    fun `保留率四舍五入取整`() {
        assertEquals(71, dueRetentionPercent(listOf(word(2.0, now - 1 * dayMs)), now))
    }

    /** 从没复习过的词锚点退回建卡时刻，免得算出 Δt=0 ⇒ 100%（与 TomorrowForecast 同一句约定） */
    @Test
    fun `未复习过的词用建卡时刻当锚点`() {
        assertEquals(50, dueRetentionPercent(listOf(word(3.0, lastReviewAt = null, createdAt = now - 3 * dayMs)), now))
    }

    /** 平均的是概率不是半衰期：0.5 与 0.7071 平均 ⇒ 60% */
    @Test
    fun `一批词取概率的算术平均`() {
        val words = listOf(word(2.0, now - 2 * dayMs), word(2.0, now - 1 * dayMs))
        assertEquals(60, dueRetentionPercent(words, now))
    }

    /**
     * h 不可信的行**留在分母**：这与 [com.studykit.ui.study.TomorrowForecast] 一致，
     * 踢掉它们会让百分比与「N 个单词」数的不是同一批词。
     */
    @Test
    fun `半衰期不可用的词仍然占一个分母位置`() {
        // 0.7071 + 0.0（h≤0 时曲线回 0）再平均 ⇒ 35%
        val words = listOf(word(2.0, now - 1 * dayMs), word(0.0, now - 1 * dayMs))
        assertEquals(35, dueRetentionPercent(words, now))
    }

    /** 没有词就没有百分比：0 个词时不许印 0% */
    @Test
    fun `词集为空时不给保留率`() {
        assertNull(dueRetentionPercent(emptyList(), now))
    }

    /** 整批都不可信时宁可整段不提，也不印一个来路不明的数（0% 或假的 100% 都不行） */
    @Test
    fun `整批半衰期不可信时宁可不提保留率`() {
        val dirty = listOf(word(0.0, now), word(-1.0, now), word(Double.NaN, now))
        assertNull(dueRetentionPercent(dirty, now))
    }

    /** 时钟漂移（此刻早于锚点）按 Δt=0 处理，不给出超过 100% 的把握 */
    @Test
    fun `锚点在未来也不许算出超过百分之百`() {
        val percent = dueRetentionPercent(listOf(word(2.0, now + 5 * dayMs)), now)
        assertEquals(100, percent)
    }

    // ── 正文：零头不提、取不到就不提 ────────────────────────────

    /** 两类都为 0 ⇒ null，调用方据此**不发通知**（保持改动前的行为） */
    @Test
    fun `到期两类都为零时不写正文`() {
        assertNull(reminderContentText(0, 0, null))
        // 带着保留率也一样：没东西要复习就不该有通知
        assertNull(reminderContentText(0, 0, 80))
        assertNull(reminderContentText(-1, 0, null))
    }

    @Test
    fun `只有错题时正文里不提单词`() {
        val text = reminderContentText(3, 0, null)
        assertEquals("3 道错题到期，打开 StudyKit 开始复习", text)
        assertFalse(text!!.contains("单词"))
    }

    @Test
    fun `只有单词时正文里不提错题`() {
        val text = reminderContentText(0, 5, null)
        assertEquals("5 个单词待复习，打开 StudyKit 开始复习", text)
        assertFalse(text!!.contains("错题"))
    }

    /** 两类都在时保持改动前的顺序与措辞，只在末尾追加那一句 */
    @Test
    fun `两类都在时先错题再单词再保留率`() {
        val text = reminderContentText(3, 5, 74)
        assertEquals("3 道错题到期，5 个单词待复习，这些单词现在大约还记得 74%，打开 StudyKit 开始复习", text)
    }

    /** 保留率取不到 ⇒ 整段不提，正文里一个百分号都不许出现（而不是印个 0%） */
    @Test
    fun `拿不到保留率时正文里不提百分数`() {
        listOf(
            reminderContentText(3, 5, null),
            reminderContentText(0, 5, null),
        ).forEach { text ->
            assertNotNull(text)
            assertFalse(text!!.contains("%"))
        }
    }

    /** 单词为 0 时保留率没有归属对象，那一整段不许挂到错题身上 */
    @Test
    fun `没有单词时不挂保留率那半句`() {
        val text = reminderContentText(3, 0, 50)
        assertEquals("3 道错题到期，打开 StudyKit 开始复习", text)
    }

    /** 保留率 0% 也不许被当成"没数据"偷偷丢掉：0 是个算出来的值，该说还是说 */
    @Test
    fun `零保留率是被算出来的时候照说`() {
        assertTrue(reminderContentText(0, 2, 0)!!.contains("还记得 0%"))
    }

    // ── 措辞：不吓唬人 ─────────────────────────────────────────

    /** 到期量摆出来就够了，"再不看就忘了""断签""清零"这类话一句都不许有 */
    @Test
    fun `正文没有威胁与绝对化措辞`() {
        val texts = listOfNotNull(
            reminderContentText(3, 5, 74),
            reminderContentText(3, 5, 12),
            reminderContentText(3, 5, null),
            reminderContentText(0, 5, 100),
        )
        val banned = listOf("断签", "清零", "再不", "就忘", "忘了", "必须", "一定要", "前功尽弃", "警告", "赶紧")
        texts.forEach { text ->
            banned.forEach { phrase ->
                assertFalse("「$text」里出现了「$phrase」", text.contains(phrase))
            }
        }
    }

    /** 每一句都以既有的收尾结束：不改变点击意图 */
    @Test
    fun `正文结尾仍是打开应用那句话`() {
        listOfNotNull(
            reminderContentText(3, 0, null),
            reminderContentText(0, 5, 60),
            reminderContentText(3, 5, 60),
        ).forEach { assertTrue(it.endsWith("，打开 StudyKit 开始复习")) }
    }
}
