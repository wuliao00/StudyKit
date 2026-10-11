package com.studykit.ui.study

import com.studykit.data.entity.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `planQuestionQueue` 装配层用例（v3.0-C Task 5）。
 *
 * 这一层唯一的新东西是**回退链的可证性**：学科层的输出序被编码成 `urgency = n - index` 喂给题面层，
 * 于是「题面遍没看到任何相似题」时输出与输入逐元素相等 —— 下面那两条相等断言钉的就是这个性质，
 * 不是「差不多」。
 *
 * 期望值全部先手推后实跑核对（逐条推演记在 task-5-report.md 的夹具表里）。简报原稿有四处夹具/期望
 * 在规格实现下永远红或根本白过，已按意图订正，逐条在注释里标了「brief 缺陷」（汇总在报告）。
 * 修复轮按裁定**新增**三条用例（T5-R2 混淆配对输入、T5-R4 非默认 policy、T5-R5 文案整句）、
 * **加固**四条既有用例（T5-R1 池项换成同 id 的另一个对象、T5-R3 池换成两张、T5-R7 的两处
 * `pulled == 0` 原本配的是「早返回 + 空池」= 永真断言），另按 T5-R6 换掉一处断言消息里的作废手推值；
 * 每条注释里标了对应的 `T5-Rn`。用例数 16 → 19。
 * ⛔ 标记的用例钉字面量，不读生产常量当期望值。
 */
class QuestionQueuePlannerTest {

    private fun q(id: Long, subject: String, stem: String, tag: String = "") = Question(
        id = id, uuid = "u$id", subject = subject, stem = stem,
        optionsJson = "[]", answerIndex = 0, explanation = "", conceptTag = tag,
    )

    /** 六题三科，其中 1 与 4 是「同一道题换个说法」（去掉标点后完全一样 ⇒ Dice = 1.0，越过 drillHi ⇒ 只分不配） */
    private fun bank() = listOf(
        q(1, "语文", "下列句子中没有语病的一项是"),
        q(2, "数学", "已知集合 A 等于 x 满足 x 平方小于 4 求 A"),
        q(3, "英语", "Choose the right answer about tense"),
        q(4, "语文", "下列句子中没有语病的一项是（　）"),
        q(5, "数学", "数列 an 的前 n 项和求公比"),
        q(6, "英语", "Fill in the blank with the correct preposition"),
    )

    /**
     * 十条**互不相让**的会话（T5-R7）：三科各摊（语文 4 / 数学 3 / 英语 3，学科层真的会重排它）、
     * 题干两两同文 ⇒ 只要题面遍真在跑，每一处相邻都是违规、每一处换位都失败、只剩下拉池卡这一招。
     * 用途：把 `pulled == 0` 从「早返回 + 空池给的永真」变成有牙齿的断言（配 [buffers] 一起用）。
     */
    private fun rivals() = listOf(
        q(1, "语文", "甲乙丙丁戊己庚辛壬癸"),
        q(2, "数学", "甲乙丙丁戊己庚辛壬癸"),
        q(3, "英语", "甲乙丙丁戊己庚辛壬癸"),
        q(4, "语文", "甲乙丙丁戊己庚辛壬癸"),
        q(5, "数学", "甲乙丙丁戊己庚辛壬癸"),
        q(6, "英语", "甲乙丙丁戊己庚辛壬癸"),
        q(7, "语文", "甲乙丙丁戊己庚辛壬癸"),
        q(8, "数学", "甲乙丙丁戊己庚辛壬癸"),
        q(9, "英语", "甲乙丙丁戊己庚辛壬癸"),
        q(10, "语文", "甲乙丙丁戊己庚辛壬癸"),
    )

    /** 一张与本轮谁都不像的缓冲卡：题面遍只要真跑了就一定拉得动它（`pulled` 的牙齿来自这张） */
    private fun buffers() = listOf(q(70, "物理", "关于牛顿第二定律的下列说法正确的是"))

    @Test fun `两个开关都关时逐字节等于今天的取数原序`() {
        // 回退链最底一档：什么都不开 ⇒ 取数原序，且 maxAdjSim 是 null（「压根没测」而不是「测出 0」）。
        // 杀的变异：M1 = 装配层无视 semantic=false 硬跑题面遍（`!semantic` 改成 `false`）。
        //           那一刀在这份夹具上让三格同时为假（末序多一张池卡、pulled 变 1、null 变实数），
        //           JUnit 只报第一句（顺序），后两格在同一刀下各自也红。
        // T5-R7：夹具从 bank() + 空池换成 rivals() + buffers()。原稿的 `assertEquals(0, plan.pulled)`
        //        走的是「`!semantic` 早返回」+「池是 emptyList()」两步 ⇒ 池压根没被看过，
        //        这一句在任何实现下都不可能红；现在十条全违规 + 池里有一张拉得动的卡，
        //        「关掉就连池都不看」才是真被钉着的那件事。
        // 顺带补上原稿自陈的局限：bank() 的输入序==学科序 ⇒ 这条钉不住「无视 interleaving」；
        //        rivals() 的输入序 [1..10] != 学科序 ⇒ 第一句现在也管这一刀（M7a：`enabled` 写死成 true）。
        val rivals = rivals()
        val plan = planQuestionQueue(rivals, buffers(), interleaving = false, semantic = false)
        assertEquals(rivals.map { it.id }, plan.questions.map { it.id })
        assertEquals("关掉语义遍就该连池都不看: pulled=${plan.pulled}", 0, plan.pulled)
        assertNull(plan.maxAdjSim)
        assertTrue(plan.drillIds.isEmpty())
    }

    @Test fun `关掉学科层时学科层也不许偷跑`() {
        // 简报没有这条，补上：bank() 那份夹具开不开学科层都是同一个序，「interleaving 开关失不复出」
        // 在原稿里没有任何用例钉得住。这份 [1,4,2,3]（语文,语文,数学,英语）的学科层贪心输出是
        // [1,2,4,3]（首步语文桶剩 2 最大；退让后数学、英语各 1，同分取先出现的数学；再语文；再英语），
        // 输入序 ≠ 学科序，两个方向才都测得出。
        // 杀的变异：① `enabled = interleaving` 写死成 true ⇒ 第一句红；② 写死成 false ⇒ 第二句红（M7）。
        val session = listOf(bank()[0], bank()[3], bank()[1], bank()[2])
        val off = planQuestionQueue(session, emptyList(), interleaving = false, semantic = false)
        assertEquals(listOf(1L, 4L, 2L, 3L), off.questions.map { it.id })
        val on = planQuestionQueue(session, emptyList(), interleaving = true, semantic = false)
        assertEquals(listOf(1L, 2L, 4L, 3L), on.questions.map { it.id })
    }

    @Test fun `只开学科交错时与 interleaveBySubject 完全相等`() {
        // ⛔ 回退链（文档 L1264「行为不变」）第一档：semantic=false ⇒ 只跑学科层，逐元素相等。
        // maxAdjSim 必须是 null —— 语义遍压根没跑，报一个数出来是假信息。
        // 杀的变异：M1（semantic=false 也跑题面遍）——现形的是第一句的顺序（池卡被插进来），
        //           maxAdjSim 与 pulled 在同一刀下也各自为假。
        // T5-R7：`assertEquals(0, plan.pulled)` 原来配的是空池 + 早返回 ⇒ 永真，现在换成 rivals() + buffers()
        //        （十条全违规的会话 + 一张拉得动的池卡），这一句才真的在钉「关掉就连池都不看」。
        // 夹具同时是**学科层真重排过**的那一份（rivals() 的输入序 != 学科序），所以这条还顺带杀
        // M7b「装配层无视 interleaving 把学科层关掉」（= `enabled` 写死成 false）—— 字面序是它的第二道闸。
        val rivals = rivals()
        val expect = interleaveBySubject(rivals, enabled = true)
        val plan = planQuestionQueue(rivals, buffers(), interleaving = true, semantic = false)
        assertEquals(expect.map { it.id }, plan.questions.map { it.id })
        // ⛔ 手推的学科层贪心末序：语文桶 4 最大→1；避语文⇒数学3/英语3 同分取先出现的数学→2；
        // 避数学⇒语文3/英语3 同分取语文→4；避语文⇒数学2<英语3⇒英语→3；之后 7,5,6,10,8,9。
        assertEquals(listOf(1L, 2L, 4L, 3L, 7L, 5L, 6L, 10L, 8L, 9L), plan.questions.map { it.id })
        assertNull(plan.maxAdjSim)
        assertEquals("关掉语义遍就该连池都不看: pulled=${plan.pulled}", 0, plan.pulled)
        assertTrue(plan.drillIds.isEmpty())
    }

    @Test fun `语义遍开但题互不相像时输出与不开完全相同`() {
        // ⛔ 回退链第二档（强形态）：**同一份输入**上 on/off 逐元素相等。
        // 简报原稿拿全 bank（6 题、关）比 bank.take(3)（3 题、开），两边长度不同（6 vs 3），
        // 这条相等在任何实现下都不可能成立 —— 已按意图改成同输入对照（brief 缺陷 ①）。
        // 夹具故意排成 [2,1,3]（三科各一、两两 sim=0）：位置序 ≠ id 升序，urgency 编码的两个变异才现得形。
        // 学科层贪心：三桶各 1，同分取先出现者 ⇒ 出序就是输入序 [2,1,3]。
        // 杀的变异：M2（urgency 退化成常量 ⇒ build 初序掉回 id 升序 [1,2,3]）、
        //          M3（urgency 方向写反 ⇒ 末序倒过来 [3,1,2]）。
        val trio = listOf(bank()[1], bank()[0], bank()[2])
        val off = planQuestionQueue(trio, emptyList(), interleaving = true, semantic = false).questions.map { it.id }
        val on = planQuestionQueue(trio, emptyList(), interleaving = true, semantic = true)
        assertEquals(off, on.questions.map { it.id })
        assertEquals(listOf(2L, 1L, 3L), on.questions.map { it.id })   // ⛔ 手推 + 实跑核对的输入序本身
    }

    @Test fun `语义遍把换说法的同道题隔开`() {
        // 1 与 4 归一化同文 ⇒ sim=1.0：越过 tauHi（必须分开），也越过 drillHi=0.75（重复题不配成对）。
        // 这份夹具里把它们隔开的是**学科层**（初序 [1,2,3,4,5,6]，1 与 4 隔 3 格）；题面遍的正确行为
        // 是一步都不动、且真的跑过（maxAdjSim 非 null）。简报没有钉末序，补一句防「题面遍乱动」。
        // 杀的变异：题面遍动了不该动的东西（末序那句红）；semantic=true 误走 null 早退（`!!` 处 NPE）。
        val bank = bank()
        val plan = planQuestionQueue(bank, emptyList(), interleaving = true, semantic = true)
        val i1 = plan.questions.indexOfFirst { it.id == 1L }
        val i4 = plan.questions.indexOfFirst { it.id == 4L }
        assertTrue("1 与 4 仍相邻: idx=$i1,$i4", kotlin.math.abs(i1 - i4) >= 2)
        val maxAdj = plan.maxAdjSim!!
        // T5-R6：原稿这里引用的 `2*1/(11+29)=0.05` 是被推翻的手推值。真值是 **2/43 = 0.046511628**：
        // 相邻最像的一对是 5/6，数学题干 11 个唯一二元组、英文题干（Fillintheblankwiththecorrectpreposition）
        // 32 个而不是 29 个，交集只有 `an` 一个 ⇒ 2*1/(11+32)。它离 0.60 差得远、不跨任何阈值，
        // 但消息里不能再写 0.05 —— 免得下一个人把那个数当实测引用。
        assertTrue("maxAdjSim=$maxAdj 越过相邻线（这一轮相邻最大的是 5/6 = 2/43 = 0.046511628）", maxAdj <= 0.60f)   // ⛔ 字面量 = DEFAULT.tauHi 的值，不读常量
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), plan.questions.map { it.id })
    }

    @Test fun `成对出现的那张带上会话内提示且只带一张`() {
        // 夹具换过了：简报原稿用 bank()，但 1 与 4 的 sim=1.0 越过 drillHi=0.75 —— 重复题只分不配，
        // drillIds 恒空，`size == 1` 在规格实现下永远红（brief 缺陷 ②）。
        // 这里的 11 与 14 靠同考点标签「病句辨析」抬到地板值 0.65：落在混淆带 [0.40,0.75] 内、又越过相邻线。
        // 学科层贪心给出初序 [11,12,14,13]（语文2→数学1→语文1→英语1）；11 与 14 的 gap=2 < gapLo=3
        // ⇒ 第二遍挪 14 到 anchor 后第 3 格（下标 3，两侧邻居 sim=0 不拒）⇒ 末序 [11,12,13,14]，靠后那张是 14。
        // 杀的变异：M5（confusablePairs 的调用摘掉、传空映射 ⇒ drillIds 空 + 末序停在 [11,12,14,13]）、
        //          M6（提示改挂 aId ⇒ {14} 变 {11}）。
        // 同一份夹具在「policy 参数不是摆设」里再跑一遍**非默认** policy —— 那条钉的是 policy 真被读。
        val session = listOf(
            q(11, "语文", "下列句子中没有语病的一项是", "病句辨析"),
            q(12, "数学", "已知集合 A 等于 x 满足 x 平方小于 4 求 A"),
            q(13, "英语", "Choose the right answer about tense"),
            q(14, "语文", "下列各句中加点的成语使用恰当的一项是", "病句辨析"),
        )
        val plan = planQuestionQueue(session, emptyList(), interleaving = true, semantic = true)
        assertEquals(listOf(11L, 12L, 13L, 14L), plan.questions.map { it.id })
        assertEquals("drill 对子只标后置那张: ${plan.drillIds}", 1, plan.drillIds.size)
        assertEquals(setOf(14L), plan.drillIds)          // ⛔ 钉字面量 id：挪完之后 14 才是靠后的那张
        assertEquals(0, plan.pulled)
    }

    @Test fun `policy 参数不是摆设：gapLo 改成 1 就不挪了`() {
        // T5-R4（最要紧的一条）：修复轮之前全仓**没有任何一条用例传过非默认 policy** ⇒ 实现里把签名上的
        // `policy` 换成硬编码的 LexicalInterleave.DEFAULT 也能一路绿，而「阈值可按提供方替换」正是偏差 D3
        // 与整份 C 计划留下的那条缝（语义包将来就从这里进来）。
        // 夹具沿用「成对出现的那张…」：11 与 14 同考点 ⇒ sim=0.65 既落进混淆带 [0.40,0.75] 又越过相邻线；
        // 学科层贪心初序 [11,12,14,13]（语文桶 2 最大→11；避语文→数学 12；再语文 14；再英语 13），11 与 14 间距 2。
        //  - DEFAULT（gapLo=3）：2 不在 [3,5] ⇒ 把 14 挪到 anchor 后第 3 格（落点两侧 sim=0 不拒）⇒ [11,12,13,14]；
        //  - copy(gapLo = 1)：2 已经在 [1,5] ⇒ 走「已隔开，不动也记」那一支，一张都不挪 ⇒ 末序停在 [11,12,14,13]。
        // 杀的变异：函数体内用硬编码的 DEFAULT 顶替传进来的 `policy` ⇒ byGapLoOne 的末序那句红。
        //          byDefault 那句是反向闸：不许靠「所有档都不动手」同时骗过它 ⇒ 默认档必须真的挪过。
        // 两档的 drillIds 都是 {14}（靠后那张没变；默认档那一份由「成对出现的那张…」钉着）
        // ⇒ 只有末序分得出这两档，所以这里钉的是顺序。
        val session = listOf(
            q(11, "语文", "下列句子中没有语病的一项是", "病句辨析"),
            q(12, "数学", "已知集合 A 等于 x 满足 x 平方小于 4 求 A"),
            q(13, "英语", "Choose the right answer about tense"),
            q(14, "语文", "下列各句中加点的成语使用恰当的一项是", "病句辨析"),
        )
        val byDefault = planQuestionQueue(session, emptyList(), interleaving = true, semantic = true)
        assertEquals(listOf(11L, 12L, 13L, 14L), byDefault.questions.map { it.id })
        val byGapLoOne = planQuestionQueue(
            session, emptyList(), interleaving = true, semantic = true,
            policy = LexicalInterleave.DEFAULT.copy(gapLo = 1),
        )
        assertEquals(listOf(11L, 12L, 14L, 13L), byGapLoOne.questions.map { it.id })   // ⛔ 同一份输入，换了 gapLo 就该停在这儿
        assertEquals("gapLo=1 时对子本来就隔在带内，提示仍挂在靠后那张: ${byGapLoOne.drillIds}", setOf(14L), byGapLoOne.drillIds)
    }

    @Test fun `拉进来的缓冲卡不挂 drill 提示`() {
        // T5-R2：KDoc 承诺「[QueueBuilder.confusablePairs] 只喂会话内的题 ⇒ 拉进来的缓冲卡永远不挂提示」，
        // 而原稿没有任何用例能让这句话为假 —— 把 `items + poolItems`（这里全 16 条）一起喂进去，
        // 原稿那 16 条照样全绿；这条用例就是为了让这句话红而存在的。
        // 这份夹具让**池里的 5** 与会话里的 2 同科同考点（「干支」）：
        //  - 落地：配对输入只有会话 10 条 ⇒ 同科两两要么 sim=1.0（越过 drillHi=0.75 ⇒ 只分不配）要么 0
        //    ⇒ 映射是空的 ⇒ drillIds == {}；
        //  - 变异：sim(2,5) = 同考点地板 0.65 落进 [0.40,0.75] ⇒ 记下这一对 ⇒ drillIds == {5}。
        // 为什么两档**末序逐格相同**、只剩 drillIds 能分辨：初序第一处违规在下标 3（id4 与 id6 同文），
        // 换位窗口 [5..9] 里全是同文 ⇒ 换不动 ⇒ 拉池卡插进下标 4；而 5（下标 4）与 anchor 2（下标 1）的间距
        // 正好 = gapLo = 3 ⇒ applyDrills 走「已隔开，不动也记」那一支 ⇒ 一张都不挪、relocated=0 ⇒ 也不追加复扫。
        // 先钉 pulled == 1（5 真的在队列里）—— 否则那句空集是「压根没拉到」白给的，正是本 lane 说的
        // 「某段代码存在但没有任何用例能让它不为 0」的反面。
        // 杀的变异：confusablePairs 的输入从 items 换成 items + poolItems（末序/pulled 两句在同刀下仍绿）。
        val session = listOf(
            q(1, "语文", "甲乙丙丁戊己庚辛壬癸"),
            q(2, "语文", "子丑寅卯辰巳午未申酉戌亥", "干支"),
            q(3, "语文", "金木水火土"),
            q(4, "语文", "子丑寅卯辰巳午未申酉戌亥"),
            q(6, "语文", "子丑寅卯辰巳午未申酉戌亥"),
            q(7, "语文", "子丑寅卯辰巳午未申酉戌亥"),
            q(8, "语文", "子丑寅卯辰巳午未申酉戌亥"),
            q(9, "语文", "子丑寅卯辰巳午未申酉戌亥"),
            q(10, "语文", "子丑寅卯辰巳午未申酉戌亥"),
            q(11, "语文", "子丑寅卯辰巳午未申酉戌亥"),
        )
        val pool = listOf(
            q(5, "语文", "梅兰竹菊", "干支"),   // 只与 2 同考点；与两侧邻居 sim=0 ⇒ 插得进来
            q(12, "语文", "笔墨纸砚"),
            q(13, "语文", "琴棋书画"),
            q(14, "语文", "宫商角徵羽"),
            q(15, "语文", "魑魅魍魉"),
            q(16, "语文", "东南西北中"),
        )
        val plan = planQuestionQueue(session, pool, interleaving = true, semantic = true)
        assertEquals(1, plan.pulled)                                        // ⛔ 5 真进了队列，下一句的空集才有牙齿
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), plan.questions.map { it.id })   // ⛔ 手推：插在下标 4
        assertEquals(
            "缓冲卡 5 与会话里的 2 同科同考点，但它不该进混淆配对的输入: ${plan.drillIds}",
            emptySet<Long>(),
            plan.drillIds,
        )
        assertTrue("末态该留下一处 sim=1.0 的相邻（挪不开就照原样出）: ${plan.maxAdjSim}", plan.maxAdjSim == 1.0f)
    }

    @Test fun `不丢条目不增条目`() {
        // 装配层的「映射回题」（cards）不许丢、不许造：数量与 id 多重集两头都钉。
        // 诚实口径：这条夹具没有缓冲卡，检测不到「带池时映射丢条目」—— 那一刀是 check 守卫 +
        // 「缓冲卡来自池且是同一个对象」负责（M4：cards 只由 base 建 ⇒ check 抛「装配丢条目」，实跑见报告）。
        val bank = bank()
        val plan = planQuestionQueue(bank, emptyList(), interleaving = true, semantic = true)
        assertEquals(bank.size, plan.questions.size)
        assertEquals(bank.map { it.id }.sorted(), plan.questions.map { it.id }.sorted())
    }

    @Test fun `缓冲卡来自池且是同一个对象`() {
        // 十题同文 ⇒ 每一处相邻违规都换不动（窗口里的候选全 sim=1.0 ≥ tauLo），只能拉缓冲卡；
        // 上限 = 10*10/100 = 1（⛔ 一成），第一处违规（下标 0 与 1）插一张就再没有额度。
        // 简报原稿的夹具只有两条题：due.size*10/100 在 n<10 时整除为 0 ⇒ `pulled == 1` 永不成立（brief 缺陷 ③）。
        // T5-R3：池换成**两张**（简报原稿只有一张）。70 与本轮只差尾巴两个字 ⇒ Dice = 2*9/(9+10) = 18/19 ≈ 0.947
        //        ≥ tauLo ⇒ insertBuffer 的两侧邻居判定把它拒了；71 与谁都不像 ⇒ 合格的只有它 ⇒ 落地插 71。
        //        相似度快照若只喂会话（`QuestionSimularity(base)`），70 属于「不认识的 id」⇒ 按 Simularity
        //        契约给 0f ⇒ 池卡一律「不像」 ⇒ 插的就是 70 —— 原稿那份单卡池无论怎么改快照都绿，
        //        两张池卡 + 字面末序才把这一刀显形（pulled / size 两档都相同，只能断顺序与对象身份）。
        // 杀的变异：M4（cards 只由 base 建 ⇒ check 抛「装配丢条目」）、
        //          M8（装配不把池交给 build：poolItems 传空 ⇒ pulled 停在 0）、
        //          相似度快照漏掉池（末序那句红，T5-R3）、映射按 id 造替身（assertSame 红 —— 数据类相等救不了它）。
        val session = (1L..10L).map { q(it, "语文", "甲乙丙丁戊己庚辛壬癸") }
        val pool = listOf(
            q(70, "语文", "甲乙丙丁戊己庚辛壬癸甲乙"),            // 与本轮很像 ⇒ 邻居查表 0.947 ⇒ 不合格
            q(71, "物理", "关于牛顿第二定律的下列说法正确的是"),  // 与谁都不像 ⇒ 合格的那一张
        )
        val plan = planQuestionQueue(session, pool, interleaving = true, semantic = true)
        assertEquals(1, plan.pulled)
        assertEquals(11, plan.questions.size)
        assertEquals(listOf(1L, 71L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), plan.questions.map { it.id })   // ⛔ 手推 + 实跑核对：插 71 不插 70
        assertSame(pool[1], plan.questions.single { it.id == 71L })   // 同一个实例，不是按 id 造的替身
        assertEquals(1, plan.questions.indexOfFirst { it.id == 71L })
    }

    @Test fun `池里的条数受一成上限`() {
        // 十条全同 ⇒ 贴邻违规有 9 处，额度只有一成 = 1 张。简报只断言 `<= 1`，
        // 补一句 `== 1` 防「装配根本没把池交给 build」那种假绿（M8 同样杀在这句上）；
        // 「上限恰好是一成」本身由 QueueBuilderAdjacentTest 的「拉前条数受上限约束等于条数的一成」钉着。
        val session = (1L..10L).map { q(it, "语文", "甲乙丙丁戊己庚辛壬癸") }   // 全同 ⇒ 上限 1
        val pool = (70L..89L).map { q(it, "物理$it", "关于第 $it 个力学问题的说法正确的是") }
        val plan = planQuestionQueue(session, pool, interleaving = true, semantic = true)
        assertTrue("pulled=${plan.pulled}", plan.pulled <= 1)
        assertEquals(1, plan.pulled)
        assertEquals(10 + plan.pulled, plan.questions.size)
    }

    @Test fun `session 与 pool 有同 id 时不重复入列且会话那份胜出`() {
        // T5-R1：池里那张换成**同 id 的另一个对象**（`card.copy()`：内容逐字段相同、身份不同）。
        // 原稿让池项与 session[0] 共用同一个实例，于是「删掉 filter 那行」在这一条下确实杀不掉，
        // 报告 §5 M9 把它记成「不可杀的防御行」—— 那个结论是错的：`cards = (base + extra).associateBy { it.id }`
        // 后者胜，摘掉 filter 之后 id 1 映射到的就是**池里那个对象**，`questions` 会把用户正在做的这张题
        // 换成池里的替身。两档内容逐字段相同 ⇒ equals / id 断言全都救不了，只有 assertSame 看得见。
        // 杀的变异：① 删掉 `pool.filter { it.id !in baseIds }`（assertSame 红）；
        //          ②「装配把 base + extra 直接当结果吐」那种双份缺陷（size / distinct 两句红）。
        val card = q(1, "语文", "甲乙丙丁戊己庚辛壬癸")
        val session = listOf(card, card.copy(id = 2, stem = "甲乙丙丁戊己庚辛壬癸"))
        val plan = planQuestionQueue(session, listOf(card.copy()), interleaving = true, semantic = true)
        assertEquals(2, plan.questions.size)
        assertEquals(2, plan.questions.map { it.id }.distinct().size)
        assertSame(session[0], plan.questions.first { it.id == 1L })   // ⛔ 胜出的是会话那一份实例，不是池里的同 id 替身
    }

    @Test fun `交错关掉但语义打开时语义遍照常跑`() {
        // 两个开关各管一层：学科层关掉（四题同科它本来也原样退回），题面这层照样分开两道变式题。
        // 1 与 2 归一化同文（sim=1.0 > tauHi）贴邻 ⇒ 往后看窗口：3 与 1 的 sim=0 < tauLo ⇒ 换位 2↔3，
        // 末序 [1,3,2,4]（4 与谁都不像，留在原位）。
        // 杀的变异：装配层把 semantic 与 interleaving 绑成同一个开关（interleaving=false 连题面遍一起跳）。
        val session = listOf(
            q(1, "语文", "下列句子中没有语病的一项是"),
            q(2, "语文", "下列句子中没有语病的一项是（　）"),
            q(3, "语文", "数列 an 的前 n 项和求公比"),
            q(4, "语文", "已知集合 A 等于 x 满足 x 平方小于 4 求 A"),
        )
        val plan = planQuestionQueue(session, emptyList(), interleaving = false, semantic = true)
        val i1 = plan.questions.indexOfFirst { it.id == 1L }
        val i2 = plan.questions.indexOfFirst { it.id == 2L }
        assertTrue("1 与 2 仍相邻: idx=$i1,$i2", kotlin.math.abs(i1 - i2) >= 2)
        assertEquals(listOf(1L, 3L, 2L, 4L), plan.questions.map { it.id })   // 手推：这份夹具唯一可行的修法
    }

    @Test fun `空池空会话与单题都不抛`() {
        // 边界两格：空会话、单题都不抛 —— 钉的是「不许抛」这个用户可见底线。
        // T5-R8：`base.size < 2` 那半步早退是**成本优化，不是语义守卫**，所以它不是被这条钉着的分支：
        // QueueBuilder 对 n<2 自己也不崩（前扫空转、maxOfOrNull 给 null），删掉它这里照样绿、
        // 三个读数与队列内容逐格相同（报告 §5 M10）。留着只省一次无谓的 QueueItem / QuestionSimularity 构造。
        val none = planQuestionQueue(emptyList(), emptyList(), interleaving = true, semantic = true)
        assertTrue(none.questions.isEmpty())
        assertNull(none.maxAdjSim)
        val one = planQuestionQueue(listOf(q(1, "语文", "甲乙丙")), emptyList(), interleaving = true, semantic = true)
        assertEquals(listOf(1L), one.questions.map { it.id })
        assertNull(one.maxAdjSim)
    }

    @Test fun `只有一个学科时语义遍仍然生效`() {
        // 这条是「新层不是学科交错的重命名」的证据：学科层在单科下原样退回（第一句），
        // 题面层照样把贴邻的变式题分开（第二、三句）。
        // 简报原稿的四题（1/2 与 3/4 两对变式题、跨对共用「的一项是」）推出来 sim≈2*4/(12+17)=0.276，
        // ≥ tauLo=0.15 ⇒ 窗口换位全被拒；会话 4 条 ⇒ 一成额度整除为 0；池又是空 —— 三扇门同时关死，
        // 「修到 0 违规」在任何按规格的实现下都不可能（brief 缺陷 ④）。
        // 换成立/地支两串互不相干的题干：1 与 2 归一化同文（sim=1.0）贴邻，3 与它们 sim=0 ⇒ 换得动，
        // 唯一可行修法是把 3 换进下标 1 ⇒ [1,3,2,4]。
        // 杀的变异：「语义遍只是学科层的换皮」（单科下退化成原样退回）⇒ 末序与 adj 两句一起红。
        val sameSubject = listOf(
            q(1, "语文", "甲乙丙丁戊己庚辛壬癸"),
            q(2, "语文", "甲乙丙丁戊己庚辛壬癸"),
            q(3, "语文", "子丑寅卯辰巳午未申酉戌亥"),
            q(4, "语文", "春夏秋冬东南西北中发白"),
        )
        assertEquals(sameSubject.map { it.id }, interleaveBySubject(sameSubject, enabled = true).map { it.id })
        val plan = planQuestionQueue(sameSubject, emptyList(), interleaving = true, semantic = true)
        assertEquals(listOf(1L, 3L, 2L, 4L), plan.questions.map { it.id })
        val adj = plan.questions.zipWithNext().count { (a, b) ->
            TextSimilarity.questionSimilarity(a.stem, a.conceptTag, b.stem, b.conceptTag) > 0.60f   // ⛔ 字面量 = DEFAULT.tauHi 的值
        }
        assertEquals("仍有相邻变式题: ${plan.questions.map { it.id }}", 0, adj)
    }

    @Test fun `同一输入两次装配逐元素相等`() {
        // 确定性守卫：装配层不许吃隐式状态（顺序、缓存、随机都不行）。当前实现下没有可踩的变异，
        // 它是防「将来在这层加缓存/随机源」的那道门 —— 报告里记为「不可杀（前瞻守卫）」，不是空断言。
        val bank = bank()
        val a = planQuestionQueue(bank, emptyList(), interleaving = true, semantic = true)
        val b = planQuestionQueue(bank, emptyList(), interleaving = true, semantic = true)
        assertEquals(a.questions.map { it.id }, b.questions.map { it.id })
        assertEquals(a.drillIds, b.drillIds)
        assertEquals(a.pulled, b.pulled)
        assertEquals(a.maxAdjSim, b.maxAdjSim)
    }

    @Test fun `会话内提示文案逐字钉住`() {
        // 文档 L1266 逐字。改一个字都要让这条红 —— 它是用户看得见的那句话。
        assertEquals("这两张卡容易混，隔开放是故意的。", confusableDrillTip())
    }

    @Test fun `设置行文案逐字钉住`() {
        // T5-R5：这一句是 Task 6 要渲染的原文，评审数出三处超诺（「安排在同一轮里」结构上做不到、
        // 「别让它们连着出现」与「隔三到五题」是无条件承诺）。禁词表拦不住「把『尽量』删回去」这种回填
        // —— 收窄后的句子仍然一个字都不许多、一个字都不许少，所以整句逐字钉住。
        // 修复轮 2 第 2 条又往里加了「同一学科」：`confusablePairs` 的同学科过滤让跨学科的同考点对子
        // **连一次挪位都不试**，那是结构性的不试，「尽量」挡不住它（那道过滤归 `QueueBuilderDrillTest`）。
        // 杀的变异：把「尽量」删掉（第一句就红）、把「已经出现的」改回「考点标签相同的题会被安排进同一轮」、
        //          把「同一学科」删回去（本条只有那一句整句相等，它就是红的那一句；实跑的 ComparisonFailure
        //          把少了的那一段直接指出来：`同[一学科同]考点题` vs `同[]考点题`）。
        assertEquals(
            "把题面文字上就很像的题尽量隔开几道出；同一轮里已经出现的同一学科同考点题，会尽量隔三到五题再遇一次 —— " +
                "挪不开就照原样出。判据是题干文字与考点标签，不联网、不装额外东西。关掉这一层就只按学科打散。",
            semanticInterleaveHint(),
        )
    }

    @Test fun `设置行文案不许承诺语义包或模型`() {
        // ⛔ 偏差 D2：文档那句「需语义包（约 25MB，本机计算）」是假话，这一层是词面。
        // 简报 Step 3 的文案自己写着「不额外下载东西」，而「下载」就在这条的禁词表里 ——
        // 原稿自相矛盾，已改成「不装额外东西」（测试为准，意图不变；brief 缺陷 ⑤）。
        // 杀的变异：把 hint 换成一句不含「文字/题面」的空话（最后一句红），或把 D2 那句假话搬回来（禁词循环红）。
        val hint = semanticInterleaveHint()
        for (banned in listOf("语义", "模型", "MB", "向量", "下载", "嵌入")) {
            assertTrue("文案里出现了不该有的「$banned」: $hint", !hint.contains(banned))
        }
        assertTrue("文案要说明白按什么判: $hint", hint.contains("文字") || hint.contains("题面"))
    }
}
