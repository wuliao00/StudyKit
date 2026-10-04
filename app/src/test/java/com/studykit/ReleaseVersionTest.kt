package com.studykit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 版本号的机检位（发版批次唯一能被机检的那一件事）。
 *
 * `versionCode` / `versionName` 与 CHANGELOG 顶部那一段必须**同批**动：手工收口最容易漏的是
 * "文档写了新号、号没改" —— 真机上覆盖装上去仍是旧 versionCode，升级差异根本看不出来，
 * 而这次要复验的恰恰是"带真实数据覆盖升级"。CHANGELOG 是人读的，不反过来当断言源
 * （读文件会让单测依赖工作目录，CI 一变目录就假红）。
 */
class ReleaseVersionTest {

    @Test
    fun 发布版本名是三段式2_8_0() {
        assertEquals("2.8.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun 版本号在16之上单调递增到18() {
        assertEquals(18, BuildConfig.VERSION_CODE)
    }
}
