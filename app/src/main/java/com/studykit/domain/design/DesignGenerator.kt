package com.studykit.domain.design

/**
 * 方案拼装器：**模板拼装，不是模型生成**。
 *
 * 本应用纯本地、无后端。[generate] 只做一件确定的事：把 `mode` 的字段清单与用户填的值
 * 拼成一份结构化 Markdown —— 同样的输入永远得到同样的输出，没有网络、没有随机性、
 * 没有模型权重。因此它断网可用、可复现、可被单元测试逐字断言；界面上那句"生成方案"
 * 指的就是这里，而不是某次远程调用。
 *
 * 三条硬规矩：
 *  1. **不虚构**：用户没填的内容绝不补写。必填项为空写「（待补充）」占位；可选项为空则
 *     整段省略（不输出只有标题的空段）。数值、时长一律不替用户换算。
 *  2. **不丢结构**：多行字段逐行拆成 `- ` 列表；`A | B | C` 这类按列填写的字段转成
 *     Markdown 表格，列数对不上时降级为列表 —— 宁可不好看，也不能拼出一张错位的表。
 *  3. **可执行**：每种模式末尾都有一句「下一步」，是具体到能今天就做的动作，不是
 *     "继续加油"。
 *
 * 输出形状固定为：`# 标题` → `> 方法：…` → 若干 `## 段落` → `## 下一步`。
 */
object DesignGenerator {

    /**
     * 必填项留空时的占位文本。
     *
     * 用中文全角括号，是为了在预览里一眼区分"用户写的"和"系统留的位"；
     * 公开成常量方便测试与 UI 共用同一份字面量，避免哪天改字后两边对不上。
     */
    const val MISSING = "（待补充）"

    /**
     * 拼装一份 Markdown 方案。
     *
     * @param mode 模板；决定字段清单、段落顺序与表格列。
     * @param title 方案标题；trim 后为空时退回 [DesignMode.label]（不写"未命名"，
     *   因为模板名本身对用户就是有意义的信息）。
     * @param values 用户填的字段（键 = [DesignField.key]）。缺键、空串、全是空白都按空处理；
     *   不认识的键直接忽略 —— 旧方案里残留的字段不该冒到新正文里。
     */
    fun generate(mode: DesignMode, title: String, values: Map<String, String>): String {
        val doc = DesignDoc(mode, values, heading = title.trim().ifBlank { mode.label })

        when (mode) {
            DesignMode.FEYNMAN -> doc.run {
                section(
                    "概念与听众",
                    labeled("概念或主题" to text("concept"), "讲给谁听" to text("audience")),
                )
                section("用大白话解释", list("explanation"))
                section("卡住 / 说不清的地方", list("gaps"))
                section("类比与生活例子", list("analogy"))
                section("自测问题", list("selfTest"))
                next("把上面这段解释读给一个真人听，记录对方的每一次追问 —— 追问最多的地方，就是下次要补的缺口。")
            }

            DesignMode.GUIDED_PRACTICE -> doc.run {
                section(
                    "知识点与题型",
                    labeled("知识点" to text("topic"), "题型" to text("questionType")),
                )
                section("例题", list("sample"))
                section("解题步骤", list("steps"))
                section("易错点", list("traps"))
                section("变式题", list("variant"))
                section("下次遇到同类题先问什么", list("transfer"))
                next("遮住例题答案重做一遍，再用变式题自测；卡住的那一步，就是这次要补进「易错点」的那一步。")
            }

            DesignMode.LEARNING_TO_LEARN -> doc.run {
                section(
                    "目标与复盘时间",
                    labeled("当前目标" to text("goal"), "复盘时间" to text("reviewAt")),
                )
                section("现在怎么做", list("current"))
                section("最大卡点", list("bottleneck"))
                section("一周内的证据 / 数据", list("evidence"))
                section("接下来两周要试的方法", list("experiment"))
                section("继续 / 停止", list("keepDrop"))
                next("把复盘时间写进日历；到点只回答一个问题：这几条实验里，哪一条真的让证据变好了？")
            }

            DesignMode.WORKSHOP -> doc.run {
                section(
                    "主题与参与者",
                    labeled(
                        "主题" to text("theme"),
                        "参与者与人数" to text("audience"),
                        "总时长" to text("duration"),
                    ),
                )
                section("学习目标", list("goal"))
                section("破冰 / 激活", list("warmup"))
                section("核心活动", list("activity"))
                section("复盘问题", list("debrief"))
                section("材料与分工", list("materials"))
                next("按 10 分钟粒度把活动写成流程单，标出每一段的引导语；时间不够就砍内容，不砍复盘。")
            }

            DesignMode.SERIES -> doc.run {
                section("课程与对象", labeled("课程名" to text("course"), "对象" to text("audience")))
                section("课程目标", list("outcomes"))
                section("主线 / 递进逻辑", list("arc"))
                section("课时安排", table("sessionPlan", listOf("课时主题", "核心目标", "关键活动")))
                section("评价方式", list("assessment"))
                section("资源与作业", list("resources"))
                next("挑第一课，把它的核心目标改写成一句可观察的「学员能……」，写不出来就说明这一课还没准备好。")
            }

            DesignMode.BACKWARD -> doc.run {
                section("课标 / 目标来源", text("standard"))
                section("持久理解 / 大概念", list("enduring"))
                section("基本问题", list("questions"))
                section("可接受的证据 / 表现性任务", list("evidence"))
                section("学习活动", list("activities"))
                section("迁移情境", list("transfer"))
                next("逐条检查活动与证据的对应：在每条活动旁写下它支撑哪一条证据，写不出的活动删掉或改掉。")
            }

            DesignMode.COMPETENCY -> doc.run {
                section(
                    "学科与课题",
                    labeled("学科与学段" to text("subject"), "课题" to text("unit")),
                )
                section("素养目标", table("competency", listOf("素养", "具体表现")))
                section("核心任务", list("task"))
                section("评价量规", table("assessment", listOf("维度", "水平描述")))
                section("支架与资源", list("support"))
                section("课后反思点", list("reflection"))
                next("拿核心任务对着量规走一遍：找不到可评维度的任务要改小，直到量规能看见它。")
            }

            DesignMode.SPIRAL -> doc.run {
                section(
                    "核心概念与轮次",
                    labeled("核心概念" to text("concept"), "学段 / 轮次" to text("levels")),
                )
                section("第一轮：直观经验", list("round1"))
                section("第二轮：结构化解释", list("round2"))
                section("第三轮：迁移与创造", list("round3"))
                section("每轮如何判断「真的更深了」", list("check"))
                next("给每一轮各留一份作品或记录，三轮并排放在一起 —— 看不出后一轮更抽象，就还没真的螺旋上去。")
            }
        }

        return doc.render()
    }
}

/**
 * 拼装过程中的小工具：段落追加、字段取值、表格渲染。
 *
 * 单独成类而不是一堆顶层函数，是为了让"字段缺失怎么办"只有一套规则（必填占位 / 可选省略），
 * 各个 `when` 分支里不再重复判断。
 */
private class DesignDoc(
    private val mode: DesignMode,
    private val values: Map<String, String>,
    private val heading: String,
) {
    private val sb = StringBuilder()

    init {
        // 标题与"方法"行紧挨着：预览里这两行是同一块信息，空一行反而把出处拆散了。
        sb.append("# ").append(heading).append('\n')
        sb.append("> 方法：").append(mode.label).append(" · ").append(mode.tagline).append('\n')
    }

    /** 追加一个二级段落；正文为空（可选项全空）时整段不输出，不留空标题。 */
    fun section(title: String, body: String?) {
        if (body.isNullOrBlank()) return
        sb.append('\n').append("## ").append(title).append("\n\n")
        sb.append(body.trimEnd()).append('\n')
    }

    /** 固定收尾段落：一句可执行动作。 */
    fun next(action: String) = section("下一步", action)

    /** 单行字段：缺失且必填 → 占位；缺失且可选 → null（调用方据此省略整段）。 */
    fun text(key: String): String? = plain(field(key))

    /** 多行字段：逐行拆成 `- ` 列表。 */
    fun list(key: String): String? = bullets(field(key))

    /** 按列填写的字段：列数对得上就出表格，对不上降级成列表。 */
    fun table(key: String, headers: List<String>): String? = tableOf(field(key), headers)

    /**
     * 把若干个"标签 + 值"拼成一段：单行值写成 `**标签**：值`，多行/列表值另起一行。
     * 全部值都为 null（可选字段都没填）时返回 null，整段省略。
     */
    fun labeled(vararg rows: Pair<String, String?>): String? {
        val kept = rows.mapNotNull { (label, value) ->
            val body = value ?: return@mapNotNull null
            if (body.contains('\n') || body.startsWith("- ")) "**$label**\n\n$body" else "**$label**：$body"
        }
        return kept.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    fun render(): String = sb.toString().trimEnd() + "\n"

    private fun field(key: String): DesignField =
        mode.fields.firstOrNull { it.key == key }
            ?: error("内部错误：模板 ${mode.name} 没有字段 $key")

    private fun plain(field: DesignField): String? {
        val value = values[field.key].orEmpty().trim()
        return when {
            value.isNotEmpty() -> value
            field.required -> DesignGenerator.MISSING
            else -> null
        }
    }

    /** 非空行（已 trim、已丢空行）；整段空白返回 null，让调用方走"缺失"分支。 */
    private fun nonEmptyLines(field: DesignField): List<String>? {
        val value = values[field.key].orEmpty().trim()
        if (value.isEmpty()) return null
        val trimmed = value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (trimmed.isEmpty()) null else trimmed
    }

    private fun bullets(field: DesignField): String? {
        val items = nonEmptyLines(field)
        return when {
            items != null -> items.joinToString("\n") { "- $it" }
            field.required -> DesignGenerator.MISSING
            else -> null
        }
    }

    /**
     * `A | B | C` → 表格。
     *
     * 只要**有一行**的列数与表头不符（少写、多写、整行没写竖线），整段就降级成列表：
     * 半张表比没有表更难读，而"降级"不会丢用户写的任何字。单元格不转义竖线 ——
     * 能进到这里的内容已经按竖线切过了，单元格内部不可能再含竖线。
     */
    private fun tableOf(field: DesignField, headers: List<String>): String? {
        val items = nonEmptyLines(field)
        if (items == null) return if (field.required) DesignGenerator.MISSING else null
        val rows = items.map { line -> line.split('|').map { cell -> cell.trim() } }
        if (rows.any { it.size != headers.size }) {
            return items.joinToString("\n") { "- $it" }
        }
        val out = StringBuilder()
        out.append("| ").append(headers.joinToString(" | ")).append(" |\n")
        out.append("| ").append(headers.joinToString(" | ") { "---" }).append(" |\n")
        rows.forEachIndexed { index, cells ->
            out.append("| ").append(cells.joinToString(" | ")).append(" |")
            if (index != rows.lastIndex) out.append('\n')
        }
        return out.toString()
    }
}
