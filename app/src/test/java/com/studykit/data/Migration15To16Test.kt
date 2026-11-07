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
 * v15 -> v16 迁移证明：daily_sign_ins 加 makeup 列，旧签到全部按正常签到（0）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration15To16Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate15to16AddsMakeupFlagAndKeepsSignIns() {
        var db = helper.createDatabase(DB, 15)
        db.execSQL("INSERT INTO daily_sign_ins (day, created_at) VALUES ('2026-11-05', 1000)")
        db.close()

        db = helper.runMigrationsAndValidate(DB, 16, true, AppDatabase.MIGRATION_15_16)
        val row = db.query("SELECT day, created_at, makeup FROM daily_sign_ins").also { it.moveToFirst() }
        assertEquals("2026-11-05", row.getString(0))
        assertEquals(1000L, row.getLong(1))
        assertEquals(0, row.getInt(2))

        db.execSQL("INSERT INTO daily_sign_ins (day, created_at, makeup) VALUES ('2026-11-06', 2000, 1)")
        val makeup = db.query("SELECT makeup FROM daily_sign_ins WHERE day='2026-11-06'")
            .also { it.moveToFirst() }
        assertEquals(1, makeup.getInt(0))
        db.close()
    }

    private companion object {
        const val DB = "migration-15-16-test.db"
    }
}
