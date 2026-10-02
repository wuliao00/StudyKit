package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Test

class IfThenTemplateTest {
    @Test fun `compose renders when-where-then sentence`() {
        assertEquals(
            "当早上·书桌前，我就背 10 个单词",
            IfThenTemplate.compose(whenLabel = "早上", where = "书桌前", then = "背 10 个单词"),
        )
    }
    @Test fun `missing parts degrade gracefully`() {
        assertEquals("当晚上，我就阅读", IfThenTemplate.compose("晚上", "", "阅读"))
        assertEquals("", IfThenTemplate.compose("", "", ""))
    }
}
