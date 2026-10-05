package com.studykit.optimize

import kotlin.math.exp
import kotlin.math.ln

/**
 * FSRS v6 参数向量用的**前向模式自动微分数**（21 维梯度）。
 *
 * v2.9 的端侧优化器要对 21 个参数求 BCE 损失的梯度。损失要对每条复习记录再对全部 21 参求导，
 * 数值差分要 21 次前向、反向模式要维护计算图；前向模式一次前向就能同时得到**所有** 21 个偏导
 * （本仓核函数是短链条初等运算，正向传播成本可忽略），与 AdamW 训练循环天然契合。
 *
 * 约定：
 * - [v] 是函数值，[g] 是它对 21 个自变量的梯度向量（长度恒为 [NP]）。
 * - 自变量只能经 [p] 进入表达式（第 i 个位置的偏导基），常数经 [k] 进入。
 * - **每个运算都返回全新的 [D21]，携带全新的 21 长数组**，绝不复用/改写操作数的数组，
 *   因此表达式可安全地复用同一个中间量（如 `x.mul(x)`、`x.powv(x)`）。
 * - [clamp] 在越界处按"常数段"处理：返回夹取后的常数、梯度置 0（与推理侧
 *   `coerceIn` 的实际行为一致——越界输出不再随参数变化）。
 *
 * 对数/幂运算假定值为正域（FSRS 的 S、D、稳定性增量均落在正域）；非正域会给出 NaN/Inf，
 * 由上层训练器的 NaN 守卫负责拦截，不在这里做隐式兜底。
 */
class D21(val v: Double, val g: DoubleArray) {

    init {
        require(g.size == NP) { "梯度向量长度必须为 $NP，实际 ${g.size}" }
    }

    // ── 四则运算 ──────────────────────────────────────────────

    /** (a+b)' = a' + b' */
    fun add(o: D21): D21 = combine(v + o.v) { i -> g[i] + o.g[i] }

    /** (a−b)' = a' − b' */
    fun sub(o: D21): D21 = combine(v - o.v) { i -> g[i] - o.g[i] }

    /** 乘法规则：(uv)' = u'v + uv' */
    fun mul(o: D21): D21 = combine(v * o.v) { i -> g[i] * o.v + o.g[i] * v }

    /** 除法规则：(u/v)' = (u'v − uv')/v² */
    fun div(o: D21): D21 = combine(v / o.v) { i -> (g[i] * o.v - o.g[i] * v) / (o.v * o.v) }

    /** 乘常数：(c·u)' = c·u' */
    fun scale(c: Double): D21 = combine(v * c) { i -> g[i] * c }

    /** 加常数：(u+c)' = u'（梯度原样搬运，但仍是新数组） */
    fun shift(c: Double): D21 = combine(v + c) { i -> g[i] }

    /** 取负：(−u)' = −u' */
    fun neg(): D21 = combine(-v) { i -> -g[i] }

    // ── 初等函数 ──────────────────────────────────────────────

    /** d/du eᵘ = eᵘ·u' */
    fun exp(): D21 = combine(exp(v)) { i -> g[i] * exp(v) }

    /** d/du ln u = u'/u */
    fun ln(): D21 = combine(ln(v)) { i -> g[i] / v }

    /** 常数指数：按计划口径实现为 `uᵉ = e^(e·ln u)`，梯度即 e·u^(e−1)·u' */
    fun powk(e: Double): D21 = ln().scale(e).exp()

    /** 变量指数：按计划口径实现为 `fᵍ = e^(g·ln f)`，梯度 fᵍ·(g'·ln f + g·f'/f) */
    fun powv(e: D21): D21 = e.mul(ln()).exp()

    // ── 夹取 ─────────────────────────────────────────────────

    /**
     * 区间内原样搬运值与梯度；**越界返回常数 [k]（值夹到边界、梯度全 0）**，
     * 与 [com.studykit.data.memory.FsrsModel] 里 `coerceIn`/`coerceAtLeast` 的取值口径一致。
     */
    fun clamp(lo: Double, hi: Double): D21 = when {
        v < lo -> k(lo)
        v > hi -> k(hi)
        else -> D21(v, g.copyOf())
    }

    // ── 内部工具 ─────────────────────────────────────────────

    /** 以 [value] 为函数值、按 [gradient] 逐下标生成一份**全新**的 21 长梯度数组。 */
    private fun combine(value: Double, gradient: (Int) -> Double): D21 =
        D21(value, DoubleArray(NP) { i -> gradient(i) })

    companion object {
        /** FSRS v6 参数维度（梯度向量长度）。 */
        const val NP = 21

        /** 常数：值为 [x]，梯度全 0（每次新建数组）。 */
        fun k(x: Double): D21 = D21(x, DoubleArray(NP))

        /** 第 [i] 个自变量：值为 [x]，只有第 i 个偏导为 1、其余为 0。 */
        fun p(i: Int, x: Double): D21 {
            require(i in 0 until NP) { "参数下标必须在 0..${NP - 1}，实际 $i" }
            val g = DoubleArray(NP)
            g[i] = 1.0
            return D21(x, g)
        }
    }
}
