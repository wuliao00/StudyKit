package com.studykit.ui.stats

/**
 * 一个稳定性档位：`[minDays, 下一档下界)` 天这一段的卡。
 *
 * [sharePercent] 供进度条用（四舍五入的整数）；**给用户看的必须是 [shareLabel]** ——
 * 与 [DurabilityBucket.shareLabel] 同一条教训：一千张卡里只有 1 张落在某档时四舍五入是 0%，
 * 那行字就变成"1 个 · 0%"这种自相矛盾的话。
 */
data class StabilityBin(val label: String, val minDays: Double, val count: Int, val sharePercent: Int) {
    val shareLabel: String
        get() = when {
            count == 0 -> "0%"
            sharePercent == 0 -> "不足 1%"
            else -> "$sharePercent%"
        }
}

/**
 * 稳定性全景的分箱器（v3.0-A Task 7）。**纯函数、零 Android / 零 Room**，与 [MemoryHealth] 同一层。
 *
 * ## 这块图回答的是「这些卡按 FSRS 的口径能扛多久」
 * 上面「这些记忆能扛多久」数的是 `words.half_life_days`（半衰期，双内核下还可能是另一条曲线的镜像列），
 * 这里数的是 [ScheduledCard.stabilityDays]——FSRS 的稳定度 S，且母体是**已排期且被 FSRS 写过的卡**
 * （词 + 错题 + 书摘，见 [ForecastCards]）。两个母体不一样，所以两节的百分比不能横向对比，
 * 视图那边把这句话写进 caption，别由这里默认。
 *
 * ## 边界：log2 步长、下闭上开
 * [Edges] 取 `0.5, 1, 2, 4, …, 256, 365`（文档要求；256→365 那一档是"一年"这条人话边界，不再是 512）。
 * 判档用 `Edges.indexOfFirst { s < it }`，于是每档**含下界、不含上界**：`S = 4.0` 进「4-8 天」，
 * `S = 3.9` 进「2-4 天」（文档 ⛔ 单独点这一条）。方向写反不会报错，只会让每张卡在边界上的卡
 * 集体升一档——图上看着"记忆更牢"，而总数照样对。
 *
 * ## 分母与 [MemoryHealth.durability] 同一个口径
 * `sharePercent` 的分母是**可信卡数**（[usable] 过滤后那批），不是传入条数，也不是各箱之和的再分配。
 * 那里的原话是"分母是全部可信的词"，这里是"分母是全部可信的卡"——**同一件事**（脏值既不进分子也不进分母），
 * 所以两节的百分比说的是同一种话。判据也只在这里写一次，与 [ForecastCards] 那侧保持一致：
 * 两处各判一次"什么算可信"迟早分叉（[com.studykit.ui.study.TomorrowLoad.sampledCount] 是这条纪律的前例）。
 *
 * ## 与 durability 那四档的区别（读图的人需要知道）
 * 那边是嵌套的 `≥` 关系（一个词同时计入 10/30/90 三档），这边是**互斥**分箱（一张卡只落一箱），
 * 所以这里各箱百分比之和回到 100%，而那边不会。
 *
 * ## 空输入
 * 返回全零的 12 档而不是抛——视图按"有没有可信卡"决定画空态还是画行条，判据留给视图。
 */
object StabilityBins {

    /** 各档的**上界**（不含），log2 步长；最后一档是「≥365 天」。 */
    val Edges: List<Double> = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 365.0)

    /** 逐档标签：`Edges.size + 1` 条（首档「≤0.5 天」、尾档「1 年以上」）。 */
    private val Labels: List<String> = buildList {
        add("≤${dayLabel(Edges.first())} 天")
        for (i in 1 until Edges.size) add("${dayLabel(Edges[i - 1])}-${dayLabel(Edges[i])} 天")
        add("1 年以上")
    }

    /**
     * 分一批稳定度到各档。不可信的值（null 之外的 `NaN` / `±Infinity` / `<= 0`）直接不进结果，
     * 也**不进分母**（口径见类 KDoc）。
     */
    fun bin(stabilities: List<Double>): List<StabilityBin> {
        val valid = stabilities.filter(::usable)
        val total = valid.size
        val counts = IntArray(Labels.size)
        valid.forEach { counts[indexOf(it)]++ }
        return Labels.mapIndexed { i, label ->
            StabilityBin(
                label = label,
                minDays = if (i == 0) 0.0 else Edges[i - 1],
                count = counts[i],
                // 与 MemoryHealth.durability 同一个算法：Math.round 而不是整除截断，
                // 否则 99.6% 那一档会显示 99%，进度条与文字又差一格
                sharePercent = if (total == 0) 0 else Math.round(counts[i] * 100.0 / total).toInt(),
            )
        }
    }

    /** 可信稳定度：正有限数。与 [ForecastCards] 的判据同一条（±Infinity 过得了 `> 0.0`，只有这里挡得住）。 */
    private fun usable(s: Double): Boolean = s.isFinite() && s > 0.0

    /** 落在哪一档：`indexOfFirst { s < it }` = 下闭上开；越过最后一条边（s ≥ 365）归末档。 */
    private fun indexOf(s: Double): Int = Edges.indexOfFirst { s < it }.let { if (it < 0) Edges.size else it }

    /** 天数标签：整数不带小数点（"4-8 天" 而不是 "4.0-8.0 天"），0.5 保持一位小数。 */
    private fun dayLabel(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
