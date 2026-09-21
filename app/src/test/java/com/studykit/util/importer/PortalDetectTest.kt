package com.studykit.util.importer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PortalDetect] 的纯逻辑单测 —— 把「网页认证门户劫持」从一堆网络异常里分出来。
 *
 * 真机实测到的那一跳：请求 `http://ydschool-online.nos.netease.com/...zip` 拿到
 * `HTTP/1.0 302` + `Location: http://100.100.9.2/gportal/web/login?wlanuserip=10.12.109.242&...`
 * （`Server: NetEngine Server 1.0`，校园网未认证门户）。`HttpURLConnection` 默认跟跳转，
 * 于是 App 读到一页 HTML 去喂 `ZipInputStream`，最终报成「压缩包里没有找到词表 JSON」——
 * 症状和真·坏包完全一样，排查只能靠猜。所以重定向目标主机不是预期主机时，必须单独报一句。
 */
class PortalDetectTest {

    private val cdn = "nos.netease.com"

    @Test
    fun `跳到别的主机就是网络被接管 提示里要带那个主机`() {
        val hint = PortalDetect.hint(
            location = "http://100.100.9.2/gportal/web/login?wlanuserip=10.12.109.242",
            expectedHost = cdn,
        )
        assertNotNull(hint)
        assertTrue("提示里要能看见真凶主机，实际：$hint", hint!!.contains("100.100.9.2"))
    }

    @Test
    fun `同主机重定向不算接管`() {
        assertNull(PortalDetect.hint("https://nos.netease.com/ydschool-online/a.zip", cdn))
        assertNull(PortalDetect.hint("http://nos.netease.com/x", cdn))
    }

    @Test
    fun `netease 自家子域重定向放行`() {
        assertNull(PortalDetect.hint("http://ydschool-online.nos.netease.com/x.zip", cdn))
    }

    @Test
    fun `相对 Location 视为同主机`() {
        assertNull(PortalDetect.hint("/gportal/web/login", cdn))
    }

    @Test
    fun `没有 Location 或空串时不臆断`() {
        assertNull(PortalDetect.hint(null, cdn))
        assertNull(PortalDetect.hint("", cdn))
        assertNull(PortalDetect.hint("   ", cdn))
    }

    @Test
    fun `大小写与多余空格都不影响判定`() {
        assertNull(PortalDetect.hint("HTTPS://NOS.NETEASE.COM/x", cdn))
        assertNull(PortalDetect.hint("  https://nos.netease.com/x  ", cdn))
    }

    @Test
    fun `带端口的他主机照样认得出`() {
        val hint = PortalDetect.hint("http://192.168.1.1:8080/auth", cdn)
        assertNotNull(hint)
        assertTrue(hint!!.contains("192.168.1.1"))
    }

    @Test
    fun `只有主机名没有路径也要认得出`() {
        assertNotNull(PortalDetect.hint("http://10.0.0.1", cdn))
    }
}
