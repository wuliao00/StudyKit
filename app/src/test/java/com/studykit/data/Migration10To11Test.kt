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
 * v10 → v11 迁移的**加阶段列 + 复合索引 + 数据存活**证明（v3.3 阶段 × 题型）。
 *
 * 骨架同 [Migration9To10Test]：
 * 1) 用 `10.json` 造旧库并塞一行旧题（列口径取自 10.json）；
 * 2) 跑 [AppDatabase.MIGRATION_10_11] 并让 Room 逐字校验 schema（validate=true 核对 11.json）；
 * 3) 旧题零丢失，`exam_stage` 落空串 = 未标注（不猜阶段）；
 * 4) `(exam_stage, question_type, subject)` 复合索引真的建出来；
 * 5) 新列可写可读（senior / true_false 进得去、读得回来）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration10To11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate10to11AddsExamStageAndCompositeIndexAndKeepsOldRows() {
        var db = helper.createDatabase(DB, 10)
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, " +
                "question_type, answer_indices_json, answer_text_json) " +
                "VALUES ('q1','数学','1+1=?','[\"1\",\"2\",\"3\"]',1,'因为 1+1=2','single','[]','[]')",
        )
        db.close()

        db = helper.runMigrationsAndValidate(DB, 11, true, AppDatabase.MIGRATION_10_11)

        val count = db.query("SELECT COUNT(*) FROM questions").also { it.moveToFirst() }.getInt(0)
        assertEquals("迁移不许丢题", 1, count)
        val old = db.query(
            "SELECT answer_index, explanation, question_type, exam_stage FROM questions WHERE uuid='q1'",
        ).also { it.moveToFirst() }
        assertEquals(1, old.getInt(0))
        assertEquals("因为 1+1=2", old.getString(1))
        assertEquals("single", old.getString(2))
        assertEquals("旧题阶段必须是空串（未标注，不猜）", "", old.getString(3))

        val indexes = db.query("PRAGMA index_list(questions)").let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(
            "v11 必须建 (exam_stage, question_type, subject) 复合索引: $indexes",
            indexes.contains("index_questions_exam_stage_question_type_subject"),
        )
        val indexColumns = db.query(
            "PRAGMA index_info(index_questions_exam_stage_question_type_subject)",
        ).let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertEquals(listOf("exam_stage", "question_type", "subject"), indexColumns)

        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, " +
                "question_type, answer_indices_json, answer_text_json, exam_stage) " +
                "VALUES ('q2','物理','地球是平的','[\"正确\",\"错误\"]',1,'','true_false','[1]','[]','senior')",
        )
        val fresh = db.query(
            "SELECT exam_stage, question_type FROM questions WHERE uuid='q2'",
        ).also { it.moveToFirst() }
        assertEquals("senior", fresh.getString(0))
        assertEquals("true_false", fresh.getString(1))
        db.close()
    }

    private companion object {
        const val DB = "migration-10-11-test"
    }
}
