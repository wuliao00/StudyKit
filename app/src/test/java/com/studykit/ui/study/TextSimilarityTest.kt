package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSimilarityTest {

    @Test fun `归一化丢掉标点空白只留汉字字母数字`() {
        assertEquals("甲乙丙123abc", TextSimilarity.normalize("甲、乙 丙 123 ABC"))
    }

    @Test fun `归一化把字母转小写`() {
        assertEquals("abc甲", TextSimilarity.normalize("ABC 甲"))
    }

    @Test fun `归一化对空串返回空串`() {
        assertEquals("", TextSimilarity.normalize(""))
    }

    @Test fun `二元组长度 n 的串给 n 减 1 组`() {
        assertEquals(setOf("甲乙", "乙丙", "丙丁"), TextSimilarity.bigrams("甲乙丙丁"))
    }

    @Test fun `二元组单字符退化为单字符集合`() {
        assertEquals(setOf("甲"), TextSimilarity.bigrams("甲"))
    }

    @Test fun `二元组空串给空集合`() {
        assertTrue(TextSimilarity.bigrams("").isEmpty())
    }

    @Test fun `dice 自身对自身等于一`() {
        val g = TextSimilarity.bigrams("下列句子没有语病的一项是")
        assertEquals(1f, TextSimilarity.dice(g, g), 1e-6f)
    }

    @Test fun `dice 完全不相交等于零`() {
        assertEquals(0f, TextSimilarity.dice(TextSimilarity.bigrams("甲乙丙丁"), TextSimilarity.bigrams("戊己庚辛")), 1e-6f)
    }

    @Test fun `dice 任一集合为空给零不抛异常`() {
        assertEquals(0f, TextSimilarity.dice(emptySet(), TextSimilarity.bigrams("甲乙")), 1e-6f)
        assertEquals(0f, TextSimilarity.dice(TextSimilarity.bigrams("甲乙"), emptySet()), 1e-6f)
    }

    @Test fun `dice 半重叠等于零点五`() {
        // {甲乙,乙丙} 与 {乙丙,丙丁}：2*1/(2+2) = 0.5
        assertEquals(0.5f, TextSimilarity.dice(TextSimilarity.bigrams("甲乙丙"), TextSimilarity.bigrams("乙丙丁")), 1e-6f)
    }

    @Test fun `dice 不等长集合两种参数顺序都给精确值`() {
        // 9 个共用二元组 + 一侧多 2 个、另一侧多 1 个 ⇒ 11 与 10 之比：2*9/(11+10) = 18/21 = 6/7 = 0.85714…，故容差取 1e-3
        val shared = setOf("甲1", "甲2", "甲3", "甲4", "甲5", "甲6", "甲7", "甲8", "甲9")
        val eleven = shared + setOf("乙1", "乙2")
        val ten = shared + setOf("丙1")
        assertEquals(0.857f, TextSimilarity.dice(eleven, ten), 1e-3f)
        assertEquals(0.857f, TextSimilarity.dice(ten, eleven), 1e-3f)
        assertEquals(TextSimilarity.dice(ten, eleven), TextSimilarity.dice(eleven, ten), 1e-6f)
    }

    @Test fun `题面相似只改标点后等于一`() {
        val a = "下列句子中，没有语病的一项是（　）"
        val b = "下列句子中没有语病的一项是"
        assertEquals(1f, TextSimilarity.questionSimilarity(a, "", b, ""), 1e-6f)
    }

    @Test fun `题面相似的无关两题低于零点一五`() {
        val a = "已知集合 A 等于 x 满足 x 平方小于 4 求 A 的补集"
        val b = "下列关于鲁迅的说法正确的是"
        assertTrue(TextSimilarity.questionSimilarity(a, "", b, "") < 0.15f)
    }

    @Test fun `同考点标签把相似度抬到地板值且不低于它`() {
        val s = TextSimilarity.questionSimilarity("甲乙丙丁戊己", "病句辨析", "戊己庚辛壬癸", "病句辨析")
        assertEquals(0.65f, s, 1e-6f)   // 字面完全不同，但同考点 ⇒ 抬到 CONCEPT_FLOOR
    }

    @Test fun `同考点地板抬升偏低的字面相似且异标签不抬升`() {
        // 这对题面的字面 Dice 是 0.2（只共用「戊己」），落在地板值之下：
        // 异标签必须保持 0.2（地板不许跨标签生效），同标签必须被抬到 0.65（地板抬升，两个方向都钉死）
        assertEquals(0.2f, TextSimilarity.questionSimilarity("甲乙丙丁戊己", "病句辨析", "戊己庚辛壬癸", "衔接连贯"), 1e-6f)
        assertEquals(0.65f, TextSimilarity.questionSimilarity("甲乙丙丁戊己", "病句辨析", "戊己庚辛壬癸", "病句辨析"), 1e-6f)
    }

    @Test fun `空考点标签不算同考点`() {
        // 两题都没标考点：'' == '' 不能当成"同考点"，否则未标注的题会互相抬到地板值
        val low = TextSimilarity.questionSimilarity("甲乙丙丁", "", "戊己庚辛", "")
        assertTrue(low < 0.1f)
    }

    @Test fun `考点标签不同不触发地板`() {
        val s = TextSimilarity.questionSimilarity("甲乙丙丁戊己", "病句", "戊己庚辛壬癸", "成语")
        // 这对题干共用「戊己」一个二元组：2*1/(5+5) = 0.2；同一对打相同标签时才是 0.65
        // （见 `同考点地板抬升偏低的字面相似且异标签不抬升`）
        assertEquals(0.2f, s, 1e-6f)
    }

    @Test fun `相似值恒在零到一之间`() {
        val stems = listOf("甲乙丙丁戊", "乙丙丁戊己", "", "ABC abc 123", "下列各项中加点字读音完全相同的一项是")
        // 同标签这一轮走 maxOf(literal, CONCEPT_FLOOR)，下界被常量顶着；
        // 空标签这一轮不抬升，下界由 dice 自己守 —— 两条路径都要扫，否则半边的断言永远是绿的
        for (x in stems) for (y in stems) {
            val s = TextSimilarity.questionSimilarity(x, "k", y, "k")
            assertTrue("越界: [$x] vs [$y] = $s", s >= 0f && s <= 1f)
        }
        for (x in stems) for (y in stems) {
            val s = TextSimilarity.questionSimilarity(x, "", y, "")
            assertTrue("越界未抬升: [$x] vs [$y] = $s", s >= 0f && s <= 1f)
        }
    }
}
