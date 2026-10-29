package com.studykit.util.importer

import com.studykit.data.question.ExamStage
import java.io.File
import java.security.MessageDigest
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
    fun everyPack_hasHttpsDownloadUrl() {
        val catalog = QuestionPackCatalogParser.parse(read("question_banks/manifest.json"))

        catalog.packs.forEach { pack ->
            val url = pack.remoteUrl
            assertTrue("${pack.id} 缺在线地址", url != null)
            assertTrue("${pack.id} 的地址必须是 https", url!!.startsWith("https://"))
            assertTrue("${pack.id} 的地址要以自身 path 结尾（换了仓库路径要一起改）", url.endsWith(pack.path))
            assertTrue("${pack.id} 应被标成在线包", pack.isRemote)
        }
    }

    @Test
    fun unsafeOrEmptyUrl_isIgnored() {
        val raw = """
            {"version":3,"packs":[
              {"id":"a","title":"甲","stage":"junior","subjects":["数学"],"count":1,"path":"packs/junior-foundation.txt","url":"javascript:alert(1)"},
              {"id":"b","title":"乙","stage":"junior","subjects":["数学"],"count":1,"path":"packs/junior-foundation.txt","url":"https://x.test/../../etc/passwd"},
              {"id":"c","title":"丙","stage":"junior","subjects":["数学"],"count":1,"path":"packs/junior-foundation.txt","url":""},
              {"id":"d","title":"丁","stage":"junior","subjects":["数学"],"count":1,"path":"packs/junior-foundation.txt","url":"http://x.test/a.txt"}
            ]}
        """.trimIndent()

        val catalog = QuestionPackCatalogParser.parse(raw)

        assertEquals(4, catalog.packs.size)
        catalog.packs.forEach { pack ->
            assertEquals("${pack.id} 的非法地址必须被丢掉", null, pack.remoteUrl)
            assertTrue("${pack.id} 不该被当成在线包", !pack.isRemote)
        }
    }

    @Test
    fun everyPack_typeCountsAddUpToTotal() {
        val catalog = QuestionPackCatalogParser.parse(read("question_banks/manifest.json"))

        catalog.packs.forEach { pack ->
            // manifest 里的 types 是手写的展示数据，写错不会崩，只会让商店卡片上的分布对不上总题数。
            assertTrue("${pack.id} 缺题型分布", pack.typeCounts.isNotEmpty())
            assertEquals(
                "${pack.id} 题型分布之和与 count 不一致",
                pack.count,
                pack.typeCounts.sumOf { it.count },
            )
            assertTrue("${pack.id} 题型名重复", pack.typeCounts.map { it.label }.distinct().size == pack.typeCounts.size)
        }
    }

    @Test
    fun everyPack_sha256MatchesFileContent() {
        val catalog = QuestionPackCatalogParser.parse(read("question_banks/manifest.json"))

        catalog.packs.forEach { pack ->
            // 与 QuestionPackStore.loadPack 同口径：按 UTF-8 文本重新编码后取摘要。
            // 改了题包却忘了改 manifest，用户点“下载”会被校验拦下，所以在单测里先炸。
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(read(pack.assetPath).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals("${pack.id} 的 sha256 与题包文件不一致", pack.sha256, actual)
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
