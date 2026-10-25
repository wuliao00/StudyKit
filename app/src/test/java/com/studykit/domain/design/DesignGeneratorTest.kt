package com.studykit.domain.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8 种模板的拼装证明（纯 JVM，不需要 Robolectric —— `domain/design` 不 import Android）。
 *
 * 每条用例都同时钉三件事：用户的字**原样出现**在正文里、必填缺失时有「（待补充）」、
 * 全文以「下一步」收尾且没有空标题。表格模式（系列课 / 核心素养）额外钉表头与行数，
 * 以及"缺列时降级成列表而不是崩"。
 */
class DesignGeneratorTest {

    @Test
    fun feynmanKeepsExplanationGapsAndAnalogy() {
        val md = DesignGenerator.generate(
            DesignMode.FEYNMAN,
            "机会成本",
            mapOf(
                "concept" to "机会成本",
                "audience" to "我室友",
                "explanation" to "你选了电影票，就放弃了同一晚复习的机会\n所以成本是你放弃的那件事",
                "gaps" to "说不清沉没成本算不算机会成本",
                "analogy" to "周末晚上只有一场电影",
                "selfTest" to "沉没成本算机会成本吗？\n为什么机会成本按次优选择算？",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.startsWith("# 机会成本\n"))
        assertTrue(md.contains("> 方法：费曼学习 · 讲给外行听，暴露理解缺口"))
        assertTrue(md.contains("**概念或主题**：机会成本"))
        assertTrue(md.contains("**讲给谁听**：我室友"))
        assertTrue(md.contains("- 你选了电影票，就放弃了同一晚复习的机会"))
        assertTrue(md.contains("- 所以成本是你放弃的那件事"))
        assertTrue(md.contains("- 说不清沉没成本算不算机会成本"))
        assertTrue(md.contains("- 周末晚上只有一场电影"))
        assertTrue(md.contains("## 自测问题\n\n- 沉没成本算机会成本吗？\n- 为什么机会成本按次优选择算？\n"))
    }

    @Test
    fun guidedPracticeKeepsStepsTrapsAndVariants() {
        val md = DesignGenerator.generate(
            DesignMode.GUIDED_PRACTICE,
            "判别式带练",
            mapOf(
                "topic" to "一元二次方程判别式",
                "questionType" to "计算",
                "sample" to "已知 x²-4x+m=0 有两个不相等实根，求 m 的取值范围",
                "steps" to "先算判别式 Δ=b²-4ac\n再按 Δ 的符号分类讨论",
                "traps" to "把 Δ=0 当成无解",
                "variant" to "把 m 改成参数 k，再问取值范围",
                "transfer" to "先问：判别式到底在判断什么？",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.startsWith("# 判别式带练\n"))
        assertTrue(md.contains("**知识点**：一元二次方程判别式"))
        assertTrue(md.contains("**题型**：计算"))
        assertTrue(md.contains("- 先算判别式 Δ=b²-4ac"))
        assertTrue(md.contains("- 再按 Δ 的符号分类讨论"))
        assertTrue(md.contains("- 把 Δ=0 当成无解"))
        assertTrue(md.contains("- 把 m 改成参数 k，再问取值范围"))
        assertTrue(md.contains("## 下次遇到同类题先问什么"))
    }

    @Test
    fun learningToLearnPutsEvidenceBeforeExperiment() {
        val md = DesignGenerator.generate(
            DesignMode.LEARNING_TO_LEARN,
            "背词方法调整",
            mapOf(
                "goal" to "两个月过完四级核心词",
                "current" to "每天通勤时刷 30 个新词",
                "bottleneck" to "第二天几乎全忘",
                "evidence" to "7 天里复习正确率 46%，平均只回看 1 次",
                "experiment" to "改用间隔 + 检索：睡前回忆 10 个词\n取消重读单词书",
                "keepDrop" to "继续睡前检索；停止抄写三遍",
                "reviewAt" to "两周后的周日 20:00",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.contains("**当前目标**：两个月过完四级核心词"))
        assertTrue(md.contains("**复盘时间**：两周后的周日 20:00"))
        assertTrue(md.contains("- 7 天里复习正确率 46%，平均只回看 1 次"))
        assertTrue(md.contains("- 继续睡前检索；停止抄写三遍"))
        assertTrue(
            "证据段要排在实验段之前：先看数据再改方法",
            md.indexOf("## 一周内的证据") < md.indexOf("## 接下来两周要试的方法"),
        )
    }

    @Test
    fun workshopKeepsTimeBoxesAndSkipsEmptyOptionalSection() {
        val md = DesignGenerator.generate(
            DesignMode.WORKSHOP,
            "教研会改成工作坊",
            mapOf(
                "theme" to "把教研会开成工作坊",
                "audience" to "初二数学组 12 人（4 人一组）",
                "duration" to "90 分钟",
                "goal" to "结束时每组产出一份可用的活动设计",
                "warmup" to "每人写一张「最没用的一次教研」便签，贴到墙上分类",
                "activity" to "两两互换教案，找出一处「讲了但没练」\n组内选一个改写成活动",
                "debrief" to "刚才哪一步让你改变了做法？为什么？",
                "materials" to "",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.startsWith("# 教研会改成工作坊\n"))
        assertTrue(md.contains("**参与者与人数**：初二数学组 12 人（4 人一组）"))
        assertTrue(md.contains("**总时长**：90 分钟"))
        assertTrue(md.contains("- 两两互换教案，找出一处「讲了但没练」"))
        assertFalse("可选字段留空时整段省略，不留空标题", md.contains("材料与分工"))
    }

    @Test
    fun seriesRendersSessionPlanAsThreeColumnTable() {
        val md = DesignGenerator.generate(
            DesignMode.SERIES,
            "高一函数入门",
            mapOf(
                "course" to "高一函数入门（8 课时）",
                "audience" to "高一平行班",
                "outcomes" to "能判断两个变量之间是不是函数关系\n能用图像描述一次函数的增减",
                "arc" to "从「变化」到「关系」再到「模型」",
                "sessionPlan" to "函数的概念 | 说清自变量与对应关系 | 图像匹配活动\n" +
                    "一次函数 | 能从图像读出斜率 | 画图接龙\n" +
                    "函数应用 | 能建模分段计费 | 真实账单分析",
                "assessment" to "小测：每题标注考查的目标号",
                "resources" to "",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.contains("| 课时主题 | 核心目标 | 关键活动 |"))
        assertTrue(md.contains("| --- | --- | --- |"))
        assertTrue(md.contains("| 函数的概念 | 说清自变量与对应关系 | 图像匹配活动 |"))
        assertTrue(md.contains("| 函数应用 | 能建模分段计费 | 真实账单分析 |"))
        assertEquals(
            "三行课时必须变成三行表格数据",
            3,
            md.lines().count { it.startsWith("| ") && !it.startsWith("| ---") && !it.startsWith("| 课时主题") },
        )
        assertTrue(md.contains("- 能判断两个变量之间是不是函数关系"))
        assertFalse("可选字段留空时整段省略", md.contains("资源与作业"))
    }

    @Test
    fun seriesFallsBackToListWhenColumnsAreMissing() {
        val md = DesignGenerator.generate(
            DesignMode.SERIES,
            "缺列的课时表",
            mapOf(
                "course" to "高一函数入门",
                "outcomes" to "能判断两个变量之间是不是函数关系",
                "arc" to "从「变化」到「关系」",
                "sessionPlan" to "第一课：函数是什么\n第二课 | 能画图像 | 画图接龙",
                "assessment" to "小测",
            ),
        )

        assertWellFormed(md)
        assertFalse("有一行缺列就整段降级，不许拼出半张表", md.contains("| 课时主题"))
        assertTrue("降级成列表时一个字都不许丢", md.contains("- 第一课：函数是什么"))
        assertTrue(md.contains("- 第二课 | 能画图像 | 画图接龙"))
    }

    @Test
    fun backwardPutsEvidenceBeforeActivities() {
        val md = DesignGenerator.generate(
            DesignMode.BACKWARD,
            "浮力：先证据后活动",
            mapOf(
                "standard" to "义务教育物理课程标准（2022）「浮力」",
                "enduring" to "浮力是压力差，不是「物体想浮起来」",
                "questions" to "为什么铁块沉、铁船浮？\n浮力大小到底由什么决定？",
                "evidence" to "学生用两种方法测同一块石头的浮力，并解释差异",
                "activities" to "称重法测浮力\n排水法测浮力\n两组结果对比讨论",
                "transfer" to "解释潜水艇上浮与下潜时浮力的变化",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.startsWith("# 浮力：先证据后活动\n"))
        assertTrue(md.contains("## 课标 / 目标来源\n\n义务教育物理课程标准（2022）「浮力」\n"))
        assertTrue(md.contains("- 学生用两种方法测同一块石头的浮力，并解释差异"))
        assertTrue(md.contains("- 解释潜水艇上浮与下潜时浮力的变化"))
        assertTrue(
            "逆向设计：证据必须排在活动之前",
            md.indexOf("## 可接受的证据") < md.indexOf("## 学习活动"),
        )
    }

    @Test
    fun competencyRendersCompetencyAndRubricTables() {
        val md = DesignGenerator.generate(
            DesignMode.COMPETENCY,
            "浮力备课",
            mapOf(
                "subject" to "初中物理 八年级",
                "unit" to "浮力（第 1 课时）",
                "competency" to "科学思维 | 能基于证据解释浮力现象\n" +
                    "实验探究 | 能设计控制变量的实验\n" +
                    "社会责任 | 能说出浮力知识在救援中的应用",
                "task" to "用称重法与排水法各测一次浮力，并解释两次结果的差异",
                "assessment" to "解释水平 | 能说出浮力方向与大小关系\n" +
                    "实验水平 | 能独立完成三步测量\n" +
                    "迁移水平 | 能在新情境中解释浮沉",
                "support" to "",
                "reflection" to "",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.contains("| 素养 | 具体表现 |"))
        assertTrue(md.contains("| 科学思维 | 能基于证据解释浮力现象 |"))
        assertTrue(md.contains("| 社会责任 | 能说出浮力知识在救援中的应用 |"))
        assertTrue(md.contains("| 维度 | 水平描述 |"))
        assertTrue(md.contains("| 解释水平 | 能说出浮力方向与大小关系 |"))
        assertTrue(md.contains("| 迁移水平 | 能在新情境中解释浮沉 |"))
        assertEquals(
            "两张表各 3 行数据（表头与分隔行不计）",
            6,
            md.lines().count { it.startsWith("| ") && !it.startsWith("| ---") && !it.startsWith("| 素养") && !it.startsWith("| 维度") },
        )
        assertTrue(md.contains("- 用称重法与排水法各测一次浮力，并解释两次结果的差异"))
        assertFalse(md.contains("支架与资源"))
        assertFalse(md.contains("课后反思点"))
    }

    @Test
    fun spiralKeepsThreeRoundsInOrder() {
        val md = DesignGenerator.generate(
            DesignMode.SPIRAL,
            "能量守恒三轮",
            mapOf(
                "concept" to "能量守恒",
                "levels" to "小学 → 初中 → 高中",
                "round1" to "用滑轨小车测「消耗的高度去哪了」",
                "round2" to "写出 E初 = E末 + 摩擦生热 的符号表达",
                "round3" to "设计一个「最省电」的教室照明方案并论证",
                "check" to "第一轮：能指出能量转移的痕迹\n第二轮：能用式子算出差额\n第三轮：能在新情境里迁移",
            ),
        )

        assertWellFormed(md)
        assertTrue(md.contains("**核心概念**：能量守恒"))
        assertTrue(md.contains("**学段 / 轮次**：小学 → 初中 → 高中"))
        assertTrue(md.contains("- 用滑轨小车测「消耗的高度去哪了」"))
        assertTrue(md.contains("- 设计一个「最省电」的教室照明方案并论证"))
        assertTrue(md.contains("- 第三轮：能在新情境里迁移"))
        assertTrue(
            "三轮必须按 1→2→3 排",
            md.indexOf("## 第一轮") < md.indexOf("## 第二轮") &&
                md.indexOf("## 第二轮") < md.indexOf("## 第三轮"),
        )
    }

    @Test
    fun missingRequiredFieldsShowPlaceholderInsteadOfInventedContent() {
        val md = DesignGenerator.generate(
            DesignMode.FEYNMAN,
            "",
            mapOf("concept" to "  机会成本  "),
        )

        assertWellFormed(md)
        assertTrue("标题留空时退回模板名", md.startsWith("# 费曼学习\n"))
        assertTrue("字段值要 trim 后再用", md.contains("**概念或主题**：机会成本"))
        assertTrue(md.contains("**讲给谁听**：（待补充）"))
        assertTrue(md.contains("## 用大白话解释\n\n（待补充）\n"))
        assertFalse("不许把缺失字段写成一句像模像样的话", md.contains("是一种"))
    }

    @Test
    fun emptyInputStillGivesSkeletonForEveryMode() {
        assertEquals("模板顺序即展示顺序，8 种一个不少", 8, DesignMode.entries.size)
        DesignMode.entries.forEach { mode ->
            val md = DesignGenerator.generate(mode, "   ", emptyMap())
            assertTrue("${mode.name} 缺标题时要退回模板名", md.startsWith("# ${mode.label}\n"))
            assertTrue("${mode.name} 要标出方法依据", md.contains("> 方法：${mode.label} · ${mode.tagline}"))
            assertTrue("${mode.name} 至少有一个必填项，空输入应出现占位", md.contains("（待补充）"))
            assertWellFormed(md)
        }
    }

    @Test
    fun everyModeHasUniqueFieldKeysAndARequiredField() {
        DesignMode.entries.forEach { mode ->
            assertTrue("${mode.name} 至少要有一个必填字段", mode.fields.any { it.required })
            assertEquals(
                "${mode.name} 的字段键不许重复（重复会互相覆盖）",
                mode.fields.size,
                mode.fields.map { it.key }.toSet().size,
            )
            assertTrue("${mode.name} 的字段都要有提示", mode.fields.all { it.hint.isNotBlank() })
        }
    }

    @Test
    fun fromStorageReadsEnumNamesAndRejectsUnknown() {
        DesignMode.entries.forEach { assertEquals(it, DesignMode.fromStorage(it.name)) }
        assertEquals(DesignMode.FEYNMAN, DesignMode.fromStorage("  FEYNMAN  "))
        assertNull(DesignMode.fromStorage(null))
        assertNull(DesignMode.fromStorage(""))
        assertNull(DesignMode.fromStorage("费曼学习"))
        assertNull(DesignMode.fromStorage("feynman"))
    }

    /** 全文形状：一级标题 + 方法行 + 以「下一步」收尾，且没有空段落、没有连续空行。 */
    private fun assertWellFormed(markdown: String) {
        assertTrue("必须以一级标题开头：\n$markdown", markdown.startsWith("# "))
        assertTrue("第二行必须是方法行：\n$markdown", markdown.contains("\n> 方法："))
        assertTrue("必须以「下一步」收尾：\n$markdown", markdown.contains("## 下一步\n\n"))
        assertFalse("不许出现空标题：\n$markdown", markdown.contains("## \n"))
        assertFalse("不许出现连续空行：\n$markdown", markdown.contains("\n\n\n"))
        assertTrue("正文以单个换行结尾：\n$markdown", markdown.endsWith("\n"))
        assertFalse("不许出现 Markdown 空链接式占位", markdown.contains("（暂无）"))
    }
}
