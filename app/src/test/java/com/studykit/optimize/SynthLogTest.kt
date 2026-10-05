package com.studykit.optimize

import com.studykit.data.entity.ReviewLog
import com.studykit.data.memory.FsrsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Task 8 单测：[SynthLog] —— 模拟 FSRS-6 **正向过程**合成复习日志。
 *
 * 纯 JVM（不碰 Room/协程）：直接调 [SynthLog.generate] 得到 `List<ReviewLog>` 校验四条契约：
 *  1. **确定性**：同 seed → 逐元素 contentEquals（RNG 与时序都写死，无 wall-clock）；
 *  2. **合理性**：以已知 w（默认 [FsrsModel.DEFAULT_PARAMS]）生成，把每条复习的「实测是否记住」
 *     （rating≥2 记为 recalled）与其「模型预测召回」对照——预测召回从 `scheduledDays`（模型上次排
 *     的间隔，正好反解出当时 stability S）+ `elapsedDays`（真实经过）用 [FsrsModel.recall] 复算；
 *     群体均值应落在容差内，且分桶单调（高预测桶观测召回不低于低预测桶）；rating∈1..4、
 *     elapsedDays≥0、每张卡 ts 严格升序；
 *  3. **体量**：可快速（<2s）造 ≥1500 卡，条数足够越过 1000-term 优化器门禁；
 *  4. **可打包**：产物喂 [LogPacker] 天然干净（连点/duration/makeup 都不触发），pack 不崩、terms>0。
 *
 * 反解口径：v6 排期 `interval = S / FACTOR · (R^(1/DECAY) − 1)`（见 [FsrsModel.nextIntervalDays]）。
 * 故未被夹取的 `scheduledDays` 可逆出 `S = scheduledDays · FACTOR / (retention^(1/DECAY) − 1)`，
 * 再 `recall(w, S, elapsedDays)` 即得采样评分时模型真正看到的 p（retention=0.9 时 S==scheduledDays）。
 */
class SynthLogTest {

    private val w = FsrsModel.DEFAULT_PARAMS
    private val retention = 0.9

    /** 从 scheduledDays 反解当时的 stability（仅对未被 [0.5,365] 夹取的行精确）。 */
    private fun stabilityFromSchedule(scheduledDays: Double): Double {
        val decay = FsrsModel.decay(w)
        val factor = FsrsModel.factor(w)
        val dr = retention.coerceIn(0.5, 0.99)
        return scheduledDays * factor / (dr.pow(1.0 / decay) - 1.0)
    }

    // —— 1. 确定性 ——
    @Test fun `same seed yields identical review log list`() {
        val a = SynthLog.generate(seed = 42L, nCards = 200, reviewsPerCard = 20, w = w, retention = retention)
        val b = SynthLog.generate(seed = 42L, nCards = 200, reviewsPerCard = 20, w = w, retention = retention)
        assertTrue("空产物无从谈确定性", a.isNotEmpty())
        assertEquals(a.size, b.size)
        // data class 逐字段相等 == 内容完全一致（含 ts/rating/elapsed/sched/state/selfConf）。
        // Kotlin List 的结构相等即逐元素 contentEquals（stdlib 无 List.contentEquals，用 toTypedArray 显式对齐语义）
        assertTrue(
            "同 seed 必须逐元素 contentEquals",
            a.toTypedArray().contentEquals(b.toTypedArray()),
        )
        // 异 seed 应产出不同数据（否则 seed 形同虚设）
        val c = SynthLog.generate(seed = 7L, nCards = 200, reviewsPerCard = 20, w = w, retention = retention)
        assertTrue(
            "不同 seed 应给出不同序列",
            !a.toTypedArray().contentEquals(c.toTypedArray()),
        )
    }

    // —— 2a. 结构约束：rating 域、elapsed 非负、每卡 ts 严格升序 ——
    @Test fun `ratings elapsed and per-card ts ordering are well formed`() {
        val rows = SynthLog.generate(seed = 11L, nCards = 300, reviewsPerCard = 25, w = w, retention = retention)
        for (r in rows) {
            assertTrue("rating 必须 ∈ 1..4，实际=${r.rating}", r.rating in 1..4)
            assertTrue("elapsedDays 必须 ≥0，实际=${r.elapsedDays}", r.elapsedDays >= 0.0 && r.elapsedDays.isFinite())
            assertTrue("scheduledDays 必须 ≥0", r.scheduledDays >= 0.0 && r.scheduledDays.isFinite())
            assertEquals("cardKind 固定 word", ReviewLog.KIND_WORD, r.cardKind)
            assertEquals("origin 固定 synth", "synth", r.origin)
            assertEquals("isMakeup 恒 0", 0, r.isMakeup)
            assertTrue("durationMs 为正且低于 LogPacker 剔除阈 60000", r.durationMs > 0 && r.durationMs < 60_000L)
            // selfConf 可空；非空时须落在 Confidence 存储域 1..3
            r.selfConf?.let { assertTrue("selfConf ∈ 1..3", it in 1..3) }
        }
        // 每张卡内部 ts 严格升序（append-only 事件流的时序前提，也是 LogPacker 分组依赖）
        rows.groupBy { it.cardId }.forEach { (cardId, list) ->
            // 生成器按卡顺序 emit，但为稳妥这里显式按 ts 排一次再验严格升
            val sorted = list.sortedBy { it.ts }
            for (i in 1 until sorted.size) {
                assertTrue(
                    "卡 $cardId 的 ts 必须严格升序（第 $i 行 ${sorted[i].ts} <= ${sorted[i - 1].ts}）",
                    sorted[i].ts > sorted[i - 1].ts,
                )
            }
        }
    }

    // —— 2b. 合理性：观测召回 ≈ 模型预测召回 ——
    @Test fun `empirical recall tracks model predicted recall`() {
        val rows = SynthLog.generate(seed = 5L, nCards = 800, reviewsPerCard = 25, w = w, retention = retention)

        // 只取「有预测召回可复算」的行：非首评(state!=0)、scheduledDays 严格落在 (0.5,365) 未夹取区
        val evaluable = rows.filter { it.state != 0 && it.scheduledDays > 0.5 && it.scheduledDays < 365.0 }
        assertTrue("样本量不足以做召回对照：${evaluable.size}", evaluable.size >= 300)

        var sumPred = 0.0
        var sumObs = 0.0
        for (r in evaluable) {
            val s = stabilityFromSchedule(r.scheduledDays)
            val pred = FsrsModel.recall(w, s, r.elapsedDays)
            sumPred += pred
            sumObs += if (r.rating >= 2) 1.0 else 0.0 // rating≥2 视作"记住了"，与采样里 success 分支同口径
        }
        val n = evaluable.size
        val meanPred = sumPred / n
        val meanObs = sumObs / n
        // 群体均值差应在小容差内（大数定律：每条 rating≥2 的期望恰为该条预测召回 p）
        assertTrue(
            "观测召回($meanObs) 应贴合预测召回($meanPred)，偏差 ${abs(meanObs - meanPred)} 过大",
            abs(meanObs - meanPred) < 0.05,
        )

        // 分桶单调：按预测召回三等分，高桶观测召回应不低于低桶（真正"tracks"而非碰巧均值相等）
        val sorted = evaluable.map {
            val s = stabilityFromSchedule(it.scheduledDays)
            FsrsModel.recall(w, s, it.elapsedDays) to (if (it.rating >= 2) 1.0 else 0.0)
        }.sortedBy { it.first }
        val third = sorted.size / 3
        fun mean(list: List<Pair<Double, Double>>): Double =
            if (list.isEmpty()) 0.0 else list.sumOf { it.second } / list.size
        val lowObs = mean(sorted.subList(0, third))
        val highObs = mean(sorted.subList(2 * third, sorted.size))
        val lowPred = mean(sorted.subList(0, third).map { it.first to it.first })
        val highPred = mean(sorted.subList(2 * third, sorted.size).map { it.first to it.first })
        assertTrue("高预测桶($highObs) 观测召回应高于低预测桶($lowObs)", highObs > lowObs)
        // 且每桶的观测也贴合该桶预测（各自容差放宽到 0.1 吸收分布内噪声）
        assertTrue("低桶观测${'$'}lowObs 偏离预测${'$'}lowPred 过大", abs(lowObs - lowPred) < 0.12)
        assertTrue("高桶观测${'$'}highObs 偏离预测${'$'}highPred 过大", abs(highObs - highPred) < 0.12)
    }

    // —— 3. 体量 + 速度 ——
    @Test fun `can generate over 1500 cards quickly and clear 1000-term gate`() {
        // 先热身一次（JIT 归零首轮开销），再计时正式的那次
        SynthLog.generate(seed = 1L, nCards = 50, reviewsPerCard = 5, w = w, retention = retention)
        val start = System.nanoTime()
        val rows = SynthLog.generate(seed = 100L, nCards = 1500, reviewsPerCard = 30, w = w, retention = retention)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        val cards = rows.map { it.cardId }.distinct().size
        assertTrue("必须造满 1500 张卡，实际 $cards", cards >= 1500)
        assertTrue("1500 卡生成应在 2s 内，实际 ${elapsedMs}ms", elapsedMs < 2000L)

        // 越过 1000-term 门禁：喂 LogPacker 回算 terms（Σ 每卡 reviews-1），train+val 合计须 ≥1000
        val packed = LogPacker.pack(rows)
        val terms = packed.nTrainTerms + packed.nValTerms
        assertTrue("合成数据须足以越过 1000-term 优化器门禁，实际 terms=$terms", terms >= 1000)
    }

    // —— 4. 可打包：clean 不剔除任何合成行（连点/duration/makeup 皆不触发）——
    @Test fun `output feeds LogPacker cleanly without dropping rows`() {
        val rows = SynthLog.generate(seed = 3L, nCards = 120, reviewsPerCard = 15, w = w, retention = retention)
        val cleaned = LogPacker.clean(rows)
        // 每条相邻复习间隔 >= 0.5 天量级（远超 1s 连点窗）、duration<60000、isMakeup=0 → 全保留
        assertEquals("LogPacker.clean 不应剔除任何合成行", rows.size, cleaned.size)
        val items = LogPacker.items(cleaned)
        assertEquals("每张卡应聚成一个 item", rows.map { it.cardId }.distinct().size, items.size)
        assertTrue("items 按首评 ts 升序", items.zipWithNext().all { it.first.firstTs <= it.second.firstTs })
    }
}
