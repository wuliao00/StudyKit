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
 * v11 → v12 迁移的**纯加表 + 索引 + 数据存活**证明（学习设计）。
 *
 * 骨架同 [Migration10To11Test]：
 * 1) 用 `11.json` 造旧库并塞一行旧题（列口径取自 11.json，只写无 DEFAULT 的 NOT NULL 列）；
 * 2) 跑 [AppDatabase.MIGRATION_11_12] 并让 Room 逐字校验 schema（validate=true 核对 12.json，
 *    建表/建索引任何一处与实体生成结果不一致都会当场红）；
 * 3) 旧题零丢失（纯加表，一句 UPDATE/DELETE 都没有）；
 * 4) `design_plans` 表真的建出来，`updated_at` 索引真的建出来且列对；
 * 5) 新表可写可读：自增 id、mode、fields_json、content、created_at/updated_at 都进得去。
 *
 * 注意：`12.json` 由构建期 KSP 导出到 `app/schemas/`，本用例与既有迁移用例一样，
 * 依赖 `app/build.gradle.kts` 里 `sourceSets.debug.assets` 那条挂载把它带给 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration11To12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate11to12AddsDesignPlansAndKeepsOldRows() {
        // 1) v11 旧库：questions 里 NOT NULL 无 DEFAULT 的列写全（question_type 等有 DEFAULT）
        var db = helper.createDatabase(DB, 11)
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation) " +
                "VALUES ('q1','数学','1+1=?','[\"1\",\"2\",\"3\"]',1,'因为 1+1=2')",
        )
        db.close()

        // 2) 迁移并逐字校验 schema（目标版本 12）
        db = helper.runMigrationsAndValidate(DB, 12, true, AppDatabase.MIGRATION_11_12)

        // 3) 旧数据存活：一条不少，旧列原样
        val questionCount = db.query("SELECT COUNT(*) FROM questions").also { it.moveToFirst() }.getInt(0)
        assertEquals("迁移不许丢题", 1, questionCount)
        val oldQuestion = db.query(
            "SELECT subject, stem, answer_index, question_type, exam_stage FROM questions WHERE uuid='q1'",
        ).also { it.moveToFirst() }
        assertEquals("数学", oldQuestion.getString(0))
        assertEquals("1+1=?", oldQuestion.getString(1))
        assertEquals(1, oldQuestion.getInt(2))
        assertEquals("single", oldQuestion.getString(3))
        assertEquals("旧题阶段仍必须是空串（未标注，迁移不碰旧列）", "", oldQuestion.getString(4))

        // 4) 新表存在
        val tableCount = db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='design_plans'",
        ).also { it.moveToFirst() }.getInt(0)
        assertEquals("v12 必须建出 design_plans 表", 1, tableCount)

        val indexes = db.query("PRAGMA index_list(design_plans)").let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(
            "v12 必须建 updated_at 索引（列表按最近修改倒序）: $indexes",
            indexes.contains("index_design_plans_updated_at"),
        )
        val indexColumns = db.query(
            "PRAGMA index_info(index_design_plans_updated_at)",
        ).let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertEquals(listOf("updated_at"), indexColumns)

        // 5) 新表可写可读：id 自增（不手写），八个业务列原样回来
        db.execSQL(
            "INSERT INTO design_plans (uuid, mode, title, fields_json, content, created_at, updated_at) " +
                "VALUES ('d1','FEYNMAN','机会成本','{\"concept\":\"机会成本\"}','# 机会成本',1000,2000)",
        )
        val plan = db.query(
            "SELECT id, mode, title, fields_json, content, created_at, updated_at " +
                "FROM design_plans WHERE uuid='d1'",
        ).also { it.moveToFirst() }
        assertTrue("id 必须由 AUTOINCREMENT 生成", plan.getLong(0) > 0)
        assertEquals("FEYNMAN", plan.getString(1))
        assertEquals("机会成本", plan.getString(2))
        assertEquals("{\"concept\":\"机会成本\"}", plan.getString(3))
        assertEquals("# 机会成本", plan.getString(4))
        assertEquals(1000L, plan.getLong(5))
        assertEquals(2000L, plan.getLong(6))
        db.close()
    }

    private companion object {
        const val DB = "migration-11-12-test"
    }
}
