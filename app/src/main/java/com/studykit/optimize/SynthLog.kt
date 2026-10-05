package com.studykit.optimize

import com.studykit.data.dao.ReviewLogDao
import com.studykit.data.entity.ReviewLog
import com.studykit.data.memory.FsrsModel
import kotlin.random.Random

/**
 * 合成复习日志生成器（v2.9 计划 Task 8）。
 *
 * 模拟 FSRS v6 的**正向过程**：给定一组"真值参数" `w`，按模型召回率采样评分、按目标保留率
 * 排下一次复习，产出可直接喂 [LogPacker.pack] 的 [ReviewLog] 行。两大用途：
 *  - **参数恢复测试**（Task 9/10）：用已知 `wTrue` 造数据 → 训练 → 断言能把参数学回来；
 *    这是"优化器真能反推曲线"的唯一硬证据，也是验收门禁的前置回归。
 *  - **真机干跑灌数**：设置页入口（或 [seedForDebug]）一次性铺 1500 卡假复习记录，
 *    让校准链路在缺少真实记录时也能端到端跑通看报告。入口只在 `-PseedSynth=true` 的本地构建里出现，
 *    且 `review_log` 已有记录即收起——这张表 append-only、清库也不删，铺进去的假记录没有反悔路径。
 *    （v2.9 验收那台机因旧包由另一把 debug 钥匙签，只能卸载重装，见 CHANGELOG 已知边界。）
 *
 * **口径与推理同式**：状态转移逐值走 [FsrsModel]（`initStability`/`initDifficulty`/`recall`/
 * `nextIntervalDays`/`recallStability`/`forgetStability`/`nextDifficulty`），并且**完全对齐
 * [com.studykit.data.memory.FsrsKernel.review] 的分支**——非首评用「旧 S、旧 D」同时算 nextS/newD，
 * 首评只落 S0/D0；这样恢复测试里 Rust/Kotlin 训练器重放曲线时看到的正是同一份前向。
 *
 * **评分如何从预测召回采样**：复习发生在排期 `tSched` 之后带延迟噪声的真实时刻 `tAct`，模型此刻
 * 预测召回 `p = recall(w, S, tAct)`。掷 `u∈(0,1)`：`u≥p` 判为"没记住"→ Again(1)；`u<p` 判为"记住了"
 * 再按裕度分档——`u<p·0.5` 给 Easy(4)、`p·0.5≤u<p·0.9` 给 Good(3)、其余给 Hard(2)。于是
 * `P(rating≥2)=p` 逐条成立（"记住了"的概率恰等于模型预测召回），群体实测召回自然贴合模型预测。
 * 首评无历史预测，改用固定分布 [Again,Hard,Good,Easy]=[.1,.2,.5,.2]。
 *
 * **确定性**：全程只用 [Random] 的 seed 驱动 + 固定基准时间戳 [BASE_EPOCH_MS]，绝不读 wall-clock
 * （文档 Kotlin 示例用了 `System.currentTimeMillis()`，那会毁掉"同 seed 同输出"，本实现刻意改为常量）。
 *
 * 与 Rust `synth.rs` 的差异：本版**不含同天加练**（`same_day_p`）——它会引入 elapsed=0 的行、
 * 干扰 ts 严格升序与召回口径，且 Task 8 的采样契约不需要；如后续要做"同天短期路径"覆盖，再补。
 *
 * Task 11/12 落地：设置页在 `-PseedSynth=true` 构建里暴露"灌合成数据(1500 卡)"入口并调用 [seedForDebug]
 * 写满 `review_log`，再走校准（报告只出，换核要另一次显式采纳）。
 */
object SynthLog {

    const val DAY_MS = 86_400_000L

    /** 固定基准 epoch（毫秒）：所有 ts 由它 + 相对天数推出，与真实时间无关 → 保证可复现。 */
    private const val BASE_EPOCH_MS = 1_700_000_000_000L

    /** 每张卡模拟的时间地平线（天）：超过就停止给该卡追加复习。 */
    private const val HORIZON_DAYS = 730.0

    /** 首评日在 [0, FIRST_DAY_WINDOW) 天内随机错开，模拟用户陆续建卡。 */
    private const val FIRST_DAY_WINDOW = 30.0

    /** 排期间隔的合理夹取域（对齐文档 Rust synth 的 `clamp(0.5, 365)`）：亚天/超一年都不真实。 */
    private const val SCHEDULE_MIN_DAYS = 0.5
    private const val SCHEDULE_MAX_DAYS = 365.0

    /** 传给 [FsrsModel.nextIntervalDays] 的上限：远大于 [SCHEDULE_MAX_DAYS]，使排期夹取只由上面的 coerce 决定。 */
    private const val MAX_INTERVAL_CAP = 3650.0

    /** 用户"实际来复习"相对排期的延迟噪声区间 [0.7, 1.6)·tSched。 */
    private const val DELAY_LO = 0.7
    private const val DELAY_SPAN = 0.9

    /**
     * 生成 [nCards] 张卡、每卡至多 [reviewsPerCard] 次的合成复习日志，按卡顺序 emit（同一卡的 ts 严格升序）。
     *
     * @param seed 确定性种子；同 (seed, 其余参数) 恒得逐元素相等的结果。
     * @param reviewsPerCard 每卡复习次数上限；受 [HORIZON_DAYS] 影响，实际可能更少（但至少 1 次首评）。
     * @param w 真值参数向量（21 项 v6），默认 [FsrsModel.DEFAULT_PARAMS]；恢复测试会传扰动后的真值。
     * @param retention 目标保留率（排期用），默认 0.9。
     */
    fun generate(
        seed: Long,
        nCards: Int,
        reviewsPerCard: Int,
        w: DoubleArray = FsrsModel.DEFAULT_PARAMS,
        retention: Double = 0.9,
    ): List<ReviewLog> {
        val rng = Random(seed)
        val out = ArrayList<ReviewLog>(nCards * (reviewsPerCard.coerceAtLeast(1)))
        for (c in 0 until nCards) {
            val cardId = c.toLong() + 1L
            val sessionId = "synth-$c"

            // 首评：卡间错开的起始日；无历史预测，按固定分布采样。首评只落 S0/D0（state=0）。
            var day = rng.nextDouble() * FIRST_DAY_WINDOW
            val g0 = sampleFirstRating(rng)
            var s = FsrsModel.initStability(w, g0)
            var d = FsrsModel.initDifficulty(w, g0.toDouble())
            out += row(cardId, sessionId, day, g0, elapsed = 0.0, scheduled = 0.0, state = 0, rng = rng)

            for (i in 1 until reviewsPerCard) {
                // 按目标保留率排下一次复习，再夹进合理域
                val tSchedRaw = FsrsModel.nextIntervalDays(w, s, retention, MAX_INTERVAL_CAP)
                val tSched = tSchedRaw.coerceIn(SCHEDULE_MIN_DAYS, SCHEDULE_MAX_DAYS)
                // 用户实际经过时间 = 排期 × 延迟噪声
                val tAct = tSched * (DELAY_LO + rng.nextDouble() * DELAY_SPAN)
                if (day + tAct > HORIZON_DAYS) break
                day += tAct

                // 采样评分：让 P(rating≥2) = 模型此刻预测召回
                val rr = FsrsModel.recall(w, s, tAct)
                val g = sampleRating(rng, rr)

                // 与 FsrsKernel.review 同口径：nextS / nextD 均基于【旧】 s, d，算完再一起推进
                val newS = if (g == 1) FsrsModel.forgetStability(w, s, d, rr)
                else FsrsModel.recallStability(w, s, d, rr, g)
                val newD = FsrsModel.nextDifficulty(w, d, g.toDouble())
                s = newS
                d = newD

                out += row(cardId, sessionId, day, g, elapsed = tAct, scheduled = tSched, state = 2, rng = rng)
            }
        }
        return out
    }

    /**
     * debug 专用：把合成数据一次性灌进 `review_log`（[ReviewLogDao] 只有 append 路径，符合事件流纪律）。
     *
     * Task 11 的 debug 菜单 / `CalibrateViewModel.debugSeed()` 调用它铺满复习记录，随后走校准**干跑**。
     * 生产构建不应触达此入口；默认 1500 卡 × 12 次足以越过优化器的 1000-term 门禁。
     */
    suspend fun seedForDebug(
        dao: ReviewLogDao,
        nCards: Int = 1500,
        reviewsPerCard: Int = 12,
        seed: Long = 42L,
        w: DoubleArray = FsrsModel.DEFAULT_PARAMS,
        retention: Double = 0.9,
    ) {
        for (r in generate(seed, nCards, reviewsPerCard, w, retention)) {
            dao.insert(r)
        }
    }

    /** 首评固定分布 [Again,Hard,Good,Easy]=[.1,.2,.5,.2]。 */
    private fun sampleFirstRating(rng: Random): Int {
        val u = rng.nextDouble()
        return when {
            u < 0.1 -> 1
            u < 0.3 -> 2
            u < 0.8 -> 3
            else -> 4
        }
    }

    /** 成功支按裕度分档（越"确定记住"越偏 Easy/Good），失败支恒 Again(1)。 */
    private fun sampleRating(rng: Random, p: Double): Int {
        val u = rng.nextDouble()
        return if (u < p) {
            when {
                u < p * 0.5 -> 4
                u < p * 0.9 -> 3
                else -> 2
            }
        } else {
            1
        }
    }

    /** 组装一条 [ReviewLog]：cardKind=word、origin=synth、isMakeup=0、duration 小、selfConf 采样 1..3。 */
    private fun row(
        cardId: Long,
        sessionId: String,
        day: Double,
        rating: Int,
        elapsed: Double,
        scheduled: Double,
        state: Int,
        rng: Random,
    ): ReviewLog = ReviewLog(
        id = 0L,
        cardKind = ReviewLog.KIND_WORD,
        cardId = cardId,
        ts = BASE_EPOCH_MS + (day * DAY_MS).toLong(),
        rating = rating,
        elapsedDays = elapsed,
        scheduledDays = scheduled,
        state = state,
        durationMs = 2_000L + (rng.nextDouble() * 6_000.0).toLong(), // 2000..8000ms，恒低于 LogPacker 剔除阈
        origin = "synth",
        ocrConf = null,
        selfConf = 1 + rng.nextInt(3), // Confidence 存储域 1..3
        sessionId = sessionId,
        isMakeup = 0,
        logSchemaVer = ReviewLog.CURRENT_SCHEMA_VER,
    )
}
