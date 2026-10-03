package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitGuardTest {
    @Test fun `first two misses of the month are guarded`() {
        val month = listOf(true, true, false, true, false, false, true)
        assertEquals(listOf(2, 4), HabitGuard.guardedIndices(month))
    }
    @Test fun `third miss is not guarded and breaks`() {
        val month = listOf(false, false, false)
        assertEquals(listOf(0, 1), HabitGuard.guardedIndices(month))
        assertTrue(HabitGuard.breaksAt(month, index = 2))
        org.junit.Assert.assertFalse(HabitGuard.breaksAt(month, index = 0))
    }
    @Test fun `no miss no guard`() {
        assertEquals(emptyList<Int>(), HabitGuard.guardedIndices(listOf(true, true)))
    }
    @Test fun `guard text is pinned for the day bubble`() {
        assertEquals("未打卡（已用断签保护）", HabitGuard.MISSING_GUARD_TEXT)
    }
}
