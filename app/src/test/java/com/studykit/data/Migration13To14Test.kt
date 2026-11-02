package com.studykit.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v13 -> v14 迁移证明（OCR 纠错日志表）。
 *
 * 1) 用 13.json 造旧库并塞一行带 OCR 元数据的题目；
 * 2) 跑 MIGRATION_13_14 并让 Room 逐字校验 14.json；
 * 3) 老题一列不丢；ocr_corrections 可写可读，before/after 与置信度/来源页原样落库；
 * 4) 两个索引存在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration13To14Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate13to14AddsCorrectionLogAndKeepsQuestions() {
        var db = helper.createDatabase(DB, 13)
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, " +
                "needs_review, ocr_confidence, ocr_page_path, ocr_layout_json) " +
                "VALUES ('q-old','数学','旧题干','[]',0,'',1,0.55,'ocr_pages/a.jpg','{}')",
        )
        db.close()

        db = helper.runMigrationsAndValidate(DB, 14, true, AppDatabase.MIGRATION_13_14)

        val count = db.query("SELECT COUNT(*) FROM questions").also { it.moveToFirst() }.getInt(0)
        assertEquals("迁移不许丢题", 1, count)
        val old = db.query(
            "SELECT needs_review, ocr_confidence, ocr_page_path FROM questions WHERE uuid='q-old'",
        ).also { it.moveToFirst() }
        assertEquals(1, old.getInt(0))
        assertEquals(0.55f, old.getFloat(1), 1e-6f)
        assertEquals("ocr_pages/a.jpg", old.getString(2))

        db.execSQL(
            "INSERT INTO ocr_corrections (question_id, created_at, before_stem, after_stem, " +
                "before_answer, after_answer, ocr_confidence, source_page) " +
                "VALUES (7, 1700000000000, '旧题干', '新题干', 'B', 'C', 0.55, 'ocr_pages/a.jpg')",
        )
        val row = db.query(
            "SELECT question_id, before_stem, after_stem, before_answer, after_answer, " +
                "ocr_confidence, source_page FROM ocr_corrections",
        ).also { it.moveToFirst() }
        assertEquals(7L, row.getLong(0))
        assertEquals("旧题干", row.getString(1))
        assertEquals("新题干", row.getString(2))
        assertEquals("B", row.getString(3))
        assertEquals("C", row.getString(4))
        assertEquals(0.55f, row.getFloat(5), 1e-6f)
        assertEquals("ocr_pages/a.jpg", row.getString(6))

        val indexes = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='ocr_corrections'",
        )
        val names = HashSet<String>()
        while (indexes.moveToNext()) names.add(indexes.getString(0))
        assertTrue("question_id index missing: " + names, names.contains("index_ocr_corrections_question_id"))
        assertTrue("created_at index missing: " + names, names.contains("index_ocr_corrections_created_at"))
        db.close()
    }

    private companion object {
        const val DB = "migration-13-14-test.db"
    }
}
