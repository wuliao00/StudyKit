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
 * v8 → v9 迁移的**建表 + 加列 + 数据存活**证明（v3.0-B 计划 Task 8；套卷持久化 `mock_sessions` 落库）。
 *
 * 沿用 [Migration7To8Test] 那套 A-T7 起的 CI 防漂移网，五段骨架：
 * 1) v8 老库先塞一行错题，列口径取自 `8.json`——`mistakes` 里 NOT NULL 且无 SQL DEFAULT 的列
 *    （uuid/source/subject/title/content/note/mastered/created_at）必须写全，否则红得像迁移写坏了、其实是夹具无效；
 * 2) 迁移产物 `mock_sessions` 建表列**逐字**取自 `app/schemas/com.studykit.data.AppDatabase/9.json`
 *    （`${TABLE_NAME}` 换成真实表名）—— 差一个 `NOT NULL`/DEFAULT 就红，不再等真机撞运气；
 * 3) ⛔ 旧错题行零丢失，且新列 `kind=0`/`extra='{}'` 读回来就是「普通错题、无遮挡」（计划硬闸）；
 * 4) 新表能写能读，`deadline_ts`/`result` 未填保持 NULL（不限时 = 截止时刻为空）。
 *
 * 环境钉 `sdk = [34]`：与本仓所有 Robolectric 用例同规矩（见 [Migration6To7Test] 注释）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration8To9Test {

    // room 2.6.1 的构造器是 (Instrumentation, Class<out RoomDatabase>)，不是 2.7 那个带 Context 的重载
    // （照 2.7 写会编译红，[Migration6To7Test] 已反查 jar 钉死这点）。
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate8to9AddsMockSessionsAndOcclusionColumnsAndKeepsOldRows() {
        // 1) 先造一个 **v8** 库并塞一行旧错题。NOT NULL 无 DEFAULT 的列按 8.json 写全；
        //    带 DEFAULT 的列也显式给值，免得「默认值恰好正确」掩盖迁移问题。
        var db = helper.createDatabase(DB, 8)
        db.execSQL(
            "INSERT INTO mistakes (uuid, source, subject, title, image_path, content, note, review_at, " +
                "mastered, created_at, fsrs_stability, fsrs_difficulty, fsrs_state, correct_streak, " +
                "review_count, priority) VALUES ('m1','photo','数学','题','m1.jpg','题干','qid:7',NULL,0,11,2.5,5.5,2,0,0,1)",
        )
        db.close()

        // 2) 跑迁移并让 Room 逐字校验 schema（validate 参数=迁移目标版本 9）
        db = helper.runMigrationsAndValidate(DB, 9, true, AppDatabase.MIGRATION_8_9)

        // 3) 新表列集合与实体逐一对应
        val cols = db.query("PRAGMA table_info(mock_sessions)").let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertEquals(
            "mock_sessions 的列集合必须与实体逐一对应",
            setOf(
                "id", "uuid", "syncStatus", "subject", "exam_name", "question_ids", "answers", "cursor",
                "start_ts", "deadline_ts", "limit_min", "state", "result", "created_at",
            ),
            cols.toSet(),
        )
        // 4) 错题两张新列到位且默认值就是「普通错题、无遮挡」
        val mcols = db.query("PRAGMA table_info(mistakes)").let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(mcols.containsAll(listOf("kind", "extra")))
        // ③ ⛔ 旧行零丢失
        val row = db.query("SELECT title, priority, kind, extra FROM mistakes WHERE uuid='m1'").also { it.moveToFirst() }
        assertEquals("题", row.getString(0))
        assertEquals(1, row.getInt(1))
        assertEquals("⛔ 旧卡迁移后必须是 kind=0（普通）", 0, row.getInt(2))
        assertEquals("⛔ 旧卡默认无遮挡", "{}", row.getString(3))
        // 5) 新表能写能读，NULL 列保持 NULL（不限时 = deadline_ts 为空）
        db.execSQL(
            "INSERT INTO mock_sessions (uuid, subject, exam_name, question_ids, answers, cursor, start_ts, " +
                "limit_min, state, created_at) VALUES ('s1','数学','期末考','[1,2]','{}',0,1000,45,1,1000)",
        )
        val s = db.query("SELECT subject, state, deadline_ts, result FROM mock_sessions WHERE uuid='s1'").also { it.moveToFirst() }
        assertEquals("数学", s.getString(0))
        assertEquals(1, s.getInt(1))
        assertTrue("不限时时 deadline_ts 应为 NULL", s.isNull(2))
        assertTrue("未交卷时 result 应为 NULL", s.isNull(3))
        db.close()
    }

    private companion object {
        const val DB = "migration-8-9-test"
    }
}
