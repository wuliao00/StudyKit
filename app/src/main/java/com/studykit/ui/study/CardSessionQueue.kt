package com.studykit.ui.study

import com.studykit.data.entity.Word

/**
 * 卡片学习会话的**队列组成**纯函数。
 *
 * 从 [StudyViewModel.startCardSession] 里抽出来，只为一件事：让"这一轮到底装哪些词"
 * 能被单测钉住，而不必把整个 ViewModel + Room + Android 框架拉起来。仓内同一姿态已有先例
 * （`TomorrowForecast` / `QuestionOrdering` / `RecallGate` / `StudyStreak` 都是这种可单测的纯函数）。
 *
 * v2.3 之前组轮口径是 `status != MASTERED && nextReviewAt <= now`——`next_review_at = 0` 的新词
 * 天然落在 `<= now` 里，所以"背新词"和"复习到期"走的是同一支。v2.3 把判据换成
 * `next_review_at in 1L..now`（[Word] 的"有排期"真判据，见 `WordDao.getDueForReview` KDoc），
 * 副作用是把从没被排过期的新词**整批挡在队列外**：新词只有被评分一次才会拿到
 * `next_review_at > 0`，可它进不了会话就永远没机会被评分——一个先有鸡还是先有蛋的死锁。
 * 演示数据恰好给新词排了 `now`（见 `DemoSeeder.seedWords`），把这条缺口盖住了，真实导入的词库才暴露它。
 */
internal object CardSessionQueue {

    /**
     * 从没排过期的新词：`total_reviews == 0` 且 `next_review_at <= 0`。
     *
     * 两个条件各挡一种误判：
     *  - 单看 `next_review_at <= 0` 会把"被侧信道/手工清过排期但已复习过"的词当新词；
     *  - 单看 `total_reviews == 0` 理论上与前者等价（只有 [WordRepository.applyReview] 会写
     *    `next_review_at > 0` 且同一条 UPDATE 里 `total_reviews + 1`），写上两条是把意图钉死。
     */
    fun isFresh(word: Word): Boolean = word.totalReviews == 0 && word.nextReviewAt <= 0L

    /**
     * 组一轮队列。
     *
     * @param words 全量词（调用方一次 `getAll()` 取回，这里不再回源）。
     * @param now 进入会话那一刻的时刻。
     * @param dailyWordGoal 每日目标词数（`AppSettings.dailyWordGoal`，语义 = 今天新学满 N 词达标）。
     * @param todayReviewed 今天已经复习/学习过的条数（用于把新词补位卡在剩余目标内）。
     * @param dueCap 到期复习那一侧的条数上限（沿用 `SESSION_SIZE`，防止复习量失控）。
     *
     * 顺序：**到期优先**，其后才是新词补位；两段拼接。
     */
    fun compose(
        words: List<Word>,
        now: Long,
        dailyWordGoal: Int,
        todayReviewed: Int,
        dueCap: Int,
    ): List<Word> {
        // 到期优先：有排期且已到期的，按到期时刻升序，先清欠账
        val due = words.asSequence()
            .filter { it.nextReviewAt in 1L..now }
            .sortedBy { it.nextReviewAt }
            .take(dueCap)
            .toList()
        // 新词补位：到期一支不足以撑起一轮时，从没复习过的新词按加入顺序补到剩余每日目标。
        // 补位条数 = dailyWordGoal − todayReviewed（夹到 >=0）；新词一旦在这轮被评分，
        // gradeCard 会走 applyReview 给它排出 next_review_at>0，之后自然转成正常到期复习。
        // 这里只把新词**放进会话**，不改任何一行的 next_review_at（不做批量 UPDATE 的数据补丁）。
        val newSlots = (dailyWordGoal - todayReviewed).coerceAtLeast(0)
        val fresh = if (newSlots == 0) {
            emptyList()
        } else {
            words.asSequence()
                .filter { isFresh(it) }
                .sortedBy { it.createdAt } // 先录入的先学
                .take(newSlots)
                .toList()
        }
        return due + fresh
    }
}
