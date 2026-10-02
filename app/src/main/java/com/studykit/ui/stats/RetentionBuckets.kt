package com.studykit.ui.stats

import com.studykit.data.dao.RetentionRow
import com.studykit.data.entity.WordReview

/**
 * 「延迟后测」的一个桶（v2.7 spec §9 / D7）。
 *
 * [label] 是稳定标识，取值只可能是 [RetentionBuckets.LABEL_SEVEN] / [LABEL_THIRTY] /
 * [LABEL_OTHER] / [LABEL_UNKNOWN]；界面显示用 [displayName]，导出 CSV 用
 * [RetentionBuckets.csvBucket]，三者都从同一处派生。
 *
 * [observedRate] 非空：[RetentionBuckets.of] 根本不产出空桶（"一条样本都没有"的那种桶
 * 压根没资格谈保留率），所以"没有数据"这件事由**列表里没有这个 label** 来表达，
 * 而不是由一个 0.0 冒充 —— 后者会被读成"你全忘了"，与 `CurvePoint.observedRecall`
 * 为什么可空是同一条纪律。
 * [predictedMean] 可空：整个桶一条 `p_at_review` 都没记过（v4 之前的旧行）时是 null，
 * 界面上写"当时没记预测"，不许写 0%。
 * [n] 就是这个桶的行数，也是界面上那个"样本还少"判定的唯一输入。
 */
data class RetentionBucket(
    val label: String,
    val observedRate: Double,
    val predictedMean: Double?,
    val n: Int,
) {
    /** 给用户看的档名。`unknown` 那一行必须自己说清"不计达标"，否则会被当成第四个桶 */
    val displayName: String
        get() = RetentionBuckets.displayNameOf(label)

    /** 只有 `unknown` 桶不计达标（spec §9："grade 回填 -1 桶单列不计入"） */
    val countsTowardRetention: Boolean
        get() = label != RetentionBuckets.LABEL_UNKNOWN
}

/**
 * 延迟后测的分桶算法。**纯函数**：不调 Room、不碰 Android 类型，全部能在 JVM 单测里跑；
 * 唯一的 data 层 import 是 [RetentionRow] 这个投影行和 [WordReview] 的几个档位常量（`const val`，编译期就内联了）。
 *
 * ## 为什么是 7 / 30 两档
 * D7 把文档里的"7/30 天延迟后测保留率"降级成本地前后对照：唯一的原料就是 `word_reviews`
 * 里每一次复习的间隔。所以这里的桶读作"这次复习距离上次至少隔了 7 天 / 至少隔了 30 天"，
 * 边界含下界、不含上界（7 ≤ gap < 30 进 7 天桶，gap ≥ 30 进 30 天桶），
 * 不足 7 天与拿不到 gap 的行进 other。三档加 unknown 覆盖**全部**输入行，一条都不静默丢。
 *
 * ## 为什么这一处是唯一判定
 * 记忆看板的卡片与备份包里的 `review_history.csv`（`elapsed_bucket` 列）必须同一个口径，
 * 否则用户拿 CSV 自己复算会跟屏幕上对不上，而两边各自看着都成立。
 * 所以 CSV 侧不许重抄边界，只调 [csvBucket]。
 */
object RetentionBuckets {

    const val LABEL_SEVEN = "7d"
    const val LABEL_THIRTY = "30d"
    const val LABEL_OTHER = "other"

    /** `grade` 不认识（-1 的迁移前旧行，或将来新增的档位）时归到这里，单列不并入三档 */
    const val LABEL_UNKNOWN = "unknown"

    /** 界面上那三行的固定顺序；`unknown` 不在其中（它不计达标，只能当脚注） */
    val CardLabels: List<String> = listOf(LABEL_SEVEN, LABEL_THIRTY, LABEL_OTHER)

    /** 桶下界（天）。改这两个数字要同时改 spec §9 —— 不许只改代码 */
    const val SEVEN_DAYS = 7.0
    const val THIRTY_DAYS = 30.0

    /**
     * 少于这个样本数，实测率就只是"这几次的运气"，不当结论（与遗忘曲线的
     * `MinCurveSamples` 同值同理由，但**各写各的**：两处一旦被合并成一个常量，
     * 以后调一处会静默改掉另一处的口径）。
     */
    const val MinVerdictSamples = 20

    /** 认识/模糊/忘记三档才算"有评分"；别的一律当没记（-1 与将来新增的档位都走 unknown） */
    private val GRADED = setOf(WordReview.GRADE_RECALL, WordReview.GRADE_VAGUE, WordReview.GRADE_FORGET)

    /**
     * 一行该进哪个"间隔桶"。拿不到 gap（v4 之前的行）、NaN、负数都进 [LABEL_OTHER]：
     * 它们确实"不是 7 天以上也不是 30 天以上"，而不是"没有数据"——
     * 归进 other 会让 n 变大、率变稀，这是**如实**反映，不猜进最近的桶。
     */
    fun bucketOf(gapDays: Double?): String = when {
        gapDays == null || !gapDays.isFinite() -> LABEL_OTHER
        gapDays >= THIRTY_DAYS -> LABEL_THIRTY
        gapDays >= SEVEN_DAYS -> LABEL_SEVEN
        else -> LABEL_OTHER
    }

    /** [bucketOf] 的导出记号版：spec 附录 A 的 `elapsed_bucket` 取值是 `7|30|other`（不带 d） */
    fun csvBucket(gapDays: Double?): String = bucketOf(gapDays).removeSuffix("d")

    /** 样本量薄到不足以当结论的程度（判定只在这里做一次，界面不许自己抄阈值） */
    fun isThin(n: Int): Boolean = n < MinVerdictSamples

    /**
     * 档名。只看 label，所以**空桶也有名字** —— 界面上"这一档还没数据"那一行同样要能把
     * "满 7 天"三个字念出来，否则用户不知道那行空格是哪一档。
     */
    fun displayNameOf(label: String): String = when (label) {
        LABEL_SEVEN -> "满 7 天"
        LABEL_THIRTY -> "满 30 天"
        LABEL_OTHER -> "不足 7 天"
        else -> "没记评分的旧数据"
    }

    /**
     * 分桶 + 算实测率与预测均值。空输入返回**空列表**（不是四个 0% 的桶），
     * 每个桶的分母就是它自己的 n，所以任何一路都不会除以零。
     */
    fun of(rows: List<RetentionRow>): List<RetentionBucket> {
        // 先按"有没有评分"切，再按间隔切：grade=-1 的迁移前行如果并进 7d 桶，
        // 那张卡的分母就掺进了"当年压根没记档位"的数据，读起来像变准了，其实只是分母换了
        val (legacy, graded) = rows.partition { it.grade !in GRADED }
        val buckets = CardLabels.mapNotNull { label ->
            val inBucket = graded.filter { bucketOf(it.gapDays) == label }
            inBucket.toBucket(label)
        }
        return buckets + listOfNotNull(legacy.toBucket(LABEL_UNKNOWN))
    }

    /** 空列表 -> null（调用方用 `mapNotNull`/`listOfNotNull` 把它从结果里摘掉） */
    private fun List<RetentionRow>.toBucket(label: String): RetentionBucket? {
        if (isEmpty()) return null
        val predictions = mapNotNull { it.pAtReview }.filter { it.isFinite() }
        return RetentionBucket(
            label = label,
            observedRate = count { it.correct }.toDouble() / size,
            predictedMean = if (predictions.isEmpty()) {
                null
            } else {
                predictions.sum() / predictions.size
            },
            n = size,
        )
    }
}
