package com.studykit.optimize

import com.studykit.data.entity.ReviewLog

/**
 * [LogPacker.pack] 的产物：喂给 Rust JNI（[FsrsNative.nativeTrain] / [FsrsNative.nativeEvaluate]）的
 * 两段已清洗、已分组、已打包的时序数据。
 *
 * 契约完全对齐 `fsrsjni/src/lib.rs` 的 `parse()`：
 * - `*Reviews`：`FloatArray`，每条 review 4 个 float，顺序 `[rating, elapsedDays, scheduledDays, state]`
 *   （lib.rs 里即 `c[0]=rating, c[1]=elapsed, c[2]=sched, c[3]=state`）；
 * - `*Bounds`：`IntArray` 前缀和，**单位是 review 条数**（不是 float 下标），`bounds[i+1]-bounds[i]`
 *   即第 i 卡的 review 数，`bounds.size == 卡数 + 1`；
 * - items 按「首评时间」升序，train 取前段、val 取后段，**整卡归边不跨切分点**（防数据泄漏）。
 *
 * `nTrainTerms` / `nValTerms`：各自侧「能产生损失的 review 数」= Σ(组内条数 - 1)
 * （每卡首评只落 S0/D0、不计损失）。JNI 侧 `nativeTrain` 也按同式回算 n_terms。
 */
data class Packed(
    val trainReviews: FloatArray,
    val trainBounds: IntArray,
    val valReviews: FloatArray,
    val valBounds: IntArray,
    val nTrainTerms: Int,
    val nValTerms: Int,
)

/**
 * 阶段 1：把原始 `review_log` 行清洗、分组、打包成 JNI 可消费的 FloatArray+IntArray，并做时序切分。
 *
 * 纯函数、确定性：同样的输入恒得同样的输出（排序键、切分公式都写死，不依赖迭代顺序或时间）。
 * 清洗规则逐条对齐开发文档 §②「清洗规则表」，并按真实的 [ReviewLog] 字段做了映射（见 [clean] 注释）。
 */
object LogPacker {

    /** 同卡两条 review 间隔小于此毫秒数判为误触连点，只留第一条。 */
    const val DUP_WINDOW_MS = 1_000L

    /** 单条作答耗时上限；超过判为中断/挂机。（文档给了"降权或剔除"两选，本实现选剔除。） */
    const val MAX_DURATION_MS = 60_000L

    /** val 占比；按首评时间升序后取末尾这么多比例的**整卡**做验证集。 */
    const val VAL_FRACTION = 0.20

    /**
     * `scheduled_days` 一列可信的下界版本 —— CHANGELOG 那句「消费侧只可信任 `logSchemaVer >= 2`」
     * 由这里落到代码（此前只写在注释里，无人执行）。
     *
     * 那一列从 [ReviewLog.CURRENT_SCHEMA_VER]=2 起才是后向口径；v1 老行是混合口径（词侧记的是
     * 「这次刚排出去的间隔」，见 [ReviewLog.scheduledDays] 的 KDoc），与同一行的 `elapsed_days`
     * 配不成一对，而 JNI 契约（`Rev.sched`）交的就是这一对。所以 v1 行**整行**不进语料。
     * 代价如实登记在 CHANGELOG：v2.9 就在用的设备那批 v1 记录缺席校准，`FsrsGate` 的 term 门槛
     * 更晚过；今天的 Rust 侧还不调度这一列（`fsrsjni/src/lib.rs` 顶部注释），这一步买的是
     * 「以后真读时不踩」，不是当下的精度。
     */
    const val TRUSTED_SCHEDULED_DAYS_MIN_VER = 2

    private const val RATING_MIN = 1
    private const val RATING_MAX = 4

    /** 清洗后、打包前的一条 review（正好对应 JNI 的 4 float）。 */
    data class Rev(
        val rating: Int,
        val elapsedDays: Float,
        val scheduledDays: Float,
        val state: Int,
    )

    /** 一张卡打包前的视图：按 ts 升序的 reviews + 分组键与首评时间。 */
    data class Item(
        val cardKind: String,
        val cardId: Long,
        val firstTs: Long,
        val reviews: List<Rev>,
    )

    /**
     * 应用清洗规则，返回按 `(cardKind, cardId, ts)` 升序的保留行（**不**做分组与短卡丢弃——
     * 那属于 [items]，让两步都可被单测单独钉）。真实 [ReviewLog] 字段与文档规则表的映射：
     *
     * - **makeup**：`isMakeup != 0` → 剔除（补卡不反映真实记忆）；
     * - **schema 版本**：`logSchemaVer <` [TRUSTED_SCHEDULED_DAYS_MIN_VER] → **整行**剔除
     *   （v1 老行的 `scheduled_days` 是混合口径，见那条常量注释；这是 Kotlin 侧的读过滤，
     *   不动 Room schema、无迁移）；
     * - **duration**：`durationMs > MAX_DURATION_MS` → 剔除（中断/挂机。文档允许"降权或剔除"，此处**选剔除**并在常量注释说明）；
     * - **连点去重**：同 `(cardKind, cardId)` 内、与上一条**已保留**行间隔 `< DUP_WINDOW_MS` → 丢弃后续（保留第一条）；
     * - **rating**：不在 1..4 → 夹断到边界（`copy(rating=…)` 修正后入列）；
     * - **system 动作**：本表仅由评分点写入（Task 6 那四处 + v3.0-B T12 的套卷交卷，共五处），
     *   天然不含系统占位评分，无需过滤（文档 flags.bit0 在此无对应列）；
     * - **imported**：无对应列，按文档"保留"处理（ts 精度只影响 elapsed 取整，可接受）。
     *
     * 注意过滤次序：makeup/duration 先于去重，故被剔除的行不会成为去重锚点。
     */
    fun clean(rows: List<ReviewLog>): List<ReviewLog> {
        val kept = ArrayList<ReviewLog>(rows.size)
        // 每张"卡"最近一次保留行的 ts，用于连点判定；键含 cardKind，避免同名不同 kind 互相误伤
        val lastKeptTs = HashMap<Pair<String, Long>, Long>()
        val sorted = rows.sortedWith(compareBy({ it.cardKind }, { it.cardId }, { it.ts }))
        for (l in sorted) {
            if (l.logSchemaVer < TRUSTED_SCHEDULED_DAYS_MIN_VER) continue
            if (l.isMakeup != 0) continue
            if (l.durationMs > MAX_DURATION_MS) continue
            val key = l.cardKind to l.cardId
            val prev = lastKeptTs[key]
            if (prev != null && l.ts - prev < DUP_WINDOW_MS) continue
            lastKeptTs[key] = l.ts
            val clamped = l.rating.coerceIn(RATING_MIN, RATING_MAX)
            kept += if (clamped == l.rating) l else l.copy(rating = clamped)
        }
        return kept
    }

    /**
     * 分组 → 组内 ts 升序 → 丢弃 review 数 < 2 的整卡（首评外无损失项）→ 按首评 ts 升序。
     * 升序 items 是时序切分的前提（lib.rs 顶部注释要求 items 按首评时间升序传入）。
     * 入参应是 [clean] 的输出；对任意顺序的清洗结果都能产出确定性的排序结果。
     */
    fun items(cleaned: List<ReviewLog>): List<Item> =
        cleaned
            .groupBy { it.cardKind to it.cardId }
            .mapNotNull { (key, g) ->
                if (g.size < 2) return@mapNotNull null
                val ordered = g.sortedBy { it.ts }
                Item(
                    cardKind = key.first,
                    cardId = key.second,
                    firstTs = ordered.first().ts,
                    reviews = ordered.map {
                        Rev(it.rating, it.elapsedDays.toFloat(), it.scheduledDays.toFloat(), it.state)
                    },
                )
            }
            // 首评时间为主键，卡键为次序键，保证同 firstTs 时仍确定性
            .sortedWith(compareBy({ it.firstTs }, { it.cardKind }, { it.cardId }))

    /**
     * 端到端：清洗 → 分组打包 → 时序切分。切分点 `cut = floor(n × (1 - VAL_FRACTION))`，
     * `[0, cut)` 卡进 train、`[cut, n)` 卡进 val，切分只落在卡与卡之间，任何卡都不会被劈开。
     */
    fun pack(rows: List<ReviewLog>): Packed {
        val its = items(clean(rows))
        val cut = (its.size * (1.0 - VAL_FRACTION)).toInt().coerceIn(0, its.size)
        val (trainReviews, trainBounds, trainTerms) = flat(its.subList(0, cut))
        val (valReviews, valBounds, valTerms) = flat(its.subList(cut, its.size))
        return Packed(trainReviews, trainBounds, valReviews, valBounds, trainTerms, valTerms)
    }

    /** 把若干 items 摊平成 (reviews, bounds, terms)；空输入得到 `FloatArray(0)` 与 `IntArray(1){0}`。 */
    private fun flat(sub: List<Item>): Triple<FloatArray, IntArray, Int> {
        val totalReviews = sub.sumOf { it.reviews.size }
        val reviews = FloatArray(totalReviews * 4)
        val bounds = IntArray(sub.size + 1)
        var cursor = 0 // float 游标
        var count = 0 // review 计数游标（bounds 以此为单位）
        var terms = 0
        sub.forEachIndexed { i, item ->
            bounds[i] = count
            for (r in item.reviews) {
                reviews[cursor] = r.rating.toFloat()
                reviews[cursor + 1] = r.elapsedDays
                reviews[cursor + 2] = r.scheduledDays
                reviews[cursor + 3] = r.state.toFloat()
                cursor += 4
                count += 1
            }
            terms += item.reviews.size - 1
        }
        bounds[sub.size] = count
        return Triple(reviews, bounds, terms)
    }
}
