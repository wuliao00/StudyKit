package com.studykit.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v6 → v7 迁移的**数据存活**证明（spec §3、计划 A-T7）。
 *
 * 这是整批任务里唯一直接回答"老装机升级后，用户那 1000 多条词还在不在"的测试：
 * 之前所有版本迁移（1→2 … 5→6）都只能靠真机升级撞运气，`exportSchema` 打开之后
 * Room 才有了逐字校验的凭据（`app/schemas/…/<v>.json`），于是这条路径第一次进得了 CI。
 *
 * 三条硬约束，都由 [MigrationTestHelper] 的 `validate = true` 兜住：
 * 1) 迁移**只增不删**，老行的老列原样保留（`half_life_days` 一句没动 → 30.0 还在）；
 * 2) 回填口径 = spec §2.4（有历史才折算，纯新词 `fsrs_stability` 留 null 走 FSRS 原生 S0 支）；
 * 3) 建表/建列 SQL 与 7.json 逐字一致 —— 差一个 `DEFAULT` 就红，不再等真机报错。
 *
 * 环境钉 `sdk = [34]`：与本仓所有 Robolectric 用例同规矩（见 `CheckInSheetRenderTest`），
 * 免得 Robolectric 升级时测试语义悄悄漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration6To7Test {

    // 注意：room 2.6.1 的构造器是 (Instrumentation, Class<out RoomDatabase>)，
    // 不是 2.7 那个带 Context/arguments 的重载（T1 质量审查已反查 jar 证实，照 2.7 写会编译红）。
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * 造 v6 数据时为何要把 `created_at`（错题还要 `note`/`mastered`）写全：
     * 这些列在 6.json 里是 `NOT NULL` 而**没有 SQL DEFAULT** —— Room 只看 `@ColumnInfo(defaultValue)`，
     * 不看 Kotlin 属性初值（`createdAt: Long = System.currentTimeMillis()` 是纯 Kotlin 侧的）。
     * 漏写不会"取默认"，而是 `SQLiteConstraintException: NOT NULL constraint failed`，
     * 红得像迁移写坏了，其实是夹具无效。
     */
    @Test fun migrate6to7KeepsRowsAndSeedsFsrs() {
        // 1) 先造一个 **v6** 库并塞四行：有历史的 MASTERED、有历史的 LEARNING、纯新词、脏 h 行
        var db = helper.createDatabase(DB, 6)
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses, created_at) VALUES ('u1','apple','苹果','An apple a day','MASTERED',100,30.0,2.0,900,7,1,1)")
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses, created_at) VALUES ('u2','vim','编辑','vim vim','LEARNING',200,3.0,1.5,800,3,0,1)")
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses, created_at) VALUES ('u3','new','新词','','NEW',0,0.5,1.0,NULL,0,0,1)")
        // 脏 h 行必须**在迁移前**入库：回填是迁移里的一条 UPDATE，
        // 迁移跑完再插的行根本不会经过它 —— 那样第 4 段测的是 SQLite，不是 [AppDatabase.MIGRATION_6_7]。
        db.execSQL("INSERT INTO words (uuid, word, meaning, example, status, next_review_at, half_life_days, difficulty, last_review_at, total_reviews, lapses, created_at) VALUES ('u4','dirty','脏','d','LEARNING',50,-2.0,1.0,700,2,0,1)")
        db.close()

        // 2) 跑迁移并让 Room 逐字校验 schema（validate 参数=迁移目标版本 7）
        db = helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7)

        // 3) 断言：行都在、老列原样、回填符合 spec §2.4 的换算
        val c = db.query("SELECT COUNT(*) FROM words").also { it.moveToFirst() }
        assertEquals(4, c.getInt(0))
        val row = db.query(
            "SELECT half_life_days, fsrs_stability, fsrs_state, kernel FROM words WHERE uuid='u1'",
        ).also { it.moveToFirst() }
        assertEquals(30.0, row.getDouble(0), 1e-9)
        assertEquals(30.0 / 12.789473684210526, row.getDouble(1), 1e-6) // S=h/比值
        assertEquals(2, row.getInt(2)) // REVIEW
        assertEquals("FSRS", row.getString(3))
        val u2 = db.query(
            "SELECT fsrs_stability, fsrs_state FROM words WHERE uuid='u2'",
        ).also { it.moveToFirst() }
        assertEquals(3.0 / 12.789473684210526, u2.getDouble(0), 1e-6)
        assertEquals(2, u2.getInt(1))
        val fresh = db.query(
            "SELECT fsrs_stability, fsrs_state FROM words WHERE uuid='u3'",
        ).also { it.moveToFirst() }
        check(fresh.isNull(0)) // 纯新词不回填 S —— 走原生 S0 支，spec §2.4 注
        assertEquals(1, fresh.getInt(1)) // LEARNING

        // 4) 老表新列取默认 + 脏 h 行不炸（回填 SQL 遇 h<=0 必须降级不抛）
        db.execSQL("INSERT INTO mistakes (uuid, source, subject, title, content, note, mastered, created_at) VALUES ('m1','word','数学','t','c','',0,1)")
        val m = db.query("SELECT fsrs_state, correct_streak, priority FROM mistakes WHERE uuid='m1'").also { it.moveToFirst() }
        assertEquals(1, m.getInt(0))
        assertEquals(0, m.getInt(1))
        assertEquals(0, m.getInt(2))
        val dirty = db.query("SELECT fsrs_stability FROM words WHERE uuid='u4'").also { it.moveToFirst() }
        check(!dirty.isNull(0) && dirty.getDouble(0) > 0.0) // h<=0 脏行：回退 NEW 半衰期折算，不许 NULL/负数/NaN
        db.close()
    }

    private companion object { const val DB = "migration-6-7-test" }
}
