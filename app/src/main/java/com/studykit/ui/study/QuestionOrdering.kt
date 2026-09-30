package com.studykit.ui.study

import com.studykit.data.entity.Question

/**
 * 交错练习排序（interleaving）—— 纯函数、零 Compose、零 Android 依赖，抽出来的理由与
 * [RecallGate.kt] 完全一样：这类顺序规则写在界面上就只能靠真机一题题点数，写反了**不报错**。
 *
 * 证据口径（诚实版）：Brunmair & Richter 2019（Psychological Bulletin）对交错练习的
 * 元分析是**中等效应、且不是普适有效**；Kornell & Bjork 2008 显示它当下更费劲、后测更好。
 * 所以这里只承诺两件事：
 *  - 多学科时把同科连排打散（主观感受与客观效果错位的那一层，交给设置项说明与 TipId.INTERLEAVE）；
 *  - **只有一个学科时保持原序，不硬打散**—— 交错对单一材料没有意义，硬打乱只是平白添噪。
 *
 * ## 确定性
 * 算法不吃任何随机源：按「剩余条数最多者优先、与上一题同科时退让」的贪心走，
 * 同分时按学科**首次出现的先后**决出（[LinkedHashMap] 的迭代序）。同样的输入永远得到
 * 同样的输出，单测里钉的分布就是线上跑出来的分布，不存在"测试绿了真机不一样"。
 */

/** 综合练习的队列学科标签（`QuizUiState.subject` 用；单科轮永远是真实学科名，不会撞上它） */
const val MIXED_SUBJECT_LABEL = "综合练习"

/**
 * 把一轮题目的学科顺序打散。返回**新列表**或与 [questions] 同一引用（不打散时原样退回）。
 *
 * 保证的性质（逐条对应 `QuestionOrderingTest`）：
 *  - `enabled = false` ⇒ 原样返回（关掉就完全退回取数顺序，不留半截打乱）；
 *  - 空表 / 单题 / 只有一个学科 ⇒ 原样返回（交错对单一材料无意义）；
 *  - 条目不增不减、不重复，同科内部保持录入的相对次序；
 *  - 确定性可复现；
 *  - 尽力而无保证：某一科占比过半时不可能完全避免连排，此时把它均匀摊开而不是硬凑。
 */
internal fun interleaveBySubject(questions: List<Question>, enabled: Boolean): List<Question> {
    if (!enabled || questions.size < 2) return questions
    val buckets = LinkedHashMap<String, ArrayDeque<Question>>()
    for (question in questions) {
        buckets.getOrPut(question.subject) { ArrayDeque() }.addLast(question)
    }
    if (buckets.size < 2) return questions

    val result = ArrayList<Question>(questions.size)
    var lastSubject: String? = null
    while (result.size < questions.size) {
        // 默认避开与上一题同科；只剩同一科可取时放宽限制（能拆就拆，拆不动就连排）
        result.add(pickNext(buckets, avoid = lastSubject) ?: pickNext(buckets, avoid = null) ?: break)
        lastSubject = result.last().subject
    }
    return result
}

/**
 * 取下一条：在（可选地排除 [avoid] 后的）非空桶里拿**剩余最多**的一科，
 * 同剩余数时取先出现的科目 —— 两个决出条件都是确定的，没有随机。
 */
private fun pickNext(buckets: LinkedHashMap<String, ArrayDeque<Question>>, avoid: String?): Question? {
    var chosen: ArrayDeque<Question>? = null
    for ((subject, queue) in buckets) {
        if (queue.isEmpty() || subject == avoid) continue
        // 只比严格更多：同剩余数保持先出现者，迭代序（LinkedHashMap）保证确定性
        if (chosen == null || queue.size > chosen.size) chosen = queue
    }
    return chosen?.removeFirst()
}
