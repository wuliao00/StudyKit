package com.studykit.ui.study

import com.studykit.data.entity.Question

/**
 * 一轮题目队列的装配结果。
 *
 * @param questions 最终顺序的题（**与输入同一批实例**，不复制、不改字段）
 * @param pulled 从池里拉到前面的条数。上限只有一条口径：[QueueBuilder] 的「due 条数的一成」（整数除法，
 *        不足十题的会话就是 0），装配层不另加护栏、也不加第二条
 * @param maxAdjSim 语义遍没跑时为 null（回退链：null 的意思是「压根没测」，不是「测出 0」）；
 *        跑了就是最终队列的相邻最大值
 * @param drillIds 该挂「这两张卡容易混」提示的题 id（每个 drill 对子里靠后的那张）。
 *        口径只住在 [QueueBuilder] 那一处，这里不兜底、不改口径、也不重述一遍（同一件事两处各说一套是这条 lane
 *        的红线）：配对只发生在**学科相同且非空白**的题之间（`confusablePairs`），而一对子进了映射之后，它最终的
 *        实际距离有**四条**出带的路、**上下两尾都出得到** —— 落点被拒时停在**原**距离（那一档可以高于 `gapHi`），
 *        `coerceIn` 夹到队尾时短于 `gapLo`，后一次 drill 的动作还能把前一对的间隔往两个方向再改一次，
 *        而 drills 之后那一轮**复扫**为了拆掉相邻违规，可以把记录在案的对子里的一张换走（两尾都出得到）。四条的推演
 *        与夹具全在 `QueueBuilder.applyDrills` 的注释里、由 `QueueBuilderDrillTest` 一条钉一条（被拒那一档它的
 *        夹具报的是 7，在 `gapHi = 5` 之上）。
 *        ⛔ 这四条**没有一条**由本层的用例钉着：[QuestionQueuePlan] 压根不暴露 `gap`，装配层造不出带外的形状。
 *        这一段只拦一个误读 —— 别把「id 在 drillIds 里」读成「这一对隔在 `gapLo..gapHi` 之间」。
 */
internal data class QuestionQueuePlan(
    val questions: List<Question>,
    val pulled: Int,
    val maxAdjSim: Float?,
    val drillIds: Set<Long>,
)

/**
 * 唯一一处把（设置 + 这一轮的题 + 候选池）拼成交错队列的地方。
 *
 * 两层叠在一起，上层不改下层：
 *  1. 学科层 —— [interleaveBySubject]，与 v2.x 起完全相同；关掉交错开关时它就是原序；
 *  2. 题面层 —— [QueueBuilder]，在第 1 层的**结果顺序**上做最小扰动。
 *
 * **一轮里学科层只准跑一次，而这一层默认住在 [planQuestionQueue] 内部。**
 * [interleaveBySubject] 是「剩余桶最大者优先」的贪心重排，不保序、**不幂等**（对它的输出再跑一遍
 * 可以得到不同的序），所以「跑过两次」不能靠约定挡 —— 挡的办法是给这一层一个显式的关：
 * `interleaving = false` 时本函数**绝不碰**学科顺序（测试「关掉学科层时学科层也不许偷跑」逐格钉着）。
 * 单科轮的学科层就是本函数内的这一次；综合练习轮（Task 7 接线）在**截断前**于整份合并序列上跑过
 * 学科层，随后必须显式传 `interleaving = false` 进来 —— 那是「调用方跑过了，别再跑第二遍」的记号，
 * 不是关掉打散（计划 `721746a` 对该接线的订正；把外层调用删掉会让 `take(QUIZ_SIZE)` 只截到第一科，
 * 综合练习退化成单科）。
 *
 * 第 2 层把原序编码成 `urgency = n - index`（越靠前越大），于是 [QueueBuilder.build] 的
 * 「按 urgency 降序」初值就等于第 1 层给出的顺序：**没有相似题要分开时，输出逐元素等于输入**。
 * 这条不是巧合，是回退链能写成相等断言的前提（测试「语义遍开但题互不相像时输出与不开完全相同」）。
 * 换句话说：这一层只会在真的看到相似题时才动手，它不动手的时候必须看得见「什么都没变」。
 *
 * 候选池 [pool] 不参与学科层，也不参与第二遍的配对输入（[QueueBuilder.confusablePairs] 喂的是
 * 会话那批卡，所以拉进来的缓冲卡永远不会挂 drill 提示 —— 测试「拉进来的缓冲卡不挂 drill 提示」
 * 钉的就是这句话：池里放一张与某张会话题同科同考点的卡，落地 `drillIds` 是空集，
 * 把 `items + poolItems` 一起喂进去才长出那一张，而两档的**末序逐格相同**），
 * [pool] 只当缓冲卡来源。池里与本轮同 id 的条目先按 id 滤掉：[cards] 用 `associateBy` 建、
 * **后者胜**，不滤就会让池里那个同 id 对象顶掉本轮那一份（用户正在做的这张题被换成池里的替身）。
 * `QueueBuilder` 的判重（`inQueue`）只兜「重复入列」，兜不住「顶掉实例」
 * —— 见测试「session 与 pool 有同 id 时不重复入列且会话那份胜出」。
 * 池中的题是用户这一轮新见到的，
 * 所以 [QueueBuilder] 的 pull 上限（条数一成）是唯一的护栏 —— 见测试「池里的条数受一成上限」。
 * 队列映射回 [Question] 用的必须是同一批实例；`check` 守卫防「队列里有 id 回不到题」——
 * 丢了要当场炸，不许静默少题（测试「缓冲卡来自池且是同一个对象」把这一刀钉着）。
 * 这条守卫在生产输入下**走不到**（[cards] 覆盖 `result.queue` 的每一个 id）：它**杀得掉变异，
 * 不是跑得到的分支** —— 报告 §5 M4（`cards` 只由 base 建）实测把它踩红过。
 *
 * 两道早退里只有 `!semantic` 是语义守卫。`base.size < 2` 那一半是**成本优化，不是语义守卫**：
 * [QueueBuilder.build] 对 n<2 自己也不崩（前扫空转、`maxOfOrNull` 给 null），删掉它之后三个读数与
 * 队列内容逐格相同（报告 §5 M10 实测全绿、没有任何用例能显形），留着只省掉一次无谓的
 * [QueueItem] / [QuestionSimularity] 构造。
 */
internal fun planQuestionQueue(
    session: List<Question>,
    pool: List<Question>,
    interleaving: Boolean,
    semantic: Boolean,
    policy: InterleavePolicy = LexicalInterleave.DEFAULT,
): QuestionQueuePlan {
    val base = interleaveBySubject(session, enabled = interleaving)
    if (!semantic || base.size < 2) {
        return QuestionQueuePlan(questions = base, pulled = 0, maxAdjSim = null, drillIds = emptySet())
    }
    // 池里去掉与本轮重复的 id：[cards] 是 associateBy 建的、后者胜，不滤就会被池里那个同 id 对象顶掉本轮那一份
    val baseIds = base.mapTo(HashSet(base.size)) { it.id }
    val extra = pool.filter { it.id !in baseIds }
    val cards = (base + extra).associateBy { it.id }
    // 相似度快照必须连池一起收：insertBuffer 要问「池卡 vs 本轮两侧邻居」的分值。
    // 只喂 base 的话 Simularity 对不认识的 id 一律给 0f ⇒ 池里的题永远「不像」 ⇒ 连与本轮同文的池卡也会被插进来
    // （测试「缓冲卡来自池且是同一个对象」的两张池卡夹具 + 字面末序钉着这一档）。
    val sim = QuestionSimularity(base + extra)
    val items = base.mapIndexed { index, card ->
        QueueItem(id = card.id, urgency = (base.size - index).toFloat(), subject = card.subject)
    }
    // 池候选的 urgency 是**惰性字段**：QueueBuilder.build 只对 due 那批按 urgency 排初值序，
    // 池那批走 insertBuffer 的清单序（firstOrNull），这个字段在本装配的接线下没有读者 —— 别「修」它，
    // 换成真实到期值不会改变一条结果，反而会让人以为它参与过排序。
    val poolItems = extra.map { QueueItem(id = it.id, urgency = 0f, subject = it.subject) }
    val result = QueueBuilder.build(items, sim, poolItems, QueueBuilder.confusablePairs(items, sim, policy), policy)
    val ordered = result.queue.mapNotNull { cards[it.id] }
    check(ordered.size == result.queue.size) { "装配丢条目: 队列 ${result.queue.size} 题，映射回 ${ordered.size} 题" }
    return QuestionQueuePlan(
        questions = ordered,
        pulled = result.pulled,
        maxAdjSim = result.maxAdjSim,
        drillIds = drillNoteIds(result.queue, result.drillPairs),
    )
}

/**
 * 设置行「相似题打散」的说明。收进函数是为了钉进测试：
 * 文案一旦散落在 Composable 的字面量里，就没有任何机器在守着它不说假话（偏差 D2）。
 *
 * 禁词表由测试「设置行文案不许承诺语义包或模型」执行：这一层是词面判据，
 * 不许说「语义/模型/MB/向量/下载/嵌入」。简报 Step 3 的原稿写着「不额外下载东西」，
 * 「下载」正在禁词表里 —— 按测试改成「不装额外东西」，意图不变（报告里记为 brief 缺陷 ⑤）。
 *
 * T5-R5 又把它三句超诺的承诺逐句收窄（Task 6 渲染的就是下面这一句，测试「设置行文案逐字钉住」整句钉着，
 * 禁词表拦不住「把『尽量』删回去」那种回填）：
 *  1. 原稿「考点标签相同的题会被**安排在同一轮里**」结构上就做不到 —— [QueueBuilder.confusablePairs]
 *     只喂会话内的题，而 `insertBuffer` 要求缓冲卡与两侧邻居都 `< tauLo` ⇒ 被拉进来的池卡永远不可能是
 *     混淆伙伴（测试「拉进来的缓冲卡不挂 drill 提示」）。改成「同一轮里**已经出现的**同考点题」。
 *  2. 原稿「别让它们连着出现」是无条件承诺，而落点被拒或夹到队尾时**会**留下 `sim > tauHi` 的相邻
 *     （本仓夹具里就有一轮末态 `maxAdjSim = 1.0`）⇒ 加「尽量」，并补「挪不开就照原样出」。
 *  3. 原稿「隔三到五题再遇一次」是**目标带**不是结果（`placeBeside` 可拒、`coerceIn` 可夹）⇒ 同样加「尽量」。
 * 修复轮 2 第 2 条又给上面**第 1 句**改出来的那半句补上一档限定 —— 它同样是「尽量」挡不住的那一类：
 * [QueueBuilder.confusablePairs] 的同学科过滤（`b.subject != a.subject` 直接 `continue`）让**跨学科的
 * 同考点对子压根进不了映射、连一次挪位都不试**，那不是「这一趟挪不动」而是「按设计就不试」 ⇒ 那句改成
 * 「同一学科同考点题」（这道过滤住在 `QueueBuilderDrillTest.跨学科的对子不 drill`，本层不重复钉）。
 * 另：`unresolved` 在装配层被丢弃、UI 拿不到它 ⇒ 这句更不能承诺「绝不连着出现」，只能承诺尽力。
 */
internal fun semanticInterleaveHint(): String =
    "把题面文字上就很像的题尽量隔开几道出；同一轮里已经出现的同一学科同考点题，会尽量隔三到五题再遇一次 —— " +
        "挪不开就照原样出。判据是题干文字与考点标签，不联网、不装额外东西。关掉这一层就只按学科打散。"

/** 会话内提示（文档 L1266 逐字，测试「会话内提示文案逐字钉住」一字一钉）。它只解释「为什么隔开放」，不解释相似度是怎么算的。 */
internal fun confusableDrillTip(): String = "这两张卡容易混，隔开放是故意的。"
