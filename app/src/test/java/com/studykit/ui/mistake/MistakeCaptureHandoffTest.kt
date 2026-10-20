package com.studykit.ui.mistake

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 题库拍照 → 错题本的单槽交接。三条断言钉的都是**消费方看得到的语义**：
 * 空槽取不到东西；取走即清空（转屏重建不会把同一张图用两遍）；后一次覆盖前一次（不会堆积两张图）。
 *
 * 这是**应用内**状态，跨进程死亡会丢；丢了的后果是错题本页只剩空表单，不会崩，也不会重复消费。
 */
class MistakeCaptureHandoffTest {

    @Before
    fun drain() {
        // 单例对象跨用例共享；每次先把上一次留下的内容取空，避免用例顺序影响结果
        MistakeCaptureHandoff.take()
    }

    @Test
    fun `空槽取走是 null`() {
        assertNull(MistakeCaptureHandoff.take())
    }

    @Test
    fun `存取一次后槽位清空`() {
        val file = File.createTempFile("handoff", ".jpg")
        try {
            MistakeCaptureHandoff.stash(file, "8. 求下列函数的导数：\n(1) y=x^2")
            val taken = MistakeCaptureHandoff.take()
            assertEquals(file, taken?.first)
            assertEquals("8. 求下列函数的导数：\n(1) y=x^2", taken?.second)
            assertNull(MistakeCaptureHandoff.take())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `后一次覆盖前一次`() {
        val first = File.createTempFile("handoff-a", ".jpg")
        val second = File.createTempFile("handoff-b", ".jpg")
        try {
            MistakeCaptureHandoff.stash(first, "A")
            MistakeCaptureHandoff.stash(second, "B")
            val taken = MistakeCaptureHandoff.take()
            assertEquals(second, taken?.first)
            assertEquals("B", taken?.second)
            assertNull(MistakeCaptureHandoff.take())
        } finally {
            first.delete()
            second.delete()
        }
    }
}
