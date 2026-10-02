package com.studykit.ui.study

/**
 * 模考模式（v2.7 计划 B Task 13）的**纯状态机**：一次作答、交卷前不出任何对错信息。
 *
 * 与练习模式（`QuizScreen` + `StudyViewModel.selectOption` 的即时判定）完全分叉——
 * 这一台只记「选了哪个」，对错要等 [submit] 那一刻才算，且算出来的结果在 UI 里
 * 只有到 `ExamResultScreen` 才读得到。为什么要单独立一台：模考要模拟真实考试，
 * 逐题给反馈就等于把答案边答边摊开，测不出真实水平（spec「延迟反馈」承诺）。
 *
 * 纯函数层、零 Android / Room / Compose 依赖（同 [RecallGate] / [QuestionOrdering] / [QuizFeedback]
 * 的纪律）：「交卷前拿不拿得到对错」这种承诺一旦写进 Composable 就只能靠肉眼验收，
 * 写反了也不报错，所以判定收在这里、由 MockExamStateTest 钉死。
 *
 * 关于 [answerKey]：判对错要知道每题的 `Question.answerIndex`。真机上这一份由调用方
 * （`StudyViewModel`）在交卷时按已选中的整沓题灌进来；测试里可以留空——[submit] 会把它
 * 认成「没有正确项可对齐」，未答/答不上都判为错，路径不崩。
 *
 * @param questionIds 本轮试卷的题号序列（交卷结果按它去重后逐题给出）。
 * @param answerCount 选项数，供上层渲染题面用；状态机本身不拿它判分。
 * @param answerKey 题号 → 正确项下标；缺省空表，此时 [submit] 把所有题判为错（无崩）。
 */
class MockExamState(
    val questionIds: List<Long>,
    val answerCount: Int,
    private val answerKey: Map<Long, Int> = emptyMap(),
) {

    /**
     * 一题交卷后的答案：[selected] 为所选下标（未作答 = null），[correct] 为是否命中答案。
     * 只在 [submit] 之后存在；交卷前 [verdictOf] 一律给 null，这是"延迟反馈"的机检点。
     */
    data class Answer(val selected: Int?, val correct: Boolean)

    private val ids = questionIds.toSet()

    // 作答只往这里记，绝不顺手算对错——算的时机被 [submit] 独占。
    private val selections = LinkedHashMap<Long, Int>()

    // 交卷前恒为 null；一旦被赋值，[verdictOf] 才从它回读，[submit] 也不再重算。
    private var results: Map<Long, Answer>? = null

    /** 已作答的题数（顶栏「已答 x / y」用）。 */
    val answeredCount: Int get() = selections.size

    /** 是否已交卷。交卷后作答与再交卷都不再生效。 */
    val submitted: Boolean get() = results != null

    /**
     * 记录一次作答：同一题重复选择以最后一次为准（交卷前允许改答案）。
     * 题号不在本轮试卷里就忽略——不写进 selections、也就不会污染 [submit] 的结果集。
     */
    fun answer(questionId: Long, selected: Int) {
        if (submitted) return
        if (questionId !in ids) return
        selections[questionId] = selected
    }

    /**
     * 交卷前**永远**返回 null；交卷后返回该题的 [Answer]。
     * 这条不对称就是渲染守卫要钉的东西：UI 想在交卷前读到 `correct` 都没有入口。
     */
    fun verdictOf(questionId: Long): Answer? = results?.get(questionId)

    /**
     * 交卷：一次性把每题算成 (selected, correct) 并冻结。
     *
     * 未作答的题 [Answer.selected] 落 null、[Answer.correct] 判 false（跳过不等于蒙对）。
     * 重复调用返回同一份结果（幂等），不会二次改动已交卷的试卷。
     */
    fun submit(): Map<Long, Answer> {
        results?.let { return it }
        val computed = questionIds.distinct().associateWith { id ->
            val selected = selections[id]
            Answer(
                selected = selected,
                correct = selected != null && selected == answerKey[id],
            )
        }
        results = computed
        return computed
    }
}
