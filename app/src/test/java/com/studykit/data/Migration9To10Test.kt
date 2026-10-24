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
 * v9 → v10 迁移的**加列 + 默认值 + 数据存活**证明（题型扩展）。
 *
 * 骨架同 [Migration8To9Test]：
 * 1) 用 `9.json` 造旧库并塞两行旧题（只写 NOT NULL 且无 DEFAULT 的列）；
 * 2) 跑 [AppDatabase.MIGRATION_9_10] 并让 Room 逐字校验 schema（validate=true 会核对 10.json 的列/默认值）；
 * 3) 旧题零丢失、`answer_index` 等旧列原样，新列落成 `single` / `[]` / `[]`；
 * 4) 新列可写可读（多选/填空数据真的进得去）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration9To10Test {

    // room 2.6.1 的构造器是 (Instrumentation, Class<out RoomDatabase>)，不是 2.7 那个带 Context 的重载。
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate9to10AddsQuestionTypeColumnsAndKeepsOldRows() {
        // 1) v9 旧库：questions 里 NOT NULL 无 DEFAULT 的列写全（concept_tag/syncStatus 有 DEFAULT）
        var db = helper.createDatabase(DB, 9)
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation) " +
                "VALUES ('q1','数学','1+1=?','[\"1\",\"2\",\"3\"]',1,'因为 1+1=2')",
        )
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, concept_tag) " +
                "VALUES ('q2','英语','past tense of go','[\"go\",\"went\",\"goed\"]',1,'不规则动词','时态')",
        )
        db.close()

        // 2) 迁移并逐字校验 schema（目标版本 10）
        db = helper.runMigrationsAndValidate(DB, 10, true, AppDatabase.MIGRATION_9_10)

        // 3) 三列到位，且 PRAGMA 里的 SQL DEFAULT 与 10.json 完全一致
        val columns = db.query("PRAGMA table_info(questions)").let { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(
                        c.getString(c.getColumnIndexOrThrow("name")),
                        c.getString(c.getColumnIndexOrThrow("dflt_value")),
                    )
                }
            }
        }
        assertTrue(columns.containsKey("question_type"))
        assertTrue(columns.containsKey("answer_indices_json"))
        assertTrue(columns.containsKey("answer_text_json"))
        assertEquals("'single'", columns["question_type"])
        assertEquals("'[]'", columns["answer_indices_json"])
        assertEquals("'[]'", columns["answer_text_json"])

        // 4) 行数守恒 + 旧列原样 + 新列默认值
        val count = db.query("SELECT COUNT(*) FROM questions").also { it.moveToFirst() }.getInt(0)
        assertEquals("迁移不许丢题", 2, count)
        val old = db.query(
            "SELECT answer_index, explanation, question_type, answer_indices_json, answer_text_json " +
                "FROM questions WHERE uuid='q1'",
        ).also { it.moveToFirst() }
        assertEquals(1, old.getInt(0))
        assertEquals("因为 1+1=2", old.getString(1))
        assertEquals("single", old.getString(2))
        assertEquals("[]", old.getString(3))
        assertEquals("[]", old.getString(4))

        // 5) 新列可写可读：多选/填空数据进得去、读得回来
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, " +
                "question_type, answer_indices_json, answer_text_json) " +
                "VALUES ('q3','数学','选出偶数','[\"1\",\"2\",\"3\",\"4\"]',1,'', 'multiple','[1,3]','[]')",
        )
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation, " +
                "question_type, answer_indices_json, answer_text_json) " +
                "VALUES ('q4','语文','“春眠不觉晓”下一句','[]',0,'', 'fill','[]','[\"处处闻啼鸟\"]')",
        )
        val multi = db.query(
            "SELECT question_type, answer_indices_json FROM questions WHERE uuid='q3'",
        ).also { it.moveToFirst() }
        assertEquals("multiple", multi.getString(0))
        assertEquals("[1,3]", multi.getString(1))
        val fill = db.query(
            "SELECT question_type, answer_text_json FROM questions WHERE uuid='q4'",
        ).also { it.moveToFirst() }
        assertEquals("fill", fill.getString(0))
        assertEquals("[\"处处闻啼鸟\"]", fill.getString(1))
        db.close()
    }

    private companion object {
        const val DB = "migration-9-10-test"
    }
}
