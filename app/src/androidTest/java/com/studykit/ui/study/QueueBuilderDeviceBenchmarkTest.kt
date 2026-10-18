package com.studykit.ui.study

import android.util.Log
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 500 题三档（50 / 200 / 500）的**设备侧**耗时基准 —— 清单第 6 条「500 题三档设备基准」的窄版交付。
 *
 * 它做什么：把 [QueueBuilder.build] 在三档规模上各跑一遍，用 [measureTimeMillis] 量一次，
 * 往 stdout（同时打一条 logcat，tag 为 `DEVICE-BENCH`）打一行
 * `DEVICE-BENCH items=<n> ms=<...> lookups=<...>`，随后**只**断言正确性不变量。
 * 打印不是断言：毫秒数不参与任何比较 —— 设备档位、温度、后台进程都会改变它，写死时间就是假闸门。
 *
 * 与文档 §② 那条「500 due 构建 <50ms」的关系：用户/维护者可以拿 500 档那行的 `ms` 去对，
 * 但**本用例不代替那条门**。单次读数没有统计意义，也没有在受控环境下采过；
 * 它也不替代单测 `QueueBuilderStressTest.实测耗时如实打印（不当闸门）` —— 那条是 JVM 读数，
 * 这条是设备读数，两者都只打印。
 *
 * 夹具：`id / 10` 连号分簇、urgency 递减、subject 随簇 —— 与
 * `app/src/test/java/com/studykit/ui/study/QueueBuilderStressTest.kt` 里 `fiveHundred()` 那一档
 * **同形**（500 档逐项一致）。本文件自带一份、与单测那份**各自维护**：跨源集共享夹具是另一件事，
 * 不在本笔里做；那边改形状时这边要跟着改，反过来也一样。
 *
 * ⛔ 诚实声明：本文件从建立到现在**从未在任何设备或模拟器上执行过** —— 本机既没有设备也没有
 * 模拟器镜像（AVD 列表为空），CI 里也只有 `:app:assembleDebugAndroidTest` 这条「只编译」的闸。
 * 第一份真实读数必须在用户那台真机上跑 `.\gradlew.bat :app:connectedDebugAndroidTest` 才能取得。
 */
class QueueBuilderDeviceBenchmarkTest {

    private val policy = LexicalInterleave.DEFAULT

    /**
     * 与单测 `QueueBuilderStressTest` 里那份 `FixtureSim` 同形的替身：
     * 同簇 `0.9f`（> `tauHi` ⇒ 相邻违规）、异簇 `0f`（< `tauLo` ⇒ 可换位/可当缓冲），
     * 每次 [sim] 记一笔读数。`lookups` 就是打印行里那个数。
     */
    private class BenchSim(private val cluster: Map<Long, Int>) : Simularity {
        var lookups: Int = 0
            private set

        override fun sim(a: Long, b: Long): Float {
            lookups++
            if (a == b) return 1f
            val ca = cluster[a] ?: return 0f
            val cb = cluster[b] ?: return 0f
            return if (ca == cb) 0.9f else 0f
        }
    }

    /**
     * `id / 10` 连号分簇；urgency 递减 ⇒ `build` 的排序键还原成 id 升序。
     * 单测 `QueueBuilderStressTest.itemsOf(10)` 的同一形状，这里按档位参数化到 n 条。
     */
    private fun fixture(n: Int): Pair<List<QueueItem>, BenchSim> {
        require(n >= 2) { "n=$n 连一对相邻都凑不出来，量不了相邻约束" }
        val items = (1L..n.toLong()).map { id ->
            QueueItem(id, (n + 1 - id).toFloat(), subject = "C${id / 10}")
        }
        return items to BenchSim(items.associate { it.id to (it.id / 10).toInt() })
    }

    /** 夹具自己的"像不像"：同簇号即同簇。断言侧不经过 [BenchSim]，免得把读数一起改掉。 */
    private fun sameCluster(a: QueueItem, b: QueueItem): Boolean = a.id / 10 == b.id / 10

    /**
     * 三档各跑一次并打印，断言只有正确性（不丢/不重/不改字段/读数自洽/确定性）。
     * 没有一条断言读 `ms`。
     */
    @Test
    fun `三档规模只打印耗时并钉住正确性`() {
        for (n in listOf(50, 200, 500)) {
            // 预热一趟：让类加载与 JIT 不落进被计量的那一趟。预热读数不打印、不参与断言。
            val (warmItems, warmSim) = fixture(n)
            QueueBuilder.build(warmItems, warmSim, emptyList(), emptyMap(), policy)

            val (items, sim) = fixture(n)
            lateinit var plan: QueuePlan
            val ms = measureTimeMillis {
                plan = QueueBuilder.build(items, sim, emptyList(), emptyMap(), policy)
            }
            val line = "DEVICE-BENCH items=$n ms=$ms lookups=${sim.lookups}"
            println(line)
            Log.i("DEVICE-BENCH", line)

            // ── 正确性不变量（以下没有一行比较毫秒）───────────────────────────
            assertEquals("队列条数必须等于输入条数", n, plan.queue.size)
            assertEquals("不丢：id 集合与输入逐项相同", items.map { it.id }.toSet(), plan.queue.map { it.id }.toSet())
            assertEquals("不重：条数与去重后条数相同", plan.queue.size, plan.queue.map { it.id }.distinct().size)
            // 每一项都还是原夹具那一条（没被复制、没被改 subject）
            assertEquals(
                "队列里的每一项都必须是原夹具的同一份 (id, subject)",
                items.map { it.id to it.subject }.toSet(),
                plan.queue.map { it.id to it.subject }.toSet(),
            )
            assertEquals("空池 ⇒ 一张缓冲卡都不该被拉进来", 0, plan.pulled)
            assertTrue("空混淆映射 ⇒ 不该记下任何 drill 对", plan.drillPairs.isEmpty())
            assertTrue("替身的计数必须真的被走到（否则打印的 lookups 是假读数）", sim.lookups > 0)

            // report 的两个读数必须与夹具自己的相似度定义自洽：同簇 0.9、异簇 0。
            val expectedAdj = plan.queue.zipWithNext { a, b -> if (sameCluster(a, b)) 0.9f else 0f }
            val expectedMax = expectedAdj.maxOrNull()
            assertNotNull("队列至少两条，最大值必须存在", expectedMax)
            assertEquals(
                "maxAdjSim 必须等于最终队列相邻对的最大值",
                expectedMax!!,
                plan.maxAdjSim!!,
                0f,
            )
            val expectedUnresolved = expectedAdj.indices.filter { expectedAdj[it] > policy.tauHi }
            assertEquals(
                "unresolved 必须恰好是最终队列里仍超 tauHi 的相邻位置",
                expectedUnresolved,
                plan.unresolved,
            )

            // 同样的逻辑输入再来一遍（全新替身）：结果必须逐元素相同 ⇒ 构建是确定的。
            val (itemsAgain, simAgain) = fixture(n)
            val again = QueueBuilder.build(itemsAgain, simAgain, emptyList(), emptyMap(), policy)
            assertEquals("两次构建的 id 序列必须逐元素相同", plan.queue.map { it.id }, again.queue.map { it.id })
            assertEquals("两次构建的 pulled 必须相同", plan.pulled, again.pulled)
            assertEquals("两次构建的 maxAdjSim 必须相同", plan.maxAdjSim!!, again.maxAdjSim!!, 0f)
            assertEquals("两次构建的 unresolved 必须相同", plan.unresolved, again.unresolved)
            assertEquals("两次构建的 drillPairs 必须相同", plan.drillPairs, again.drillPairs)
        }
    }
}
