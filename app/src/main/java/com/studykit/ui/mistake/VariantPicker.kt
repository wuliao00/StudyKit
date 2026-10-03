package com.studykit.ui.mistake

import com.studykit.data.entity.Question
import kotlin.random.Random

/**
 * 同考点变式题的选取（v2.7 计划 B Task 15；app.docx 模块3「换一道同考点的」）。
 *
 * 抽成纯函数的理由与 `RedoFlow` / `MistakeScheduling` 一模一样：「同 tag、排除自身、拿不到就说不出来」
 * 这三条规则一旦写进 Composable 就只能靠真机点，写反了（比如把原题又选回去、或拿空 tag 去匹配一堆未标注的题）
 * 也不报错。这里一个 Room / Android API 都不出现，逐条钉在 `VariantPickerTest`。
 *
 * ## 口径
 * - 变式只在 `conceptTag` **完全相等**且**不是自己**的题里挑：本题的 schema 没有知识点树（spec §3.2/G7），
 *   标签是录题时手填的自由文本，不做包含、前缀之类的模糊匹配 —— 宁可少给，不给错考点的题。
 * - `conceptTag` 为空串（未标注）→ 返回 null：没有考点可言，UI 据此**不出现**「换一道同考点的」按钮。
 * - 同考点只剩自己、或压根没有同考点的题 → 返回 null：无候选就不假装能换。
 * - 随机由**注入的 [random]** 决定：生产传 `Random.Default`，测试传固定种子复现同一道，
 *   UI 那侧的「点了真的换掉」也因此可测（不会因随机而变成掷骰子）。
 *
 * 只**读取**候选、不改数据库：这条路径替换的是「这一次重做哪道题」（会话内），
 * 错题行本身一个字都不动（见 `MistakeDetailScreen` 的替换按钮，无 DB 写回）。
 */
internal object VariantPicker {

    /**
     * 从 [all] 里给 [questionId] 那道题挑一道同考点的变式。
     *
     * @param questionId 当前重做对象那道题的 id（拿它的 `conceptTag` 当匹配依据）。
     * @param all 候选池，通常是本页已加载的题目集合；[questionId] 必须能在其中找到，否则无从取标签。
     * @param random 随机源，默认 [Random.Default]；测试注入固定种子以复现选取结果。
     * @return 同考点非自身的一道题；空 tag / 无候选 / 找不到目标 → null。
     */
    fun pick(questionId: Long, all: List<Question>, random: Random = Random.Default): Question? {
        val tag = all.firstOrNull { it.id == questionId }?.conceptTag?.takeIf { it.isNotBlank() }
            ?: return null
        val candidates = all.filter { it.conceptTag == tag && it.id != questionId }
        if (candidates.isEmpty()) return null
        return candidates[random.nextInt(candidates.size)]
    }
}
