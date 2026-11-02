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
 * v12 -> v13 迁移证明（OCR 校对元数据）。
 *
 * 与既有迁移用例同一条骨架：
 * 1) 用 12.json 造旧库并塞一行 v12 题目（只写无 DEFAULT 的 NOT NULL 列）；
 * 2) 跑 MIGRATION_12_13 并让 Room 逐字校验 13.json（列类型/默认值/索引不一致会当场红）；
 * 3) 老行一条不丢，四个新列落在默认值：needs_review=0 / ocr_confidence=-1 / 空路径 / 空 JSON；
 * 4) 新列可写可读，needs_review 的更新能按 id 命中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration12To13Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate12to13AddsOcrReviewColumnsAndKeepsOldRows() {
        var db = helper.createDatabase(DB, 12)
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation) " +
                "VALUES ('q-old','数学','1+1=?','[\"1\",\"2\",\"3\"]',1,'因为 1+1=2')",
        )
        db.close()

        db = helper.runMigrationsAndValidate(DB, 13, true, AppDatabase.MIGRATION_12_13)

        val count = db.query("SELECT COUNT(*) FROM questions").also { it.moveToFirst() }.getInt(0)
        assertEquals("迁移不许丢题", 1, count)

        val old = db.query(
            "SELECT needs_review, ocr_confidence, ocr_page_path, ocr_layout_json " +
                "FROM questions WHERE uuid='q-old'",
        ).also { it.moveToFirst() }
        assertEquals("老题默认不需要校对", 0, old.getInt(0))
        assertEquals(-1f, old.getFloat(1), 1e-6f)
        assertEquals("", old.getString(2))
        assertEquals("{}", old.getString(3))

        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, " +
                "needs_review, ocr_confidence, ocr_page_path, ocr_layout_json) " +
                "VALUES ('q-ocr','数学','函数题','[]',0,'',1,0.42,'ocr_pages/a.jpg','{\"formula\":true}')",
        )
        db.execSQL("UPDATE questions SET needs_review = 0 WHERE uuid = 'q-ocr'")
        val ocr = db.query(
            "SELECT needs_review, ocr_confidence, ocr_page_path, ocr_layout_json " +
                "FROM questions WHERE uuid='q-ocr'",
        ).also { it.moveToFirst() }
        assertEquals(0, ocr.getInt(0))
        assertEquals(0.42f, ocr.getFloat(1), 1e-6f)
        assertEquals("ocr_pages/a.jpg", ocr.getString(2))
        assertTrue(ocr.getString(3).contains("formula"))

        db.close()
    }

    private companion object {
        const val DB = "migration-12-13-test.db"
    }
}
