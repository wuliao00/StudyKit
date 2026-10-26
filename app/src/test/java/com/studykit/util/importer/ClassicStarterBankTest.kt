package com.studykit.util.importer

import com.studykit.data.question.ExamStage
import com.studykit.data.question.QuestionType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置示例题库必须真的能导进去。
 *
 * 这条测试守的不是“文本非空”，而是整份资产的**格式契约**：38 行全部可解析、0 行待修正、
 * 6 个阶段与 8 种题型都出现。改题库时若多打一个竖线或写错题型别名，这里先红，
 * 而不是等用户点“示例题库”看到一片待修正。
 */
class ClassicStarterBankTest {

    @Test
    fun classicStarter_everyLineParsesAndCoversAllStagesAndTypes() {
        val plan = QuestionLineParser.parseAll(readAsset())

        assertEquals(
            "示例题库不应有待修正行：" + plan.rejected.joinToString { "第${it.sourceLine}行 ${it.reason}" },
            0,
            plan.rejected.size,
        )
        assertEquals(38, plan.items.size)

        val questions = plan.items.filterIsInstance<ImportItem.Question>()
        assertEquals(plan.items.size, questions.size)
        assertEquals(QuestionType.entries.toSet(), questions.map { it.type }.toSet())
        assertEquals(
            ExamStage.entries.filterNot { it == ExamStage.UNSPECIFIED }.toSet(),
            questions.map { it.stage }.toSet(),
        )
        assertTrue(
            "每道题都要有解析或评分要点",
            questions.all { it.explanation.isNotBlank() },
        )
    }

    private fun readAsset(): String {
        val candidates = listOf(
            File("src/main/assets/question_banks/classic-starter.txt"),
            File("app/src/main/assets/question_banks/classic-starter.txt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到示例题库资产；工作目录=${File(".").absolutePath}")
        return file.readText()
    }
}
