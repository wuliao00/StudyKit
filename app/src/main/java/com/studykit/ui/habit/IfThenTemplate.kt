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

/**
 * 那条 IF_THEN 贴士的**触发闸门**（计划 B Task 16 Step 4；B17 复审 ⚠2）。
 *
 * 计划里写的是「首次**成功保存**带 `ifThen` 的习惯」，不是「正在写 `ifThen`」：
 * 以前这一句直接挂在创建页的输入区下面，用户一个字都还没保存就能看到它，
 * 而且不进那个页面就永远看不到。现在闸门只认保存那一件事：
 *  - [justSavedWithIfThen] —— 刚发生的那一次 `createHabit` **成功**返回（不是点了保存按钮）；
 *  - [ifThenText] —— 那一次真的写了整句（空句不算，否则不打算执行意图的人也消费掉了一次机会）；
 *  - [tipSeen] —— 这台安装已经说过一次了（[com.studykit.data.AppSettings.ifThenTipSeen]）。
 *
 * 纯函数、零 Compose：创建页保存后立即 pop，所以渲染落在返回落点（习惯列表），
 * 那里只读 ViewModel 的一次性标记，判定全部留在这一处。
 */
internal fun ifThenTipShouldShow(
    justSavedWithIfThen: Boolean,
    ifThenText: String,
    tipSeen: Boolean,
): Boolean = justSavedWithIfThen && ifThenText.isNotBlank() && !tipSeen
