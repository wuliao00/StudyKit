package com.studykit.ui.mistake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeMasteryTest {
    // 每项 = (本次判对, 与上一次之间的间隔天数)，按时间升序
    @Test fun `two correct three days apart masters`() {
        assertTrue(MistakeMastery.isMastered(listOf(true to 0.0, false to 0.0, true to 4.0, true to 3.0)))
    }
    @Test fun `same day double correct does not master`() {
        assertFalse(MistakeMastery.isMastered(listOf(true to 4.0, true to 0.0)))
    }
    @Test fun `three correct close together does not master`() {
        assertFalse(MistakeMastery.isMastered(listOf(true to 0.0, true to 2.0, true to 2.0)))
    }
    @Test fun `wrong answer resets the chain`() {
        assertFalse(MistakeMastery.isMastered(listOf(true to 4.0, true to 4.0, false to 1.0, true to 0.5)))
    }
    @Test fun `empty or single-entry history never masters`() {
        assertFalse(MistakeMastery.isMastered(emptyList()))
        assertFalse(MistakeMastery.isMastered(listOf(true to 9.0)))
    }
}
