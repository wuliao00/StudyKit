package com.studykit.ui.habit

/**
 * 执行意图（if-then 计划）整句组装器（app.docx 模块4 P0；计划 B Task 16 / spec §7、D4）。
 *
 * 纯函数、零 Compose、零 Android —— 只把「何时 · 何地 → 做什么」三段拼成一句
 * "当…，我就…"，Gollwitzer & Sheeran 2006 的核心就是把目标翻译成这种情境-反应绑定。
 * 组装口径与 [com.studykit.data.entity.Habit.ifThen] 那一个列一一对应。
 *
 * 退化规则（缺一块就少一块，别让半截句子看起来像坏数据）：
 *  - [then]（做什么）为空 ⇒ 整句为空串：行为是这套方法的骨架，没有它就不该拼句子（创建页也据此
 *    把「做什么」当必填）；
 *  - [whenLabel]/[where] 为空 ⇒ 从"·"分隔的前情境段里省掉它，只留非空的那一段；
 *  - 三段全空 ⇒ 空串。
 */
object IfThenTemplate {
    fun compose(whenLabel: String, where: String, then: String): String {
        val action = then.trim()
        if (action.isEmpty()) return ""
        val cue = listOf(whenLabel.trim(), where.trim()).filter { it.isNotEmpty() }.joinToString(separator = "·")
        return if (cue.isEmpty()) "我就$action" else "当$cue，我就$action"
    }
}
