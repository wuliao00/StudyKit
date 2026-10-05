package com.studykit.data.memory

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9/R2 口径一致**硬闸**：Rust `fsrsjni` 的 v6 前向 ≡ Kotlin [FsrsModel]。
 *
 * 期望值**不是**这里算出来的，而是 Rust 侧纯 Double 参考实现产出的夹具——
 * 由 [fsrsjni/src/core.rs] 的 `recall_v / next_s_ok_v / next_s_lapse_v / next_s_short_v /
 * next_d_v / d0_raw_v / d0_v / s0_idx` 逐条算出，落成 JSON 提交进仓：
 *
 *     cd fsrsjni
 *     cargo run --bin gen_fixture
 *     copy fixtures\forward_parity.json ..\app\src\test\resources\fsrs_forward_parity.json
 *
 * 改完任何一侧的公式，必须重跑上面两条命令再同步夹具，否则这里会红。
 * 期望值若改成用 Kotlin 自己反推，闸门就变成自我循环论证，等于没有。
 *
 * 为什么不走"Robolectric 加载 .so 调 nativeEvaluate"那条路：`libfsrsjni.so` 只有
 * arm64-v8a / armeabi-v7a 两份（CI 唯一的执行器 `testDebugUnitTest` 跑在 x86 JVM 上，
 * 既装不进 arm 库，仓里也没有 host 版 .so），所以设备侧的 JNI 链路留给 T13 真机验证，
 * **公式口径**由这份夹具在 CI 里钉死。
 *
 * 容差 1e-6：两侧分别是 Rust `powf/exp` 与 JVM `Math.pow/exp`（同一量级的 libm 实现），
 * 差在 1~2 ulp 级；相对误差到绝对误差最坏落在 1e-11 量级，1e-6 既留得出余量又能抓住真实分叉
 * （比如把 DECAY 写死成 v5 的 −0.5、或 lapse 支漏了 `min(长期, S/e^(w17·w18))` 的短上限）。
 */
class FsrsModelParityTest {

    private fun fixture(): JSONArray {
        CACHED?.let { return it }
        // 优先按**绝对资源路径**从类的 classpath 取（`app/src/test/resources/` 会被 AGP 并进
        // 单元测试的 runtime classpath）；万一某天 packaging 变了，再退回 classLoader 形式。
        val stream = javaClass.getResourceAsStream("/$RESOURCE")
            ?: javaClass.classLoader?.getResourceAsStream(RESOURCE)
            ?: error(
                "测试资源缺失：$RESOURCE。它必须由 Rust 生成后再提交：" +
                    "`cd fsrsjni; cargo run --bin gen_fixture`，" +
                    "然后把 fsrsjni/fixtures/forward_parity.json 复制为 " +
                    "app/src/test/resources/$RESOURCE。",
            )
        val text = stream.use { it.readBytes().decodeToString() }
        return JSONArray(text).also { CACHED = it }
    }

    private fun caseAt(i: Int): ParsedCase {
        val o = fixture().getJSONObject(i)
        val wArr = o.getJSONArray("w")
        assertEquals("用例 $i 的参数向量长度必须是 21（v6）", FsrsModel.NP, wArr.length())
        val w = DoubleArray(FsrsModel.NP) { wArr.getDouble(it) }
        return ParsedCase(
            index = i,
            note = o.optString("note"),
            w = w,
            s = o.getDouble("s"),
            d = o.getDouble("d"),
            t = o.getDouble("t"),
            rating = o.getInt("rating"),
            expect = o.getJSONObject("expect"),
        )
    }

    private fun cases(): List<ParsedCase> = List(fixture().length()) { caseAt(it) }

    // ==== 夹具本身：条数、覆盖面、以及"期望值确实来自 Rust 默认参数" ====

    @Test fun `fixture has at least 30 cases`() {
        assertTrue("夹具条数=${fixture().length()}，太少不足以钉死口径", fixture().length() >= 30)
    }

    @Test fun `fixture covers every rating and both elapsed branches`() {
        val cs = cases()
        assertEquals(
            "四档评分都要有用例",
            setOf(1, 2, 3, 4),
            cs.map { it.rating }.toSet(),
        )
        assertTrue("缺 elapsed<1 的短期支用例", cs.any { it.t < 1.0 })
        assertTrue("缺 elapsed>=1 的长期支用例", cs.any { it.t >= 1.0 })
        assertTrue("缺 decay(w[20]) 非默认的变体用例", cs.any { it.w[20] != FsrsModel.DEFAULT_PARAMS[20] })
    }

    @Test fun `rust default params equal kotlin default params`() {
        // 夹具的 V0 组由 Rust 的 DEFAULT_PARAMS 常量生成；两侧基线参数本身也必须逐值相等，
        // 否则"公式一致"会在一个错的基线上成立。
        val v0 = cases().first { it.note.startsWith("V0") }
        assertEquals(
            "Rust core::DEFAULT_PARAMS ≡ FsrsModel.DEFAULT_PARAMS",
            FsrsModel.DEFAULT_PARAMS.toList(),
            v0.w.toList(),
        )
    }

    // ==== 逐式对拍：每个纯函数一个用例方法，失败时直接点出是哪条公式分叉 ====

    @Test fun `recall equals rust`() {
        for (c in cases()) {
            assertEquals("用例 ${c.index}（${c.note}）recall", c.expect("recall"), FsrsModel.recall(c.w, c.s, c.t), TOL)
        }
    }

    @Test fun `recallStability equals rust`() {
        for (c in cases()) {
            // rr 取夹具里的 Rust recall，把"公式差异"与"上游误差传播"分开
            val got = FsrsModel.recallStability(c.w, c.s, c.d, c.expect("recall"), c.rating)
            assertEquals("用例 ${c.index}（${c.note}）nextS_ok", c.expect("nextS_ok"), got, TOL)
        }
    }

    @Test fun `forgetStability equals rust`() {
        for (c in cases()) {
            val got = FsrsModel.forgetStability(c.w, c.s, c.d, c.expect("recall"))
            assertEquals("用例 ${c.index}（${c.note}）nextS_lapse", c.expect("nextS_lapse"), got, TOL)
        }
    }

    @Test fun `shortTermStability equals rust`() {
        for (c in cases()) {
            val got = FsrsModel.shortTermStability(c.w, c.s, c.rating.toDouble())
            assertEquals("用例 ${c.index}（${c.note}）nextS_short", c.expect("nextS_short"), got, TOL)
        }
    }

    @Test fun `nextDifficulty equals rust`() {
        for (c in cases()) {
            val got = FsrsModel.nextDifficulty(c.w, c.d, c.rating.toDouble())
            assertEquals("用例 ${c.index}（${c.note}）nextD", c.expect("nextD"), got, TOL)
        }
    }

    @Test fun `initDifficultyRaw equals rust`() {
        // nextDifficulty 的均值回归目标用的是**未夹取**的 D0(Easy)，这一支单独钉，
        // 免得哪天有人把夹取版塞进 nextDifficulty 而长期/难度测试仍恰好通过。
        for (c in cases()) {
            val got = FsrsModel.initDifficultyRaw(c.w, c.rating.toDouble())
            assertEquals("用例 ${c.index}（${c.note}）d0raw", c.expect("d0raw"), got, TOL)
        }
    }

    @Test fun `initDifficulty equals rust`() {
        for (c in cases()) {
            val got = FsrsModel.initDifficulty(c.w, c.rating.toDouble())
            assertEquals("用例 ${c.index}（${c.note}）d0", c.expect("d0"), got, TOL)
        }
    }

    @Test fun `initStability equals rust`() {
        for (c in cases()) {
            val got = FsrsModel.initStability(c.w, c.rating)
            assertEquals("用例 ${c.index}（${c.note}）s0", c.expect("s0"), got, TOL)
        }
    }

    /** 解析后的用例（`expect` 保留原始 JSON 子对象，按需取键，缺键立刻是 NaN 而断言会红）。 */
    private class ParsedCase(
        val index: Int,
        val note: String,
        val w: DoubleArray,
        val s: Double,
        val d: Double,
        val t: Double,
        val rating: Int,
        val expect: org.json.JSONObject,
    ) {
        fun expect(key: String): Double {
            val x = expect.optDouble(key, Double.NaN)
            assertTrue("用例 $index 缺 expect.$key（夹具格式与生成器不一致？）", !x.isNaN())
            return x
        }
    }

    companion object {
        private const val RESOURCE = "fsrs_forward_parity.json"
        private const val TOL = 1e-6

        /** 夹具只读一次（40 条用例 × 8 个断言，没必要反复解析 JSON）。 */
        @Volatile
        private var CACHED: JSONArray? = null
    }
}
