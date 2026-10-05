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
 * v7 → v8 迁移的**建表 + 数据存活**证明（v2.9 计划 Task 4；优化事件流 `review_log` 落库）。
 *
 * 沿用 [Migration6To7Test] 那套 A-T7 起的 CI 防漂移网，三条都由 [MigrationTestHelper] 的
 * `validate = true` 兜住：
 * 1) 迁移**只增表、不动既有表** —— 老 `words` 行一句没丢（`half_life_days`/`created_at` 原样）；
 * 2) `review_log` 建表列**逐字**取自 `app/schemas/com.studykit.data.AppDatabase/8.json`
 *    （`${TABLE_NAME}` 换成真实表名）—— 差一个 `NOT NULL`/`REAL`↔`INTEGER` 就红，不再等真机撞运气；
 * 3) 迁移产物 `review_log` 可用：往里插一条新事件能读回（append-only 事件流真的建起来了）。
 *
 * 环境钉 `sdk = [34]`：与本仓所有 Robolectric 用例同规矩（见 [Migration6To7Test] 注释）。
 * MigrationTestHelper 读快照走 debug 变体 assets（`app/build.gradle.kts` 的 `sourceSets.debug.assets`，
 * 即 F1 口径）；v7 快照 [AppDatabase] 早已入库，v8 快照由本次 KSP 生成后一并提交。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration7To8Test {

    // room 2.6.1 的构造器是 (Instrumentation, Class<out RoomDatabase>)，不是 2.7 那个带 Context 的重载
    // （照 2.7 写会编译红，[Migration6To7Test] 已反查 jar 钉死这点）。
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate7to8AddsReviewLogAndKeepsOldRows() {
        // 1) 先造一个 **v7** 库并塞两行旧数据。列口径同 [Migration6To7Test]：
        //    `words` 里那些 NOT NULL 且无 SQL DEFAULT 的列必须写全，否则红得像迁移写坏了、其实是夹具无效。
        var db = helper.createDatabase(DB, 7)
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses, created_at) VALUES ('w1','apple','苹果','An apple a day','MASTERED',100,30.0,2.0,900,7,1,1)")
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses, created_at) VALUES ('w2','vim','编辑','vim vim','NEW',0,0.5,1.0,NULL,0,0,2)")
        db.close()

        // 2) 跑迁移并让 Room 逐字校验 schema（validate 参数=迁移目标版本 8）
        db = helper.runMigrationsAndValidate(DB, 8, true, AppDatabase.MIGRATION_7_8)

        // 3) review_log 表存在且列齐全 —— 一列不缺、不多，名字逐一对上实体
        val cols = db.query("PRAGMA table_info(review_log)").let { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        assertEquals(
            "review_log 的列集合必须与实体逐一对应",
            setOf(
                "id", "card_kind", "card_id", "ts", "rating", "elapsed_days", "scheduled_days",
                "state", "duration_ms", "origin", "ocr_conf", "self_conf", "session_id",
                "is_makeup", "log_schema_ver",
            ),
            cols.toSet(),
        )

        // 4) 既有表既有数据零丢失：迁移只建新表，一句 UPDATE/DELETE 都没有
        val wordCount = db.query("SELECT COUNT(*) FROM words").also { it.moveToFirst() }.getInt(0)
        assertEquals("迁移不该动 words 一行", 2, wordCount)
        val w1 = db.query("SELECT half_life_days, created_at FROM words WHERE uuid='w1'").also { it.moveToFirst() }
        assertEquals(30.0, w1.getDouble(0), 1e-9)
        assertEquals(1L, w1.getLong(1))

        // 5) 迁移后的 review_log 可用：插一条新复习事件能读回，可空列默认 NULL
        db.execSQL(
            "INSERT INTO review_log (card_kind, card_id, ts, rating, elapsed_days, scheduled_days, " +
                "state, duration_ms, origin, session_id, is_makeup, log_schema_ver) " +
                "VALUES ('word', 5, 1000, 3, 2.0, 4.0, 2, 800, 'word', 's1', 0, 1)",
        )
        val log = db.query(
            "SELECT card_kind, card_id, rating, elapsed_days, ocr_conf, self_conf FROM review_log WHERE card_id=5",
        ).also { it.moveToFirst() }
        assertEquals("word", log.getString(0))
        assertEquals(5L, log.getLong(1))
        assertEquals(3, log.getInt(2))
        assertEquals(2.0, log.getDouble(3), 1e-9)
        assertTrue("ocr_conf 未填应为 NULL", log.isNull(4))
        assertTrue("self_conf 未填应为 NULL", log.isNull(5))
        db.close()
    }

    private companion object { const val DB = "migration-7-8-test" }
}
