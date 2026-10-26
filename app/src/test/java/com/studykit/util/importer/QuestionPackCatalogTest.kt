package com.studykit.util.importer

import com.studykit.data.question.ExamStage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题库商店目录 + 六个题包的格式契约。
 *
 * 这些题包会出现在“下载并预览”里，任何一个包计数与 manifest 对不上、或出现待修正行，
 * 用户下载后看到的都是坏结果。所以和经典示例题库一样，用资产原文跑解析器。
 */
class QuestionPackCatalogTest {

    @Test
    fun manifest_coversAllStagesWithVerifiableMetadata() {
        val catalog = QuestionPackCatalogParser.parse(read("question_banks/manifest.json"))

        assertEquals(6, catalog.packs.size)
        assertEquals(
            setOf(
                ExamStage.PRIMARY,
                ExamStage.JUNIOR,
                ExamStage.SENIOR,
                ExamStage.COLLEGE,
                ExamStage.POSTGRAD,
                ExamStage.CIVIL_SERVICE,
            ),
            catalog.packs.map { it.stage }.toSet(),
        )
        catalog.packs.forEach { pack ->
            assertTrue("${pack.id} 缺许可", pack.license.isNotBlank())
            assertTrue("${pack.id} 缺来源", pack.source.isNotBlank())
            assertEquals("${pack.id} sha256 长度", 64, pack.sha256.length)
            assertTrue("${pack.id} 路径必须在 packs/ 下", pack.path.startsWith("packs/"))
        }
    }

    @Test
    fun everyPack_parsesWithoutRejectedLinesAndMatchesCount() {
        val catalog = QuestionPackCatalogParser.parse(read("question_banks/manifest.json"))

        catalog.packs.forEach { pack ->
            val plan = QuestionLineParser.parseAll(read(pack.assetPath))
            assertEquals("${pack.id} 有坏行", 0, plan.rejected.size)
            assertEquals("${pack.id} 计数与 manifest 不一致", pack.count, plan.items.size)
            val questions = plan.items.filterIsInstance<ImportItem.Question>()
            assertEquals("${pack.id} 必须全是题目", plan.items.size, questions.size)
            assertTrue("${pack.id} 阶段不符", questions.all { it.stage == pack.stage })
            assertTrue("${pack.id} 有题没有解析/评分要点", questions.all { it.explanation.isNotBlank() })
        }
    }

    @Test
    fun malformedEntries_areSkippedNotCrashingWholeCatalog() {
        val raw = """
            {"version":1,"packs":[
              {"id":"ok","title":"合法","stage":"junior","subjects":["数学"],"count":1,"path":"packs/junior-foundation.txt"},
              {"id":"escape","title":"穿越","stage":"junior","subjects":["数学"],"count":1,"path":"../../secret.txt"},
              {"id":"badstage","title":"未知阶段","stage":"火星","subjects":["数学"],"count":1,"path":"packs/junior-foundation.txt"},
              {"id":"ok","title":"重复 id","stage":"senior","subjects":["数学"],"count":1,"path":"packs/senior-foundation.txt"},
              {"id":"nosubject","title":"没学科","stage":"senior","subjects":[],"count":1,"path":"packs/senior-foundation.txt"}
            ]}
        """.trimIndent()

        val catalog = QuestionPackCatalogParser.parse(raw)

        assertEquals(listOf("ok"), catalog.packs.map { it.id })
        assertEquals(ExamStage.JUNIOR, catalog.packs.single().stage)
    }

    private fun read(path: String): String {
        val candidates = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到题库资产 $path；工作目录=${File(".").absolutePath}")
        return file.readText()
    }
}
