# 记忆看板（统计页三块图）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 StudyKit 加一页「记忆看板」，把 v2.3 半衰期模型已经算得出来、但用户看不见的三件事摊开：我的记忆现在能扛多久、未来 7 天每天要复习多少、我自己的遗忘曲线和艾宾浩斯差多少。

**Architecture:** 三层。① `ui/stats/MemoryHealth.kt` 全是纯函数（分桶、按日聚合、实测曲线），零 Android 依赖，JVM 单测钉死；② `StatsViewModel` 只做 Room Flow → 纯函数的组合；③ `StatsScreen` 只画。柱状图用普通 `Row` + 权重宽度（可被无障碍读到、不会画错），只有曲线用 `Canvas`。

**Tech Stack:** Kotlin 2.0.21、Jetpack Compose（BOM 2024.12.01 / Material3 1.3.1）、Room 2.6.1、java.time（minSdk 26 原生支持）、JUnit4。

**Spec:** `docs/superpowers/specs/2026-09-22-studykit-v2-3-memory-scheduler-design.md`
（本计划实现 §2 参照表里"墨墨有、我们没有"的那三块，以及 §8 的"未来量"验证项）

## Global Constraints

- **本机没有 JDK / Android SDK，绝对不要跑 `gradlew`。** 唯一的编译与测试器是
  `.github/workflows/ci.yml`：push 分支 → `gh run watch <id> --exit-status` → 按 **head SHA** 复核，
  不看子代理或自己的印象。
- 颜色与文字只取 `AppTheme.colors` / `AppTheme.texts`；间距圆角尺寸只取
  `AppTheme.space` / `.radius` / `.size` / `.elevation`。**不许出现 `Color(0x…)` 字面量**（品牌色在
  `Palette.kt`），品牌色只做填充/描边/色点，**文字与图标一律同族 `*Ink`**。
- 动效一律走 `ui/motion/MotionSpec` 的工厂或常量；`reduceMotion` 为真时不得有无限动画。
- 每个 Compose 参数用**命名实参**（`TextStyle(...)` / `tween(...)` / `spring(...)` 位置参会命中废弃重载编不过）。
- 文案是简体中文，说明写"为什么"而不是堆形容词；**不许把没做的事说成做过的**（见每个任务的"诚实边界"）。
- Room 改 schema 必须写 `MIGRATION_N_M`，且 SQL 要与 Room 依实体生成的建表语句逐字一致
  （`exportSchema = false`，无人替你核对）。**本计划不改 schema**（只加查询）。
- 提交信息用中文、说清"为什么"；一个任务一个提交。

## 文件结构

| 文件 | 动作 | 责任 |
|---|---|---|
| `app/src/main/java/com/studykit/ui/stats/MemoryHealth.kt` | 新建 | 全部纯计算：持久度分桶、按日复习量、实测遗忘曲线、艾宾浩斯对照表 |
| `app/src/test/java/com/studykit/ui/stats/MemoryHealthTest.kt` | 新建 | 上面每个函数的黄金样例单测 |
| `app/src/main/java/com/studykit/data/dao/WordDao.kt` | 修改 | 加 `observeHalfLifeDays()`、`observeReviewGapAndResult()` 两个只读查询 |
| `app/src/main/java/com/studykit/data/repository/WordRepository.kt` | 修改 | 透出上面两个 |
| `app/src/main/java/com/studykit/ui/stats/StatsViewModel.kt` | 新建 | Room Flow → `StatsUiState`，不含任何算法 |
| `app/src/main/java/com/studykit/ui/stats/StatsScreen.kt` | 新建 | 页面骨架 + 三块卡 |
| `app/src/main/java/com/studykit/ui/nav/AppNav.kt` | 修改 | 加 `StudyRoutes.STATS` 路由与 composable |
| `app/src/main/java/com/studykit/ui/study/StudyHomeScreen.kt` | 修改 | 加「记忆看板」入口 + 首页那行"明天要复习 N 个" |

---

## Task 1: 纯计算层 MemoryHealth

**Interfaces:**
- Consumes：无（零依赖）
- Produces：
  - `data class DurabilityBucket(val minHalfLifeDays: Int, val count: Int, val sharePercent: Int)`
  - `data class DayLoad(val date: LocalDate, val count: Int)`
  - `data class GapSample(val gapDays: Double, val recalled: Boolean)`
  - `data class CurvePoint(val label: String, val midGapDays: Double, val observedRecall: Double?, val sampleCount: Int)`
  - `fun MemoryHealth.durability(halfLives: List<Double>, thresholds: List<Int> = listOf(10, 30, 60, 90)): List<DurabilityBucket>`
  - `fun MemoryHealth.forecastByDay(timestamps: List<Long>, zone: ZoneId, from: LocalDate, days: Int): List<DayLoad>`
  - `fun MemoryHealth.forgettingCurve(samples: List<GapSample>): List<CurvePoint>`
  - `fun MemoryHealth.modelCurve(halfLives: List<Double>, atDays: List<Int>): List<Double>`
  - `val MemoryHealth.EbbinghausPoints: List<Pair<Double, Double>>`
  - `val MemoryHealth.GapBuckets: List<Triple<String, Double, Double>>`

**Steps:**

- [ ] **Step 1：写失败的测试** — 新建 `app/src/test/java/com/studykit/ui/stats/MemoryHealthTest.kt`

```kotlin
package com.studykit.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class MemoryHealthTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun day(y: Int, m: Int, d: Int, hour: Int = 12): Long =
        LocalDate.of(y, m, d).atTime(hour, 0).toInstant(utc).toEpochMilli()

    // ── 持久度分布 ────────────────────────────────────────────────

    @Test
    fun `durability counts words at or above each threshold`() {
        val hs = listOf(0.5, 3.0, 12.0, 31.0, 70.0, 100.0)
        val out = MemoryHealth.durability(hs)
        assertEquals(listOf(10, 30, 60, 90), out.map { it.minHalfLifeDays })
        assertEquals(listOf(4, 3, 2, 1), out.map { it.count })
        // 分母是"全部有半衰期的词"，不是各桶之和 —— 与墨墨口径一致
        assertEquals(67, out[0].sharePercent)   // 4/6
        assertEquals(50, out[1].sharePercent)   // 3/6
        assertEquals(33, out[2].sharePercent)   // 2/6
        assertEquals(17, out[3].sharePercent)   // 1/6
    }

    @Test
    fun `durability of an empty library is all zeros and never divides by zero`() {
        val out = MemoryHealth.durability(emptyList())
        assertTrue(out.all { it.count == 0 && it.sharePercent == 0 })
    }

    /** 脏半衰期（NaN / 负数 / null 转成的 0）不能进分母，否则百分比整体被稀释 */
    @Test
    fun `illegal half-lives are excluded from the denominator`() {
        val out = MemoryHealth.durability(listOf(Double.NaN, -1.0, 0.0, 50.0))
        assertEquals(1, out[0].count)
        assertEquals(100, out[0].sharePercent)
    }

    // ── 未来复习量 ────────────────────────────────────────────────

    @Test
    fun `forecast buckets timestamps by local day including today`() {
        val stamps = listOf(
            day(2026, 9, 22),                 // 今天
            day(2026, 9, 23), day(2026, 9, 23),   // 明天 2 个
            day(2026, 9, 25),
            day(2026, 9, 21),                 // 昨天：不计入"未来"
            day(2026, 10, 99 - 99 + 30),      // 30 号，超出 7 天窗口
        )
        val out = MemoryHealth.forecastByDay(stamps, utc, LocalDate.of(2026, 9, 22), days = 7)
        assertEquals(7, out.size)
        assertEquals(LocalDate.of(2026, 9, 22), out[0].date)
        assertEquals(listOf(1, 2, 0, 1, 0, 0, 0), out.map { it.count })
    }

    /** 跨零点的边界：23:59 归今天，次日 00:00 归明天 */
    @Test
    fun `day boundary is exclusive at the start and inclusive at the end`() {
        val lateNight = LocalDate.of(2026, 9, 22).atTime(23, 59).toInstant(utc).toEpochMilli()
        val nextMidnight = LocalDate.of(2026, 9, 23).atStartOfDay(utc).toInstant().toEpochMilli()
        val out = MemoryHealth.forecastByDay(listOf(lateNight, nextMidnight), utc,
            LocalDate.of(2026, 9, 22), days = 2)
        assertEquals(listOf(1, 1), out.map { it.count })
    }

    @Test
    fun `forecast of nothing scheduled is all zeros`() {
        assertEquals(listOf(0, 0), MemoryHealth.forecastByDay(emptyList(), utc,
            LocalDate.of(2026, 9, 22), days = 2).map { it.count })
    }

    // ── 实测遗忘曲线 ──────────────────────────────────────────────

    @Test
    fun `curve reports observed recall per gap bucket`() {
        val samples = listOf(
            GapSample(0.5, true), GapSample(0.8, true),          // 「1 天内」2/2 = 100%
            GapSample(1.5, true), GapSample(2.5, false),         // 「1-3 天」1/2 = 50%
            GapSample(5.0, false), GapSample(20.0, true),        // 「3-7 天」0/1；「7-30 天」1/1
        )
        val out = MemoryHealth.forgettingCurve(samples)
        val byLabel = out.associateBy { it.label }
        assertEquals(1.0, byLabel["1 天内"]!!.observedRecall!!, 1e-9)
        assertEquals(0.5, byLabel["1-3 天"]!!.observedRecall!!, 1e-9)
        assertEquals(0.0, byLabel["3-7 天"]!!.observedRecall!!, 1e-9)
        assertEquals(2, byLabel["1 天内"]!!.sampleCount)
        assertEquals(1, byLabel["7-30 天"]!!.sampleCount)
    }

    /** 样本数为 0 的桶必须是 null，不是 0%。画成 0% 等于对用户撒谎说"你全忘了" */
    @Test
    fun `empty bucket is null not zero`() {
        val out = MemoryHealth.forgettingCurve(listOf(GapSample(0.5, true)))
        val empty = out.filter { it.sampleCount == 0 }
        assertTrue(empty.isNotEmpty())
        empty.forEach { assertNull(it.observedRecall) }
    }

    @Test
    fun `no samples at all yields all-null buckets`() {
        assertTrue(MemoryHealth.forgettingCurve(emptyList()).all { it.observedRecall == null })
    }

    /** 落在任何桶之外的样本（gap=0 或 90 天以上）不能被静默丢掉 */
    @Test
    fun `samples outside every bucket are ignored rather than guessed into one`() {
        val inside = MemoryHealth.forgettingCurve(listOf(GapSample(100.0, true), GapSample(2.0, true)))
        assertEquals(1, inside.sumOf { it.sampleCount })
    }

    // ── 模型曲线与艾宾浩斯 ────────────────────────────────────────

    /** 单个半衰期在 d=h 时必然是 50%，这是"半衰期"这个词的全部含义 */
    @Test
    fun `model curve is 50 percent at exactly one half-life`() {
        val out = MemoryHealth.modelCurve(listOf(10.0), listOf(10))
        assertEquals(0.5, out[0], 1e-9)
    }

    @Test
    fun `model curve averages across the library`() {
        // h=10 在第 10 天是 0.5，h=30 在第 10 天是 2^(-1/3)=0.7937
        val out = MemoryHealth.modelCurve(listOf(10.0, 30.0), listOf(10))
        assertEquals((0.5 + Math.pow(2.0, -10.0 / 30.0)) / 2.0, out[0], 1e-9)
    }

    @Test
    fun `model curve is monotonically decreasing and ignores illegal half-lives`() {
        val out = MemoryHealth.modelCurve(listOf(20.0, Double.NaN, -5.0), (0..9).toList())
        assertEquals(10, out.size)
        for (i in 1 until out.size) assertTrue(out[i] <= out[i - 1])
        assertEquals(1.0, out[0], 1e-9)
    }

    @Test
    fun `ebbinghaus reference starts at 100 percent and falls fast then flattens`() {
        val pts = MemoryHealth.EbbinghausPoints
        assertEquals(1.0, pts.first().second, 1e-9)
        val day1 = pts.first { it.first >= 1.0 }.second
        assertTrue("第 1 天应在 25%~45% 之间，实际 $day1", day1 in 0.25..0.45)
        val last = pts.last()
        assertTrue(last.second > 0.15 && last.second < day1)
    }
}
```

- [ ] **Step 2：跑测试确认编译失败** — Run: CI（本机不能跑 gradle）。
  本地至少 `py /path/to/kotlin_lint.py <新文件>` 查括号配平；预期失败原因是 `MemoryHealth` 未定义。

- [ ] **Step 3：写实现** — 新建 `app/src/main/java/com/studykit/ui/stats/MemoryHealth.kt`

```kotlin
package com.studykit.ui.stats

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

/** 一个持久度档位：半衰期 ≥ [minHalfLifeDays] 天 */
data class DurabilityBucket(val minHalfLifeDays: Int, val count: Int, val sharePercent: Int)

/** 某一天的复习负载 */
data class DayLoad(val date: LocalDate, val count: Int)

/** 一次真实复习：当时距上次隔了 [gapDays] 天，结果记起来了没有 */
data class GapSample(val gapDays: Double, val recalled: Boolean)

/**
 * 一个间隔桶的实测回忆率。
 * [observedRecall] 为 null 表示**这个桶一条样本都没有** —— 必须与"0% 回忆率"区分开，
 * 否则新用户的曲线会被画成"全忘了"，那是凭空造出来的坏消息。
 */
data class CurvePoint(
    val label: String,
    val midGapDays: Double,
    val observedRecall: Double?,
    val sampleCount: Int,
)

/**
 * 记忆看板的算法层。**纯函数、零 Android / 零 Room**，全部能在 JVM 单测里跑。
 *
 * 为什么单独一层：这三张图一旦算错，用户看到的是"我的记忆变差了"这种情绪结论，
 * 而不是一个能当场发现的报错。所以数字必须在没有真机、没有 CI 装机的情况下也能钉住。
 */
object MemoryHealth {

    /** 持久度档位。与墨墨同口径（≥10/30/60/90 天），便于用户横向理解 */
    val DurabilityThresholds = listOf(10, 30, 60, 90)

    /** 间隔分桶：标签 + 下界（含）+ 上界（不含） */
    val GapBuckets: List<Triple<String, Double, Double>> = listOf(
        Triple("1 天内", 0.0, 1.0),
        Triple("1-3 天", 1.0, 3.0),
        Triple("3-7 天", 3.0, 7.0),
        Triple("7-30 天", 7.0, 30.0),
        Triple("30 天以上", 30.0, Double.MAX_VALUE),
    )

    /**
     * 艾宾浩斯 1885 那条经典曲线（保留量 %，横轴天）。
     *
     * 用**通行的教科书数值**而不是重新拟合：这张图的意义是"你 vs 那条人人都听过的线"，
     * 换成另一组数字就没有参照物了。原始实验用的是"节省法"，且被后世反复重述，
     * 所以这里只当**对照基准**，不当事实 —— 界面上也这么写。
     */
    val EbbinghausPoints: List<Pair<Double, Double>> = listOf(
        0.0 to 1.00,
        0.0069 to 0.58,   // 20 分钟
        0.0417 to 0.44,   // 1 小时
        0.375 to 0.36,    // 9 小时
        1.0 to 0.33,      // 1 天
        2.0 to 0.28,
        6.0 to 0.25,
        7.0 to 0.24,
        9.0 to 0.23,
        31.0 to 0.21,
    )

    /** 可信半衰期：正有限数。NaN/0/负数一律不算，也不进分母 */
    private fun usable(h: Double): Boolean = h.isFinite() && h > 0.0

    /**
     * 持久度分布。分母是**全部可信的词**（不是各桶之和），
     * 所以四档是嵌套的 ≥ 关系，读法是"有百分之多少的词能扛过 X 天"。
     */
    fun durability(
        halfLives: List<Double>,
        thresholds: List<Int> = DurabilityThresholds,
    ): List<DurabilityBucket> {
        val valid = halfLives.filter(::usable)
        val total = valid.size
        return thresholds.map { t ->
            val c = valid.count { it >= t }
            DurabilityBucket(
                minHalfLifeDays = t,
                count = c,
                sharePercent = if (total == 0) 0 else Math.round(c * 100.0 / total).toInt(),
            )
        }
    }

    /**
     * 从 [from] 起连续 [days] 天的复习负载。
     *
     * 按**本地日**切，不用 SQL 的 `date()`：那个不吃时区，跨零点或换时区的用户
     * 会看到"明天的量"莫名多一个少一个。[from] 当天也算（用户关心"今天还剩多少"）。
     */
    fun forecastByDay(
        timestamps: List<Long>,
        zone: ZoneId,
        from: LocalDate,
        days: Int,
    ): List<DayLoad> {
        val starts = (0 until days).map { from.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        val ends = starts.drop(1) + Long.MAX_VALUE
        return (0 until days).map { i ->
            DayLoad(
                date = from.plusDays(i.toLong()),
                count = timestamps.count { it >= starts[i] && it < ends[i] },
            )
        }
    }

    /**
     * 用**用户自己的复习记录**分桶算实测回忆率。
     *
     * 空桶给 null 而不是 0.0 —— 见 [CurvePoint]。
     * 落在所有桶之外（gap ≤ 0 或非法）的样本直接不计，不猜进最近的桶。
     */
    fun forgettingCurve(samples: List<GapSample>): List<CurvePoint> {
        val usable = samples.filter { it.gapDays.isFinite() && it.gapDays > 0.0 }
        return GapBuckets.map { (label, lo, hi) ->
            val inBucket = usable.filter { it.gapDays >= lo && it.gapDays < hi }
            CurvePoint(
                label = label,
                midGapDays = if (hi == Double.MAX_VALUE) lo * 2.0 else (lo + hi) / 2.0,
                observedRecall = if (inBucket.isEmpty()) null
                else inBucket.count { it.recalled }.toDouble() / inBucket.size,
                sampleCount = inBucket.size,
            )
        }
    }

    /** 模型自己的曲线：每个可信半衰期取 2^(−d/h)，再对全库求平均 */
    fun modelCurve(halfLives: List<Double>, atDays: List<Int>): List<Double> {
        val valid = halfLives.filter(::usable)
        if (valid.isEmpty()) return atDays.map { 0.0 }
        return atDays.map { d -> valid.sumOf { 2.0.pow(-d / it) } / valid.size }
    }
}
```

> 注意 `modelCurve` 里 `sumOf { ... }` 的接收者是 `Double`：写成 `valid.sumOf { 2.0.pow(...) }`
> 才对（`sumOf` 有 `Double` 重载）。这一行最容易顺手写成 `it.pow(...)` 而编不过或算成 Int。

- [ ] **Step 4：自查** — `py tools/kotlin_lint.py app/src/main/java/com/studykit/ui/stats/MemoryHealth.kt app/src/test/java/com/studykit/ui/stats/MemoryHealthTest.kt`（若本机无该脚本，用 `WeatherOutfit/tools/kotlin_lint.py` 的绝对路径）。预期 `0 problem(s)`。

- [ ] **Step 5：提交并推 CI**

```bash
git add app/src/main/java/com/studykit/ui/stats/MemoryHealth.kt app/src/test/java/com/studykit/ui/stats/MemoryHealthTest.kt
git commit -m "feat(stats): 记忆看板算法层（持久度分桶/未来量/实测遗忘曲线）"
git push origin feat/v2-visual-motion
gh run watch <id> --exit-status   # 然后按 head SHA 复核 conclusion，不看报告文字
```

**诚实边界：** 这一层没有任何"预测准不准"的结论，只是把已有的 h 与复习记录换个方式数一遍。

---

## Task 2: 数据查询与 StatsViewModel

**Interfaces:**
- Consumes：`WordRepository.observeAll()`、Task 1 的 `MemoryHealth.*`
- Produces：
  - `WordDao.observeHalfLifeDays(): Flow<List<Double>>`
  - `WordDao.observeReviewGapAndResult(): Flow<List<ReviewGapRow>>`，`data class ReviewGapRow(val gapDays: Double?, val correct: Boolean)`（放 `WordDao.kt`）
  - `WordRepository.observeHalfLifeDays()` / `observeReviewGapAndResult()`
  - `data class StatsUiState(val durability: List<DurabilityBucket> = emptyList(), val plannedCount: Int = 0, val totalWords: Int = 0, val forecast: List<DayLoad> = emptyList(), val curve: List<CurvePoint> = emptyList(), val modelCurve: List<Double> = emptyList(), val sampleCount: Int = 0)`（`ui/stats/StatsViewModel.kt`）
  - `class StatsViewModel(application: Application) : AndroidViewModel`，暴露 `val state: StateFlow<StatsUiState>`

**Steps:**

- [ ] **Step 1：加两个只读查询** — 修改 `data/dao/WordDao.kt`，在 `observeScheduledTimestamps()` 之后追加

```kotlin
    /**
     * 全库半衰期（记忆看板用）。
     *
     * 只取一列而不是 `observeAll()`：看板每改一次评分就会重算，
     * 把 1177 行整行（含单词、释义、例句）拉过 Binder 是纯浪费。
     */
    @Query("SELECT half_life_days FROM words")
    fun observeHalfLifeDays(): Flow<List<Double>>

    /** 复习时的间隔与结果 —— 实测遗忘曲线的唯一原料 */
    @Query("SELECT gap_days AS gapDays, correct FROM word_reviews")
    fun observeReviewGapAndResult(): Flow<List<ReviewGapRow>>
```

并在文件末尾（`interface WordDao` 之外）加：

```kotlin
/** [WordDao.observeReviewGapAndResult] 的投影行。`gapDays` 为 null = v2.3 之前的旧记录没这个字段 */
data class ReviewGapRow(val gapDays: Double?, val correct: Boolean)
```

- [ ] **Step 2：仓储层透出** — 修改 `data/repository/WordRepository.kt`

```kotlin
    /** 全库半衰期（看板用） */
    fun observeHalfLifeDays(): Flow<List<Double>> = wordDao.observeHalfLifeDays()

    /** 复习间隔 + 结果（实测遗忘曲线用） */
    fun observeReviewGapAndResult(): Flow<List<ReviewGapRow>> = wordDao.observeReviewGapAndResult()
```

- [ ] **Step 3：写 ViewModel** — 新建 `ui/stats/StatsViewModel.kt`

```kotlin
package com.studykit.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.dao.ReviewGapRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/** 看板一屏要画的全部数字。算法都在 MemoryHealth 里，这里只做组合 */
data class StatsUiState(
    val durability: List<DurabilityBucket> = emptyList(),
    val plannedCount: Int = 0,
    val totalWords: Int = 0,
    val forecast: List<DayLoad> = emptyList(),
    val curve: List<CurvePoint> = emptyList(),
    val modelCurve: List<Double> = emptyList(),
    val sampleCount: Int = 0,
)

/**
 * 记忆看板 ViewModel。
 *
 * `combine` 的变换（全库半衰期分桶、按日聚合）放 Default 线程：
 * 一次评分就会触发这四个 Flow 重算，落在 Main 上就是主线程数一千多个数。
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val wordRepository = (application as StudyKitApp).container.wordRepository

    companion object {
        /** 未来量看 7 天：够排一周，再多就只是把"下周"也压进焦虑里 */
        private const val FORECAST_DAYS = 7

        /** 模型曲线取第 0~9 天，与墨墨那张图的横轴一致 */
        private val CURVE_DAYS = (0..9).toList()
    }

    val state: StateFlow<StatsUiState> = combine(
        wordRepository.observeHalfLifeDays(),
        wordRepository.observeScheduledTimestamps(),
        wordRepository.observeReviewGapAndResult(),
        wordRepository.observeCount(),
    ) { halfLives, scheduled, reviews, total ->
        val samples = reviews.mapNotNull { row: ReviewGapRow ->
            row.gapDays?.let { GapSample(gapDays = it, recalled = row.correct) }
        }
        StatsUiState(
            durability = MemoryHealth.durability(halfLives),
            plannedCount = halfLives.count { it.isFinite() && it > 0.0 },
            totalWords = total,
            forecast = MemoryHealth.forecastByDay(
                timestamps = scheduled,
                zone = ZoneId.systemDefault(),
                from = LocalDate.now(),
                days = FORECAST_DAYS,
            ),
            curve = MemoryHealth.forgettingCurve(samples),
            modelCurve = MemoryHealth.modelCurve(halfLives, CURVE_DAYS),
            sampleCount = samples.size,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
```

- [ ] **Step 4：自查 + 提交 + 推 CI**（同 Task 1 Step 4/5，提交信息
  `feat(stats): 看板数据层（半衰期与复习间隔查询 + StatsViewModel）`）

**诚实边界：** `plannedCount` 的口径是"有可信半衰期的词"，**不等于**墨墨的"已加入记忆规划"——
我们没有"是否加入规划"这个开关。界面措辞照实写"有排期的词"。

---

## Task 3: 看板页面骨架 + 未来 7 天复习量

**Interfaces:**
- Consumes：`StatsViewModel.state`、`DayLoad`
- Produces：`ui/stats/StatsScreen.kt` 里的 `@Composable fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit)`；`StudyRoutes.STATS = "study/stats"`

**Steps:**

- [ ] **Step 1：建页面** — 新建 `ui/stats/StatsScreen.kt`

```kotlin
package com.studykit.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppTheme
import java.time.format.DateTimeFormatter

/**
 * 记忆看板：把 v2.3 半衰期模型已经算得出来、但用户看不见的三件事摊开。
 *
 * 根 Column 只有一处 `verticalScroll`，**不碰 insets** —— 全仓唯一的 innerPadding 消费者是
 * `AppNav` 的根 Scaffold（设置页踩过两份键盘高度的坑，理由同）。
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "记忆看板", style = texts.pageTitle)
        }
        Spacer(Modifier.height(AppTheme.space.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
        ) {
            StatTile(value = "${state.totalWords}", label = "单词总数", modifier = Modifier.weight(1f))
            StatTile(value = "${state.plannedCount}", label = "有排期的词", modifier = Modifier.weight(1f))
            StatTile(value = "${state.sampleCount}", label = "复习记录", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(AppTheme.space.lg))

        SectionHeader(title = "未来 7 天要复习多少")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            if (state.forecast.all { it.count == 0 }) {
                EmptyState(text = "接下来一周没有排期", caption = "背完一轮之后才会排出来")
            } else {
                ForecastBars(forecast = state.forecast)
            }
        }
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/** 一周柱状：柱高按当天占本周峰值的比例，纯 Row 不用 Canvas（能被无障碍读到） */
@Composable
private fun ForecastBars(forecast: List<DayLoad>) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val peak = (forecast.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val dayFmt = DateTimeFormatter.ofPattern("EEE")
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            forecast.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "${day.count}", style = texts.caption, color = colors.secondaryText)
                    Spacer(Modifier.height(2.dp))
                    Spacer(
                        modifier = Modifier
                            .width(28.dp)
                            .height((6 + 54 * day.count / peak).dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (day.count == 0) colors.divider else colors.accent)
                            .semantics { contentDescription = "${day.date} 要复习 ${day.count} 个" },
                    )
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs, Alignment.CenterHorizontally),
        ) {
            forecast.forEach { day ->
                Text(
                    text = day.date.format(dayFmt),
                    style = texts.caption,
                    color = colors.secondaryText,
                    modifier = Modifier.width(28.dp),
                )
            }
        }
    }
}
```

> 柱高用 `(6 + 54 * count / peak).dp` 直接算 dp，**不要用 `weight`**：`weight` 只在
> `RowScope`/`ColumnScope` 的直接子节点有效，套在 `Column` 里那层会编译失败。
> `Int` 除法在这里是故意的（柱高取整 dp），别改成浮点。

- [ ] **Step 2：确认组件签名对得上** — `EmptyState` 与 `SectionHeader` 的参数名以文件里的实际声明为准
  （`Read app/src/main/java/com/studykit/ui/components/EmptyState.kt`），不一致就改调用方，不改组件。

- [ ] **Step 3：接路由** — 修改 `ui/nav/AppNav.kt`：`object StudyRoutes` 里加
  `const val STATS = "study/stats"`；在 `composable(SettingsRoutes.SETTINGS)` 之前加

```kotlin
            composable(StudyRoutes.STATS) {
                StatsScreen(
                    viewModel = remember { StatsViewModel(LocalContext.current as Application) },
                    onBack = { navController.popBackStack() },
                )
            }
```

> 本仓 ViewModel 一律由页面自己 `viewModel()` 创建（见 `SettingsScreen` 的调用处），
> **照抄那里已有的构造方式**，不要新造第三种。

- [ ] **Step 4：首页加入口 + 明天那一行** — 修改 `ui/study/StudyHomeScreen.kt`：
  给 `StudyHomeScreen(...)` 的参数列表加 `onOpenStats: () -> Unit`（放在 `onOpenSettings` 之前），
  在「背单词」那张卡下面加一行说明（数据来自 `state.tomorrowCount`，Task 2 之前已存在于 `StudyHomeUiState`）：

```kotlin
                if (state.tomorrowCount > 0) {
                    Text(
                        text = "明天还要复习 ${state.tomorrowCount} 个",
                        style = AppTheme.texts.caption,
                        color = AppTheme.colors.secondaryText,
                    )
                }
```

并在 `AppNav.kt` 的 `StudyHomeScreen(...)` 调用里补 `onOpenStats = { navController.navigate(StudyRoutes.STATS) { launchSingleTop = true } }`。

- [ ] **Step 5：自查 + 提交 + 推 CI**（提交信息 `feat(stats): 记忆看板页面与未来 7 天复习量`）

**诚实边界：** 柱状只画"已排期"的量，不含"你今天没背完的新词"。

---

## Task 4: 记忆持久度分布卡

**Steps:**

- [ ] **Step 1：加卡片** — 在 `StatsScreen` 的未来量卡之后追加

```kotlin
        SectionHeader(title = "这些记忆能扛多久")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "半衰期 ≥ 一档的词有多少：扛过 10 天不算白背，扛过 90 天才是真的记住了。",
                style = texts.caption,
                color = colors.secondaryText,
            )
            Spacer(Modifier.height(AppTheme.space.md))
            state.durability.forEach { bucket ->
                DurabilityRow(
                    label = "≥ ${bucket.minHalfLifeDays} 天",
                    count = bucket.count,
                    sharePercent = bucket.sharePercent,
                )
            }
        }
```

```kotlin
@Composable
private fun DurabilityRow(label: String, count: Int, sharePercent: Int) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = AppTheme.space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = texts.body, modifier = Modifier.weight(1f))
            Text(
                text = "$count 个 · $sharePercent%",
                style = texts.caption,
                color = colors.secondaryText,
            )
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        LinearProgressIndicator(
            progress = { sharePercent / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = colors.accent,
            trackColor = colors.divider,
            strokeCap = StrokeCap.Round,
        )
    }
}
```

需要 `import androidx.compose.material3.LinearProgressIndicator` 与 `androidx.compose.ui.graphics.StrokeCap`。

- [ ] **Step 2：自查 + 提交 + 推 CI**（`feat(stats): 记忆持久度分布卡`）

**诚实边界：** 分母是"有排期的词"，不是词库总量 —— 未学的词不该出现在这里，界面那句话已经这么写。

---

## Task 5: 我的遗忘曲线 vs 艾宾浩斯

**Steps:**

- [ ] **Step 1：加曲线卡** — `StatsScreen` 末尾追加

```kotlin
        SectionHeader(title = "你的遗忘曲线")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            if (state.sampleCount < MinCurveSamples) {
                EmptyState(
                    text = "复习记录还不够画你的曲线",
                    caption = "攒够 $MinCurveSamples 次复习就会出现在这里。" +
                        "现在图上只有艾宾浩斯那条经典参照线。",
                )
            }
            RetentionChart(
                ebbinghaus = MemoryHealth.EbbinghausPoints,
                modelCurve = state.modelCurve,
                curve = state.curve,
                showModel = state.sampleCount < MinCurveSamples,
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            ChartLegend(colors = colors, texts = texts, showModel = state.sampleCount < MinCurveSamples)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = if (state.sampleCount < MinCurveSamples)
                    "艾宾浩斯那条是 1885 年的经典实验数值，被后世反复转述，只当参照，不是你的数据。"
                else
                    "实测点来自你自己每次复习的间隔与结果；艾宾浩斯那条只是参照。",
                style = texts.caption,
                color = colors.secondaryText,
            )
        }
```

- [ ] **Step 2：写 Canvas 曲线** — 同文件追加（颜色全部走令牌，**不许出现字面量色**）

```kotlin
/** 少于这个样本数就不画"你的实测"，否则一条三个点的折线会被当成结论 */
private const val MinCurveSamples = 20

@Composable
private fun RetentionChart(
    ebbinghaus: List<Pair<Double, Double>>,
    modelCurve: List<Double>,
    curve: List<CurvePoint>,
    showModel: Boolean,
) {
    val colors = AppTheme.colors
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics {
                contentDescription = "遗忘曲线：纵轴记住的百分比，横轴天数"
            },
    ) {
        val w = size.width
        val h = size.height
        val maxDay = 9.0
        // 网格：0/25/50/75/100%
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { r ->
            val y = h * (1f - r)
            drawLine(
                color = colors.divider,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
        fun point(day: Double, retention: Double): Offset = Offset(
            x = w * (day / maxDay).toFloat().coerceIn(0f, 1f),
            y = h * (1f - retention.toFloat()),
        )
        if (showModel && modelCurve.isNotEmpty()) {
            drawLine(
                color = colors.accent,
                path = Path().apply {
                    modelCurve.forEachIndexed { i, r ->
                        val p = point(i.toDouble(), r)
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                },
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        // 艾宾浩斯：横轴是线性的，它前两个小时那几个点会挤在最左边 —— 这正是它"先陡后平"的形状
        drawLine(
            color = colors.warning,
            path = Path().apply {
                ebbinghaus.forEachIndexed { i, (day, r) ->
                    val p = point(day, r)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            },
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        curve.forEach { pt ->
            val obs = pt.observedRecall ?: return@forEach
            val c = point(pt.midGapDays.coerceAtMost(maxDay), obs)
            drawCircle(color = colors.success, radius = 4.dp.toPx(), center = c)
        }
    }
}

@Composable
private fun ChartLegend(colors: AppColors, texts: AppTexts, showModel: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
        if (showModel) LegendDot(ink = colors.accentInk, dot = colors.accent, label = "模型预测", texts = texts)
        LegendDot(ink = colors.warningInk, dot = colors.warning, label = "艾宾浩斯", texts = texts)
        LegendDot(ink = colors.successInk, dot = colors.success, label = "你的实测", texts = texts)
    }
}

@Composable
private fun LegendDot(ink: androidx.compose.ui.graphics.Color, dot: androidx.compose.ui.graphics.Color, label: String, texts: AppTexts) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(dot))
        Spacer(Modifier.width(AppTheme.space.xs))
        Text(text = label, style = texts.caption, color = ink)
    }
}
```

需要 `import androidx.compose.foundation.Canvas`、`androidx.compose.ui.geometry.Offset`、
`androidx.compose.ui.graphics.Path`、`com.studykit.ui.theme.AppColors`、`com.studykit.ui.theme.AppTexts`。

- [ ] **Step 3：自查 + 提交 + 推 CI**（`feat(stats): 遗忘曲线对比卡（实测/模型/艾宾浩斯）`）

**诚实边界：** 样本不足时明确说"还不够画你的曲线"，并用 `showModel` 改画模型预测 —— 模型线用的是**全库平均半衰期**，不是拟合出来的个人曲线，图例上也这么写。

---

## Task 6: CI 复核 + 真机走查 + 交付

- [ ] **Step 1：按 head SHA 复核 CI** —— `gh run view <id> --json conclusion,headSha,jobs`，
  确认 `headSha` 就是本地 HEAD，`conclusion=success`。**不看任何转述。**
- [ ] **Step 2：下载 debug 产物** —— `gh api repos/wuliao00/StudyKit/actions/runs/<id>/artifacts`
  取 id，再 `curl -sS --ssl-no-revoke -L --retry 4 -H "Authorization: Bearer $(gh auth token)"
  -o artifact.zip https://api.github.com/repos/wuliao00/StudyKit/actions/artifacts/<id>/zip`
  （`gh run download` 在这台机器上会断流）。
- [ ] **Step 3：真机走查**（vivo 装包要先卸载 —— CI 每次新生成 debug keystore）。逐项截图存
  `Desktop\StudyKit-v2.3.0\walk\`：
  - 首页出现「明天还要复习 N 个」，N 与 `SELECT COUNT(*) FROM words WHERE next_review_at` 落在明天 0 点到后天 0 点的结果一致
  - 看板三块图都有数据；复习记录 < 20 时曲线卡显示"还不够画你的曲线"而不是空图或假数据
  - 柱状在最坏负载下不溢出（临时把 7 天里某天灌到 200 个，看柱高与标签）
  - 全 0 时不出现"y 轴刻度重复 1,1,0,0,0"那类退化（小计划的翻车点）
  - 深色主题下三条线/三种点都能分清（品牌色只作填充，文字一律 `*Ink`）
- [ ] **Step 4：更新交付文档** —— `更新说明.md` 的"仍然没验的"里划掉统计页；
  `真机验证报告.md` 补一节；APK 换名重出（versionCode 5 → 若本轮改了 schema 才要升，本计划不改）。

---

## Self-Review 结论

- **Spec 覆盖**：spec §2 参照表里"墨墨有、我们没有"的三块 —— 持久度分布（Task 4）、未来量（Task 3）、
  遗忘曲线对比（Task 5）—— 都有任务；spec §8 的"统计页未来 7 天柱状与 SQL 手算一致"落在 Task 6 Step 3。
  spec 里没要求但顺手补齐的：首页"明天要复习 N 个"（`tomorrowCount` 字段早已存在但没渲染，属于半成品，必须收口）。
- **占位符**：Task 1 的 `modelCurve` 与 Task 3 的 `ForecastBars` 我**故意**在计划里留了一版错的并标注了为什么错
  （`weight` 不在正确的作用域、`sumOf` 写歪）—— 执行者必须用给定的那版。除此之外无 TBD。
- **类型一致性**：`DurabilityBucket` / `DayLoad` / `GapSample` / `CurvePoint` 全部定义在 Task 1，
  Task 2/3/4/5 只引用；`StatsUiState` 字段名与 Task 3–5 用到的 `state.durability` / `state.forecast` /
  `state.curve` / `state.modelCurve` / `state.sampleCount` / `state.plannedCount` 一一对上。
  `MemoryHealth.EbbinghausPoints` 是 `List<Pair<Double, Double>>`，Task 5 里用 `(day, r)` 解构一致。
