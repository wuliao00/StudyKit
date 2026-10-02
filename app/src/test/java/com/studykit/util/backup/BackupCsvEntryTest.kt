package com.studykit.util.backup

import com.studykit.data.dao.ReviewHistoryRow
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包里的 `review_history.csv`（v2.7 spec §9 / 附录 A）。
 *
 * ## 为什么这些测试全是纯逻辑
 *
 * 生成 CSV 的那三件事（列序、可空字段怎么写、分桶口径）与 zip 的那两件事（条目名、恢复端认不认它）
 * 都能在 JVM 上钉住；真正碰 Room 的只有"从库里捞行"那一步，而那一步坏了最多的也只是
 * "包里少一个旁证文件"，不会动摇 `studykit.db` 这份主数据。所以网撒在格式与容忍性上。
 *
 * ## 钉住的是什么
 *
 * 1. 列序逐字等于 spec 附录 A —— 这份 CSV 是给**用户和将来的自己**算保留率用的，
 *    列序一变，旧包就再也读不对了，而它不会报错，只会算出个错数；
 * 2. 恢复端**必须**把它当陌生条目跳过（只增不改）：新版本往包里加东西，
 *    旧版本照样能恢复自己认领的那几样。
 */
class BackupCsvEntryTest {

    /** 计划 Task 19 Step 2 钉的那条例例行（列序=附录 A） */
    private val planRow = ReviewHistoryRow(
        itemId = 42L,
        reviewedAt = 1_717_000_000_000L,
        gapDays = 7.0,
        confidence = 3,
        grade = 0,
        priority = 0,
    )

    // region 列序与逐行格式

    @Test
    fun `表头逐字等于 spec 附录 A 的列序`() {
        assertEquals(
            "item_kind,item_id,reviewed_at_epoch_ms,gap_days,confidence,grade,priority,elapsed_bucket",
            ReviewHistoryCsv.HEADER,
        )
    }

    @Test
    fun `单词行按附录 A 的列序拼出计划钉住的那条例例`() {
        assertEquals("word,42,1717000000000,7.0,3,0,0,7", ReviewHistoryCsv.line(planRow))
    }

    /**
     * 计划里那行写的是 `word,42,1717000000000,3.0,3,0,0,7`（gap 3.0 却给了桶 7），
     * 而 spec §9 的 7/30 分桶是唯一判定：gap 3.0 属于"7 天都不到"，只能进 other。
     * 这里按 spec 的口径钉，**列序与其余字段仍与计划那行逐字相同** —— 偏离写在 Task 19 的汇报里。
     */
    @Test
    fun `间隔三天就是三天不许进七天桶`() {
        val row = planRow.copy(gapDays = 3.0)
        assertEquals("word,42,1717000000000,3.0,3,0,0,other", ReviewHistoryCsv.line(row))
    }

    /**
     * `gap_days` / `confidence` 都可空，空值写**空字段**而不是 0：
     * 把"没记信心"写成 0，事后拿 CSV 算超纠正率就会凭空多出成堆的"用户答『肯定不记得』"
     * （与 `AppDatabase.MIGRATION_6_7` 第 2 段拒绝给旧行填 0 是同一条理由）。
     */
    @Test
    fun `可空字段留空而不是拿零冒充`() {
        val row = planRow.copy(gapDays = null, confidence = null, grade = -1)
        assertEquals("word,42,1717000000000,,,-1,0,other", ReviewHistoryCsv.line(row))
    }

    /** 整表：表头 + 每数据行一行，行序就是送进来的顺序（调用方的 SQL 已按 reviewed_at 排过） */
    @Test
    fun `文本是表头加逐行且行数对得上`() {
        val rows = listOf(
            planRow,
            planRow.copy(itemId = 43L, gapDays = 45.0),
            planRow.copy(itemId = 44L, gapDays = 1.0, confidence = null),
        )
        val lines = ReviewHistoryCsv.text(rows).trimEnd('\n').split("\n")
        assertEquals(1 + rows.size, lines.size)
        assertEquals(ReviewHistoryCsv.HEADER, lines.first())
        // lines[1] 对应 rows[0]：行序就是送进去的顺序，不许悄悄按别的键重排
        assertEquals("word,42,1717000000000,7.0,3,0,0,7", lines[1])
        assertEquals("word,43,1717000000000,45.0,3,0,0,30", lines[2])
        assertEquals("word,44,1717000000000,1.0,,0,0,other", lines[3])
    }

    @Test
    fun `没有复习记录时只剩表头而不是空文件`() {
        assertEquals(ReviewHistoryCsv.HEADER + "\n", ReviewHistoryCsv.text(emptyList()))
    }

    /** 换行只用 `\n`：CSV 里的 `\r` 会让"行数"这种最朴素的检查在 Windows 上骗人 */
    @Test
    fun `输出里不许出现回车`() {
        val text = ReviewHistoryCsv.text(listOf(planRow, planRow.copy(itemId = 7L)))
        assertTrue("出现了 \\r", !text.contains('\r'))
        assertTrue(text.endsWith("\n"))
    }

    /** 字段全是数字与固定枚举，逗号与换行进不来；这一条是给"以后有人加文本字段"留的哨兵 */
    @Test
    fun `每行的字段数恒等于表头字段数`() {
        val expected = ReviewHistoryCsv.HEADER.split(",").size
        listOf(planRow, planRow.copy(gapDays = null), planRow.copy(grade = -1), planRow.copy(priority = 9)).forEach {
            assertEquals(expected, ReviewHistoryCsv.line(it).split(",").size)
        }
    }

    // endregion

    // region zip 条目：名字、内容、行数

    private fun zipWithCsv(rows: List<ReviewHistoryRow>): Pair<ByteArray, BackupManifest> {
        val manifest = BackupManifest("2.7.0", 1_760_000_000_000L, 4L, BackupCodec.checksum(byteArrayOf(1, 2, 3, 4)), 1)
        val archive = zipOf(
            BackupCodec.MANIFEST_ENTRY to BackupCodec.manifestJson(manifest).toByteArray(Charsets.UTF_8),
            BackupCodec.DB_ENTRY to byteArrayOf(1, 2, 3, 4),
            ReviewHistoryCsv.ENTRY_NAME to ReviewHistoryCsv.bytes(rows),
            "mistake_images/a.jpg" to byteArrayOf(9),
        )
        return archive to manifest
    }

    @Test
    fun `条目名就是包根的 review_history_csv 且内容带表头与全部数据行`() {
        val rows = (1..5).map { planRow.copy(itemId = it.toLong(), gapDays = it * 10.0) }
        val (archive, _) = zipWithCsv(rows)
        val names = entryNames(archive)
        assertTrue("包里没有 review_history.csv：${names}", names.contains(ReviewHistoryCsv.ENTRY_NAME))
        // 文件名进 zip 根（附录 A），不是 mistake_images/ 那种带目录的条目
        assertEquals(ReviewHistoryCsv.ENTRY_NAME, names.first { it == ReviewHistoryCsv.ENTRY_NAME })

        val text = entryText(archive, ReviewHistoryCsv.ENTRY_NAME)
        val lines = text.trimEnd('\n').split("\n")
        assertEquals(ReviewHistoryCsv.HEADER, lines.first())
        assertEquals("表头之外的行数要和送进去的行数一致", rows.size, lines.size - 1)
    }

    @Test
    fun `编码是 UTF_8 且中文条目名不受影响`() {
        val rows = listOf(planRow)
        val (archive, _) = zipWithCsv(rows)
        assertEquals(ReviewHistoryCsv.text(rows), entryText(archive, ReviewHistoryCsv.ENTRY_NAME))
        assertEquals(
            ReviewHistoryCsv.text(rows).toByteArray(Charsets.UTF_8).toList(),
            ReviewHistoryCsv.bytes(rows).toList(),
        )
    }

    // endregion

    // region 恢复端的容忍性（只增不改）

    @Test
    fun `恢复端把 review_history_csv 当陌生条目而不是认领它`() {
        // 附录 A 明写"解析端只需容忍性读，不做回导"；一旦本版本把它归成某种已知条目，
        // 旧版本恢复新版本打出的包就会因为"看不懂的条目"整体拒绝，等于让用户丢掉唯一的退路
        assertEquals(ZipEntryKind.UNKNOWN, BackupCodec.entryKindOf(ReviewHistoryCsv.ENTRY_NAME))
        assertEquals(ZipEntryKind.UNKNOWN, BackupCodec.entryKindOf("./${ReviewHistoryCsv.ENTRY_NAME}"))
    }

    /** 走一遍恢复侧真实的过滤路径：条目名先规范化、再归类，被认领的只有本模块认识的那三样 */
    @Test
    fun `带着旁证文件的包照样只认领自己那三样`() {
        val rows = listOf(planRow, planRow.copy(itemId = 8L))
        val (archive, _) = zipWithCsv(rows)
        val claimed = ArrayList<String>()
        val ignored = ArrayList<String>()
        ZipInputStream(archive.inputStream()).use { zip ->
            for (entry in generateSequence { zip.nextEntry }) {
                if (entry.isDirectory) continue
                val name = BackupCodec.canonicalEntryName(entry.name) ?: continue
                if (BackupCodec.entryKindOf(name) == ZipEntryKind.UNKNOWN) {
                    ignored += name
                } else {
                    claimed += name
                }
            }
        }
        // 认领的是哪几样才是重点，包里的条目顺序不是这份断言要钉的东西，所以按集合比
        assertEquals(
            setOf(BackupCodec.MANIFEST_ENTRY, BackupCodec.DB_ENTRY, "mistake_images/a.jpg"),
            claimed.toSet(),
        )
        assertEquals(3, claimed.size)
        assertEquals(listOf(ReviewHistoryCsv.ENTRY_NAME), ignored)
    }

    @Test
    fun `多出来的旁证条目不影响预览读 manifest`() {
        val (archive, manifest) = zipWithCsv(listOf(planRow))
        assertNotNull(BackupArchive.readManifest(archive))
        assertEquals(manifest, BackupArchive.readManifest(archive))
    }

    /** 图片计数只数图片：CSV 多一条目，manifest 的 imageCount 校验不该被带偏 */
    @Test
    fun `旁证条目不计进图片数`() {
        val (archive, manifest) = zipWithCsv(List(3) { planRow.copy(itemId = it.toLong()) })
        var images = 0
        var history = 0
        ZipInputStream(archive.inputStream()).use { zip ->
            for (entry in generateSequence { zip.nextEntry }) {
                if (entry.isDirectory) continue
                val name = BackupCodec.canonicalEntryName(entry.name) ?: continue
                when (BackupCodec.entryKindOf(name)) {
                    ZipEntryKind.IMAGE -> images++
                    ZipEntryKind.UNKNOWN -> history++
                    else -> Unit
                }
            }
        }
        assertEquals(manifest.imageCount, images)
        assertEquals(1, history)
    }

    // endregion

    // region 与卡片共用的分桶口径

    /**
     * elapsed_bucket 由 [com.studykit.ui.stats.RetentionBuckets] 单点定义。
     * 这条钉的是"屏幕上的 7d 桶与 CSV 里的 7 是同一个判据"，否则用户拿 CSV 复算会对不上，
     * 而且两边各自都看着有道理。
     */
    @Test
    fun `CSV 的分桶记号与卡片的分桶是同一个判定`() {
        val cases: List<Pair<Double?, String>> = listOf(
            7.0 to "7", 29.9 to "7", 30.0 to "30", 400.0 to "30", 0.5 to "other", null to "other",
        )
        cases.forEach { (gap, token) ->
            assertEquals(token, ReviewHistoryCsv.bucketOf(gap))
        }
    }

    // endregion

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return sink.toByteArray()
    }

    private fun entryNames(archive: ByteArray): List<String> =
        ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }

    private fun entryText(archive: ByteArray, wanted: String): String {
        val text = ZipInputStream(archive.inputStream()).use { zip ->
            for (entry in generateSequence { zip.nextEntry }) {
                if (entry.isDirectory) continue
                if (BackupCodec.canonicalEntryName(entry.name) != wanted) continue
                return@use zip.reader(Charsets.UTF_8).readText()
            }
            null
        }
        assertNotNull("包里没有 $wanted 条目", text)
        return text!!
    }
}
