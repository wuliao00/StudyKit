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
 * v14 -> v15 迁移证明：新增 daily_sign_ins（每日签到），老题一行不丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration14To15Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate14to15AddsDailySignInsAndKeepsQuestions() {
        var db = helper.createDatabase(DB, 14)
        db.execSQL(
            "INSERT INTO questions (uuid, subject, stem, options_json, answer_index, explanation) " +
                "VALUES ('q-old','数学','旧题干','[]',0,'')",
        )
        db.close()

        db = helper.runMigrationsAndValidate(DB, 15, true, AppDatabase.MIGRATION_14_15)
        assertEquals(1, db.query("SELECT COUNT(*) FROM questions").also { it.moveToFirst() }.getInt(0))

        db.execSQL("INSERT INTO daily_sign_ins (day, created_at) VALUES ('2026-11-05', 1234)")
        val row = db.query("SELECT day, created_at FROM daily_sign_ins").also { it.moveToFirst() }
        assertEquals("2026-11-05", row.getString(0))
        assertEquals(1234L, row.getLong(1))
        db.close()
    }

    private companion object {
        const val DB = "migration-14-15-test.db"
    }
}
