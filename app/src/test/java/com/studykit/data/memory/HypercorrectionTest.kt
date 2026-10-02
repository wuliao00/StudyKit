package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HypercorrectionTest {
    @Test fun `sure but wrong gets retest within ten minutes`() {
        assertEquals(10L, Hypercorrection.retestDelayMinutes(Confidence.SURE, recalled = false))
        assertEquals(FsrsKernel.TEN_MINUTES_IN_DAYS * 1440.0, Hypercorrection.RETEST_DELAY_MINUTES.toDouble(), 1e-9)
    }
    @Test fun `guess and wrong is ordinary scheduling, no priority`() {
        assertNull(Hypercorrection.retestDelayMinutes(Confidence.GUESS, recalled = false))
        assertNull(Hypercorrection.retestDelayMinutes(null, recalled = false))
        assertNull(Hypercorrection.retestDelayMinutes(Confidence.FAIR, recalled = false))
    }
    @Test fun `sure and right never triggers`() {
        assertNull(Hypercorrection.retestDelayMinutes(Confidence.SURE, recalled = true))
    }
}
