package com.studykit.data.memory

import com.studykit.data.entity.ReviewLog
import com.studykit.optimize.SynthLog
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Task 2（v3.0-A）负荷带的**精度门**：机器检文档那句「合成日志预测 vs replay 实测日均 MAE ≤1 天」。
 *
 * ## 1. 语料分配：两份语料，谁用哪一份（round-2 修 I-1）
 *  - **自洽语料**（生成用 [w]，预测也用 [w]）→ **门 1** 与 **门 3**。门 1 是**记账门**（反解→取整落格→
 *    返工摊派→窗边界那本账），它的 `MAE = 0.0` 恰恰依赖这份自洽；把它指到分叉语料上，它会为
 *    「模型失配」这个**错理由**而红，从此再也读不出记账问题。
 *  - **扰动语料**（生成用 [perturbedW]，预测仍只用 [w]）→ **门 2**。存进语料的排期间隔出自另一个模型，
 *    「预测 vs 直接相信排期日」的朴素基线才是**两个数**，这条不劣化门才有独立威力。
 *  两份语料各用独立 seed（门 1 seed=7 / 门 3 seed=11 / 门 2 seed=13），互不复用样本。
 *
 * ## 2. 那条恒等式（旧版 KDoc 说错了因果）
 * [stabilityFromScheduled] 是 [DueForecast.intervalDays] 在同一份 `w` 上的**解析逆**，于是
 * `intervalDays(w, stabilityFromScheduled(t, w), R) ≡ t` 对**任意** `R` 都成立——与 `R == 0.9` 无关
 * （旧文把它归因于「retention 正好是 0.9」，那是错的；0.9 只在另一处出场：[FsrsModel.factor] 以
 * `0.9^(1/decay) − 1` 定义，所以调度器在 R=0.9 时排出的 `interval ≡ S`）。后果：真相侧的排期格与
 * 「直接相信落库排期日」的基线格本来就是同一个数，门 2 在**同 `w` 语料**上只可能测到记账。
 * 旧版还有一处稀释：系统性的预测偏移被塞进 MAE 的**差**里，与语料自身 ±0.25·S 的迟到噪声对冲，
 * 门几乎不可能红。所以本轮把门 2 的语料换成分叉语料，并加断言盯住「两侧确实分叉」（[minSeparatedCards]）。
 *
 * ## 3. 逾期夹取：结果有多少是在夹取之上挣来的（round-2 修 I-2）
 * [DueForecast.forecast] 里 `remain = max(0, interval − elapsed)` 把**逾期**卡一律钉在第 0 格，实测
 * 约一半样本（真值与预测都是）落在这一格——于是门 1 的 `MAE = 0.0` 与门 3 的「0 vs 0」只有一半是挣来的。
 * 两条门各自**计数并断言**「真值格 > 0」的样本量下限（[minTruthBeyondClampGate1] / [minTruthBeyondClampGate3]），
 * 并把这一占比同时打进 `GATE*` 行与断言消息：以后谁改了语料参数，先看的是 informative 样本有没有塌掉。
 *
 * ## 4. 文档那句 ≤1 天的**两种读法**，两道门都实现（裁决：不许放宽阈值，歧义两侧各自成门）
 * `SynthLog` 给每张卡的实际复习时刻叠了 `U(0.7,1.6)` 的**用户迟到噪声**（`SynthLog.kt:60-61,97`），
 * 于是「replay 实测」既可能指**排期到期日**，也可能指**用户实际出现那天**。两侧分开量：
 *  1. **读法 (b)——绝对门**：逐卡到期格子 vs 语料落库的**排期到期日** ≤1 天，并且（M-3）在自洽语料上
 *     **逐格相等**——`MAE ≤ 1.0` 是文档给的规格地板，但真正抓得住记账漂移的是那条逐格断言：
 *     一次 `roundToInt`→`toInt` 级别的回归平均只有 ~0.5 天，靠 1.0 的地板拦不住；
 *  2. **读法 (a)——不劣化门**：逐卡到期格子 vs **实际到达日**，不得比朴素基线差过 0.5 天。那条**绝对**
 *     ≤1 天在这里没有意义：噪声比例 f ~ U(0.7,1.6) 的 `E|f−1| = 0.25`，实际到达日离自己的排期日恒有
 *     约 `0.25·S` 天，与预测质量无关（算术写在门 2 的注释里）。外加「基线 > 1 天」哨兵：哪天语料抖动
 *     被压进 1 天以内它会红，提醒把这条改回绝对口径，而不是让相对门永久化。
 *  3. 负荷带峰值日 vs **排期**最忙日相差 ≤1 天。旧版这里算了「噪声后的实际最忙日」却**从不断言**
 *     （实测 0 vs 4，永远过不了 ±1，那 4 天是迟到量级不是本门的账），round-2 按裁决删掉这份死计算。
 *
 * 状态重建只用 `ts <= cut` 的行（切点后各行的 `scheduledDays` 只出现在**真相**侧，不进预测输入）。
 */
class DueForecastAccuracyTest {

    private val dayMs = SynthLog.DAY_MS
    private val horizon = 14
    private val retention = 0.9

    /** 预测侧唯一的参数向量：三道门的**预测**（重放 + 反解 + 负荷带）都只吃它，即 app 的活跃参数。 */
    private val w = FsrsModel.DEFAULT_PARAMS

    /**
     * 只谈已排卡片的到期时机：新词流入与学习步关掉，免得把「今天进多少张」的假设混进精度账。
     *
     * 上限取 [ForecastCfg.MAX_INTERVAL_DAYS_UNSCOPED]（3650 天）而不是某份 `Scheduling`：本文件测的是
     * **记账**（反解→取整→摊派→窗边界），语料出自合成器自己那份 365 天政策上限（见
     * [synthScheduleCapDays]），没有考试日期可问。取大值等于「不额外夹一道」，
     * 让恒等式 `intervalDays(w, stabilityFromScheduled(t, w), R) ≡ t` 保持成立——
     * 真政策上限怎么夹，由 `DueForecastTest` 那三条专门盯。
     */
    private val cfg = ForecastCfg(
        horizonDays = horizon,
        retention = retention,
        maxIntervalDays = ForecastCfg.MAX_INTERVAL_DAYS_UNSCOPED,
        newPerDay = 0,
        learningPerNew = 0.0,
    )

    /** 生成器给排期间隔夹的政策上限（`SynthLog.kt:55` 的私有常量，值 365 天）；撞上它时记录值不再等于稳定度。 */
    private val synthScheduleCapDays = 365.0

    /**
     * **语料侧**的「真值」参数，只有门 2 的语料用它生成（`w = perturbedW`），预测仍用 [w]。
     *
     * 为什么只动 `w[8]`：R=0.9 时排期间隔恒等于稳定度（见文件头第 2 段），所以「历史出自哪个模型」
     * 完全写在稳定度轨迹上，而 `w[8]` 正是成功支增幅的指数项——动这一个维度，效果能说成一句人话：
     * **每次成功复习后的稳定度增幅放大 `e^0.01 ≈ 1.0101` 倍**（`recallStability` 的 `inc` 项整体乘它）。
     * 取**最小可用**量级：够让两张格子图真实分叉（门 2 断言分叉卡数 ≥ [minSeparatedCards]），
     * 又不至于把这条门从「记账门」改写成「模型失配门」——失配本身值多少个 0.5 天不由本门裁决。
     * 方向是**对预测不利**的一侧（语料把间隔排得比默认参数更乐观 → 预测系统性偏早），
     * 这样门绿起来是硬挣的，不是挑了个让自己好看的方向。
     */
    private val perturbedW: DoubleArray = FsrsModel.DEFAULT_PARAMS.copyOf().also { it[8] += 0.01 }

    // —— 非零真值样本量地板（I-2）。三个数都按本轮实测写下限（留 30–65% 余量），语料参数日后被改动到
    //    只剩一点点信息量时先在这里红，而不是让 `MAE=0.0` / `0 vs 0` 悄悄变成逾期夹取的产物：
    //    门 1 实测 17/55 张真值格 >0（第 0 格 38 张）、门 3 实测 38/76、门 2 实测两侧分叉 18/157 张。
    private val minTruthBeyondClampGate1 = 12
    private val minTruthBeyondClampGate3 = 25
    private val minSeparatedCards = 10

    /**
     * 计划间隔 → 稳定度的反解，即 [FsrsModel.nextIntervalDays] 的解析逆。
     * **全文件唯一一处**，三个测试都经 [replays] 走它，不许各写一遍反解。
     *
     * 因为是解析逆，`intervalDays(w, stabilityFromScheduled(t, w), R, cap) ≡ t` 对**任意** `R` 都成立
     * （不是「因为 retention == 0.9」——那句旧话错了因果）。只有三处夹取能让它不成立：传进去的
     * 政策上限 [cfg]（本文件取 3650 天的 [ForecastCfg.MAX_INTERVAL_DAYS_UNSCOPED]，等于不额外夹）、
     * 生成器的 365 天政策上限（见 [synthScheduleCapDays]）、以及
     * `nextIntervalDays` 把 `target` 夹进 `[0.5, 0.99]` 而本反解不夹。写成一般式而不是化简成
     * `scheduledDays`，就是为了让这些真发生时这条仍是对的。
     */
    private fun stabilityFromScheduled(scheduledDays: Double): Double =
        scheduledDays * FsrsModel.factor(w) / (retention.pow(1.0 / FsrsModel.decay(w)) - 1.0)

    /**
     * 切点上的卡状态：把 `ts <= cut` 的行按**预测侧**的 [w] 前向重放（`FsrsModel` 的
     * `initStability/initDifficulty/recall/recallStability/forgetStability/nextDifficulty`），
     * 得到切点上的稳定度与距末次复习的天数。门 2 的语料是 [perturbedW] 排的，这里**故意不**跟着换过去：
     * app 手上只有活跃参数，「用错的参数重放自己的历史」正是这条门要测的失配。
     *
     * **为什么不按 brief 那样直接反解「切点前最后一行的 `scheduledDays`」**：合成器把每一行的
     * `scheduledDays` 记成**促成这次复习的那个间隔**（= 上次复习后的稳定度 `S_{k-1}`，见
     * `SynthLog.kt:92-112`，与 py-fsrs 的 `review_log.scheduled_days` 同口径；app 的词/摘评分点
     * 自 v3.0-A T2.5 起也统一到这一口径，`log_schema_ver=2`，此前词侧落的是「这次刚排出去的间隔」）。
     * 两种读法都反解不出切点上的 `S_k`——这一列讲的是**上一次**排了多长，切点要的却是**这一次**排出去
     * 的多长。按 brief 取 `S_{k-1}` 会系统性地把到期日提前（`S` 在涨），实测把 MAE 抬到 5.8 天，且让
     * 负荷带 274/400 张卡堆在第 0 格——那是出题口径的错，不是预测的错。这里改成重放，切点前的信息就够，不吃未来。
     */
    private fun stateAt(hist: List<ReviewLog>, cutTs: Long): ForecastCard? {
        var s = 0.0
        var d = 0.0
        var lastTs = -1L
        var seen = false
        for (r in hist.sortedBy { it.ts }) {
            if (r.ts > cutTs) break
            if (!seen) {
                s = FsrsModel.initStability(w, r.rating)
                d = FsrsModel.initDifficulty(w, r.rating.toDouble())
                seen = true
            } else {
                val rr = FsrsModel.recall(w, s, r.elapsedDays)
                val grown = if (r.rating == 1) FsrsModel.forgetStability(w, s, d, rr)
                else FsrsModel.recallStability(w, s, d, rr, r.rating)
                d = FsrsModel.nextDifficulty(w, d, r.rating.toDouble())
                s = grown
            }
            lastTs = r.ts
        }
        return if (seen) ForecastCard(s, (cutTs - lastTs).toDouble() / dayMs) else null
    }

    /** 一张卡在切点上的四份账：预测输入 + 调度器排定的到期日 + 朴素基线的到期日 + 实际到达日。 */
    private class Replayed(
        val card: ForecastCard,
        /** 调度器为「切点后第一次复习」排的那一天（0..，>= [horizon] 即出窗）。 */
        val planDay: Int,
        /** 朴素基线「直接相信落库的排期日」给出的到期格：`round(scheduledDays − elapsedDays)`，不经 [DueForecast]。 */
        val baselineDay: Int,
        /** 实际发生复习的那一天（切点后必有下次复习，否则这张卡不入账）。 */
        val actualDay: Int,
        /** 该行记的间隔撞到生成器的 365 天政策上限：此时记录值不再等于稳定度，不能当真相。 */
        val planSaturated: Boolean,
    )

    /**
     * 把语料折成切点上的账。`card`（预测输入）只用切点前的行 + [w]；`planDay`/`baselineDay`/`actualDay`
     * 是真相侧，允许读切点后的行——那本来就是「事后」。
     *
     * 由文件头第 2 段的恒等式，`planDay` 与 `baselineDay` 本来就是同一个数（都 = `round(落库排期 − elapsed)`）；
     * 留着两份是为了让「门 1 的真相」与「门 2 的基线」在代码里各有所指，而不是互相引用。
     */
    private fun replays(logs: List<ReviewLog>, cutTs: Long): List<Replayed> =
        logs.groupBy { it.cardKind to it.cardId }.mapNotNull { (_, hist) ->
            val card = stateAt(hist, cutTs) ?: return@mapNotNull null
            val next = hist.filter { it.ts > cutTs }.minByOrNull { it.ts } ?: return@mapNotNull null
            val planned = DueForecast.intervalDays(
                w, stabilityFromScheduled(next.scheduledDays), retention, cfg.maxIntervalDays,
            )
            Replayed(
                card = card,
                planDay = maxOf(0, (planned - card.elapsedDays).roundToInt()),
                baselineDay = maxOf(0, (next.scheduledDays - card.elapsedDays).roundToInt()),
                actualDay = ((next.ts - cutTs).toDouble() / dayMs).roundToInt(),
                planSaturated = next.scheduledDays >= synthScheduleCapDays,
            )
        }

    /**
     * 负荷带把这张卡放到哪一格。整带接不住（反解出的到期日落窗外）时按 [horizon] 记——
     * 取最严口径：计划说明明在窗内、带子却把人丢了，就该记一整天的账而不是跳过。
     */
    private fun arrivalDay(card: ForecastCard): Int =
        DueForecast.forecast(listOf(card), cfg, w)
            .indexOfFirst { it.mean >= 1.0 - 1e-9 }
            .let { if (it < 0) horizon else it }

    /** 一条 MAE 统计：样本数与日均绝对偏差。`toString` 带上实测数字，失败消息直接可抄进报告。 */
    private class MaeStat(val n: Int, val mae: Double) {
        override fun toString(): String = "n=$n mae=$mae 天"
    }

    /**
     * 一组（预测格, 真相格）配对的日均绝对偏差。**两条 MAE 门共用这一条循环**，不各写一遍累加的形状。
     * 空样本返回 `mae = NaN`——任何 `<=` 断言都会因此变红，不会悄悄放行。
     */
    private fun maeOf(pairs: List<Pair<Int, Int>>): MaeStat {
        if (pairs.isEmpty()) return MaeStat(0, Double.NaN)
        var sum = 0.0
        for ((pred, truth) in pairs) sum += abs(pred - truth)
        return MaeStat(pairs.size, sum / pairs.size)
    }

    /** 样本里「真值格 > 0」的张数——即**没被**逾期夹取钉在第 0 格的那部分，门的成绩是这部分挣来的。 */
    private fun truthBeyondClamp(pairs: List<Pair<Int, Int>>): Int = pairs.count { it.second > 0 }

    /**
     * 读法 (b) 的**绝对**门（**自洽语料** seed=7）：MAE ≤1 天的规格地板 + 逐格相等的记账断言（M-3）。
     */
    @Test
    fun `预测到期日与排期到期日的日均偏差不超过一天`() {
        val logs = SynthLog.generate(seed = 7L, nCards = 400, reviewsPerCard = 12, retention = retention)
        val cut = logs.minOf { it.ts } + 200 * dayMs
        val pairs = ArrayList<Pair<Int, Int>>()
        var saturated = 0
        var outOfWindow = 0
        for (r in replays(logs, cut)) {
            if (r.planSaturated) { saturated++; continue }
            if (r.planDay >= horizon) { outOfWindow++; continue }   // 与负荷带「出窗即丢」同一口径
            pairs += arrivalDay(r.card) to r.planDay
        }
        val stat = maeOf(pairs)
        val beyondClamp = truthBeyondClamp(pairs)
        val mismatched = pairs.filter { it.first != it.second }
        val clampSplit = "真值>0 的卡=$beyondClamp/${stat.n}（第 0 格逾期夹取=${stat.n - beyondClamp}）"
        println(
            "GATE1 逐卡 vs 排期日（读法 b，绝对门，自洽语料 seed=7/w=DEFAULT）：$stat；$clampSplit；" +
                "逐格不符=${mismatched.size}；饱和行=$saturated 出窗=$outOfWindow",
        )
        assertTrue("参与统计的卡太少（n=${stat.n}），这个门没有意义", stat.n >= 50)
        assertTrue(
            "门 1 的样本几乎全被逾期夹取钉在第 0 格（$clampSplit，下限 $minTruthBeyondClampGate1）：" +
                "MAE=0.0 只剩夹取自证，请先把语料/切点改回有信息量的区间",
            beyondClamp >= minTruthBeyondClampGate1,
        )
        assertTrue(
            "逐卡到期时机 MAE=${stat.mae} 天（n=${stat.n}，$clampSplit），超出文档给的 1 天门",
            stat.mae <= 1.0,
        )
        // M-3：规格地板 1.0 太宽——`roundToInt`→`toInt` 那类回归平均只有 ~0.5 天，MAE ≤1 仍绿。
        // 自洽语料（生成与预测同一份 w）上更强的那句话是**逐格相等**，它才是抓记账漂移的那一条。
        assertTrue(
            "自洽语料上预测格必须逐格等于排期格，实测 ${mismatched.size}/${stat.n} 张不符（$clampSplit），" +
                "样例（预测→排期）=${mismatched.take(5)}",
            mismatched.isEmpty(),
        )
    }

    /**
     * 读法 (a) 的**诚实版本**——不劣化门（**扰动语料** seed=13/`w = perturbedW`）：预测与「直接相信落库
     * 排期日」的朴素基线同台比，双方都对齐**同一个**真相（实际到达日），预测不许比基线差过 0.5 天。
     *
     * **为什么语料必须换个 `w` 生成**（I-1）：同一份 `w` 下 `intervalDays(w, stabilityFromScheduled(t, w), R) ≡ t`
     * 是恒等式，基线格与「经模型的排期格」本来就是同一个数，这条门只能在与门 1 重合的地方才可能红；
     * 而系统性的预测偏移放进 MAE 的**差**里，还要被语料自身 ±0.25·S 的迟到噪声稀释。现在语料由
     * [perturbedW] 排期、由 [w] 预测，两侧确实来自不同模型，门才有独立威力——威力本身由
     * [minSeparatedCards] 这条「两侧分叉卡数」断言钉住：哪天扰动被化简掉、分叉数掉下去，这条先红。
     *
     * 为什么这条**只能**是相对的、不能用绝对 ≤1 天：合成器把实际复习时刻画成
     * `tAct = tSched · U(0.7,1.6)`（`SynthLog.kt:60-61,97`），迟到比例 f 的
     * `E|f−1| = (0.045 + 0.18) / 0.9 = 0.25`（两段三角面积除以区间宽度），于是实际到达日离
     * **它自己的排期日**恒有约 `0.25·S` 天——窗内样本平均 S≈20 天 ⇒ ≈5.6 天。这一项与预测质量无关：
     * 逐卡再准也追不上语料自己的抖动，绝对门量的是噪声而不是账。基线与预测共用同一样本集，
     * 两者的差才是负荷带自己引入的那部分。
     */
    @Test
    fun `预测到期日相对实际到达日不劣于排期基线（绝对一天门在此语料不可满足）`() {
        // 400 卡时切点后 14 天内到达的卡只有 20 张，撑不起 brief 的 n>=50 门，故把语料放大 5 倍
        // （只增样，不缩样、不加筛）。
        val logs = SynthLog.generate(
            seed = 13L,
            nCards = 2000,
            reviewsPerCard = 12,
            w = perturbedW,       // 语料出自 perturbedW；预测只吃 w（见 stateAt / arrivalDay）
            retention = retention,
        )
        val cut = logs.minOf { it.ts } + 200 * dayMs
        val forecastPairs = ArrayList<Pair<Int, Int>>()
        val baselinePairs = ArrayList<Pair<Int, Int>>()
        var saturated = 0
        var separated = 0
        var gapSum = 0
        for (r in replays(logs, cut)) {
            if (r.planSaturated) { saturated++; continue }          // 计划值被生成器的 365 天上限夹过，两侧都不入账
            val actual = r.actualDay
            if (actual >= horizon) continue                          // 与负荷带「出窗即丢」同一口径
            // 基线两侧同口径：基线出窗时也按窗边缘（horizon）记，否则「带子如实丢掉出窗卡」这件
            // 正确的事会被误算成我们的误差（负荷带按设计就是不含出窗卡的）。
            val base = minOf(r.baselineDay, horizon)
            val pred = arrivalDay(r.card)
            forecastPairs += pred to actual
            baselinePairs += base to actual
            if (pred != base) { separated++; gapSum += pred - base }
        }
        val forecast = maeOf(forecastPairs)
        val baseline = maeOf(baselinePairs)
        val beyondClamp = truthBeyondClamp(forecastPairs)
        println(
            "GATE2 逐卡 vs 实际到达（读法 a，不劣化门，扰动语料 seed=13/w=perturbedW）：预测 $forecast，" +
                "排期基线 $baseline，差值=${forecast.mae - baseline.mae} 天；两侧分叉卡=$separated/" +
                "${forecast.n}（平均预测−基线格=${if (separated == 0) 0.0 else gapSum.toDouble() / forecast.n}）；" +
                "真值>0 的卡=$beyondClamp/${forecast.n}；饱和行=$saturated",
        )
        assertTrue("参与统计的卡太少（n=${forecast.n}），这个门没有意义", forecast.n >= 50)
        assertTrue(
            "门 2 失去独立威力：预测格与排期基线格只有 $separated/${forecast.n} 张不同（下限 $minSeparatedCards），" +
                "说明语料又变回与预测同一份参数（恒等式复活）。请把语料改回 perturbedW 生成",
            separated >= minSeparatedCards,
        )
        assertTrue(
            "负荷带的逐卡误差 ${forecast.mae} 天（n=${forecast.n}），比「直接相信排期日」基线的 ${baseline.mae} 天" +
                "还差过 0.5 天",
            forecast.mae <= baseline.mae + 0.5,
        )
        // 哨兵：文档那条**绝对**门（forecast.mae <= 1.0）在这份语料上不可满足。若哪天基线误差被压进
        // 1 天以内，这条会红——那时请把断言改回绝对口径，而不是留着相对门。
        assertTrue(
            "基线误差（语料迟到量级）${baseline.mae} 天已 ≤1 天：文档的绝对门可以用回来了，别再跑这条相对门",
            baseline.mae > 1.0,
        )
    }

    /**
     * 峰值门（**自洽语料** seed=11）：负荷带最忙的一格 vs 调度器排定的最忙一格相差 ≤1 天。
     * 真相按**排期**日取（不按迟到后的实际日），理由与读法 (a) 那条一样：实际最忙日被语料噪声主导。
     * 旧版这里顺手算了「实际最忙日」却从不引用（M-5 的死计算），本轮删除。
     */
    @Test
    fun `预测峰值日与调度器排定的最忙日相差不超过一天`() {
        val logs = SynthLog.generate(seed = 11L, nCards = 400, reviewsPerCard = 12, retention = retention)
        val cut = logs.minOf { it.ts } + 120 * dayMs
        val cards = ArrayList<ForecastCard>()
        val planned = IntArray(horizon)
        var scheduled = 0
        for (r in replays(logs, cut)) {
            cards += r.card                                          // 出窗的卡由 forecast 自己丢，口径一致
            if (!r.planSaturated && r.planDay < horizon) { planned[r.planDay]++; scheduled++ }
        }
        val bands = DueForecast.forecast(cards, cfg, w)
        val peakPredicted = peakIndexOf(bands) { it.mean }
        val peakPlanned = peakIndexOf(planned.toList()) { it.toDouble() }
        // 逾期夹取把一堆卡钉在第 0 格：第 0 格当峰时，「0 vs 0」有一半是夹取的产物，
        // 所以这里把非零真值的样本量也断言住，让下一个人看得见成绩是在多少张卡上挣的。
        val beyondClamp = scheduled - planned[0]
        val clampSplit = "计划真值>0 的卡=$beyondClamp/$scheduled（第 0 格逾期夹取=${planned[0]}）"
        println(
            "GATE3 峰值日（自洽语料 seed=11）：预测=$peakPredicted 计划=$peakPlanned；$clampSplit；" +
                "带子=${bands.map { it.mean.roundToInt() }} 计划=${planned.toList()}",
        )
        assertTrue(
            "预测峰值日=$peakPredicted，调度器排定的最忙日=$peakPlanned，相差超过 1 天（$clampSplit）",
            abs(peakPredicted - peakPlanned) <= 1,
        )
        assertTrue(
            "门 3 的最忙日几乎全由逾期夹取决定（$clampSplit，下限 $minTruthBeyondClampGate3）：" +
                "「0 vs 0」不再说明负荷带跟住了排期，请把语料/切点改回有信息量的区间",
            beyondClamp >= minTruthBeyondClampGate3,
        )
    }

    /** 峰值格子下标。泛型一条搞定——brief 那两个同名扩展擦除后同签名，编不过。 */
    private fun <T> peakIndexOf(items: List<T>, value: (T) -> Double): Int =
        items.indices.maxByOrNull { value(items[it]) } ?: 0
}
