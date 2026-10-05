package com.studykit.optimize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Task 2 单测：`D21` 前向模式自动微分的数值与梯度口径。
 *
 * 约定：梯度向量长 [D21.NP]（21），`D21.p(i, x)` 表示"第 i 个 FSRS 参数"，
 * 其对自身的偏导为 1、其余分量为 0；`D21.k(x)` 是常数，梯度全 0。
 * 幂运算的梯度用中心差分（步长 1e-5）对照，容差 1e-6。
 */
class Dual21Test {

    private val tol = 1e-9

    /** 断言梯度向量的每一个分量都等于期望值（长度必须为 NP；expected 只写非零前缀即可）。 */
    private fun assertGrad(label: String, actual: DoubleArray, expected: DoubleArray) {
        assertEquals("$label 梯度长度", D21.NP, actual.size)
        assertTrue("$label 期望梯度不得长于 NP", expected.size <= D21.NP)
        for (i in 0 until D21.NP) {
            val want = if (i < expected.size) expected[i] else 0.0
            assertEquals("$label g[$i]", want, actual[i], tol)
        }
    }

    private fun zeros(): DoubleArray = DoubleArray(D21.NP)

    /** 用 `p(0, ·)` 造一个单变量自变量。 */
    private fun x0(x: Double) = D21.p(0, x)

    // ── 构造子 ────────────────────────────────────────────────

    @Test fun `const carries value and zero gradient`() {
        val c = D21.k(2.5)
        assertEquals(2.5, c.v, 0.0)
        assertGrad("k(2.5)", c.g, zeros())
    }

    @Test fun `parameter slot i has unit gradient only in component i`() {
        for (i in 0 until D21.NP) {
            val p = D21.p(i, 0.25 + i)
            assertEquals("p($i).v", 0.25 + i, p.v, 0.0)
            val expected = zeros().also { it[i] = 1.0 }
            assertGrad("p($i)", p.g, expected)
        }
    }

    // ── 四则运算 ──────────────────────────────────────────────

    @Test fun `add keeps value and sums gradients`() {
        val a = D21.p(0, 3.0).shift(1.0)   // v=4, g[0]=1
        val b = D21.p(1, 5.0).scale(2.0)   // v=10, g[1]=2
        val s = a.add(b)
        assertEquals(14.0, s.v, tol)
        assertGrad("a+b", s.g, doubleArrayOf(1.0, 2.0))
    }

    @Test fun `sub keeps value and diffs gradients`() {
        val a = D21.p(0, 3.0).shift(1.0)
        val b = D21.p(1, 5.0).scale(2.0)
        val s = a.sub(b)
        assertEquals(-6.0, s.v, tol)
        assertGrad("a-b", s.g, doubleArrayOf(1.0, -2.0))
    }

    @Test fun `d of x times x over x is two x`() {
        val x = x0(2.7)
        val y = x.mul(x)
        assertEquals(2.7 * 2.7, y.v, tol)
        assertGrad("d(x·x)/dx", y.g, doubleArrayOf(2.0 * 2.7))
    }

    @Test fun `mul obeys product rule on two parameters`() {
        // u = 3·p0 + 1，v = 2·p1 + 5，在 p0=2、p1=4 处：
        // d(uv)/dp0 = u'v + uv' = 3·13 + 7·0 = 39；d(uv)/dp1 = 0·13 + 7·2 = 14
        val u = D21.p(0, 2.0).scale(3.0).shift(1.0)
        val v = D21.p(1, 4.0).scale(2.0).shift(5.0)
        val prod = u.mul(v)
        assertEquals(7.0 * 13.0, prod.v, tol)
        assertGrad("∂(uv)", prod.g, doubleArrayOf(39.0, 14.0))
    }

    @Test fun `div obeys quotient rule on two parameters`() {
        // u = 3·p0 + 1，v = 2·p1 + 5 同上；d(u/v)/dp0 = u'/v = 3/13；
        // d(u/v)/dp1 = −u·v'/v² = −7·2/169
        val u = D21.p(0, 2.0).scale(3.0).shift(1.0)
        val v = D21.p(1, 4.0).scale(2.0).shift(5.0)
        val q = u.div(v)
        assertEquals(7.0 / 13.0, q.v, tol)
        assertGrad(
            "∂(u/v)",
            q.g,
            doubleArrayOf(3.0 / 13.0, -7.0 * 2.0 / (13.0 * 13.0)),
        )
    }

    @Test fun `div by dual denominator matches analytic gradient`() {
        // f(x) = 1/(x+1)，x=p(0,2) → f=1/3，f'=−1/(x+1)²=−1/9
        val den = x0(2.0).shift(1.0)
        val f = D21.k(1.0).div(den)
        assertEquals(1.0 / 3.0, f.v, tol)
        assertGrad("∂(1/(x+1))", f.g, doubleArrayOf(-1.0 / 9.0))
    }

    // ── scale / shift / neg ───────────────────────────────────

    @Test fun `scale multiplies value and gradient`() {
        val y = D21.p(3, 1.5).scale(-2.0)
        assertEquals(-3.0, y.v, tol)
        val expected = zeros().also { it[3] = -2.0 }
        assertGrad("scale(−2)", y.g, expected)
    }

    @Test fun `shift moves value and leaves gradient`() {
        val y = D21.p(7, 1.5).shift(0.25)
        assertEquals(1.75, y.v, tol)
        val expected = zeros().also { it[7] = 1.0 }
        assertGrad("shift(0.25)", y.g, expected)
    }

    @Test fun `neg flips value and gradient`() {
        val y = D21.p(2, 1.5).scale(3.0).neg()
        assertEquals(-4.5, y.v, tol)
        val expected = zeros().also { it[2] = -3.0 }
        assertGrad("neg", y.g, expected)
    }

    // ── 初等函数 ──────────────────────────────────────────────

    @Test fun `d of exp equals exp`() {
        val x = x0(1.3)
        val y = x.exp()
        assertEquals(exp(1.3), y.v, tol)
        assertGrad("d(exp)/dx", y.g, doubleArrayOf(exp(1.3)))
    }

    @Test fun `d of ln equals one over x`() {
        val x = x0(0.7)
        val y = x.ln()
        assertEquals(ln(0.7), y.v, tol)
        assertGrad("d(ln)/dx", y.g, doubleArrayOf(1.0 / 0.7))
    }

    @Test fun `powk matches analytic derivative and numeric diff`() {
        val x = x0(1.7)
        val e = 2.3
        val y = x.powk(e)
        assertEquals(1.7.pow(e), y.v, tol)
        assertGrad("d(x^e)/dx", y.g, doubleArrayOf(e * 1.7.pow(e - 1.0)))

        val numeric = centralDiff(doubleArrayOf(1.7)) { ps -> ps[0].powk(e).v }
        for (i in 0 until D21.NP) {
            assertEquals("powk 数值对照 g[$i]", numeric[i], y.g[i], 1e-6)
        }
    }

    @Test fun `powv matches analytic derivative and numeric diff`() {
        // f(u,v) = u^v，u=p(0,2.0)、v=p(1,3.0)
        // ∂f/∂u = v·u^(v−1) = 3·4 = 12；∂f/∂v = u^v·ln u = 8·ln 2
        val u = D21.p(0, 2.0)
        val v = D21.p(1, 3.0)
        val y = u.powv(v)
        assertEquals(8.0, y.v, tol)
        assertGrad("∂(u^v)", y.g, doubleArrayOf(12.0, 8.0 * ln(2.0)))

        val numeric = centralDiff(doubleArrayOf(2.0, 3.0)) { ps -> ps[0].powv(ps[1]).v }
        for (i in 0 until D21.NP) {
            assertEquals("powv 数值对照 g[$i]", numeric[i], y.g[i], 1e-6)
        }
    }

    @Test fun `powv with variable base and exponent chains through ln`() {
        // f(x) = x^x，x=p(0,1.4) → f' = x^x·(ln x + 1)
        val x = x0(1.4)
        val y = x.powv(x)
        assertEquals(1.4.pow(1.4), y.v, tol)
        assertGrad("d(x^x)/dx", y.g, doubleArrayOf(1.4.pow(1.4) * (ln(1.4) + 1.0)))
    }

    // ── clamp ────────────────────────────────────────────────

    @Test fun `clamp below returns lower constant with zero gradient`() {
        val y = D21.p(4, 0.2).scale(3.0).clamp(1.0, 10.0)
        assertEquals(1.0, y.v, 0.0)
        assertGrad("clamp 下越界", y.g, zeros())
    }

    @Test fun `clamp above returns upper constant with zero gradient`() {
        val y = D21.p(4, 20.0).scale(3.0).clamp(1.0, 10.0)
        assertEquals(10.0, y.v, 0.0)
        assertGrad("clamp 上越界", y.g, zeros())
    }

    @Test fun `clamp inside keeps value and gradient`() {
        val y = D21.p(4, 5.0).scale(1.5).clamp(1.0, 10.0)
        assertEquals(7.5, y.v, 0.0)
        val expected = zeros().also { it[4] = 1.5 }
        assertGrad("clamp 区间内", y.g, expected)
    }

    @Test fun `clamp boundary values stay inside and keep gradient`() {
        val lo = x0(1.0).clamp(1.0, 10.0)
        assertEquals(1.0, lo.v, 0.0)
        assertGrad("clamp 下边界", lo.g, doubleArrayOf(1.0))

        val hi = x0(10.0).clamp(1.0, 10.0)
        assertEquals(10.0, hi.v, 0.0)
        assertGrad("clamp 上边界", hi.g, doubleArrayOf(1.0))
    }

    // ── 独立性：每次运算都新建梯度数组 ─────────────────────────

    @Test fun `every op returns a fresh dual with its own array`() {
        val a = x0(2.0)
        val b = x0(3.0)
        val ops = listOf(a.add(b), a.sub(b), a.mul(b), a.div(b), a.scale(2.0), a.shift(1.0), a.neg(), a.exp(), a.ln(), a.powk(2.0), a.powv(b), a.clamp(0.0, 9.0))
        for (y in ops) {
            assertNotSame("数组不得共享", a.g, y.g)
            assertEquals(D21.NP, y.g.size)
        }
        // 输入未被改写
        assertGrad("a 未被改写", a.g, doubleArrayOf(1.0))
        assertGrad("b 未被改写", b.g, doubleArrayOf(1.0))
    }

    @Test fun `gradient arrays from k and p are not shared between instances`() {
        val c1 = D21.k(1.0)
        val c2 = D21.k(1.0)
        assertNotSame(c1.g, c2.g)
        val p0 = D21.p(0, 1.0)
        val p1 = D21.p(1, 1.0)
        assertNotSame(p0.g, p1.g)
    }

    // ── 链式：复合表达式的梯度与数值对照 ───────────────────────

    @Test fun `chained expression gradient matches numeric derivative`() {
        // f(a,b) = exp(a·b) / ln(a+1) 在 a=1.2、b=0.8
        val valueFn: (Array<D21>) -> Double = { ps -> ps[0].mul(ps[1]).exp().div(ps[0].shift(1.0).ln()).v }
        val dual = run {
            val a = D21.p(0, 1.2)
            val b = D21.p(1, 0.8)
            a.mul(b).exp().div(a.shift(1.0).ln())
        }
        val numeric = centralDiff(doubleArrayOf(1.2, 0.8), eval = valueFn)
        for (i in 0 until D21.NP) {
            assertEquals("链式 g[$i]", numeric[i], dual.g[i], 1e-6)
        }
    }

    /** 中心差分：对 x0 每个分量求数值偏导（其余分量不变），未参与的分量恒为 0。 */
    private fun centralDiff(
        x0: DoubleArray,
        h: Double = 1e-5,
        eval: (Array<D21>) -> Double,
    ): DoubleArray {
        val out = zeros()
        for (i in x0.indices) {
            val plus = x0.copyOf().also { it[i] += h }
            val minus = x0.copyOf().also { it[i] -= h }
            val fp = eval(plus.mapIndexed { idx, v -> D21.p(idx, v) }.toTypedArray())
            val fm = eval(minus.mapIndexed { idx, v -> D21.p(idx, v) }.toTypedArray())
            out[i] = (fp - fm) / (2.0 * h)
        }
        return out
    }
}
