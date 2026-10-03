# StudyKit v2.7 计划 A：排期内核 + Room v7 + 迁移测试网（地基）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §2/§3 —— 双内核（默认 FSRS）+ Room v7 + 迁移测试网 + 信心/超纠正/掌握/断签保护的纯逻辑层，全部 TDD。

**Architecture:** `SchedulingKernel` 接口收口排期计算；`FsrsKernel`（FSRS-5 公式组 + py-fsrs@9446cb0 权重表 w[0..15]）为默认，`HalfLifeKernel` 适配器包住现有 `MemoryModel`。schema 变更全部"先写迁移测试（红）→ 实体/迁移（绿）"。

**Tech Stack:** Kotlin 2.0.21 / AGP 8.7.3 / Room 2.6.1(KSP) / JUnit4 + Robolectric 4.14.1。测试命令一律 `.\gradlew`（Windows，仓库根 `C:\Users\Administrator\Desktop\workspace\StudyKit`）。

** spec:** `docs/superpowers/specs/2026-10-02-studykit-v2-7-docx-alignment-design.md`

**约定（所有任务通用）**
- 提交信息风格沿用仓内既有格式 `feat(v2.7/Ax): …` / `test(v2.7/Ax): …`。**首次提交前必须先征得用户同意**（用户规矩：不明确说不 commit）。
- 每一步"先跑红"必须看到**失败**再继续；红的原因必须是缺实现，不是拼写/依赖错误。
- 所有新文件包名 = 目录路径对应 `com.studykit.*`；注释密度对齐周边代码（中文、解释"为什么"）。

---

### Task 1: exportSchema + room-testing + v6 快照

**Files:**
- Modify: `gradle/libs.versions.toml`（[versions]/[libraries]）
- Modify: `app/build.gradle.kts`（ksp 参数 + test assets）
- Create（构建产物，入库）: `app/schemas/com.studykit.data.AppDatabase/6.json`

- [ ] **Step 1: toml 加依赖别名**

`gradle/libs.versions.toml` `[libraries]` 中 `androidx-room-compiler` 行后加：

```toml
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

- [ ] **Step 2: build.gradle.kts 三处**

`android { … }` 块内 `testOptions { … }` 之后加：

```kotlin
    // Room schema 快照目录：MigrationTestHelper 从 test assets 读 `com.studykit.data.AppDatabase/<v>.json`
    // 逐字校验迁移产物（spec §3.1；没有它，"SQL 与实体一致"只能靠真机撞上去）。
    sourceSets {
        getByName("test").assets.srcDirs("$projectDir/schemas")
    }
```

文件末尾 `dependencies { … }` 里 `testImplementation(libs.junit)` 之后加：

```kotlin
    testImplementation(libs.androidx.room.testing)
```

`plugins { … }` 块之后、`android { … }` 之前加：

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

并把 `@Database(..., version = 6, exportSchema = false)`（`data/AppDatabase.kt:49-50`）改为 `exportSchema = true`（version 先留 6，Task 7 再升 7）。

- [ ] **Step 3: 生成并核对 6.json**

Run: `.\gradlew :app:kspDebugKotlin`
Expected: BUILD SUCCESSFUL，且 `app/schemas/com.studykit.data.AppDatabase/6.json` 存在。
打开 6.json 抽 `words` 表，确认列与 `Word.kt` 实体一致（**15 列**（列序=实体字段序；13 是 entities 个数，别和列数混），含 `half_life_days REAL NOT NULL DEFAULT 0.5` 等）。**这一步是快照取证，改任何东西前先把它 commit（或至少备份）**。

- [ ] **Step 4: 全量测试仍绿 + Commit（提交需用户同意）**

Run: `.\gradlew :app:testDebugUnitTest` → Expected: 全绿（实测 53 suites / 519 用例；既有测试类一个不许改）。

---

### Task 2: SchedulingKernel 类型层 + HalfLife 适配器

> 实现与下文有四处已定调差异（以代码为准，详情见各节注释）：`FSRS_SEED_RATIO` 已按 A-T2 双审升为包级 `FSRS_HALF_OVER_S = 243.0/19.0`（commit 41120ba）；`review` 不再发明 `cardState`（D-B）；`difficulty` 列量纲在 A-T9 终审进一步定调为**两列各守各量纲**（见 `halfDifficultyFromFsrs`）；测试名/防呆断言以盘上为准。

**Files:**
- Create: `app/src/main/java/com/studykit/data/memory/SchedulingKernel.kt`
- Test: `app/src/test/java/com/studykit/data/memory/SchedulingKernelTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulingKernelTest {
    @Test fun `halfLife adapter reproduces v2_6 update and schedule`() {
        // 与 MemoryModelTest 黄金轨迹同源：h=30、d=2、拖 2 天评认识
        val kernel = HalfLifeKernel()
        val before = KernelState(stability = null, difficulty = 2.0, cardState = CardState.REVIEW, hDays = 30.0)
        val after = kernel.review(before, elapsedDays = 2.0, rating = KernelRating.GOOD, conf = null)
        // 30 天半衰期、target 0.9 下"认识"一次应显著加固 h（对照 MemoryModelTest 黄金轨迹量级）
        check((after.hDays ?: 0.0) > 30.0)
        val days = kernel.nextIntervalDays(after, rating = KernelRating.GOOD, targetRecall = 0.9, maxIntervalDays = 365.0)
        assertEquals(days, MemoryModel.schedule(
            MemoryState(after.hDays!!, after.difficulty), ReviewGrade.RECALL, 0.9, 365.0,
        ), 1e-9)
    }

    @Test fun `toKernelState roundtrip`() {
        val s = MemoryState(halfLifeDays = 12.5, difficulty = 3.2)
        val k = HalfLifeKernel().seedFromHalfLife(s.halfLifeDays, s.difficulty)
        assertEquals(12.5, k.hDays!!, 1e-9)
        assertEquals(3.2, k.difficulty, 1e-9)
    }
}
```

- [ ] **Step 2: 跑红**

Run: `.\gradlew :app:testDebugUnitTest --tests "*SchedulingKernelTest*"` → 编译失败（类型不存在）即视为红。

- [ ] **Step 3: 实现**

```kotlin
package com.studykit.data.memory

/** 用户翻面/提交前采集的信心三档；null = 跳过（不阻塞，也不参与超纠正判定）。spec §2.3 */
enum class Confidence { GUESS, FAIR, SURE }

/** 内核视角的评分。AGAIN=忘记/HARD=模糊/GOOD=认识；EASY 存在但本 App 永不产生（spec D5） */
enum class KernelRating { AGAIN, HARD, GOOD, EASY }

/** 卡片生命周期，语义对齐 py-fsrs State（LEARNING=未定 S 的新词） */
enum class CardState { LEARNING, REVIEW, RELEARNING }

/**
 * 一条记忆的内核状态。
 *
 * 一个类同时服务两个内核，字段谁用谁认：
 *  - FSRS 用 [stability]（天，null=还没首次评分）与 [difficulty]（1..10）；
 *  - Half-Life 用 [hDays]（半衰期，天）与 [difficulty]。
 *  镜像语义见 spec §2.1：复习只原生更新活跃内核字段，另一字段按换算回填（近似值）。
 */
data class KernelState(
    val stability: Double?,
    val difficulty: Double,
    val cardState: CardState,
    val hDays: Double? = null,
)

/** 排期内核接口（spec §2.1）。全部纯函数 + java.time，JVM 可测，零 Android 依赖 */
interface SchedulingKernel {
    /** 预测可提取性 R∈[0,1]。[elapsedDays] 距上次复习的真实天数 */
    fun recall(state: KernelState, elapsedDays: Double): Double

    /** 评一次分出新状态。[conf] 仅用于超纠正侧信道，内核本身可忽略 */
    fun review(
        state: KernelState,
        elapsedDays: Double,
        rating: KernelRating,
        conf: Confidence?,
    ): KernelState

    /** 该排到几天后；AGAIN 走"当日再见"地板（对齐旧 intervalDaysAfterForget 的 10 分钟语义） */
    fun nextIntervalDays(
        state: KernelState,
        rating: KernelRating,
        targetRecall: Double,
        maxIntervalDays: Double,
    ): Double

    /** 迁移/适配：从半衰期 (h,d) 造状态。FSRS 实现按 spec §2.4 换算，HalfLife 原样带回 */
    fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState

    val id: String // "FSRS" | "HALF_LIFE"
}

/** v2.6 半衰期内核的适配器：行为必须与直接调 MemoryModel 逐比特一致（黄金轨迹测试守） */
class HalfLifeKernel : SchedulingKernel {

    override val id = "HALF_LIFE"
    private val params = MemoryParams()

    private fun toState(s: KernelState): MemoryState =
        MemoryState(halfLifeDays = s.hDays ?: MemoryState.NEW.halfLifeDays, difficulty = s.difficulty)

    private fun grade(r: KernelRating): ReviewGrade = when (r) {
        KernelRating.AGAIN -> ReviewGrade.FORGET
        KernelRating.HARD -> ReviewGrade.VAGUE
        KernelRating.GOOD, KernelRating.EASY -> ReviewGrade.RECALL
    }

    override fun recall(state: KernelState, elapsedDays: Double): Double =
        MemoryModel.recallProbability(elapsedDays, state.hDays ?: MemoryState.NEW.halfLifeDays)

    override fun review(
        state: KernelState, elapsedDays: Double, rating: KernelRating, conf: Confidence?,
    ): KernelState {
        val after = MemoryModel.update(toState(state), elapsedDays, grade(rating), params)
        // 镜像字段：FSRS 语义下的 stability 只是换算近似（spec §2.1 双写口径）
        return state.copy(
            hDays = after.halfLifeDays,
            difficulty = after.difficulty,
            stability = after.halfLifeDays / FSRS_SEED_RATIO,
            cardState = if (rating == KernelRating.AGAIN) CardState.RELEARNING else CardState.REVIEW,
        )
    }

    override fun nextIntervalDays(
        state: KernelState, rating: KernelRating, targetRecall: Double, maxIntervalDays: Double,
    ): Double = MemoryModel.schedule(
        toState(state), grade(rating), targetRecall, maxIntervalDays, params,
    )

    override fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState =
        KernelState(stability = halfLifeDays / FSRS_SEED_RATIO, difficulty = difficulty,
            cardState = CardState.REVIEW, hDays = halfLifeDays)

    companion object {
        /** 幂律族 (decay=-0.5) 下 h/S 常数比值，见 FsrsKernel 同名常量与 spec §2.4 */
        const val FSRS_SEED_RATIO = 12.789473684210526
    }
}
```

- [ ] **Step 4: 跑绿 + 全量回归**

Run: `.\gradlew :app:testDebugUnitTest --tests "*SchedulingKernelTest*"` → PASS
Run: `.\gradlew :app:testDebugUnitTest` → 全绿（既有 MemoryModelTest 不许改）。

---

### Task 3: FsrsKernel（黄金轨迹先钉死）

**Files:**
- Create: `app/src/main/java/com/studykit/data/memory/FsrsKernel.kt`
- Test: `app/src/test/java/com/studykit/data/memory/FsrsKernelTest.kt`

**黄金数字来源：** 本计划写作时用独立 Python 脚本按下列公式手算复核（factor 恰为 19/81；`I(S, dr=0.9) ≡ S` 自检通过；比值 12.789474 与 reverse-ref `引用复查台账.md §2` 的 12.7895 一致）。断言容差 1e-4。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class FsrsKernelTest {

    private val k = FsrsKernel()

    @Test fun `retrievability anchor R(t=S) is 0_9`() {
        val s = KernelState(stability = 10.0, difficulty = 3.0, cardState = CardState.REVIEW)
        assertEquals(0.9, k.recall(s, elapsedDays = 10.0), 1e-9) // S 的定义：R=90% 的间隔
    }

    @Test fun `interval at desired 0_9 equals stability exactly`() {
        val s = KernelState(stability = 10.757465, difficulty = 2.117, cardState = CardState.REVIEW)
        assertEquals(10.757465, k.nextIntervalDays(s, KernelRating.GOOD, 0.9, 36500.0), 1e-6)
    }

    @Test fun `golden trace GOOD after 2 days from S0=w2 D0=2_118`() {
        // 起点：新词首次评 GOOD 之前的状态由 S0 直接给（w[2]=2.3065）
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.GOOD, conf = null)
        assertEquals(10.757465, after.stability!!, 1e-4)
        assertEquals(2.116986, after.difficulty, 1e-4)
        assertEquals(13.360266, k.nextIntervalDays(after, KernelRating.GOOD, 0.88, 36500.0), 1e-4)
    }

    @Test fun `golden trace HARD after 2 days`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.HARD, conf = null)
        assertEquals(7.388910, after.stability!!, 1e-4)
        assertEquals(4.758630, after.difficulty, 1e-4)
    }

    @Test fun `golden trace AGAIN clamps stability to 1 and marks relearning`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.AGAIN, conf = null)
        assertEquals(1.0, after.stability!!, 1e-4)          // v5 支：clip 下限 1 触发
        assertEquals(7.400274, after.difficulty, 1e-4)
        assertEquals(CardState.RELEARNING, after.cardState)
        // "当日再见"地板 = 10 分钟（与旧 intervalDaysAfterForget 同一语义）
        assertEquals(10.0 / 1440.0, k.nextIntervalDays(after, KernelRating.AGAIN, 0.9, 36500.0), 1e-9)
    }

    @Test fun `seed from half life divides by power law ratio`() {
        val seeded = k.seedFromHalfLife(halfLifeDays = 30.0, difficulty = 2.0)
        assertEquals(2.345674, seeded.stability!!, 1e-4)
        assertEquals(30.0, seeded.hDays!!, 1e-9) // 镜像字段按 spec §2.1 回填
    }

    @Test fun `non finite inputs degrade to safe defaults`() {
        // 脏库行不许炸掉整条队列 —— 与 recallProbability 同一防御口径
        val s = KernelState(stability = Double.NaN, difficulty = -3.0, cardState = CardState.REVIEW)
        // 非法 S 被抬到地板 MIN_STABILITY_FLOOR，R 仍可算（这个断言钉的是"地板存在且生效"）
        assertEquals(
            (1.0 + FsrsKernel.FACTOR * 5.0 / FsrsKernel.MIN_STABILITY_FLOOR).pow(-0.5),
            k.recall(s, elapsedDays = 5.0), 1e-9,
        )
        val after = k.review(s, elapsedDays = -2.0, rating = KernelRating.GOOD, conf = null)
        check(after.stability!!.isFinite() && after.stability!! > 0.0)
        check(after.difficulty in 1.0..10.0)
    }
}
```

- [ ] **Step 2: 跑红** — `.\gradlew :app:testDebugUnitTest --tests "*FsrsKernelTest*"`，预期编译失败。

- [ ] **Step 3: 实现 FsrsKernel**

```kotlin
package com.studykit.data.memory

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * FSRS-5 公式组（幂律遗忘曲线，decay 固定 −0.5）。
 *
 * 公式形式逐条对齐 `fsrs-rs src/model_v6.rs:28-88` 的已核实锚点（reverse-ref 竞品 02，A 级），
 * 分支守卫取 **v5 口径**：失败支 `S' = clip(w11·D^-w12·((S+1)^w13−1)·e^{(1−R)·w14}, 1, S)`；
 * v6 的 `new_s_min = S/e^{w17·w18}` 同日短支守卫不使用（无优化器、无第四档，spec §2.2/D5）。
 *
 * 权重 w[0..15] 取 open-spaced-repetition/py-fsrs@9446cb0 README 默认表前 16 项
 * （v5/v6 两表这 16 个值一致）。诚实声明：不声称与 Anki 结果相等，黄金轨迹钉的是本实现的定义。
 *
 * h/S 比值不在本类定义——用包级常量 `FSRS_HALF_OVER_S`（A-T2 双审后定调的单一归属，
 * 定义在 `SchedulingKernel.kt` 末尾）。
 */
class FsrsKernel : SchedulingKernel {

    override val id = "FSRS"

    private val w = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
    )

    override fun recall(state: KernelState, elapsedDays: Double): Double {
        val s = sanitizeStability(state.stability)
        val t = if (elapsedDays.isFinite()) elapsedDays.coerceAtLeast(0.0) else 0.0
        return (1.0 + FACTOR * t / s).pow(DECAY)
    }

    override fun review(
        state: KernelState, elapsedDays: Double, rating: KernelRating, conf: Confidence?,
    ): KernelState {
        val firstTime = state.stability == null || state.cardState == CardState.LEARNING
        val r = when (rating) {
            KernelRating.AGAIN -> 1.0
            KernelRating.HARD -> 2.0
            KernelRating.GOOD -> 3.0
            KernelRating.EASY -> 4.0 // 本 App 不产生（D5），保留语义
        }
        val s0 = w[(rating.ordinal).coerceIn(0, 3)] // AGAIN/HARD/GOOD/EASY → w[0..3]
        val d0 = initDifficulty(r)
        val s = if (firstTime) s0 else sanitizeStability(state.stability)
        val d = if (firstTime) d0 else state.difficulty.coerceIn(1.0, 10.0)
        val t = if (elapsedDays.isFinite()) elapsedDays.coerceAtLeast(0.0) else 0.0
        val rr = recall(KernelState(s, d, state.cardState), t)

        val nextS = if (rating == KernelRating.AGAIN) {
            val raw = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1.0 - rr) * w[14])
            // 链式夹取而非 coerceIn(1.0, s)：真新词首次 AGAIN 时 s=S0=w[0]=0.212<1，
            // coerceIn 遇空区间直接抛（A-T3 实跑抓出）；s≥1 时两式逐比特相同
            raw.coerceAtLeast(1.0).coerceAtMost(s) // v5 守卫：lapse 只降不升，地板 1 天
        } else {
            val hard = if (rating == KernelRating.HARD) w[15] else 1.0
            val inc = exp(w[8]) * (11.0 - d) * s.pow(-w[9]) * (exp((1.0 - rr) * w[10]) - 1.0) * hard
            (s * (inc + 1.0)).coerceAtMost(MAX_STABILITY)
        }
        val nextD = nextDifficulty(d, r)
        return KernelState(
            stability = nextS,
            difficulty = nextD,
            cardState = when (rating) {
                KernelRating.AGAIN -> CardState.RELEARNING
                else -> CardState.REVIEW
            },
            hDays = nextS * FSRS_HALF_OVER_S, // 镜像回填（近似，spec §2.1 双写口径；包级常量，A-T2 定调）
        )
    }

    override fun nextIntervalDays(
        state: KernelState, rating: KernelRating, targetRecall: Double, maxIntervalDays: Double,
    ): Double {
        if (rating == KernelRating.AGAIN) return TEN_MINUTES_IN_DAYS
        val s = sanitizeStability(state.stability)
        val dr = if (targetRecall.isFinite()) targetRecall.coerceIn(0.5, 0.99) else 0.9
        val raw = s / FACTOR * (dr.pow(1.0 / DECAY) - 1.0)
        // maxIntervalDays 来自设置/考试日期推导，脏值可能 <0：同样链式夹取防空区间抛异常（A-T3 跟进项，Task 9 接线时必须保证传入前已夹正，两处双保险）
        return raw.coerceAtLeast(0.0).coerceAtMost(maxIntervalDays.coerceAtLeast(0.0))
    }

    override fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState {
        val h = if (halfLifeDays.isFinite() && halfLifeDays > 0.0) halfLifeDays else MemoryState.NEW.halfLifeDays
        return KernelState(
            stability = (h / FSRS_HALF_OVER_S).coerceAtLeast(MIN_STABILITY_FLOOR),
            difficulty = difficulty.coerceIn(1.0, 10.0),
            cardState = CardState.REVIEW,
            hDays = h,
        )
    }

    private fun initDifficulty(r: Double): Double =
        (w[4] - exp(w[5] * (r - 1.0)) + 1.0).coerceIn(1.0, 10.0)

    /** D' = d + ΔD·(10−d)/9，随后向 D0(4) 均值回归（w[7]），夹在 [1,10] */
    private fun nextDifficulty(d: Double, r: Double): Double {
        val delta = -w[6] * (r - 3.0)
        val updated = d + (10.0 - d) / 9.0 * delta
        return (w[7] * initDifficulty(4.0) + (1.0 - w[7]) * updated).coerceIn(1.0, 10.0)
    }

    private fun sanitizeStability(raw: Double?): Double =
        if (raw == null || !raw.isFinite() || raw <= 0.0) MIN_STABILITY_FLOOR else raw.coerceAtMost(MAX_STABILITY)

    companion object {
        const val DECAY = -0.5
        /** 0.9^(1/DECAY) − 1 = 19/81，恰为有理数（写作时已独立验算） */
        const val FACTOR = 19.0 / 81.0
        // h/S 比值已按 A-T2 双审提到包级 FSRS_HALF_OVER_S（SchedulingKernel.kt），此处不再定义
        const val MIN_STABILITY_FLOOR = 0.01
        const val MAX_STABILITY = 36500.0
        const val TEN_MINUTES_IN_DAYS = 10.0 / 1440.0
    }
}
```

- [ ] **Step 4: 跑绿** — 同上命令 → PASS。若 `non finite` 用例因 `recall` 返回口径失败，调整的是**实现**不是断言（脏值降级不抛异常是 spec 承诺）。
- [ ] **Step 5: 全量 `.\gradlew :app:testDebugUnitTest` 绿。**

---

### Task 4: 信心 → 超纠正优先级（纯逻辑）

**Files:**
- Create: `app/src/main/java/com/studykit/data/memory/Hypercorrection.kt`
- Test: `app/src/test/java/com/studykit/data/memory/HypercorrectionTest.kt`

- [ ] **Step 1: 失败测试**

```kotlin
package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HypercorrectionTest {
    @Test fun `sure but wrong gets retest within ten minutes`() {
        assertEquals(10L, Hypercorrection.retestDelayMinutes(Confidence.SURE, recalled = false))
    }
    @Test fun `guess and wrong is ordinary scheduling, no priority`() {
        assertNull(Hypercorrection.retestDelayMinutes(Confidence.GUESS, recalled = false))
        assertNull(Hypercorrection.retestDelayMinutes(null, recalled = false))
    }
    @Test fun `sure and right never triggers`() {
        assertNull(Hypercorrection.retestDelayMinutes(Confidence.SURE, recalled = true))
    }
}
```

- [ ] **Step 2: 跑红 → Step 3: 实现**

```kotlin
package com.studykit.data.memory

/**
 * 超纠正侧信道（app.docx 模块2 P1 + Butler 2011 回弹证据，spec §2.3）。
 * 判定与内核无关：任何内核排完正常间隔之后，额外问一句"要不要当日再见一次"。
 * 只有 SURE×未忆起 触发；其余组合返回 null = 不干预。
 */
object Hypercorrection {
    fun retestDelayMinutes(conf: Confidence?, recalled: Boolean): Long? =
        if (conf == Confidence.SURE && !recalled) 10L else null
}
```

- [ ] **Step 4: 跑绿；Step 5: 全量绿。**

---

### Task 5: 错题自动掌握 + 断签保护（纯逻辑）

**Files:**
- Create: `app/src/main/java/com/studykit/ui/mistake/MistakeMastery.kt`
- Create: `app/src/main/java/com/studykit/ui/habit/HabitGuard.kt`
- Test: `app/src/test/java/com/studykit/ui/mistake/MistakeMasteryTest.kt`
- Test: `app/src/test/java/com/studykit/ui/habit/HabitGuardTest.kt`

- [ ] **Step 1: 两份失败测试（关键用例照贴）**

```kotlin
package com.studykit.ui.mistake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeMasteryTest {
    // (判对, 距上次判对的天数) 时间序列表
    @Test fun `two correct three days apart masters`() {
        val history = listOf(true to 0.0, false to 0.0, true to 4.0, true to 3.0)
        // 规则只看**最近连续两次判对**且它们之间 ≥3 天
        assertTrue(MistakeMastery.isMastered(history))
    }
    @Test fun `same day double correct does not master`() {
        assertFalse(MistakeMastery.isMastered(listOf(true to 4.0, true to 0.0)))
    }
    @Test fun `three correct close together does not master`() {
        assertFalse(MistakeMastery.isMastered(listOf(true to 0.0, true to 2.0, true to 2.0)))
    }
    @Test fun `wrong answer resets the chain`() {
        assertFalse(MistakeMastery.isMastered(listOf(true to 4.0, true to 4.0, false to 1.0, true to 0.5)))
    }
    @Test fun `empty or single-entry history never masters`() {
        assertFalse(MistakeMastery.isMastered(emptyList()))
        assertFalse(MistakeMastery.isMastered(listOf(true to 9.0)))
    }
}
```

```kotlin
package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HabitGuardTest {
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    @Test fun `first two misses of the month are guarded`() {
        val month = listOf(
            true, true, false, true, false, false, true, // d1..d7：3 个缺，第 1、2 个受保护
        )
        val guarded = HabitGuard.guardedIndices(month)
        assertEquals(listOf(2, 4), guarded)
    }
    @Test fun `third miss is not guarded and breaks`() {
        val month = listOf(false, false, false)
        assertEquals(listOf(0, 1), HabitGuard.guardedIndices(month))
        assertTrue(HabitGuard.breaksAt(month, index = 2))
        org.junit.Assert.assertFalse(HabitGuard.breaksAt(month, index = 0))
    }
    @Test fun `no miss no guard`() {
        assertEquals(emptyList<Int>(), HabitGuard.guardedIndices(listOf(true, true)))
    }
    @Test fun `guard text is pinned for the day bubble`() {
        assertEquals("未打卡（已用断签保护）", HabitGuard.MISSING_GUARD_TEXT)
    }
}
```

- [ ] **Step 2: 跑红。**

- [ ] **Step 3: 实现**

```kotlin
package com.studykit.ui.mistake

/**
 * 自动掌握判定（app.docx 模块3 P1，spec §6：取"2–3 次"里的 2，取舍记录进 CHANGELOG）。
 * [history] 按时间升序，每项 = (本次判对, 距上一次之间的间隔天数)；首项间隔无意义传 0。
 */
object MistakeMastery {
    const val MIN_GAP_DAYS = 3.0

    fun isMastered(history: List<Pair<Boolean, Double>>): Boolean {
        if (history.size < 2) return false
        // 间隔存在**后一项**（"本次判对, 距上一次几天"）——首版草稿从倒数第二项取 gap，
        // 会把"同日两连对"误判成掌握（A-T5 实跑抓出，用例 same day…钉死）
        val (lastOk, gap) = history.last()
        val (prevOk, _) = history[history.size - 2]
        return lastOk && prevOk && gap >= MIN_GAP_DAYS
    }
}
```

```kotlin
package com.studykit.ui.habit

/**
 * 断签保护（app.docx 第三部分#4"宽恕机制"，spec D6/§7.1：每月 2 次、纯推导零落库）。
 * 输入 = 某自然月逐日打卡布尔（当月 1 号起，true=当日有打卡）；下标 0 起。
 * 缺卡按出现顺序编号，第 1、2 次受保护（连续天数跨它延续），第 3 次起照常断链。
 */
object HabitGuard {
    const val MONTHLY_ALLOWANCE = 2

    /** 日详情气泡文案（钉死可测；措辞先共情后规则，不带刑罚味） */
    const val MISSING_GUARD_TEXT = "未打卡（已用断签保护）"

    fun guardedIndices(missPattern: List<Boolean>): List<Int> =
        missPattern.indices.filter { !missPattern[it] }.take(MONTHLY_ALLOWANCE)

    fun breaksAt(missPattern: List<Boolean>, index: Int): Boolean =
        !missPattern[index] && !guardedIndices(missPattern).contains(index)
}
```

- [ ] **Step 4: 跑绿；Step 5: 全量绿。**

---

### Task 6: v7 实体与新表（只改 schema 声明，先不迁移）

**Files:**
- Modify: `data/entity/Word.kt`、`WordReview.kt`、`PracticeRecord.kt`、`Question.kt`、`Mistake.kt`、`Excerpt.kt`、`Habit.kt`
- Create: `data/entity/MistakeRedo.kt`、`data/entity/ChapterTest.kt`
- Modify: `data/AppDatabase.kt`（entities 列表 + version=7 —— **此步不写 MIGRATION_6_7**）

- [ ] **Step 1: 逐实体加列（精确内容）**

`Word.kt` 在 `sourceListId` 之前插入（可空列**不写** defaultValue，镜像既有 `last_review_at` 的口径）：

```kotlin
    /** v2.7：FSRS 稳定性（天）。null = 还没被 FSRS 内核评过（新词/迁移前旧词未回填） */
    @ColumnInfo(name = "fsrs_stability") val fsrsStability: Double? = null,
    /** v2.7：FSRS 难度 D∈[1,10]，null 同上 */
    @ColumnInfo(name = "fsrs_difficulty") val fsrsDifficulty: Double? = null,
    /** v2.7：py-fsrs State 语义 1=LEARNING 2=REVIEW 3=RELEARNING */
    @ColumnInfo(name = "fsrs_state", defaultValue = "1") val fsrsState: Int = 1,
    /** v2.7：本条当前归属内核（"FSRS"/"HALF_LIFE"，spec §2.1 双写） */
    @ColumnInfo(name = "kernel", defaultValue = "'FSRS'") val kernel: String = "FSRS",
```

`WordReview.kt`：`reactionMs` 之后加 `@ColumnInfo(name = "confidence") val confidence: Int? = null`（1=瞎猜 2=有点印象 3=非常确定；null=跳过）与 `@ColumnInfo(name = "fsrs_rating") val fsrsRating: Int? = null`。
`PracticeRecord.kt`：`at` 之前加同型 `confidence` 列。
`Question.kt`：`explanation` 之后加 `@ColumnInfo(name = "concept_tag", defaultValue = "''") val conceptTag: String = ""`。
`Mistake.kt`：`reviewAt` 注释改为"手动覆盖排期（null=交给算法）"，并加列 `fsrs_stability REAL NULL`、`fsrs_difficulty REAL NULL`、`fsrs_state INTEGER NOT NULL DEFAULT 1`、`correct_streak INTEGER NOT NULL DEFAULT 0`、`review_count INTEGER NOT NULL DEFAULT 0`、`priority INTEGER NOT NULL DEFAULT 0`（Kotlin 形态与 Word 同式）。
`Excerpt.kt`：加 `@ColumnInfo(name = "next_review_at", defaultValue = "0") val nextReviewAt: Long = 0L`、`@ColumnInfo(name = "review_count", defaultValue = "0") val reviewCount: Int = 0`、`@ColumnInfo(name = "stability") val stability: Double? = null`。
`Habit.kt`：`sortOrder` 之后加 `@ColumnInfo(name = "if_then", defaultValue = "''") val ifThen: String = ""`（执行意图整句；when 维度复用现有 `category`）。

新文件（列序 = 迁移 SQL 的列序，Room 校验建表语句按实体顺序生成）：

```kotlin
package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一次错题重做的完整轨迹（RedoFlow 注释自认缺的那张表，spec §3.2） */
@Entity(
    tableName = "mistake_redos",
    foreignKeys = [ForeignKey(entity = Mistake::class, parentColumns = ["id"],
        childColumns = ["mistake_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mistake_id")],
)
data class MistakeRedo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "mistake_id") val mistakeId: Long,
    @ColumnInfo(name = "redone_at") val redoneAt: Long = System.currentTimeMillis(),
    val correct: Boolean,
    @ColumnInfo(name = "hints_used") val hintsUsed: Int = 0,
    @ColumnInfo(name = "had_note_rebuild") val hadNoteRebuild: Boolean = false,
)
```

```kotlin
package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 章节自测题：题面与参考答案都是用户自己写的文本，App 不判题（spec §3.2 注） */
@Entity(
    tableName = "chapter_tests",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"],
        childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id")],
)
data class ChapterTest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "chapter_label") val chapterLabel: String,
    val question: String,
    @ColumnInfo(name = "expected_answer") val expectedAnswer: String,
    val passed: Boolean,
    @ColumnInfo(name = "tested_at") val testedAt: Long = System.currentTimeMillis(),
)
```

`AppDatabase.kt`：entities 列表追加 `MistakeRedo::class, ChapterTest::class`，`version = 6` → `version = 7`。

- [ ] **Step 2: 构建确认 Room 生成 7.json**

Run: `.\gradlew :app:kspDebugKotlin` → 绿；确认生成 `app/schemas/com.studykit.data.AppDatabase/7.json`，**打开它抄出每张变更表的新列定义**（Task 7 的迁移 SQL 必须与它逐字一致——不许手写"大概"的 SQL）。
此时 app 编译过、单测基本过，但**旧装机升级会炸**（无 6→7 迁移）——正是 Task 7 测试要抓的。

---

### Task 7: Migration_6_7 + 迁移测试（先红后绿）

**Files:**
- Test Create: `app/src/test/java/com/studykit/data/Migration6To7Test.kt`
- Modify: `data/AppDatabase.kt`（加 `MIGRATION_6_7` 并注册 `:231`）

- [ ] **Step 1: 写迁移测试（此时必红：迁移不存在）**

```kotlin
package com.studykit.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration6To7Test {

    // 注意：room 2.6.1 的构造器是 (Instrumentation, Class<out RoomDatabase>)，
    // 不是 2.7 那个带 Context/arguments 的重载（T1 质量审查已反查 jar 证实，照 2.7 写会编译红）。
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test fun migrate6to7KeepsRowsAndSeedsFsrs() {
        // 1) 先造一个 **v6** 库并塞三行：有历史的 MASTERED、有历史的 LEARNING、纯新词
        var db = helper.createDatabase(DB, 6)
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses) VALUES ('u1','apple','苹果','An apple a day','MASTERED',100,30.0,2.0,900,7,1)")
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses) VALUES ('u2','vim','编辑','vim vim','LEARNING',200,3.0,1.5,800,3,0)")
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses) VALUES ('u3','new','新词','','NEW',0,0.5,1.0,NULL,0,0)")
        db.close()

        // 2) 跑迁移并让 Room 逐字校验 schema（validate 参数=迁移目标版本 7）
        db = helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7)

        // 3) 断言：行都在、老列原样、回填符合 spec §2.4 的换算
        val c = db.query("SELECT COUNT(*) FROM words").also { it.moveToFirst() }
        assertEquals(3, c.getInt(0))
        val row = db.query(
            "SELECT half_life_days, fsrs_stability, fsrs_state, kernel FROM words WHERE uuid='u1'",
        ).also { it.moveToFirst() }
        assertEquals(30.0, row.getDouble(0), 1e-9)
        assertEquals(30.0 / 12.789473684210526, row.getDouble(1), 1e-6) // S=h/比值
        assertEquals(2, row.getInt(2)) // REVIEW
        assertEquals("FSRS", row.getString(3))
        val fresh = db.query(
            "SELECT fsrs_stability, fsrs_state FROM words WHERE uuid='u3'",
        ).also { it.moveToFirst() }
        check(fresh.isNull(0)) // 纯新词不回填 S —— 走原生 S0 支，spec §2.4 注
        assertEquals(1, fresh.getInt(1)) // LEARNING

        // 4) mistakes/excerpts/habits 老行不丢、新列取默认
        db.execSQL("INSERT INTO mistakes (uuid, source, subject, title, content) VALUES ('m1','word','数学','t','c')")
        val m = db.query("SELECT fsrs_state, correct_streak, priority FROM mistakes WHERE uuid='m1'").also { it.moveToFirst() }
        assertEquals(1, m.getInt(0)); assertEquals(0, m.getInt(1)); assertEquals(0, m.getInt(2))
        db.close()
    }

    private companion object { const val DB = "migration-6-7-test" }
}
```

Run: `.\gradlew :app:testDebugUnitTest --tests "*Migration6To7Test*"` → 预期 FAIL（`MIGRATION_6_7` 不存在→编译红；或注册缺失→迁移校验红）。

- [ ] **Step 2: 写 MIGRATION_6_7**

**SQL 的每一列定义从 `7.json` 里抄**（Task 6 Step 2 产物），骨架：

```kotlin
        /**
         * v6 → v7（v2.7，spec §3）：双内核与 v2.7 全部新列/新表，一次做完。
         * 只增不删；每列定义与 `app/schemas/…/7.json` 逐字一致（exportSchema=true 后由它兜底，
         * 且本文件顶部注释的 [Migration6To7Test] 会在 CI 校验 —— 不再靠真机撞）。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // words
                db.execSQL("ALTER TABLE `words` ADD COLUMN `fsrs_stability` REAL")
                db.execSQL("ALTER TABLE `words` ADD COLUMN `fsrs_difficulty` REAL")
                db.execSQL("ALTER TABLE `words` ADD COLUMN `fsrs_state` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `words` ADD COLUMN `kernel` TEXT NOT NULL DEFAULT 'FSRS'")
                // 回填（spec §2.4：有历史才折算；纯新词留 null 走 S0 原生支）
                db.execSQL(
                    "UPDATE `words` SET `fsrs_stability` = `half_life_days` / 12.789473684210526, " +
                        "`fsrs_difficulty` = 5.0 + (`difficulty` - 1.0) * 0.5, `fsrs_state` = 2 " +
                        "WHERE `last_review_at` IS NOT NULL",
                )
                // 其余表 ALTER 同式逐表列出：word_reviews(+confidence,+fsrs_rating)
                //   practice_records(+confidence) questions(+concept_tag DEFAULT '')
                //   mistakes(+6 列) excerpts(+3 列) habits(+if_then DEFAULT '')
                // 新表（列序 = 实体声明序 = 7.json）：
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mistake_redos` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                        "`syncStatus` INTEGER NOT NULL DEFAULT 0, `mistake_id` INTEGER NOT NULL, " +
                        "`redone_at` INTEGER NOT NULL, `correct` INTEGER NOT NULL, " +
                        "`hints_used` INTEGER NOT NULL DEFAULT 0, " +
                        "`had_note_rebuild` INTEGER NOT NULL DEFAULT 0" + ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mistake_redos_mistake_id` ON `mistake_redos` (`mistake_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_tests` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                        "`syncStatus` INTEGER NOT NULL DEFAULT 0, `book_id` INTEGER NOT NULL, " +
                        "`chapter_label` TEXT NOT NULL, `question` TEXT NOT NULL, " +
                        "`expected_answer` TEXT NOT NULL, `passed` INTEGER NOT NULL, " +
                        "`tested_at` INTEGER NOT NULL" + ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_tests_book_id` ON `chapter_tests` (`book_id`)")
            }
        }
```

并在 `getInstance` 的 `addMigrations(...)`（`AppDatabase.kt:231`）末尾追加 `MIGRATION_6_7`。**其余表的 ALTER 与索引 SQL 不许省略——实现者须按 7.json 补全后整块提交。**

另外两步 T1 质量审查挂账，在本任务一并清掉（都在同一个文件里）：
- `MIGRATION_4_5` KDoc（现 :129-138）里 `exportSchema = false` 那句已成真话作废，改写为当前事实；`MIGRATION_2_3` KDoc 的条件句（"exportSchema=false 时无人替你核对"）同步改成"自 A-T7 起由 MigrationTestHelper 核对"；其下"CI 全绿也测不出来"改为"A-T7 之前测不出来"。
- `app/build.gradle.kts` 顶部 ksp 注释里"不给 schemaLocation 会退回默认编译输出/schemas"是错的（room 2.6.1 实际是**不导出并报警**），顺手改成准确表述。

- [ ] **Step 3: 迁移测试绿 + 全量绿 + assembleDebug 绿**（三条命令依次跑，贴输出进提交说明）。

---

### Task 8: AppSettings 新键（内核选择 + 信心开关）

**Files:**
- Modify: `data/Settings.kt`
- Test: Modify `app/src/test/java/com/studykit/data/AppSettingsTest.kt`（追加用例）

- [ ] **Step 1: 追加失败用例**（写进既有 `AppSettingsTest`）

```kotlin
    @Test fun `scheduling kernel defaults to FSRS and garbage falls back`() {
        assertEquals("FSRS", AppSettings.fromMap(emptyMap()).schedulingKernel)
        assertEquals("FSRS", AppSettings.fromMap(mapOf(AppSettings.KEY_SCHEDULING_KERNEL to "???")).schedulingKernel)
        assertEquals("HALF_LIFE", AppSettings.fromMap(mapOf(AppSettings.KEY_SCHEDULING_KERNEL to "HALF_LIFE")).schedulingKernel)
    }

    @Test fun `confidence enabled defaults true and roundtrips through toMap`() {
        val s = AppSettings(confidenceEnabled = false)
        assertEquals(false, AppSettings.fromMap(s.toMap()).confidenceEnabled)
        assertEquals(true, AppSettings.fromMap(emptyMap()).confidenceEnabled)
    }
```

- [ ] **Step 2: 跑红。**
- [ ] **Step 3: 实现**：`AppSettings` 加两字段（javadoc 按文件头"每一条都必须有消费点"的规矩写清消费处——Task 9/计划 B 的 Task 11），`toMap`/`fromMap`/KEY 常量三处同步：

```kotlin
    /** v2.7：排期内核 id（"FSRS"|"HALF_LIFE"，见 memory/SchedulingKernel.kt；消费点 StudyViewModel/GradeRow） */
    val schedulingKernel: String = "FSRS",
    /** v2.7：信心条是否出现（默认开；关掉 = 背词/刷题退回 v2.6 交互，消费点 CardStudyScreen/QuizScreen） */
    val confidenceEnabled: Boolean = true,
```

```kotlin
        const val KEY_SCHEDULING_KERNEL = "scheduling_kernel"
        const val KEY_CONFIDENCE_ENABLED = "confidence_enabled"
        val KERNEL_IDS = setOf("FSRS", "HALF_LIFE")
```

`fromMap` 项：`map[KEY_SCHEDULING_KERNEL]?.takeIf { it in KERNEL_IDS } ?: defaults.schedulingKernel`；布尔项 `toBooleanStrictOrNull ?: default`。
- [ ] **Step 4: 绿；Step 5: 全量绿。**

---

### Task 9: 接线内核（评分路径 + 按钮预览），保持 HalfLife 路径行为不变

**Files:**
- Modify: `ui/study/StudyViewModel.kt:224-265`（`gradeCard`）
- Modify: `ui/study/CardStudyScreen.kt:984-989`（`GradeRow` 的 preview）
- Modify: `ui/study/TomorrowForecast.kt:78`、`worker/ReminderWorker.kt:49`（逐词 R）
- Modify: `data/repository/WordRepository.kt:51-88`（`applyReview`/`recordGradedReview` 加新参数）
- Modify: `data/dao/WordDao.kt`（`applyReview` SQL 加 fsrs 列写回）
- Test: `app/src/test/java/com/studykit/ui/study/KernelWiringTest.kt`

- [ ] **Step 0（取证，不许跳）：** 读 `StudyViewModel.kt` 第 1–140 行确认 `AppSettings` 的 StateFlow 字段名（本计划下文写作 `settingsFlow`；若实际名不同，以下各步以实际名为准并记录在提交说明），读 `WordDao.applyReview` 的 UPDATE SQL 原文。
- [ ] **Step 1: 失败测试**——`KernelWiringTest` 只测纯函数部分：

```kotlin
package com.studykit.ui.study

import com.studykit.data.memory.Confidence
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.HalfLifeKernel
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.KernelState
import com.studykit.data.memory.SchedulingKernel
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class KernelWiringTest {
    @Test fun `kernelFor picks by settings id`() {
        assertEquals("FSRS", KernelHub.forId("FSRS").id)
        assertEquals("HALF_LIFE", KernelHub.forId("HALF_LIFE").id)
        assertEquals("FSRS", KernelHub.forId("garbage").id) // 坏值回默认，不抛
    }

    @Test fun `kernel state fields are read by their own kernel only`() {
        // FSRS 只认 stability（R(t=S)=0.9 锚点），HalfLife 只认 hDays（t=h 即 0.5）：
        // 互不冒领 —— 这条测试是 spec §2.1 "单活跃内核"的证人
        val w = KernelState(stability = 5.0, difficulty = 4.0,
            cardState = com.studykit.data.memory.CardState.REVIEW, hDays = 10.0)
        assertEquals(0.9, FsrsKernel().recall(w, elapsedDays = 5.0), 1e-9)
        assertEquals(0.5, HalfLifeKernel().recall(w, elapsedDays = 10.0), 1e-9)
    }
}
```

- [ ] **Step 2: 红。**
- [ ] **Step 3: 实现**

新文件：

```kotlin
package com.studykit.ui.study

import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.HalfLifeKernel
import com.studykit.data.memory.SchedulingKernel

/** 内核注册表：全仓唯一知道"有哪些内核、怎么按 id 取"的地方 */
object KernelHub {
    private val fsrs = FsrsKernel()
    private val halfLife = HalfLifeKernel()
    fun forId(id: String?): SchedulingKernel = if (id == "HALF_LIFE") halfLife else fsrs
}
```

`gradeCard`（:224-265）内核心段替换为（保留原注释、锚点、顺序纪律）：

```kotlin
            val kernel = KernelHub.forId(settingsFlow.value.schedulingKernel)
            val before = if (kernel.id == "HALF_LIFE") {
                KernelState(stability = word.fsrsStability, difficulty = word.difficulty,
                    cardState = CardState.REVIEW, hDays = word.halfLifeDays)
            } else {
                KernelState(stability = word.fsrsStability, difficulty = word.fsrsDifficulty
                    ?: (word.difficulty.coerceIn(1.0, 10.0)),
                    cardState = CardState.entries[word.fsrsState - 1], hDays = word.halfLifeDays)
            }
            val predicted = kernel.recall(before, gapDays)
            val rating = when (grade) {
                ReviewGrade.FORGET -> KernelRating.AGAIN
                ReviewGrade.VAGUE -> KernelRating.HARD
                ReviewGrade.RECALL -> KernelRating.GOOD
            }
            val after = kernel.review(before, gapDays, rating, conf = null) // 信心由 UI 层传入，见计划 B Task 11
            val days = kernel.nextIntervalDays(after, rating, sched.targetRecall, sched.maxIntervalDays)
            val status = when {
                grade == ReviewGrade.FORGET -> Word.STATUS_LEARNING
                (after.hDays ?: after.stability!! * FSRS_HALF_OVER_S) >= MASTERED_HALF_LIFE_DAYS -> Word.STATUS_MASTERED
                else -> Word.STATUS_LEARNING
            }
```

`wordRepository.applyReview(...)` 调用追加实参：`fsrsStability = after.stability, fsrsDifficulty = after.difficulty, fsrsState = after.cardState.ordinal + 1, kernel = kernel.id`，镜像半衰期 `halfLifeDays = after.hDays ?: word.halfLifeDays`（FSRS 路径时 after.hDays 已由内核换算填好）；`recordGradedReview(...)` 追加 `fsrsRating = rating.ordinal + 1`（confidence 参数在计划 B Task 11 接入时补，此处先传 null）。
`WordDao.applyReview` 的 UPDATE SQL 加 5 列赋值；`WordRepository` 两函数形参同步（默认值 null，保证既有调用点不破）。
`CardStudyScreen.GradeRow`（:984）preview 改走 `KernelHub.forId(settings.schedulingKernel)`：`recall/nextIntervalDays` 组合同样的 preview 三档；`predictedRecall` 用 `kernel.recall(...)`。**显示格式与"现在按下去，预计还记得 X%"一字不改。**
`TomorrowForecast.kt:78` 与 `ReminderWorker.kt:49`：逐词 R 从 `MemoryModel.recallProbability(gap, row.halfLifeDays)` 改为 `kernel.recall(kernelStateOf(row), gap)`（`kernelStateOf` = Word→KernelState 的小映射函数，放 `SchedulingKernel.kt` 文件尾，public）。两者读设置途径不同：TomorrowForecast 走参数注入（调用方 StudyViewModel 传 kernel），ReminderWorker 在 doWork 里 `settingsRepository` 现读（**Step 0 里确认它已有 repository 引用；没有就把 kernel 作为参数由调用方 ReminderScheduler 传入——不许在 worker 里 new AppDatabase**）。

- [ ] **Step 4: KernelWiringTest 绿；Step 5: 全量 `.\gradlew :app:testDebugUnitTest` 绿**（HalfLife 回归：`MemoryModelTest` 一字不改仍过——它就是这条承诺的证人）。
- [ ] **Step 6: 手工冒烟（构建产物，非截图任务）**：`.\gradlew :app:assembleDebug -PdemoSeed=true`；装到真机看词卡按钮预览仍有间隔数字、无崩溃（升级路径 Task 7 已测，这里只验运行）。

---

### Task 10: 计划 A 收尾核对

- [ ] **新增（T1 质量审查 I2）：CI 防快照漂移门**——`.github/workflows/ci.yml` 构建 job 里、跑单测之前加一步：`./gradlew :app:kspDebugKotlin` 后 `git diff --exit-code -- app/schemas`（改实体忘升 version 时静默污染 6.json 基线，这是唯一机械保证）；本地验收同步跑一次该命令序列。
- [ ] **覆盖面边界写进 CHANGELOG/README（I1）**：MigrationTestHelper 网只覆盖 6→7 及以后；1..5 无旧快照，那五条既有迁移仍只能靠真机走查。
- [ ] `.\gradlew :app:testDebugUnitTest :app:assembleDebug` 全绿。
- [ ] `git status`：只有预期文件变更（entities、AppDatabase、Settings、StudyViewModel、GradeRow 所在屏、Forecast/Worker、WordDao/Repository、schemas/6.json+7.json、新测试）；无 StudyKit 之外文件被碰。
- [ ] 用户界面**没有**新增可见交互（信心条等全部留给计划 B）——设 `scheduling_kernel` 默认 FSRS 后词卡按钮数字会变（预期行为），CHANGELOG 未写之前先不合并到主线宣称。
- [ ] 向用户复述状态。**提交已获用户授权（逐任务 commit 是本计划既定节奏）**，不再另问。
