package com.studykit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题面层交错开关（[AppSettings.semanticInterleaveEnabled]，v3.0-C Task 6）的键值往返单测。
 *
 * 姿势照 [AppSettingsInterleavingTest] 抄，两处刻意不同：
 *  - **默认是关**（那一条是开）。判据是未校准的词面相似度阈值，默认打开等于静默改掉所有现有用户
 *    刷题的顺序而本人不知道为什么；首装一行都没写进库时，队列必须与今天逐元素相同。
 *  - **测试里的键名一律写字面量** `"semantic_interleave_enabled"`，不引用 `AppSettings.KEY_SEMANTIC_INTERLEAVE`。
 *    库里那一行是跨版本契约：常量本身写错时「常量对常量」的断言会跟着一起错，只有字面量钉得住。
 *
 * 每条用例标出它杀的是哪一刀变异（`M-n`，逐刀实跑记录在 `task-6-report.md`；标签按**实跑红的那几条**写，
 * 不是按推演 —— 推演错过一次：M-6 红的是 `写入 false` 那条，我原先记在 `写入 true` 名下）：
 *  - `写入 true 读回 true` 与 `写入 false 读回 false` **必须双向都在**：只留 true 那一半，读侧写死 true
 *    （M-2）就全类皆绿；只留 false 那一半，`toMap()` 漏写这一行（M-3）就没人管 —— v3.0-B 的 T2 栽过同一类
 *    「绿灯但没测到契约」。实跑对得上：M-3 红在 `写入 true` + `落库的键名`；M-2 红在 `默认关闭` + `脏值` +
 *    `写入 false` 三条；M-6（fromMap 读成邻座那条键）**只有** `写入 false 读回 false` 一条抓得到 ——
 *    删掉那一条，M-6 在全类皆绿。
 *  - `脏值退回默认而不是崩` 里 `"TRUE"` 那一格是本类唯一有牙齿的脏值：换成宽松写法 `toBoolean()`
 *    （M-5）时红就红在 `脏值 [TRUE]`（ `"1"`/`"yes"`/`""` 三格在 M-5 下照旧给 false，循环走到 TRUE 才断）。
 *    严格解析 `toBooleanStrictOrNull` 只认小写 `"true"`/`"false"`（实测记在报告），于是这一格把
 *    「严格」与「宽松」区分开来 —— 少这一格，M-5 就白过。
 *  - `落库的键名是字面量` 是键名拼写的**唯一**守卫：M-4（常量值改掉）时全类只有它红，因为读写两侧
 *    都引用同一个常量，别的用例怎么改常量都自洽 —— 只有字面量能钉住库里那一行跨版本的契约。
 *  - `不影响其它键的往返` 管的是加这一行时的抄串事故：新字段的值写进邻座那一条键（M-7）时，
 *    本项自己五条全绿，只有邻座的对账红。
 */
class AppSettingsSemanticInterleaveTest {

    private fun roundtrip(value: String?): AppSettings {
        val map = if (value == null) emptyMap() else mapOf("semantic_interleave_enabled" to value)
        return AppSettings.fromMap(map)
    }

    /** 杀 M-1（字段默认写成 true）、M-2（读侧写死 true） */
    @Test fun `默认关闭`() {
        assertFalse(AppSettings().semanticInterleaveEnabled)
        assertFalse(roundtrip(null).semanticInterleaveEnabled)
    }

    /** 杀 M-3（toMap 漏写这一行）—— 写侧丢了只有这一条分辨得出来 */
    @Test fun `写入 true 读回 true`() {
        val on = AppSettings().copy(semanticInterleaveEnabled = true)
        assertTrue(AppSettings.fromMap(on.toMap()).semanticInterleaveEnabled)
    }

    /** 杀 M-2（读侧写死 true）、M-6（fromMap 读成邻座那条键）—— 与上一条成对，缺一条就有一半的写死能绿 */
    @Test fun `写入 false 读回 false`() {
        // 双向都要钉：只测 true 的话，读侧写死 true 也能过（v3.0-B 的 T2 就栽过这一类）
        val off = AppSettings().copy(semanticInterleaveEnabled = false)
        assertFalse(AppSettings.fromMap(off.toMap()).semanticInterleaveEnabled)
    }

    /** 杀 M-5（严格解析换成宽松 `toBoolean()`）—— 牙齿在 "TRUE" 那一格 */
    @Test fun `脏值退回默认而不是崩`() {
        for (bad in listOf("1", "yes", "", "TRUE", "on", "  true ")) {
            val parsed = roundtrip(bad)
            assertFalse("脏值 [$bad] 不该被认成开: ${parsed.semanticInterleaveEnabled}", parsed.semanticInterleaveEnabled)
        }
    }

    /** 杀 M-4（常量键名改掉，全类唯一红的一条）、M-3（toMap 漏写）：落库那一行的键名字面量不随常量拼写漂移 */
    @Test fun `落库的键名是字面量`() {
        val map = AppSettings().copy(semanticInterleaveEnabled = true).toMap()
        assertEquals("true", map["semantic_interleave_enabled"])
    }

    /** 杀 M-7（新字段读写串到邻座键上）：其余每一项的往返一分不变 */
    @Test fun `不影响其它键的往返`() {
        val before = AppSettings()
        val after = AppSettings.fromMap(before.toMap())
        assertEquals(before.interleavingEnabled, after.interleavingEnabled)
        assertEquals(before.exams, after.exams)
        assertEquals(before.dailyWordGoal, after.dailyWordGoal)
    }
}
