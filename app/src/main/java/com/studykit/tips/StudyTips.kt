package com.studykit.tips

/** 小贴士编号，UI 按 id 做「同一条不重复弹」的记录 */
enum class TipId {
    HYPERCORRECTION,
    MISS_ONE_DAY,
    SIXTY_SIX,
    PRETEST,
    RECALL_FIRST,
    INTERLEAVE,
    EXPLAIN_WHY,
    RECALL_NOTES,
    DESIRABLE_DIFFICULTY,
}

/** 一条带证据来源的提示 */
data class Tip(val id: TipId, val text: String, val evidence: String)

/** 触发时机：由具体交互事件驱动，不做随机弹出打扰 */
sealed class TipEvent {
    data object HighConfidenceMistake : TipEvent()
    data object GapDay : TipEvent()
    data class StreakReached(val days: Int) : TipEvent()
    data object NewCardFirstLook : TipEvent()
    data object AboutToFlip : TipEvent()
    data class BlockingStreak(val items: Int, val subjects: Int) : TipEvent()
    data object ChapterFinished : TipEvent()
    data object ExcerptOnlyNoRecall : TipEvent()
    data object StrugglingReview : TipEvent()
    data object Idle : TipEvent()
}

/**
 * 科学小贴士文案库。
 *
 * 策略：用反直觉但站得住的认知科学事实，把用户推向检索、间隔、交错、重做
 * 这些「当下更费劲、长期记得更牢」的行为；同时破除 21 天神话、学习风格等
 * 已被证伪的说法。每条都必须挂证据来源，UI 上以 [科学验证] 标签呈现。
 */
object StudyTips {

    /** 统一徽标文案，供 Tip 组件展示 */
    const val TAG_LABEL = "[科学验证]"

    /** 连续打卡 21 天时提示「66 天才是中位数」的那个节点 */
    const val STREAK_MYTH_DAY = 21

    /** 单学科连做多少题后开始提示交错 */
    const val BLOCKING_STREAK_THRESHOLD = 6

    private val byId: Map<TipId, Tip> = listOf(
        Tip(
            id = TipId.HYPERCORRECTION,
            text = "越有把握却答错的题，纠正之后记得最牢。这份惊讶正是记忆的锚点。",
            evidence = "Metcalfe & Shimamura 2011；Metcalfe 2017, Annual Review of Psychology",
        ),
        Tip(
            id = TipId.MISS_ONE_DAY,
            text = "漏掉一天不会毁掉习惯，研究里它几乎必然发生。别有负担，接着做就好。",
            evidence = "Lally et al. 2010, European Journal of Social Psychology",
        ),
        Tip(
            id = TipId.SIXTY_SIX,
            text = "第 21 天不是终点：习惯自动化中位数约 66 天，18 到 254 天都算正常。",
            evidence = "Lally et al. 2010, European Journal of Social Psychology",
        ),
        Tip(
            id = TipId.PRETEST,
            text = "先猜再学：哪怕猜错，尝试提取也会让接下来这一遍记得更牢。",
            evidence = "Kornell, Hays & Bjork 2009, JEP:LMC；Richland et al. 2009",
        ),
        Tip(
            id = TipId.RECALL_FIRST,
            text = "看懂不等于记住。先在脑子里把答案挤一遍，再翻这一面。",
            evidence = "Roediger & Karpicke 2006；Karpicke & Blunt 2011, Science",
        ),
        Tip(
            id = TipId.INTERLEAVE,
            text = "混着做当下更费劲，却更容易分辨考点。觉得难是正常的。",
            evidence = "Brunmair & Richter 2019, Psychological Bulletin；Kornell & Bjork 2008",
        ),
        Tip(
            id = TipId.EXPLAIN_WHY,
            text = "合上书问一句：作者为什么这么说？用自己的话复述一遍再往下读。",
            evidence = "Dunlosky et al. 2013, PSPI（精加工提问，中等效用）",
        ),
        Tip(
            id = TipId.RECALL_NOTES,
            text = "划线只是再次暴露，不是保留。把这条书摘变成一次自测更有用。",
            evidence = "Dunlosky et al. 2013, PSPI（高亮与重读为低效用策略）",
        ),
        Tip(
            id = TipId.DESIRABLE_DIFFICULTY,
            text = "卡壳的感觉是信号，不是坏消息：费力提取出来的东西留得最久。",
            evidence = "Bjork 1994，必要难度（desirable difficulties）",
        ),
    ).associateBy { it.id }

    /** 全量文案，供测试与设置页展示 */
    val all: List<Tip> = byId.values.toList()

    /** 按事件取提示；没有匹配的事件返回 null，绝不随机凑一条 */
    fun forEvent(event: TipEvent): Tip? = when (event) {
        TipEvent.HighConfidenceMistake -> byId[TipId.HYPERCORRECTION]
        TipEvent.GapDay -> byId[TipId.MISS_ONE_DAY]
        is TipEvent.StreakReached -> if (event.days == STREAK_MYTH_DAY) byId[TipId.SIXTY_SIX] else null
        TipEvent.NewCardFirstLook -> byId[TipId.PRETEST]
        TipEvent.AboutToFlip -> byId[TipId.RECALL_FIRST]
        is TipEvent.BlockingStreak ->
            if (event.subjects <= 1 && event.items >= BLOCKING_STREAK_THRESHOLD) byId[TipId.INTERLEAVE] else null
        TipEvent.ChapterFinished -> byId[TipId.EXPLAIN_WHY]
        TipEvent.ExcerptOnlyNoRecall -> byId[TipId.RECALL_NOTES]
        TipEvent.StrugglingReview -> byId[TipId.DESIRABLE_DIFFICULTY]
        TipEvent.Idle -> null
    }

    /** 带徽标的展示文本 */
    fun render(tip: Tip): String = "${TAG_LABEL} ${tip.text}"
}
