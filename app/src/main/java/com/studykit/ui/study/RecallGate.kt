package com.studykit.ui.study

import com.studykit.data.entity.Word
import kotlin.random.Random

/**
 * 检索优先闸门 + 新词预测试的判定层（v2.5 §3.1 / §3.3）。
 * 纯函数、零 Compose、零 Android 依赖 —— 抽出来的动机与 [decideSwipe] 完全一样：
 * 这两处一个管「手感觉不觉得被拦住」，一个管「预测试是不是真的预测试」，
 * 都最容易在改界面时被顺手改坏，而且改坏了**不报错**，只能靠单测钉住。
 *
 * ## 为什么闸门与三档自评共用一枚判定
 * 闸门要同时管三个入口：左右滑动、卡下两颗「认识/不认识」、再下面那行三档自评。
 * 各写各的条件就会出现「滑动被拦下、按钮还能结算」那种半截闸门 —— 而这一版对用户的承诺是
 * 「关掉开关就完全退回旧行为」，承诺能不能兑现，取决于拦截点是不是只有一处。
 * 于是 [decideRecallGate]（滑动去哪）与 [canGradeNow]（按钮能不能按）是同一个
 * `(闸门开关, 已翻面)` 的两份投影，`AppSettings.recallBeforeGrade` 全仓只在这两个函数里被读。
 */

/** 一次拖拽结束后，这张卡该往哪走 —— [SwipeDecision] 过了闸门之后的结论 */
internal enum class GateAction {
    /** 这一手不够格（位移与甩速都没过阈）：回弹 */
    NONE,

    /**
     * 闸门拦下：**不结算**，把这一滑变成一次翻面（卡片弹回原位 + 翻过来）。
     *
     * 不是"拦下滑动什么都不做"：这个卡片的主动作就是滑，点了没反应会被当成 bug。
     * 把第一次滑动**变成**翻面，强制的是"答案先于评分出现"，不是"不许摸"。
     */
    FLIP,

    /** 右滑结算为「认识」 */
    SETTLE_KNOWN,

    /** 左滑结算为「不认识」 */
    SETTLE_UNKNOWN,
}

/**
 * 闸门的唯一判定。三条必钉的语义（[com.studykit.ui.study.CardStudyScreen] 的手势路径直接用它）：
 *  - 闸门开 + 未翻面 + 够格的滑动 ⇒ [GateAction.FLIP]（**不结算**）；
 *  - 已翻面（含预测试走完）+ 够格的滑动 ⇒ 按方向结算；
 *  - 闸门关 + 未翻面 + 够格的滑动 ⇒ 按方向结算 —— 这一条是"可关"承诺的证据，
 *    它红了就是"关了开关还在拦人"。
 *
 * @param revealed 这张卡的答案是否已经出现在屏幕上（= 界面里那枚 `flipped`；
 *                 预测试确认过后也算 true，见 [canGradeNow] 的文档）。
 */
internal fun decideRecallGate(
    decision: SwipeDecision,
    gateEnabled: Boolean,
    revealed: Boolean,
): GateAction = when (decision) {
    SwipeDecision.NONE -> GateAction.NONE

    // 闸门只认一个条件：答案露过没有。方向不参与拦截判断（左右两侧同规则），
    // 否则"左滑=忘记不需要看答案"会变成第二条需要向用户解释的暗规则。
    SwipeDecision.KNOWN -> if (gateEnabled && !revealed) GateAction.FLIP else GateAction.SETTLE_KNOWN

    SwipeDecision.UNKNOWN -> if (gateEnabled && !revealed) GateAction.FLIP else GateAction.SETTLE_UNKNOWN
}

/**
 * 评分入口（两颗按钮 + 三档自评那一行）此刻可不可用。
 *
 * - 预测试进行中一律不可用：正确项已经标在屏幕上，但用户还没确认，
 *   这时能按就会"看一眼选项就顺手按认识"（§3.3）。
 * - 闸门开且未翻面：不可用，与 [GateAction.FLIP] 同一条件 —— 三处入口从此不可能各拦一半。
 * - 闸门关：恒可用（且闸门关时预测试根本不会出现，`pretestActive` 必然 false），
 *   即完全退回 v2.4 的行为。
 */
internal fun canGradeNow(
    gateEnabled: Boolean,
    revealed: Boolean,
    pretestActive: Boolean,
): Boolean = !pretestActive && (!gateEnabled || revealed)

// ── 新词预测试（三选一）：出题本身是纯函数 ──────────────────────────────────

/** 一道预测试的选项数：1 个正确项 + [DISTRACTOR_COUNT] 个干扰项。少于这个数就整轮跳过 */
internal const val PRETEST_OPTION_COUNT = 3

/** 需要的干扰项条数。凑不满就说明"三选一"会变成二选一或"只有一个正确项"，那是假预测试 */
internal const val DISTRACTOR_COUNT = PRETEST_OPTION_COUNT - 1

/**
 * 一道已经出好的新词预测试。
 *
 * 与 `TomorrowLoad` / `CardSessionUi` 同一档：它是**状态**而不是算法，所以是 public ——
 * `StudyViewModel.pretest` 那条 StateFlow 要把它交出去。旁边那两个判定函数才是内部实现。
 */
data class RecallPretest(
    /** 题目绑的是哪张卡：切卡后旧题不能挂在新卡上（页面按这一列判命中，避免竞态） */
    val wordId: Long,
    /** 已打乱顺序的三项，恒 [PRETEST_OPTION_COUNT] 条、恒互不相同 */
    val options: List<String>,
    /** [options] 里正确项的下标 */
    val correctIndex: Int,
) {
    /** 题面自己该是自洽的：三项互不相同、正确项落在范围内 */
    internal val valid: Boolean
        get() = options.size == PRETEST_OPTION_COUNT &&
            options.distinct().size == PRETEST_OPTION_COUNT &&
            correctIndex in options.indices
}

/**
 * 这个词该不该试着出题（§3.3 的三个触发条件里的**前两条**）：闸门开着 + 该词是新词。
 *
 * 新词的判据取 `status == NEW` **且** `lastReviewAt == null`（`entity/Word.kt:14,27`）：
 * 只看完不复习的 `status` 会把那些"结算过但被判成学习中"的词也当新词，
 * 而 `lastReviewAt` 一旦被写就说明这个词已经被评分过一轮，预测试该过去了。
 * 第三条（能凑出 3 个互不相同的释义）由 [buildRecallPretest] 返回 null 表达。
 */
internal fun isPretestCandidate(word: Word, gateEnabled: Boolean): Boolean =
    gateEnabled && word.status == Word.STATUS_NEW && word.lastReviewAt == null

/**
 * 出这道题：正确项 = 该词的释义，干扰项从 [pool]（同词库/全表捞来的别的词的释义）里取。
 *
 * 返回 `null` = **整轮跳过**，页面直接进原有的翻面+评分流程。返回 null 的条件：
 *  - 可用释义不足 [PRETEST_OPTION_COUNT] 条（正确项 + 至少两个互不相同的干扰项）。
 *    退化成"二选一"或"只有一个正确项"都不做出 —— 猜中的概率分别是 50%/100%，
 *    那不叫检索练习，叫走个过场；
 *  - 该词释义为空（没有可标的正确项）。
 *
 * [pool] 里可能混着重复释义与空白释义：同一个词库里两个词写同一句释义是常态，
 * 数据库那条 SQL 已经 `GROUP BY meaning` 去过一次，这里再按**文本**去一次并掐掉首尾空白 ——
 * 去重不彻底的后果是给出一模一样的两个选项，用户选哪个都"对一半"，题就废了。
 *
 * 顺序按 [random] 打乱（默认线上用 `Random.Default`，单测传定种子的实例来验证位置分布）。
 */
internal fun buildRecallPretest(
    word: Word,
    pool: List<String>,
    random: Random = Random.Default,
): RecallPretest? {
    val answer = word.meaning.trim()
    if (answer.isEmpty()) return null
    val distractors = pool
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != answer }
        .distinct()
        .take(DISTRACTOR_COUNT)
        .toList()
    if (distractors.size < DISTRACTOR_COUNT) return null
    val options = (listOf(answer) + distractors).shuffled(random)
    return RecallPretest(wordId = word.id, options = options, correctIndex = options.indexOf(answer))
}

/**
 * 闸门第一次拦下人时那条说明的标题（文案是纯函数层的常量，为的是能被单测钉住）。
 *
 * 要点只有一个：先自己想一想，再翻面对答案 —— 想不起来也没关系。
 * 别写成"禁止滑动"那种口气：闸门强制的是答案出现的**顺序**，不是不许摸。
 */
internal const val RECALL_GATE_HINT_TITLE = "先想一想，再翻面"

/** 说明正文：一句给做法（想一想再翻面），一句给退路（想不起来没关系 + 设置里可关） */
internal const val RECALL_GATE_HINT_BODY = "刚才这一滑没有算评分，而是帮你把卡片翻了过来：\n" +
    "先自己想一想，再翻面对答案 —— 想不起来也没关系，想这一步本身就是最管用的一次记忆。" +
    "不习惯的话，设置里可以关掉「先回忆再评分」。"
