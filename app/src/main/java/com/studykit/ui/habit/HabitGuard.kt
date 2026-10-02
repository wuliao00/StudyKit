package com.studykit.ui.habit

/**
 * 断签保护（app.docx 第三部分#4 宽恕机制；spec D6：每月 2 次、纯推导零落库）。
 * 输入 = 某自然月逐日打卡布尔（当月 1 号起）。缺卡按出现顺序编号，
 * 第 1、2 次受保护（连续天数跨它延续、热力图灰圈标识），第 3 次起照常断链。
 * 依据 Lally 2010：漏一天不损害自动性增长——受保护的本该是常态而非事故。
 */
object HabitGuard {
    const val MONTHLY_ALLOWANCE = 2

    /** 日详情气泡文案（钉死可测；措辞先共情后规则，不带刑罚味） */
    const val MISSING_GUARD_TEXT = "未打卡（已用断签保护）"

    fun guardedIndices(missPattern: List<Boolean>): List<Int> =
        missPattern.indices.filter { !missPattern[it] }.take(MONTHLY_ALLOWANCE)

    fun breaksAt(missPattern: List<Boolean>, index: Int): Boolean =
        !missPattern[index] && !guardedIndices(missPattern).contains(index)
}
