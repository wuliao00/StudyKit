package com.studykit.ui.stats

import com.studykit.data.dao.RetentionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「延迟后测」分桶（v2.7 spec §9 / D7）的黄金样例。
 *
 * 这张卡一旦算错，用户读到的是"我的记忆退步了"这种情绪结论，而不是一个能当场发现的报错，
 * 所以边界、空桶、旧数据这三件事都得在没有真机、没有 Room 的情况下钉住。
 */
class RetentionBucketsTest {

    private fun row(
        gapDays: Double?,
        correct: Boolean,
        grade: Int = WordGradeLike.RECALL,
        pAtReview: Double? = null,
    ) = RetentionRow(gapDays = gapDays, grade = grade, pAtReview = pAtReview, correct = correct)

    /** 只在这份测试里当字面量的别名用，免得满屏裸 0/1/2 读不懂 */
    private object WordGradeLike {
        const val RECALL = 0
        const val VAGUE = 1
        const val FORGET = 2
        const val UNKNOWN = -1
        const val STRAY = 5
    }

    private fun List<RetentionBucket>.byLabel(label: String): RetentionBucket? = firstOrNull { it.label == label }

    // ── 计划 Task 19 Step 1 钉的那条例例 ──────────────────────────

    /**
     * 计划原文："10 条评分行 7d 桶 6/8 → observedRate=0.75"。
     *
     * 8 条落在 7 天桶（6 对 2 错），预测值刻意取 0.9/0.9/0.7/0.7/0.5/0.5/0.3/0.3 —— 均值正好 0.6，
     * 这样 predicted 那一条也顺带钉住"取的是 pAtReview 的均值"而不是别的什么。
     * 另外 2 条落在 30 天桶（1 对 1 错 → 0.5），用来证明桶之间不会互相污染分母。
     */
    @Test
    fun sevenDayBucketReportsObservedRateAsCorrectOverTotal() {
        val rows = listOf(
            row(7.0, true, pAtReview = 0.9),
            row(9.0, true, pAtReview = 0.9),
            row(12.0, true, pAtReview = 0.7),
            row(15.0, true, pAtReview = 0.7),
            row(20.0, true, pAtReview = 0.5),
            row(25.0, true, pAtReview = 0.5),
            row(28.0, false, pAtReview = 0.3),
            row(29.5, false, pAtReview = 0.3),
            row(45.0, true, pAtReview = 0.8),
            row(60.0, false, pAtReview = 0.4),
        )
        val out = RetentionBuckets.of(rows)
        val seven = out.byLabel(RetentionBuckets.LABEL_SEVEN)
        val thirty = out.byLabel(RetentionBuckets.LABEL_THIRTY)
        assertEquals(8, seven?.n)
        assertEquals(0.75, seven?.observedRate ?: Double.NaN, 1e-9)
        assertEquals(0.6, seven?.predictedMean ?: Double.NaN, 1e-9)
        assertEquals(2, thirty?.n)
        assertEquals(0.5, thirty?.observedRate ?: Double.NaN, 1e-9)
        assertNull("7 条评分行之外没有'其他间隔'的行，就不该凭空多出一个桶", out.byLabel(RetentionBuckets.LABEL_OTHER))
    }

    // ── 桶边界 ────────────────────────────────────────────────────

    /**
     * 边界取"含下界、不含上界"：`gap = 7.0` 属于 7 天桶，`gap = 30.0` 已经进 30 天桶。
     * 这条是卡片上"7d/30d"两个标签唯一可辩护的读法 —— 7d 桶读作"到 7 天这一档（未满 30 天）"。
     */
    @Test
    fun gapBoundariesAreInclusiveLowerExclusiveUpper() {
        assertEquals(RetentionBuckets.LABEL_SEVEN, RetentionBuckets.bucketOf(7.0))
        assertEquals(RetentionBuckets.LABEL_SEVEN, RetentionBuckets.bucketOf(29.999))
        assertEquals(RetentionBuckets.LABEL_THIRTY, RetentionBuckets.bucketOf(30.0))
        assertEquals(RetentionBuckets.LABEL_THIRTY, RetentionBuckets.bucketOf(365.0))
        assertEquals(RetentionBuckets.LABEL_OTHER, RetentionBuckets.bucketOf(6.9999))
        assertEquals(RetentionBuckets.LABEL_OTHER, RetentionBuckets.bucketOf(0.5))
    }

    /**
     * 拿不到 gap（v2.3 之前的行）、NaN、负数：进 other，不猜进最近的桶，也**不静默丢掉** ——
     * 三个桶加起来必须等于送进来的行数，否则"样本量 n"就是少报了的分母。
     */
    @Test
    fun illegalGapsFallIntoOtherInsteadOfVanishing() {
        val rows = listOf(
            row(null, true),
            row(Double.NaN, true),
            row(-2.0, true),
            row(1.0, true),
        )
        val out = RetentionBuckets.of(rows)
        assertEquals(4, out.byLabel(RetentionBuckets.LABEL_OTHER)?.n)
        assertEquals("一条都不许掉", rows.size, out.sumOf { it.n })
    }

    // ── 没有评分的旧行 ────────────────────────────────────────────

    /**
     * spec §9：迁移前回填的 `grade = -1` 单列一桶，**不并入** 7/30 桶。
     * 并进去会把"没记评分的老数据"混进达标口径，等于凭空给卡片换分母。
     */
    @Test
    fun unknownGradeRowsGetTheirOwnBucketAndNeverInflateTheThree() {
        val rows = listOf(
            row(10.0, true, grade = WordGradeLike.UNKNOWN),
            row(12.0, false, grade = WordGradeLike.UNKNOWN),
            row(8.0, true, grade = WordGradeLike.RECALL),
        )
        val out = RetentionBuckets.of(rows)
        assertEquals(1, out.byLabel(RetentionBuckets.LABEL_SEVEN)?.n)
        assertEquals(1.0, out.byLabel(RetentionBuckets.LABEL_SEVEN)?.observedRate ?: Double.NaN, 1e-9)
        val legacy = out.byLabel(RetentionBuckets.LABEL_UNKNOWN)
        assertEquals(2, legacy?.n)
        assertEquals(rows.size, out.sumOf { it.n })
    }

    /**
     * 认识/模糊/忘记以外的值（脏数据、将来新增档位）也当"没评分"处理：
     * 宁可进 unknown 桶让用户看见"有 N 条读不懂"，也不要拿它算达标率。
     */
    @Test
    fun strayGradeValuesAreTreatedAsUnknownToo() {
        val rows = listOf(row(9.0, true, grade = WordGradeLike.STRAY), row(9.0, true, grade = -2))
        val out = RetentionBuckets.of(rows)
        assertNull(out.byLabel(RetentionBuckets.LABEL_SEVEN))
        assertEquals(2, out.byLabel(RetentionBuckets.LABEL_UNKNOWN)?.n)
    }

    /** 三档评分本身都算"有评分"，包括『忘记』—— 忘记是实测率的分母，不是缺数据 */
    @Test
    fun allThreeRealGradesAreCountedAsGraded() {
        val rows = listOf(
            row(9.0, true, grade = WordGradeLike.RECALL),
            row(9.0, true, grade = WordGradeLike.VAGUE),
            row(9.0, false, grade = WordGradeLike.FORGET),
        )
        val out = RetentionBuckets.of(rows)
        assertEquals(3, out.byLabel(RetentionBuckets.LABEL_SEVEN)?.n)
        assertEquals(2.0 / 3.0, out.byLabel(RetentionBuckets.LABEL_SEVEN)?.observedRate ?: Double.NaN, 1e-9)
    }

    // ── 预测那一侧 ────────────────────────────────────────────────

    /** pAtReview 可空（v4 之前的行没记过）：均值只算有值的那些，n 仍然是全部行数 */
    @Test
    fun predictedMeanAveragesOnlyRowsThatRecordedAPrediction() {
        val rows = listOf(row(8.0, true, pAtReview = 0.9), row(8.0, false, pAtReview = 0.5), row(8.0, true, pAtReview = null))
        val bucket = RetentionBuckets.of(rows).byLabel(RetentionBuckets.LABEL_SEVEN)
        assertEquals(0.7, bucket?.predictedMean ?: Double.NaN, 1e-9)
        assertEquals("分母不许被'没有预测值'的行缩小", 3, bucket?.n)
    }

    /** 整桶一条预测都没有 → null，界面上写"没记预测"，不能写 0%（那是"模型预测你全忘了"） */
    @Test
    fun predictedMeanIsNullWhenNoRowHasAPrediction() {
        val bucket = RetentionBuckets.of(listOf(row(8.0, true))).byLabel(RetentionBuckets.LABEL_SEVEN)
        assertNull(bucket?.predictedMean)
        assertEquals("实测率照旧给得出", 1.0, bucket?.observedRate ?: Double.NaN, 1e-9)
    }

    // ── 空输入与顺序 ──────────────────────────────────────────────

    /** 计划 Step 1 钉死：空集返回空列表，不许除零、也不许造出满屏 0% 的桶 */
    @Test
    fun emptyInputYieldsEmptyListAndNeverDividesByZero() {
        assertTrue(RetentionBuckets.of(emptyList()).isEmpty())
    }

    /** 只有旧行时，卡片仍然有东西可显示：unknown 桶单独成行 */
    @Test
    fun legacyOnlyInputYieldsOnlyTheUnknownBucket() {
        val out = RetentionBuckets.of(listOf(row(9.0, true, grade = WordGradeLike.UNKNOWN)))
        assertEquals(listOf(RetentionBuckets.LABEL_UNKNOWN), out.map { it.label })
    }

    /** 顺序固定 7d → 30d → other → unknown：界面直接遍历这个列表，不许每次进来顺序都变 */
    @Test
    fun bucketsComeBackInCardOrder() {
        val rows = listOf(
            row(40.0, true), row(2.0, true), row(10.0, true), row(11.0, true, grade = WordGradeLike.UNKNOWN),
        )
        assertEquals(
            listOf(
                RetentionBuckets.LABEL_SEVEN,
                RetentionBuckets.LABEL_THIRTY,
                RetentionBuckets.LABEL_OTHER,
                RetentionBuckets.LABEL_UNKNOWN,
            ),
            RetentionBuckets.of(rows).map { it.label },
        )
        assertEquals(
            listOf("7d", "30d", "other"),
            RetentionBuckets.CardLabels,
        )
    }

    // ── 样本量提示 ────────────────────────────────────────────────

    /** 计划原文"n<20 显示『样本还少，先别当真』"：判定只在这里做一次，界面不许自己抄阈值 */
    @Test
    fun thinSampleFlagFlipsAtTwenty() {
        assertTrue(RetentionBuckets.isThin(19))
        assertFalse(RetentionBuckets.isThin(20))
        assertFalse(RetentionBuckets.isThin(21))
        assertEquals(20, RetentionBuckets.MinVerdictSamples)
    }

    // ── 与备份 CSV 共用的分桶口径 ─────────────────────────────────

    /**
     * 附录 A 的 `elapsed_bucket` 取值是 `7|30|other`（不带 d），与卡片标签同源。
     * 这条钉的是"卡片和导出的 CSV 必须是同一个分桶判定"，否则用户拿 CSV 算的数
     * 会跟屏幕上对不上，而两边都"看起来有道理"。
     */
    @Test
    fun csvBucketTokenMatchesSpecAppendixA() {
        assertEquals("7", RetentionBuckets.csvBucket(7.0))
        assertEquals("7", RetentionBuckets.csvBucket(20.0))
        assertEquals("30", RetentionBuckets.csvBucket(30.0))
        assertEquals("other", RetentionBuckets.csvBucket(3.0))
        assertEquals("other", RetentionBuckets.csvBucket(null))
        assertEquals("other", RetentionBuckets.csvBucket(Double.NaN))
    }

    // 档名与达标口径

    /**
     * 界面那一列的字从这一单点出来（“这个桶目前没数据”时也得把档名念出来）。
     * bucketOf / csvBucket / displayNameOf 三者都只看 label，界面不许自己写一套中文名。
     */
    @Test
    fun displayNamesAreDerivedFromLabelNotFromTheUI() {
        assertEquals("满 7 天", RetentionBuckets.displayNameOf(RetentionBuckets.LABEL_SEVEN))
        assertEquals("满 30 天", RetentionBuckets.displayNameOf(RetentionBuckets.LABEL_THIRTY))
        assertEquals("不足 7 天", RetentionBuckets.displayNameOf(RetentionBuckets.LABEL_OTHER))
        val legacy = RetentionBuckets.of(listOf(row(9.0, true, grade = WordGradeLike.UNKNOWN))).first()
        assertEquals(RetentionBuckets.displayNameOf(RetentionBuckets.LABEL_UNKNOWN), legacy.displayName)
    }

    /** spec §9："grade 回填 -1 的桶单列、不计入" —— 达标口径也只在这里判一次 */
    @Test
    fun onlyTheUnknownBucketIsExcludedFromVerdicts() {
        val buckets = RetentionBuckets.of(
            listOf(row(9.0, true), row(9.0, true, grade = WordGradeLike.UNKNOWN)),
        )
        assertTrue(buckets.first { it.label == RetentionBuckets.LABEL_SEVEN }.countsTowardRetention)
        assertFalse(buckets.first { it.label == RetentionBuckets.LABEL_UNKNOWN }.countsTowardRetention)
    }
}
