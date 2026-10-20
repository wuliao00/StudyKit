package com.studykit.ui.study

/**
 * 交错约束的**阈值口径**。`QueueBuilder` 里不许出现任何阈值字面量：
 * 同一把尺子的两个家会分别漂，漂开那天起「修完了」和「没违规」就不是同一件事
 * （v3.0-B 一整条 lane 反复栽的就是这个形状 —— "两处各说一套"）。
 *
 * 语义（全部沿用文档 L1243 的比较方向）：
 *  - [tauHi]：相邻两题相似度**严格大于**它即违规（`sim > tauHi` ⇒ 必须分开）；
 *  - [tauLo]：缓冲卡/换位候选必须**严格小于**它才算"足够不像"；
 *  - [drillLo]..[drillHi]：混淆带 —— 落在这里的对子被强制同场并隔开 [gapLo]..[gapHi] 题。
 *    下界以上才值得 drill（太不像不是混淆），上界以上不 drill（那是重复题，只该分开，不该成对出现）。
 *  - [topK]：每张卡最多记几个混淆伙伴（文档 L1227 的"仅同学科内 top-8"）；
 *  - [lookahead]：相邻修复往后找换位候选的窗口宽度（偏差 D5，把 O(n²) 换成可证上界）；
 *  - [maxRepairRounds]：相邻修复最多扫几轮（drills 之后要复扫，见 Task 4）。
 */
internal data class InterleavePolicy(
    val tauHi: Float,
    val tauLo: Float,
    val drillLo: Float,
    val drillHi: Float,
    val gapLo: Int,
    val gapHi: Int,
    val topK: Int,
    val lookahead: Int,
    val maxRepairRounds: Int,
)

/**
 * 词面提供方的刻度。**初值未经标注校准**（偏差 D3/D9）：
 * 五个阈值按「题干二元组 Dice 的**预期**相对位置」排布 —— 缓冲线要低于混淆带下界、混淆带要跨过相邻线、
 * 上界之上算重复题而不是易混题；同考点地板 0.65（`TextSimilarity.CONCEPT_FLOOR`）落在这条带里。
 * **本仓没有量过任何分布**，所以这里不写「无关题低于多少、变式题落在多少」这类没实测过的区间：
 * 写了就等于把猜测当事实，而下限 tauLo=0.15 与上界 tauHi=0.60 正是照这几个猜出来的相对位置定的。
 * 真正的校准门是 200+200 人工标注对
 * 上的 AUC ≥0.85，属语义包计划，不在这份计划里。
 *
 * 文档 §② 给的是**余弦相似度的尺子**：`TAU_HI = 0.85f`、`TAU_LO = 0.30f`、混淆带 `[0.7, 0.9]`。
 * 那份数值不在下面 —— 照搬会让相邻修复在词面上很少触发（功能静默死）。
 * 语义包（v3.1）已为它单开 `EmbeddingInterleave.DEFAULT`（沿用文档原值），不要改这里。
 */
internal object LexicalInterleave {
    val DEFAULT = InterleavePolicy(
        tauHi = 0.60f,
        tauLo = 0.15f,
        drillLo = 0.40f,
        drillHi = 0.75f,
        gapLo = 3,
        gapHi = 5,
        topK = 8,
        lookahead = 12,
        maxRepairRounds = 4,
    )
}

/**
 * 嵌入提供方的刻度：**余弦相似度的尺子**，不是 [LexicalInterleave] 那套字符二元组 Dice 的尺子，
 * 两套数值不可互换（余弦 0.85 与 Dice 0.60 说的不是同一件事）。
 *
 * 数值沿用文档 §② 的余弦口径：`tauHi = 0.85`、`tauLo = 0.30`、混淆带 `[0.70, 0.90]`；
 * 其余结构参数与词面档相同（gap 3..5 / topK 8 / lookahead 12 / maxRepairRounds 4）。
 *
 * ⛔ **未校准**：这些值来自文档与 bge-small-zh 的一般经验，本仓**没有**在 200+200 人工标注对上量过分布，
 * 更没有针对本应用题库的高考/语言类语料调过。真实校准（AUC ≥ 0.85）属语义包计划，不在这份计划里；
 * 在那之前不要把这里的数值当成"实测最优"，也不要拿它去改 [LexicalInterleave.DEFAULT] —— 两份尺子各归各的提供方。
 */
internal object EmbeddingInterleave {
    val DEFAULT = InterleavePolicy(
        tauHi = 0.85f,
        tauLo = 0.30f,
        drillLo = 0.70f,
        drillHi = 0.90f,
        gapLo = 3,
        gapHi = 5,
        topK = 8,
        lookahead = 12,
        maxRepairRounds = 4,
    )
}
