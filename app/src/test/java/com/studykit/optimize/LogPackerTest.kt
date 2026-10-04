package com.studykit.optimize

import com.studykit.data.entity.ReviewLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 7 单测：[LogPacker] 把原始 `review_log` 行清洗、按卡分组、打包成 Rust JNI
 * （`FsrsNative.nativeTrain`/`nativeEvaluate`）所消费的 `FloatArray`+`IntArray`，
 * 并做时序 80/20 train/val 切分。
 *
 * 纯 JVM：直接喂内存 [ReviewLog] 列表（[ReviewLog] 是普通 data class，无需 Room）。
 *
 * 打包契约（取自 `fsrsjni/src/lib.rs` 的 `parse()`，逐值核对）：
 * - reviews：每条 4 float = `[rating, elapsedDays, scheduledDays, state]`（c[0..3]）
 * - bounds：前缀和，**单位=review 条数**（非 float 下标），`bounds[i+1]-bounds[i]` = 第 i 卡的 review 数，
 *   `bounds.size == 卡数 + 1`
 * - items 必须按「首评时间」升序（切分依赖此序）
 */
class LogPackerTest {

    // —— 构造辅助：只关心被清洗/打包读到的字段，其余给稳定默认 ——
    private fun log(
        cardKind: String = ReviewLog.KIND_WORD,
        cardId: Long = 1L,
        ts: Long,
        rating: Int = 3,
        elapsedDays: Double = 1.0,
        scheduledDays: Double = 4.0,
        state: Int = 2,
        durationMs: Long = 10_000L,
        origin: String = "word",
        sessionId: String = "s1",
        isMakeup: Int = 0,
    ) = ReviewLog(
        id = 0L,
        cardKind = cardKind,
        cardId = cardId,
        ts = ts,
        rating = rating,
        elapsedDays = elapsedDays,
        scheduledDays = scheduledDays,
        state = state,
        durationMs = durationMs,
        origin = origin,
        sessionId = sessionId,
        isMakeup = isMakeup,
        logSchemaVer = ReviewLog.CURRENT_SCHEMA_VER,
    )

    private val day = 86_400_000L

    // —— 1. 去重：同 session、同卡、两条相隔 <1s → 只保留第一条（误触连点）——
    @Test fun `dedupe drops the later of two same-card reviews under 1s apart`() {
        val rows = listOf(
            log(ts = 1_000L, elapsedDays = 0.0),
            log(ts = 1_400L), // 同卡、同 session、距前一条 400ms < 1s → 丢弃
            log(ts = 100_000L), // 距前一条 >1s → 保留
        )
        val cleaned = LogPacker.clean(rows)
        assertEquals("连点只保留一条，随后 >1s 的一条正常入列", 2, cleaned.size)
        assertEquals(1_000L, cleaned[0].ts)
        assertEquals(100_000L, cleaned[1].ts)
    }

    @Test fun `dedupe keeps reviews that are exactly 1s or more apart`() {
        val rows = listOf(
            log(ts = 0L, elapsedDays = 0.0),
            log(ts = LogPacker.DUP_WINDOW_MS), // 恰好 =1s → 不算连点，保留
        )
        assertEquals(2, LogPacker.clean(rows).size)
    }

    // —— 2. duration>60000ms → 剔除（文档给"降权或剔除"两选，本实现选剔除）——
    @Test fun `duration over 60s drops the row`() {
        val rows = listOf(
            log(ts = 0L, durationMs = 60_001L, elapsedDays = 0.0), // 中断/挂机 → 剔除
            log(ts = day, durationMs = 5_000L, elapsedDays = 1.0), // 正常 → 保留
        )
        val cleaned = LogPacker.clean(rows)
        assertEquals(1, cleaned.size)
        assertEquals(5_000L, cleaned[0].durationMs)
    }

    @Test fun `duration exactly 60s is kept`() {
        val rows = listOf(
            log(ts = 0L, durationMs = LogPacker.MAX_DURATION_MS, elapsedDays = 0.0),
            log(ts = day, durationMs = 1L, elapsedDays = 1.0),
        )
        assertEquals("边界 60000ms 属合规作答，不剔除", 2, LogPacker.clean(rows).size)
    }

    // —— 3. 同天多次复评（elapsed<1）保留：短期路径要消费它们，绝不能剔 ——
    @Test fun `same-day reviews with elapsed under 1 day are kept`() {
        val rows = listOf(
            log(ts = 0L, elapsedDays = 0.0),
            log(ts = 3_600_000L, elapsedDays = 0.04), // 1 小时后，同天，>1s 不属连点
            log(ts = 7_200_000L, elapsedDays = 0.04), // 再 1 小时
        )
        val cleaned = LogPacker.clean(rows)
        assertEquals("同天多次复评全部保留", 3, cleaned.size)
        val items = LogPacker.items(cleaned)
        assertEquals(1, items.size)
        assertEquals(3, items[0].reviews.size)
        assertTrue(
            "短期项 elapsed 必须 <1 天",
            items[0].reviews.all { it.elapsedDays < 1f },
        )
    }

    // —— makeup 剔除（文档清洗规则：补卡不反映真实记忆；映射到真实 isMakeup 列）——
    @Test fun `makeup rows are dropped`() {
        val rows = listOf(
            log(ts = 0L, isMakeup = 1, elapsedDays = 0.0),
            log(ts = day, isMakeup = 0, elapsedDays = 1.0),
        )
        val cleaned = LogPacker.clean(rows)
        assertEquals(1, cleaned.size)
        assertEquals(day, cleaned[0].ts)
    }

    // —— rating 夹断到 1..4 ——
    @Test fun `rating outside one to four is clamped into range`() {
        val rows = listOf(
            log(ts = 0L, rating = 7, elapsedDays = 0.0),
            log(ts = day, rating = 0, elapsedDays = 1.0),
        )
        val items = LogPacker.items(LogPacker.clean(rows))
        val revs = items[0].reviews
        assertEquals(4, revs[0].rating) // 7 → 夹到上界 4
        assertEquals(1, revs[1].rating) // 0 → 夹到下界 1
    }

    // —— 4. 分组：按 (cardKind,cardId) 分组，组内 ts 升序（乱序输入也要有序）——
    @Test fun `rows group by cardKind and cardId with ts ascending inside each group`() {
        val rows = listOf(
            log(cardKind = ReviewLog.KIND_WORD, cardId = 2L, ts = 5 * day, elapsedDays = 5.0),
            log(cardKind = ReviewLog.KIND_PRACTICE, cardId = 2L, ts = 1 * day, elapsedDays = 1.0),
            log(cardKind = ReviewLog.KIND_WORD, cardId = 1L, ts = 3 * day, elapsedDays = 3.0),
            log(cardKind = ReviewLog.KIND_WORD, cardId = 1L, ts = 9 * day, elapsedDays = 6.0),
            log(cardKind = ReviewLog.KIND_WORD, cardId = 2L, ts = 2 * day, elapsedDays = 0.0),
            log(cardKind = ReviewLog.KIND_PRACTICE, cardId = 2L, ts = 8 * day, elapsedDays = 7.0),
        )
        val items = LogPacker.items(LogPacker.clean(rows))
        // 三组不同 (kind,id) 键：word/1、word/2、practice/2（cardId=2 因 kind 不同而不与 word/2 合并）
        assertEquals(3, items.size)
        // items 按首评 ts 升序
        val firstTs = items.map { it.firstTs }
        assertEquals(firstTs.sorted(), firstTs)
        assertEquals(1L * day, firstTs[0]) // practice/2
        assertEquals(2L * day, firstTs[1]) // word/2
        assertEquals(3L * day, firstTs[2]) // word/1
        // 每组内 reviews 的 elapsed 来自按 ts 升序的行（首条 elapsed 保留原值）
        val word1 = items.first { it.cardKind == ReviewLog.KIND_WORD && it.cardId == 1L }
        assertEquals(3L * day, word1.firstTs)
        assertEquals(listOf(3.0, 6.0), word1.reviews.map { it.elapsedDays.toDouble() })
        // 同名不同 kind 不合并
        val practice2 = items.first { it.cardKind == ReviewLog.KIND_PRACTICE && it.cardId == 2L }
        assertEquals(2, practice2.reviews.size)
    }

    // —— 单 review 的卡整卡丢弃（无损失项，size-1=0）——
    @Test fun `cards with fewer than two reviews are dropped entirely`() {
        val rows = listOf(
            log(cardId = 1L, ts = 0L, elapsedDays = 0.0),
            log(cardId = 2L, ts = day, elapsedDays = 1.0),
            log(cardId = 2L, ts = 2 * day, elapsedDays = 1.0),
        )
        val items = LogPacker.items(LogPacker.clean(rows))
        assertEquals("cardId=1 只一条 → 丢弃；只留 cardId=2", 1, items.size)
        assertEquals(2L, items[0].cardId)
    }

    // —— 5. 打包：FloatArray 长 4×总 review，[rating,elapsed,sched,state] 顺序；bounds 前缀和=review 条数 ——
    @Test fun `pack produces 4 floats per review in contract order with prefix-sum bounds`() {
        val rows = listOf(
            log(cardId = 1L, ts = 0L, rating = 3, elapsedDays = 0.0, scheduledDays = 4.0, state = 0),
            log(cardId = 1L, ts = 2 * day, rating = 2, elapsedDays = 2.0, scheduledDays = 5.0, state = 2),
            // cardId=1 有两条 → 进 items；再放一张够长的卡
            log(cardId = 2L, ts = day, rating = 1, elapsedDays = 0.0, scheduledDays = 3.0, state = 1),
            log(cardId = 2L, ts = 3 * day, rating = 4, elapsedDays = 3.0, scheduledDays = 6.0, state = 2),
            log(cardId = 2L, ts = 5 * day, rating = 3, elapsedDays = 2.0, scheduledDays = 7.0, state = 3),
        )
        val items = LogPacker.items(LogPacker.clean(rows))
        val packed = LogPacker.pack(rows)

        // train/val 拼起来 = 全部 review 数（切分只按卡切，条数守恒）
        val totalReviews = items.sumOf { it.reviews.size }
        assertEquals(
            "float 数组总长 = 4×总 review",
            totalReviews * 4,
            packed.trainReviews.size + packed.valReviews.size,
        )

        // 从 bounds 反解回各卡大小，必须与 items 一致（round-trip）
        val trainSizes = parseSizes(packed.trainReviews, packed.trainBounds)
        val valSizes = parseSizes(packed.valReviews, packed.valBounds)
        assertEquals(trainSizes + valSizes, items.map { it.reviews.size })

        // 校验 [rating, elapsed, sched, state] 顺序：train 的第一条就是 cardId=1 的首评（ts 最早）
        assertEquals(3f, packed.trainReviews[0])
        assertEquals(0f, packed.trainReviews[1])
        assertEquals(4f, packed.trainReviews[2])
        assertEquals(0f, packed.trainReviews[3])
    }

    @Test fun `bounds length equals groups plus one`() {
        val rows = manyCards(n = 10, reviewsPerCard = 2)
        val items = LogPacker.items(LogPacker.clean(rows))
        val packed = LogPacker.pack(rows)
        val trainGroups = splitCut(items.size)
        assertEquals(trainGroups + 1, packed.trainBounds.size)
        assertEquals(items.size - trainGroups + 1, packed.valBounds.size)
        // bounds[0] 必为 0，bounds[last] 必为该子集的 review 总数
        assertEquals(0, packed.trainBounds.first())
        assertEquals(
            items.subList(0, trainGroups).sumOf { it.reviews.size },
            packed.trainBounds.last(),
        )
    }

    // —— 6. train/val 切分：按首评 ts 排序后，前 80% → train，后 20% → val，卡不跨边界（防泄漏）——
    @Test fun `split orders by first-review ts and puts whole cards on one side`() {
        val rows = manyCards(n = 10, reviewsPerCard = 3)
        val items = LogPacker.items(LogPacker.clean(rows))
        val packed = LogPacker.pack(rows)

        val cut = splitCut(items.size)
        assertEquals("10 卡 → 前 8 进 train", 8, cut)

        val trainSizes = parseSizes(packed.trainReviews, packed.trainBounds)
        val valSizes = parseSizes(packed.valReviews, packed.valBounds)
        assertEquals(cut, trainSizes.size)
        assertEquals(items.size - cut, valSizes.size)

        // 边界卡（train 最后一张 = index7，val 第一张 = index8）各自完整落一侧：
        // 每张卡 review 数都是 3，切分后 train 有 cut 组、val 有 n-cut 组，组数守恒 → 无卡被劈开
        assertEquals(List(cut) { 3 }, trainSizes)
        assertEquals(List(items.size - cut) { 3 }, valSizes)
        assertEquals(items.size, trainSizes.size + valSizes.size)

        // items 已按 firstTs 升序，train 覆盖最靠前的卡；用首评 ts 单调性核对边界归属
        val trainMaxFirstTs = items[cut - 1].firstTs
        val valMinFirstTs = items[cut].firstTs
        assertTrue(
            "train 里最晚的首评必须早于 val 里最早的首评",
            trainMaxFirstTs < valMinFirstTs,
        )
    }

    @Test fun `val receives the last twenty percent rounded up`() {
        // n=7 → cut=floor(0.8*7)=5 → val=2=ceil(0.2*7)
        val rows = manyCards(n = 7, reviewsPerCard = 2)
        val items = LogPacker.items(LogPacker.clean(rows))
        val packed = LogPacker.pack(rows)
        assertEquals(2, parseSizes(packed.valReviews, packed.valBounds).size)
        assertEquals(5, parseSizes(packed.trainReviews, packed.trainBounds).size)
    }

    // —— 7. nTerms = Σ(组内条数-1)，train/val 各自计 ——
    @Test fun `nTerms equals sum of group size minus one per side`() {
        // card 1: 2 条 → 1 项；card 2: 3 条 → 2 项；card 3: 4 条 → 3 项。共 9 项。
        val rows = listOf(
            *cardReviews(1L, 2),
            *cardReviews(2L, 3),
            *cardReviews(3L, 4),
        )
        val items = LogPacker.items(LogPacker.clean(rows)) // 按 firstTs：card1,card2,card3
        val packed = LogPacker.pack(rows)
        val cut = splitCut(items.size) // floor(0.8*3)=2 → train=card1,card2；val=card3
        assertEquals(2, cut)
        // train terms = (2-1)+(3-1) = 3；val terms = (4-1) = 3
        assertEquals(3, packed.nTrainTerms)
        assertEquals(3, packed.nValTerms)
    }

    // —— 8. 空输入不崩、返回空 ——
    @Test fun `empty input yields empty pack without crashing`() {
        val packed = LogPacker.pack(emptyList())
        assertEquals(0, packed.trainReviews.size)
        assertEquals(0, packed.valReviews.size)
        // bounds 长度 = 卡数+1 = 1（只剩哨兵 0）
        assertEquals(listOf(0), packed.trainBounds.toList())
        assertEquals(listOf(0), packed.valBounds.toList())
        assertEquals(0, packed.nTrainTerms)
        assertEquals(0, packed.nValTerms)
    }

    @Test fun `input that cleans down to nothing also packs empty`() {
        // 全部单 review 卡 + 一条 makeup → 清洗与分组后无 item
        val rows = listOf(
            log(cardId = 1L, ts = 0L, elapsedDays = 0.0),
            log(cardId = 2L, ts = day, elapsedDays = 1.0),
            log(cardId = 3L, ts = 2 * day, elapsedDays = 1.0, isMakeup = 1),
        )
        val packed = LogPacker.pack(rows)
        assertEquals(listOf(0), packed.trainBounds.toList())
        assertEquals(listOf(0), packed.valBounds.toList())
        assertEquals(0, packed.nTrainTerms + packed.nValTerms)
    }

    // ===================== helpers =====================

    /** 用 lib.rs 的口径把 (reviews,bounds) 反解回每卡 review 数列表。 */
    private fun parseSizes(reviews: FloatArray, bounds: IntArray): List<Int> {
        assertEquals("float 长度必须是 4 的倍数", 0, reviews.size % 4)
        assertEquals("float 长度 = 4×bounds 末值", bounds.last() * 4, reviews.size)
        return (0 until bounds.size - 1).map { bounds[it + 1] - bounds[it] }
    }

    /** 时序切分点：train 卡数 = floor(n×0.8)（与实现同一公式）。 */
    private fun splitCut(n: Int): Int = (n * (1.0 - LogPacker.VAL_FRACTION)).toInt()

    /** 造 n 张卡（首评 ts 递增、互不相连），每张 reviewsPerCard 条。 */
    private fun manyCards(n: Int, reviewsPerCard: Int): List<ReviewLog> {
        val out = ArrayList<ReviewLog>()
        for (c in 0 until n) {
            out += cardReviews(cardId = (100L + c), count = reviewsPerCard, baseTs = c.toLong() * 10 * day)
        }
        return out
    }

    /** 同一张卡的 count 条 review，间隔 2 天（既不连点、也不同天），首评 ts = baseTs。 */
    private fun cardReviews(
        cardId: Long,
        count: Int,
        baseTs: Long = cardId * day,
    ): Array<ReviewLog> = Array(count) { i ->
        log(
            cardId = cardId,
            ts = baseTs + i * 2 * day,
            rating = 3,
            elapsedDays = if (i == 0) 0.0 else 2.0,
            scheduledDays = (4 + i).toDouble(),
            state = if (i == 0) 1 else 2,
        )
    }
}
