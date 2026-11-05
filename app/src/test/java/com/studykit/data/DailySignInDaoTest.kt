package com.studykit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.entity.DailySignIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailySignInDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun signInIsIdempotentAndFlowSeesTheDay() = runBlocking {
        val dao = db.dailySignInDao()
        assertTrue(dao.insert(DailySignIn("2026-11-05", 1000L)) > 0L)
        assertEquals(-1L, dao.insert(DailySignIn("2026-11-05", 2000L)))
        assertEquals(listOf("2026-11-05"), dao.observeDays().first())
        assertEquals(1, dao.allDays().size)
    }
}
