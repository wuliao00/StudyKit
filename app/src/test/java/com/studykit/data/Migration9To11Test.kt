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
 * v9 → v11 **整链**迁移（真机 3.2.1/Room9 升 3.3.0/Room11 的实际路径）。
 *
 * 单段测试已有 [Migration9To10Test] 与 [Migration10To11Test]；这一条把两段串起来，
 * 用 `9.json` 造旧库、一次跑 `MIGRATION_9_10 + MIGRATION_10_11`，validate 到 11，
 * 证明旧题在三列题型 + 阶段列都到位后仍然一条不丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration9To11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate9to11KeepsOldQuestionsAcrossBothSteps() {
        var db = helper.createDatabase(DB, 9)
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, concept_tag) " +
                "VALUES ('q1','数学','1+1=?','[\"1\",\"2\",\"3\"]',1,'因为 1+1=2','算术')",
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            DB,
            11,
            true,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
        )

        val count = db.query("SELECT COUNT(*) FROM questions").also { it.moveToFirst() }.getInt(0)
        assertEquals("整链迁移不许丢题", 1, count)
        val row = db.query(
            "SELECT subject, stem, answer_index, question_type, exam_stage, concept_tag " +
                "FROM questions WHERE uuid='q1'",
        ).also { it.moveToFirst() }
        assertEquals("数学", row.getString(0))
        assertEquals("1+1=?", row.getString(1))
        assertEquals(1, row.getInt(2))
        assertEquals("single", row.getString(3))
        assertEquals("旧题阶段必须是空串（未标注）", "", row.getString(4))
        assertEquals("算术", row.getString(5))

        val indexes = db.query("PRAGMA index_list(questions)").let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(indexes.contains("index_questions_exam_stage_question_type_subject"))
        db.close()
    }

    private companion object {
        const val DB = "migration-9-11-test"
    }
}
