package com.studykit.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [buildReminderContent] 纯函数的单元测试：
 * 只覆盖文案格式化逻辑（到期量聚合、保留率取整、空内容不发通知），
 * 通知渠道、权限检查与点击跳转属 Android 框架行为，不在本测试范围。
 */
class ReminderContentTest {

    @Test
    fun `全为0时返回null不发通知`() {
        assertNull(buildReminderContent(0, 0, 0, 0.85))
    }

    @Test
    fun `单项为0时不出现在文案里`() {
        val content = buildReminderContent(wordDue = 5, mistakeDue = 0, excerptDue = 1, averageRetention = 0.83)
        assertNotNullContent(content)
        assertEquals("今日复习", content!!.title)
        assertEquals("今日复习：单词 5、书摘 1；预测保留率 83%。", content.text)
        assertFalse(content.text.contains("错题"))
    }

    @Test
    fun `三类都有时按单词错题书摘顺序列出`() {
        val content = assertNotNullContent(buildReminderContent(5, 2, 1, 0.83))
        assertEquals("今日复习：单词 5、错题 2、书摘 1；预测保留率 83%。", content.text)
    }

    @Test
    fun `保留率四舍五入取整显示`() {
        val content = assertNotNullContent(buildReminderContent(1, 0, 0, 0.8349))
        assertEquals("今日复习：单词 1；预测保留率 83%。", content.text)

        val roundedUp = assertNotNullContent(buildReminderContent(1, 0, 0, 0.8351))
        assertEquals("今日复习：单词 1；预测保留率 84%。", roundedUp.text)
    }

    @Test
    fun `数量超过9时正常显示`() {
        val content = assertNotNullContent(buildReminderContent(12, 10, 128, 0.75))
        assertEquals("今日复习：单词 12、错题 10、书摘 128；预测保留率 75%。", content.text)
    }

    @Test
    fun `措辞不包含威胁性词汇`() {
        val content = assertNotNullContent(buildReminderContent(3, 4, 5, 0.5))
        for (banned in listOf("断签", "清零", "再不", "就忘了", "遗忘", "警告", "危险")) {
            assertFalse("文案不应包含威胁词「$banned」：${content.text}", content.text.contains(banned))
        }
    }

    @Test
    fun `文案不包含emoji`() {
        val content = assertNotNullContent(buildReminderContent(1, 1, 1, 0.9))
        val all = content.title + content.text
        assertFalse(
            "通知文案不应包含 emoji：$all",
            all.any { it.code in 0x1F300..0x1FAFF || it.code in 0x2600..0x27BF },
        )
    }

    private fun assertNotNullContent(content: ReminderContent?): ReminderContent {
        assertNotNull0(content)
        return content!!
    }

    private fun assertNotNull0(content: ReminderContent?) {
        org.junit.Assert.assertNotNull("有到期内容时应返回通知文案", content)
    }
}
