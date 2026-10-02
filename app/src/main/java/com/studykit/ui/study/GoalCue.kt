package com.studykit.ui.study

/**
 * 目标梯度提示（Kivetz et al. 2006）：只陈述"还差多少"，不碰任何数字口径与进度环配色。
 *
 * 达成（[done] >= [goal]）或目标非法（[goal] <= 0）都返回 null —— 首页据此决定这一行出现与否，
 * 出现时也只是多一句 caption 文本，不改任何颜色、不劫持进度环的达成态。
 */
object GoalCue {
    fun text(done: Int, goal: Int): String? =
        if (goal <= 0 || done >= goal) null else "距今日目标还差 ${goal - done} 词"
}
