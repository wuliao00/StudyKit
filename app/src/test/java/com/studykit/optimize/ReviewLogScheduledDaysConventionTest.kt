package com.studykit.optimize

import com.studykit.data.entity.ReviewLog
import com.studykit.data.memory.FsrsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.5 —— `review_log.scheduled_days` 的**后向口径**到底是什么，用一张卡的合成历史钉死。
 *
 * ## 先纠一句 brief 里的下标
 * brief 写的是「`scheduled_days[k]` 应与 `elapsed_days[k+1]` 一致」。那条等式钉的其实是**前向**接线
 * ——「第 k 行记着第 k+1 次要隔多久」正是「刚排出去的间隔」的读法（旧词侧就长这样，所以它必然过，
 * 按 brief 那样写会得一条绿灯的非证据）。后向口径下，「促成第 k+1 次复习的那个间隔」与
 * 「第 k+1 次报告的真实经过」写在**同一行 k+1** 上：
 *  - `scheduled_days[k]` = 第 k−1 次评分排出去的间隔（评分**前**那份计划），
 *  - `elapsed_days[k]`   = 这一次实际隔了多少天（评分**前**那份真实），
 * 于是 `scheduled − elapsed` = 这次比计划早几天 / 迟几天，两个数才减得动
 * （`DueForecastAccuracyTest` 的朴素基线 `round(scheduledDays − elapsedDays)` 用的正是这一式）。
 * 本文件因此把断言写成**同一行内**的配对，并逐行验「第 k 行那列 == 第 k−1 次排出去的间隔」。
 *
 * ## 为什么这份断言分辨得出新旧接线（不是恒真）
 * 1. 逐行那条不读合成器的内部变量，而是用 [FsrsModel] 从行上的 `rating`/`elapsedDays` 独立**重放**出
 *    「第 k−1 次之后按目标保留率会排多远」，要求它逐字等于第 k 行的 `scheduled_days`；前向接线会把
 *    这一列整体右移一行，第 2 行起就追不上。
 * 2. 首评行 `scheduled_days == 0.0` 是最省事的分辨器：合成器与 py-fsrs 都记 0，旧词侧记的是刚排出去
 *    的 2.3065 天（`ReviewLogScheduledDaysWriterTest` 的红灯实测值）。
 * 3. [前向对照值逐个不相等] 那条把「如果是前向该写什么」当场算出来比对，钉住本用例不是空转。
 *
 * 全程只读合成产物与 [FsrsModel] 的公开式，不动任何算术、不碰 Room。
 */
class ReviewLogScheduledDaysConventionTest {

    private val w = FsrsModel.DEFAULT_PARAMS
    private val retention = 0.9

    /** 与 `SynthLog` 同一份夹取域（那两个私有常量 0.5 / 365 天，见 `SynthLog.kt:53-55`）。 */
    private val clampLo = 0.5
    private val clampHi = 365.0

    /** 生成器传给 `nextIntervalDays` 的内层上限（`SynthLog.kt:58`，只为让外层夹取说了算）。 */
    private val innerCap = 3650.0

    /** 迟到噪声 `U(0.7, 1.6)`（`SynthLog.kt:60-61`）：同一行内 elapsed 与 scheduled 的比值域。 */
    private val noiseLo = 0.7
    private val noiseHi = 1.6

    /** 「这份状态按目标保留率会排出去多长」——夹取与合成器逐字同式。 */
    private fun intervalFor(stability: Double): Double =
        FsrsModel.nextIntervalDays(w, stability, retention, innerCap).coerceIn(clampLo, clampHi)

    /** 一次重放的游标：成功支/失败支的分岔与 `FsrsKernel.review`、`SynthLog` 同口径。 */
    private class Cursor(var s: Double, var d: Double) {
        fun advance(rating: Int, elapsedDays: Double, w: DoubleArray) {
            val p = FsrsModel.recall(w, s, elapsedDays)
            val grown = if (rating == 1) {
                FsrsModel.forgetStability(w, s, d, p)
            } else {
                FsrsModel.recallStability(w, s, d, p, rating)
            }
            d = FsrsModel.nextDifficulty(w, d, rating.toDouble())
            s = grown
        }
    }

    /** 取一张够长的合成卡（同卡 ts 严格升序），首评行必须是 0（不是就先红，说明口径又串了）。 */
    private fun syntheticCard(minReviews: Int): List<ReviewLog> {
        val cards = SynthLog.generate(seed = 42L, nCards = 60, reviewsPerCard = 14, w = w, retention = retention)
            .groupBy { it.cardId to it.sessionId }
            .map { (_, rows) -> rows.sortedBy { it.ts } }
        val candidate = cards.firstOrNull { it.size >= minReviews && it.first().scheduledDays == 0.0 }
            ?: error("合成语料里没有首评记 0 的长卡，说明 SynthLog 的口径变了")
        assertTrue("这张卡的 ts 必须严格升序，否则逐行比对没有意义", candidate.zipWithNext().all { it.first.ts < it.second.ts })
        return candidate
    }

    @Test
    fun `逐行重放 第 k 行的 scheduled_days 就是第 k-1 次评分排出去的间隔`() {
        val hist = syntheticCard(minReviews = 6)
        val first = hist.first()

        // 首评：没有「上次排期」可言 → 0.0（前向接线在这里会写成刚排出去的间隔，直接红）
        assertEquals("首评行的 scheduled_days 必须是 0.0", 0.0, first.scheduledDays, 1e-9)
        assertEquals("首评行的 elapsed_days 也是 0.0", 0.0, first.elapsedDays, 1e-9)
        assertEquals("首评行 state=New0", 0, first.state)

        val cur = Cursor(
            s = FsrsModel.initStability(w, first.rating),
            d = FsrsModel.initDifficulty(w, first.rating.toDouble()),
        )

        for (k in 1 until hist.size) {
            val row = hist[k]
            val expected = intervalFor(cur.s)
            assertEquals(
                "第 $k 行的 scheduled_days 应等于第 ${k - 1} 次评分排出去的间隔（独立重放的右端），" +
                    "实际 row=${row.scheduledDays} expected=$expected —— 对不上就是这一列串行了一格",
                expected, row.scheduledDays, 1e-9,
            )
            cur.advance(row.rating, row.elapsedDays, w)
        }
    }

    @Test
    fun `同一行内的计划与实际配成一对，错一行便对不上`() {
        val hist = syntheticCard(minReviews = 6)

        // 未夹取行才谈得上噪声带：撞上 365 天政策上限时行里记的是夹取值，而 elapsed 出自未夹取的那份。
        val unclamped = hist.drop(1).filter { it.scheduledDays > clampLo && it.scheduledDays < clampHi }
        assertTrue("这张卡得有足够的未夹取行才谈得上配对，实际 ${unclamped.size}", unclamped.size >= 4)

        // ① 同一行（计划 vs 实际）：逐行都落在合成器那道 U(0.7,1.6) 噪声带里
        unclamped.forEach { row ->
            val ratio = row.elapsedDays / row.scheduledDays
            assertTrue(
                "同一行内 elapsed/scheduled=$ratio 应在噪声带 [$noiseLo,$noiseHi) 内（row=$row）",
                ratio in noiseLo..noiseHi,
            )
        }

        // ② 错一行（brief 原式的 scheduled[k] vs elapsed[k+1]，即前向接线的读法）：必须明显对不上，
        //    否则 ①就成了对两种口径都成立的空话。这里不赌具体比例，只钉「不是全都落在带里」。
        val shifted = unclamped.zip(unclamped.drop(1))
        val shiftedInBand = shifted.count { (prev, next) -> (next.elapsedDays / prev.scheduledDays) in noiseLo..noiseHi }
        assertTrue(
            "错一行配对若也全在噪声带里，①就没有分辨力：$shiftedInBand/${shifted.size}",
            shiftedInBand < shifted.size,
        )
    }

    @Test
    fun `钉住这一列不是刚排出去的间隔（前向对照值逐个算出来比）`() {
        val hist = syntheticCard(minReviews = 6)
        val cur = Cursor(
            s = FsrsModel.initStability(w, hist.first().rating),
            d = FsrsModel.initDifficulty(w, hist.first().rating.toDouble()),
        )

        // forward[k] = 「第 k 次评分刚排出去的那段间隔」。前向接线会把它写进第 k 行；
        // 后向口径把它写进第 k+1 行。两者在没撞上夹取的行上是两个不同的数，逐行对照才看得见。
        val forward = ArrayList<Double>(hist.size)
        hist.forEach { row ->
            forward += intervalFor(cur.s)
            cur.advance(row.rating, row.elapsedDays, w)
        }

        assertTrue("首评刚排出去的间隔必须 >0（=${forward.first()}），否则下面的对照是空转", forward.first() > 0.0)
        val mismatched = hist.indices.count { k ->
            kotlin.math.abs(hist[k].scheduledDays - forward[k]) > 1e-9
        }
        assertTrue(
            "落库的 scheduled_days 必须与「这次刚排出去的间隔」在第 0 行就不相等，且整卡至少 3 行不同" +
                "（不同才算钉住了后向；相等说明列又串回前向）：mismatched=$mismatched, " +
                "row0=${hist.first().scheduledDays} vs forward0=${forward.first()}",
            hist.first().scheduledDays == 0.0 && mismatched >= 3,
        )
    }

    // ── 评分点共用的那一式：ReviewLog.prevScheduledDays ───────────────────────

    @Test
    fun `后向折算只吃评分前那份快照，没有上次排期一律 0`() {
        val day = 86_400_000L
        // 正常：到期比锚点晚 5 天 → 5.0（词侧 lastReviewAt/nextReviewAt、摘侧 createdAt/nextReviewAt 同一式）
        assertEquals(5.0, ReviewLog.prevScheduledDays(1_000L * day, 1_005L * day), 1e-9)
        // 首评：没有上次复习（词侧 last_review_at 为 null）→ 0.0，不拿「刚排出去的间隔」冒充
        assertEquals(0.0, ReviewLog.prevScheduledDays(null, 6_000L * day), 1e-9)
        // 未启用哨兵：next_review_at = 0（书摘那一支）→ 0.0，绝不折成负数
        assertEquals(0.0, ReviewLog.prevScheduledDays(1_000L * day, 0L), 1e-9)
        assertEquals(0.0, ReviewLog.prevScheduledDays(1_000L * day, -1L), 1e-9)
        // 脏数据（到期早于锚点：手动改期 / 老行）→ 夹到 0.0
        assertEquals(0.0, ReviewLog.prevScheduledDays(6_000L * day, 1_000L * day), 1e-9)
        // 亚天间隔不能被整数除法吃掉（复习可以只隔几十分钟）
        assertEquals(0.5, ReviewLog.prevScheduledDays(0L, day / 2), 1e-9)
    }

    @Test
    fun `事件流版本戳 v2 才是统一的后向口径`() {
        // 语义变了而列没变（Room 仍 v8、零迁移）→ 只能靠这一列划界，所以它的值本身要被钉住。
        // 日后若再改这一列的语义，这里 +1 并在 ReviewLog.CURRENT_SCHEMA_VER 的 KDoc 里写清改了什么。
        assertEquals("v1=混合口径（词侧前向、摘侧恒 0）；v2=四处统一到后向", 2, ReviewLog.CURRENT_SCHEMA_VER)
        // 合成器也走同一枚戳（它本来就是后向口径的参照实现）
        assertEquals(ReviewLog.CURRENT_SCHEMA_VER, SynthLog.generate(1L, 1, 3).single { it.state == 0 }.logSchemaVer)
    }
}
