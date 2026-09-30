package com.studykit.ui.study

import com.studykit.data.entity.Question

/**
 * 反馈分层 + 挤牙膏提示（productive struggle）的判定与文案层。
 * 纯函数、零 Compose —— 与 [RecallGate.kt] / [QuestionOrdering.kt] 同一条纪律：
 * 「提示别剧透答案」「反馈分几层」这类规则一旦写进 Composable 就只能靠肉眼验收，写反了也不报错。
 *
 * 效应量口径（诚实版，Wisniewski et al. 2020 修正后 d≈0.48）：反馈是**中等**帮助，
 * 不是立竿见影，更不是成绩单。这一层的文案刻意不出现「保证 / 一定能」那种承诺。
 *
 * ## 挤牙膏的三档（线索 → 第一步 → 方法方向）
 * 三档模板**只引用学科**，绝不引用选项文本、也绝不引用解析原文。这不是偷懒，而是本 schema 下
 * 唯一能**结构性**保证"三档都不给最终答案"的写法：模板不吃答案，就无从把答案漏出去。
 * （本仓 questions 表没有单独的"逐题提示"字段，且不允许加列 —— 见改造约束。）
 */

/** 提示档数：线索 / 第一步 / 方法方向。第三档到底，再点也不给更多（完整解析要等作答后） */
internal const val HINT_TIER_COUNT = 3

/** 一题作答后的三层反馈：任务级 + 过程级 + 自我调节级（外加一份可展开的完整解析） */
internal data class QuizFeedback(
    /** 任务级：对不对、错时给出正确项 */
    val verdict: String,
    /** 过程级：错在哪一步 / 思路，取自 question.explanation */
    val process: String,
    /** 自我调节级：下一步建议，随「用了几档提示」变化 */
    val selfReg: String,
    /** 完整解析原文（仅在 [shouldRevealFullExplanation] 为真时摊开） */
    val explanation: String,
)

/**
 * 三档提示文本。逐档收紧，但没有一档给出正确项文本或「正确项」字样。
 * 只吃 [Question.subject]，不吃 options / explanation，因此不存在剧透路径。
 */
internal fun hintTiers(question: Question): List<String> = listOf(
    "先看问题本身：这道「${question.subject}」题到底要你判断什么？把题干里的关键条件圈出来。",
    "第一步：把每个选项分别代回题干，先排除明显与题干矛盾的那几个。",
    "方法方向：回想「${question.subject}」里这类判断对应的定义或公式，按通用做法推，而不是硬凑。",
)

/** 挤牙膏：一次只往前挪一档，[HINT_TIER_COUNT] 封顶。 */
internal fun nextHintLevel(current: Int): Int = (current + 1).coerceAtMost(HINT_TIER_COUNT)

/** 当前档位下已揭示的提示条目（越界也不会多揭示）。 */
internal fun revealedHints(question: Question, level: Int): List<String> =
    hintTiers(question).take(level.coerceIn(0, HINT_TIER_COUNT))

/**
 * 完整解析何时摊开：答错才给整段解析；答对时只给分层反馈，不再把解析灌一遍
 * （重复暴露是低效策略，见 TipId.RECALL_NOTES 那条证据口径）。
 */
internal fun shouldRevealFullExplanation(hintsUsed: Int, correct: Boolean): Boolean = !correct

/**
 * 组一题作答后的三层反馈。
 *
 * @param selected 用户所选下标（null 视为未作答，理论上不会走到这里）
 * @param options 已解析的选项文本（由调用方 [parseOptions] 传入，避免这里重复解析 JSON）
 * @param hintsUsed 作答前用了几档提示（0..[HINT_TIER_COUNT]）—— 自我调节级按它分四种建议
 */
internal fun buildFeedback(
    question: Question,
    selected: Int?,
    options: List<String>,
    hintsUsed: Int,
): QuizFeedback {
    val answerIndex = question.answerIndex
    val correct = selected != null && selected == answerIndex
    val correctLetter = ('A' + answerIndex).toString()
    val correctText = options.getOrNull(answerIndex).orEmpty()

    // 任务级
    val verdict = if (correct) {
        "回答正确"
    } else {
        val correctDesc = if (correctText.isBlank()) "正确项是 $correctLetter" else "正确项是 $correctLetter. $correctText"
        "回答错误，$correctDesc"
    }

    // 过程级：错在哪一步 / 思路，来自 explanation；脏数据（空解析）降级成一句可执行引导
    val explanation = question.explanation.trim()
    val process = if (explanation.isBlank()) {
        if (correct) "答对了。可以回想一下自己是走哪一步锁定答案的，下次同类型的题用同一个抓手。"
        else "这道题没留解析，只能自己复盘：先对照正确项，找出是从哪一步开始想岔的。"
    } else {
        "思路：$explanation"
    }

    // 自我调节级：随「对不对 × 用了几档提示」给出四种不同下一步
    val selfReg = when {
        correct && hintsUsed <= 0 ->
            "这题没靠提示就答对了，可以把它往后排，隔更久再回来看还记不记得。"
        correct ->
            "这次是顺着提示答对的，先记下是用到第 $hintsUsed 档走通的；明天再来一遍，试试不请提示。"
        hintsUsed <= 0 ->
            "没请提示却答错了，这类题优先重做：先对照正确项回想刚才的思路，再挑一道同类型的马上做一遍。"
        else ->
            "用到第 $hintsUsed 档提示还答错，先别死磕这一题：回到第一步，把卡住的那一步单独想清楚。"
    }

    return QuizFeedback(
        verdict = verdict,
        process = process,
        selfReg = selfReg,
        explanation = explanation,
    )
}

/**
 * 反馈那一句分寸说明（进 UI 底部一行）。刻意点破"反馈不是万能"，
 * 也不做任何"保证 / 一定能"式的承诺 —— 效应量按 d≈0.48 的中等预期管理。
 */
internal const val FEEDBACK_SCOPE_NOTE = "反馈能帮你定位思路，但它不是万能的，也不替你练；它的帮助是中等程度的，值得长期看，别把它当成绩单。"
