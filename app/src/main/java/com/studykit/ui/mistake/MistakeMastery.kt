package com.studykit.ui.mistake

/**
 * 自动掌握判定（app.docx 模块3 P1；spec §6 取"2–3 次"里的 2，取舍写进 CHANGELOG）。
 * [history] 按时间升序，每项 = (本次判对, 距上一次的间隔天)；首项间隔无意义传 0。
 * 规则只看**最近连续两次判对且间隔 ≥3 天**——与 hypercorrection 回弹证据同向
 * （Butler 2011：无重测时高置信错误一周后回弹，同日两连对不算数）。
 */
object MistakeMastery {
    const val MIN_GAP_DAYS = 3.0

    fun isMastered(history: List<Pair<Boolean, Double>>): Boolean {
        if (history.size < 2) return false
        val (lastOk, gap) = history.last()
        val (prevOk, _) = history[history.size - 2]
        return lastOk && prevOk && gap >= MIN_GAP_DAYS
    }
}
