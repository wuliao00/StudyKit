package com.studykit.util.importer

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 题包在线下载（v3.5+）。
 *
 * 这一层只在真网络上跑得起来，而**失败路径**才是用户最容易撞上的：地址贴错、对方回登录页、
 * 文件是个安装包、服务器 404。所以起一个本机的极小 HTTP 服务（只用 `ServerSocket`，
 * 不依赖 `com.sun.net.httpserver` —— 它不在单测的编译类路径上），把这几条逐一钉住。
 *
 * 顺带钉住两件容易被"顺手改掉"的事：重定向要自己跟（`HttpURLConnection` 默认不跟跨协议），
 * BOM 要去掉（Windows 上存过的题包首行带 BOM，留着就匹配不到 `[阶段·学科·题型]` 前缀）。
 */
class QuestionPackRemoteTest {

    private lateinit var server: ServerSocket
    private var base: String = ""

    private val packText = "[初中·数学·单选] 1+1=?|1|2|3|4|B|等于 2。"

    /** 极简响应：本测试只关心状态码、Location、体积与正文 */
    private class Reply(
        val code: Int = 200,
        val body: ByteArray = ByteArray(0),
        val location: String? = null,
    )

    @Before
    fun setUp() {
        server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true, name = "tiny-http") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var matched = false
        while (!matched) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            matched = head.toString("ISO-8859-1").endsWith("\r\n\r\n")
        }
        val requestLine = head.toString("ISO-8859-1").lineSequence().firstOrNull().orEmpty()
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val reply = respond(path)
        val out = BufferedOutputStream(socket.getOutputStream())
        val header = buildString {
            append("HTTP/1.1 ${reply.code} X\r\n")
            append("Content-Length: ${reply.body.size}\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Connection: close\r\n")
            reply.location?.let { append("Location: $it\r\n") }
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(reply.body)
        out.flush()
    }

    private fun respond(path: String): Reply = when {
        path.startsWith("/pack.txt") -> Reply(body = packText.toByteArray(Charsets.UTF_8))
        path.startsWith("/bom.txt") -> Reply(
            body = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                packText.toByteArray(Charsets.UTF_8),
        )
        path.startsWith("/page.html") -> Reply(
            body = "<!DOCTYPE html><html><body>请登录</body></html>".toByteArray(Charsets.UTF_8),
        )
        path.startsWith("/moved") -> Reply(code = 302, location = "/pack.txt")
        path.startsWith("/missing") -> Reply(code = 404, body = "not found".toByteArray())
        path.startsWith("/huge") -> Reply(
            body = ByteArray(QuestionPackRemote.MAX_BYTES + 1024) { 'a'.code.toByte() },
        )
        else -> Reply(code = 404)
    }

    private fun fetch(url: String): String =
        QuestionPackRemote.fetchText(url, maxBytes = QuestionPackRemote.MAX_BYTES)

    @Test
    fun 正常下载返回原文() {
        assertEquals(packText, fetch("$base/pack.txt"))
    }

    @Test
    fun 带BOM的文件会去掉BOM() {
        val text = fetch("$base/bom.txt")
        assertTrue("BOM 没去掉，首行会匹配不到前缀", text.startsWith("[初中·数学·单选]"))
    }

    @Test
    fun 跟随重定向() {
        assertEquals(packText, fetch("$base/moved"))
    }

    @Test
    fun 网页会被认出来并给出人话提示() {
        val error = runCatching { fetch("$base/page.html") }.exceptionOrNull()
        assertTrue(error is QuestionPackRemoteException)
        assertTrue(
            "提示里要说明「返回的是网页」：${error?.message}",
            error?.message.orEmpty().contains("网页"),
        )
    }

    @Test
    fun 找不到文件与超限都抛可读异常() {
        val notFound = runCatching { fetch("$base/missing") }.exceptionOrNull()
        assertTrue(notFound is QuestionPackRemoteException)
        assertTrue("应提示 404：${notFound?.message}", notFound?.message.orEmpty().contains("404"))

        val huge = runCatching { fetch("$base/huge") }.exceptionOrNull()
        assertTrue(huge is QuestionPackRemoteException)
        assertTrue("应提示文件过大：${huge?.message}", huge?.message.orEmpty().contains("2 MB"))
    }

    // ── 地址归一（纯函数） ────────────────────────────────────────────────

    @Test
    fun 地址归一_补协议与去空白() {
        assertEquals("https://example.com/a.txt", QuestionPackRemote.normalizeUrl("  example.com/a.txt "))
        assertEquals("https://example.com/a.txt", QuestionPackRemote.normalizeUrl("//example.com/a.txt"))
        assertEquals("http://example.com/a.txt", QuestionPackRemote.normalizeUrl("http://example.com/a.txt"))
        assertEquals("https://example.com/a.txt", QuestionPackRemote.normalizeUrl("\"https://example.com/a.txt\""))
    }

    @Test
    fun 地址归一_认不出来就给null() {
        assertNull(QuestionPackRemote.normalizeUrl("   "))
        assertNull(QuestionPackRemote.normalizeUrl("file:///sdcard/pack.txt"))
        assertNull(QuestionPackRemote.normalizeUrl("javascript:alert(1)"))
    }

    @Test
    fun html判定不误伤题包() {
        assertTrue(QuestionPackRemote.looksLikeHtml("<!doctype html><html>"))
        assertTrue(QuestionPackRemote.looksLikeHtml("<html><body>x</body></html>"))
        assertFalse(QuestionPackRemote.looksLikeHtml(packText))
        assertFalse(QuestionPackRemote.looksLikeHtml("[小学·数学·判断] 0 是偶数。|正确|能被 2 整除"))
    }

    @Test
    fun 空地址直接报错而不是发请求() {
        try {
            fetch("")
            fail("空地址应该抛异常")
        } catch (e: QuestionPackRemoteException) {
            assertTrue(e.message.orEmpty().contains("地址"))
        }
    }
}
