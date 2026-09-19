package com.studykit.ui.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 书脊色位（[spineColorIndex]）与书脊色板（[SpinePalette]）的纯逻辑单元测试：
 * 喂进去的是书名字符串，吐出来的是色板下标，不碰 Android / Compose 运行时，也不读时钟与随机数。
 *
 * 契约三件事：
 * 1. **稳定**：同一 seed 在任何一次运行、任何一台设备上都给同一个下标。
 *    `String.hashCode()`（Kotlin/JVM 即 `java.lang.String.hashCode`）是
 *    `s[0]*31^(n-1) + s[1]*31^(n-2) + … + s[n-1]` 的 int 多项式，**算法写进规范、不掺进程种子**
 *    （不同于 Python/Ruby 那种 per-process salted hash），ART 与 HotSpot 同实现，
 *    所以「一本书的书脊色」在重启、升级、换机后都不会变。下面那条钉死具体值的用例就是这条承诺的哨兵。
 * 2. **非负有界**：返回值 ∈ `0 until paletteSize`。长书名会因 int 溢出得到负 hashCode
 *    （`seed-0` = `-906232876`，1000 个样本里约 1% 为负），双重取模把它折回合法区间；
 *    这也避开了 `abs(hashCode()) % n` 那个老坑 —— `Int.MIN_VALUE` 取绝对值仍为负。
 * 3. **色板容量**：`SpinePalette.size == 8` 是本任务的对外承诺（书架卡按它取模），
 *    且 8 色互不重复 —— 加色会挪动既有书名之外的色位，故用测试锁住数量。
 */
class BookSpineTest {

    // ── brief 基例 ───────────────────────────────────────────────────────

    @Test fun `同名恒定同色`() {
        assertEquals(spineColorIndex("三体", 8), spineColorIndex("三体", 8))
    }

    @Test fun `结果为合法非负有界索引`() {
        listOf("三体", "人类简史", "", "☃", "a".repeat(500)).forEach {
            val i = spineColorIndex(it, 8)
            assertTrue(i in 0 until 8)
        }
    }

    @Test fun `任意样本均落在有界非负索引`() {
        val samples = (0..999).map { "seed-$it" } + listOf("", "☃", "a".repeat(500))
        samples.forEach { i ->
            assertTrue(spineColorIndex(i, 8) in 0 until 8)
        }
    }

    // ── 跨运行稳定性：钉死具体色位 ────────────────────────────────────────

    /**
     * 具体数值哨兵：换算法（例如改成 `murmur3` 或改用 `book.id` 之外再加盐）会让**每一本书的书脊
     * 同时换色**，那是用户能立刻看见的回退，所以这里把 8 个真实样本的下标写死。
     * 数值由 `java.lang.String.hashCode` 的多项式定义算出（非本机观测），JVM / ART 同结果。
     */
    @Test fun `具体书名的色位跨版本恒定`() {
        assertEquals(2, spineColorIndex("三体", 8))
        assertEquals(3, spineColorIndex("人类简史", 8))
        assertEquals(0, spineColorIndex("", 8))                       // 空书名（尚未填标题）
        assertEquals(3, spineColorIndex("☃", 8))                       // 单字符 BMP
        assertEquals(0, spineColorIndex("a".repeat(500), 8))           // 溢出后仍为正
        assertEquals(4, spineColorIndex("seed-0", 8))                  // hashCode = -906232876 → 4，不是 -4
        assertEquals(0, spineColorIndex("seed-4", 8))                  // hashCode 为负且整除 8 → 0
        assertEquals(4, spineColorIndex("人类简史人类简史人类简史人类简史", 8))
    }

    /** 负 hashCode 那一支必须单独走一遍：`-906232876 % 8 == -4`，双重取模后才折成 4。 */
    @Test fun `溢出为负的书名仍落在非负有界索引`() {
        listOf(
            "seed-0", "seed-1", "seed-2", "seed-3", "seed-4",
            "seed-5", "seed-6", "seed-7", "seed-8", "seed-9",
            "人类简史人类简史人类简史人类简史",
        ).forEach {
            val i = spineColorIndex(it, 8)
            assertTrue("$it 拿到越界下标 $i", i in 0 until 8)
        }
    }

    // ── 散列质量与色板本身 ───────────────────────────────────────────────

    /** 不退化：一千个书名要铺满八个色位（若实现写成 `length % n` 之类，这条会先炸）。 */
    @Test fun `一千个书名铺满八个色位`() {
        val buckets = (0..999).map { spineColorIndex("seed-$it", 8) }.toSet()
        assertEquals((0 until 8).toSet(), buckets)
    }

    /** 按真实色板大小取模（页面里的实际调用形态）：201 次查表都要拿得到颜色。 */
    @Test fun `按色板真实大小取模永不越界`() {
        val picked = (0..200).map { SpinePalette[spineColorIndex("书名 $it", SpinePalette.size)] }
        assertEquals(201, picked.size)
    }

    @Test fun `色板是八个互不重复的书脊色`() {
        assertEquals(8, SpinePalette.size)
        assertEquals(8, SpinePalette.distinct().size)
    }

    // ── paletteSize 边界 ─────────────────────────────────────────────────

    /** 容量为 1 时任何书名都只能落在唯一色位上。 */
    @Test fun `色板容量为一时恒为零`() {
        listOf("三体", "", "☃", "a".repeat(500), "seed-0").forEach {
            assertEquals(0, spineColorIndex(it, 1))
        }
    }

    /**
     * 换容量必须整体重算（不是「先按 8 取模再截断」）：同一 seed 在 5 / 7 / 16 色板下各自有确定的下标。
     */
    @Test fun `换色板容量按下标重新取模`() {
        assertEquals(4, spineColorIndex("seed-0", 5))
        assertEquals(4, spineColorIndex("seed-0", 7))
        assertEquals(10, spineColorIndex("三体", 16))
    }

    /**
     * 非正容量是调用方编程错误，**按契约抛除零异常**（而不是悄悄返回一个看似合法的下标）。
     * 两个调用点都传 `SpinePalette.size`（常量 8，已由上面的用例锁住），故运行时不可达。
     */
    @Test fun `色板容量非正数抛出除零而非越界下标`() {
        try {
            spineColorIndex("三体", 0)
            fail("paletteSize = 0 应抛出 ArithmeticException，而不是返回任何下标")
        } catch (expected: ArithmeticException) {
            // 预期：除零即抛
        }
    }
}
