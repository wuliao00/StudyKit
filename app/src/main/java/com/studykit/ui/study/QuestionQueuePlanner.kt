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
 *        口径全部照抄 [QueueBuilder]，这里不兜底也不改口径：配对只发生在**学科相同且非空白**的题之间
 *        （[QueueBuilder.confusablePairs]），而落点被拒或被 `coerceIn` 夹到队尾时，那一对的实际距离
 *        可以带外（近于 `gapLo`）—— 见测试「挪不动时宁可不挪也不能造违规」
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
 * 会话那批卡，所以拉进来的缓冲卡永远不会挂 drill 提示），只当缓冲卡来源。池里与本轮同 id 的条目
 * 先按 id 滤掉，免得同一个 id 出现两次把判重绕过去。池中的题是用户这一轮新见到的，
 * 所以 [QueueBuilder] 的 pull 上限（条数一成）是唯一的护栏 —— 见测试「池里的条数受一成上限」。
 * 队列映射回 [Question] 用的必须是同一批实例；`check` 守卫防「队列里有 id 回不到题」——
 * 丢了要当场炸，不许静默少题（测试「缓冲卡来自池且是同一个对象」把这一刀钉着）。
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
    // 池里去掉与本轮重复的 id：同 id 出现两次会让后面 cards/池候选的判重语义变得含混
    val baseIds = base.mapTo(HashSet(base.size)) { it.id }
    val extra = pool.filter { it.id !in baseIds }
    val cards = (base + extra).associateBy { it.id }
    val sim = QuestionSimularity(base + extra)
    val items = base.mapIndexed { index, card ->
        QueueItem(id = card.id, urgency = (base.size - index).toFloat(), subject = card.subject)
    }
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
 */
internal fun semanticInterleaveHint(): String =
    "把题面文字上就很像的题尽量隔开几道出；同一轮里已经出现的同考点题，会尽量隔三到五题再遇一次 —— " +
        "挪不开就照原样出。判据是题干文字与考点标签，不联网、不装额外东西。关掉这一层就只按学科打散。"

/** 会话内提示（文档 L1266 逐字，测试「会话内提示文案逐字钉住」一字一钉）。它只解释「为什么隔开放」，不解释相似度是怎么算的。 */
internal fun confusableDrillTip(): String = "这两张卡容易混，隔开放是故意的。"
