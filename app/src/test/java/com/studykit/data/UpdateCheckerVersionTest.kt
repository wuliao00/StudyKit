package com.studykit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较的边界用例。
 *
 * 为什么单独钉它：`UpdateChecker` 的拦截是**不可关闭**的（"有新版本就拦"那一档），
 * 所以这个函数的错法只有两种，都很贵 —— 把该放行的判成要拦（用户被锁在门外）、
 * 或者把该拦的判成不用拦（新版本没人升）。而它在真机上没法自然验证：
 * 要有一个比当前更新的 Release 才会触发，所以判定只能靠这里。
 *
 * 最要紧的一条是**不能拿字符串比**：`"2.4.10" < "2.4.9"` 在字典序下成立，
 * 那会让发到第十个补丁版之后所有人都不用升级了。
 */
class UpdateCheckerVersionTest {

    @Test
    fun `数字段逐段比，不是字典序`() {
        // 字符串比会给出反的答案，这条就是那个墓碑
        assertTrue(UpdateChecker.compareVersions("2.4.10", "2.4.9") > 0)
        assertTrue(UpdateChecker.compareVersions("2.10.0", "2.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("10.0.0", "9.9.9") > 0)
    }

    @Test
    fun `tag 可能带 v，两个方向都要认`() {
        assertEquals(0, UpdateChecker.compareVersions("v2.4.4", "2.4.4"))
        assertEquals(0, UpdateChecker.compareVersions("2.4.4", "v2.4.4"))
        assertTrue(UpdateChecker.compareVersions("v2.5.0", "2.4.4") > 0)
    }

    @Test
    fun `段数不等时短的按 0 补`() {
        assertEquals(0, UpdateChecker.compareVersions("2.4", "2.4.0"))
        assertTrue(UpdateChecker.compareVersions("2.4.1", "2.4") > 0)
        assertTrue(UpdateChecker.compareVersions("2.4.0", "2.4") == 0)
    }

    @Test
    fun `相等的版本返回 0（不然每次启动都会被要求升级）`() {
        assertEquals(0, UpdateChecker.compareVersions("2.4.4", "2.4.4"))
        assertEquals(0, UpdateChecker.compareVersions("v2.4.4", "v2.4.4"))
    }

    @Test
    fun `预发布后缀只取到第一个非数字段为止`() {
        // "2.4.4-rc1" 的数字段是 2.4.4，与 2.4.4 同版（预发布不该被当成新版本推给所有人）
        assertEquals(0, UpdateChecker.compareVersions("2.4.4-rc1", "2.4.4"))
        assertTrue(UpdateChecker.compareVersions("2.5.0-beta", "2.4.4") > 0)
    }

    @Test
    fun `乱七八糟的输入不许抛异常`() {
        // 这个函数在启动路径上被调用；抛异常就等于每次启动都可能崩
        UpdateChecker.compareVersions("", "2.4.4")
        UpdateChecker.compareVersions("abc", "2.4.4")
        UpdateChecker.compareVersions("2.4.4", "")
        UpdateChecker.compareVersions("v", "2.4.4")
    }
}
