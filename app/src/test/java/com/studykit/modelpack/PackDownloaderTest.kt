package com.studykit.modelpack

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PackDownloader] 的下载/校验/取消/清理（计划 T4）。
 *
 * **零真实外网请求**（计划 R3 的处置）：网络走 [PackHttpTransport] 假实现，磁盘余量走注入探针，
 * 取消走协作标志。覆盖的坏天气：404、302 跟随后成功、跳转环、sha 不符、缺条目、坏 zip、
 * 空间不足（一个请求都不发）、取消（staging 必须删干净）。
 */
class PackDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = File(tmp.newFolder("model-packs"), "voice-tts" + ModelPackStore.PARTIAL_SUFFIX)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun manifestOf(files: Map<String, ByteArray>, url: String? = "https://example.invalid/pack.zip"): PackManifest {
        val packFiles = files.map { (path, bytes) ->
            PackFile(path = path, bytes = bytes.size.toLong(), sha256 = sha256(bytes))
        }
        return PackManifest(
            schema = 1,
            id = "voice-tts",
            kind = PackKind.VOICE_TTS,
            displayName = "语音包（朗读）",
            version = "1.0.0",
            minApp = null,
            sizeBytes = packFiles.sumOf { it.bytes },
            downloadUrl = url,
            files = packFiles,
        )
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private class FakeTransport(private val respond: (String) -> PackHttpResponse) : PackHttpTransport {
        val requested = mutableListOf<String>()
        override fun open(url: String): PackHttpResponse {
            requested += url
            return respond(url)
        }
    }

    private fun downloader(
        transport: PackHttpTransport,
        usable: Long = Long.MAX_VALUE,
    ): PackDownloader = PackDownloader(transport = transport, usableSpace = { usable })

    private val modelBytes = ByteArray(512) { it.toByte() }
    private val tokenBytes = "tokens-content".toByteArray()
    private val files = mapOf("model.bin" to modelBytes, "tokens.txt" to tokenBytes)

    private fun okTransport(zip: ByteArray): FakeTransport =
        FakeTransport { PackHttpResponse.Ok(ByteArrayInputStream(zip), zip.size.toLong()) }

    @Test
    fun `成功下载解包并通过逐文件校验`() = runBlocking {
        val manifest = manifestOf(files)
        val zip = zipOf(files)
        val progress = mutableListOf<Float>()
        val transport = okTransport(zip)

        val result = downloader(transport).download(
            manifest = manifest,
            stagingDir = staging,
            onProgress = { progress += it },
        )

        assertTrue("应返回成功：$result", result is DownloadResult.Success)
        assertEquals(manifest.downloadUrl, transport.requested.single())
        val payload = File(staging, ModelPackStore.PAYLOAD_DIR)
        assertTrue(File(payload, "model.bin").readBytes().contentEquals(modelBytes))
        assertTrue(File(payload, "tokens.txt").readBytes().contentEquals(tokenBytes))

        assertTrue("进度首值应为 0", progress.isNotEmpty() && progress.first() == 0f)
        assertEquals("进度末值应为 1", 1f, progress.last())
        assertEquals("进度必须单调不减", progress.sorted(), progress)
        assertTrue(progress.all { it in 0f..1f })
    }

    @Test
    fun `服务端不给长度时进度只有起点和终点`() = runBlocking {
        val manifest = manifestOf(files)
        val zip = zipOf(files)
        val progress = mutableListOf<Float>()
        val transport = FakeTransport { PackHttpResponse.Ok(ByteArrayInputStream(zip), -1L) }

        val result = downloader(transport).download(
            manifest = manifest,
            stagingDir = staging,
            onProgress = { progress += it },
        )

        assertTrue(result is DownloadResult.Success)
        assertEquals(listOf(0f, 1f), progress)
    }

    @Test
    fun `HTTP404 失败且不留 staging`() = runBlocking {
        val transport = FakeTransport { PackHttpResponse.Failed("HTTP 404") }
        val result = downloader(transport).download(manifestOf(files), staging)
        val failed = result as DownloadResult.Failed
        assertEquals(DownloadFailure.HTTP, failed.reason)
        assertTrue(failed.detail.orEmpty().contains("404"))
        assertFalse(staging.exists())
    }

    @Test
    fun `302 跟随后成功且请求地址按 Location 解析`() = runBlocking {
        val zip = zipOf(files)
        val transport = FakeTransport { url ->
            if (url == "https://example.invalid/pack.zip") {
                PackHttpResponse.Redirect("/real.zip", 302)
            } else {
                PackHttpResponse.Ok(ByteArrayInputStream(zip), zip.size.toLong())
            }
        }
        val result = downloader(transport).download(manifestOf(files), staging)
        assertTrue("应跟随 302 后成功：$result", result is DownloadResult.Success)
        assertEquals(
            listOf("https://example.invalid/pack.zip", "https://example.invalid/real.zip"),
            transport.requested,
        )
    }

    @Test
    fun `重定向超过上限就失败`() = runBlocking {
        val transport = FakeTransport { PackHttpResponse.Redirect("/loop.zip", 302) }
        val result = downloader(transport).download(manifestOf(files), staging)
        val failed = result as DownloadResult.Failed
        assertEquals(DownloadFailure.HTTP, failed.reason)
        assertTrue(failed.detail.orEmpty().contains("重定向超过"))
        assertEquals("最多跟 5 跳 = 打开 6 次", 6, transport.requested.size)
        assertFalse(staging.exists())
    }

    @Test
    fun `相对 Location 四种写法都能解析`() {
        assertEquals("https://cdn.example/x.zip", resolveRedirectLocation("https://a.example/b/c.zip", "https://cdn.example/x.zip"))
        assertEquals("https://cdn.example/x.zip", resolveRedirectLocation("https://a.example/b/c.zip", "//cdn.example/x.zip"))
        assertEquals("https://a.example/x.zip", resolveRedirectLocation("https://a.example/b/c.zip", "/x.zip"))
        assertEquals("https://a.example/b/x.zip", resolveRedirectLocation("https://a.example/b/c.zip", "x.zip"))
        assertEquals(null, resolveRedirectLocation("https://a.example/b/c.zip", null))
        assertEquals(null, resolveRedirectLocation("https://a.example/b/c.zip", "   "))
    }

    @Test
    fun `sha256 不匹配就失败并删 staging`() = runBlocking {
        val zip = zipOf(files)
        // 清单里 model.bin 的 sha 与真实内容不符
        val manifest = manifestOf(files).copy(
            files = listOf(
                PackFile("model.bin", modelBytes.size.toLong(), "0".repeat(64)),
                PackFile("tokens.txt", tokenBytes.size.toLong(), sha256(tokenBytes)),
            ),
        )
        val result = downloader(okTransport(zip)).download(manifest, staging)
        val failed = result as DownloadResult.Failed
        assertEquals(DownloadFailure.VERIFY, failed.reason)
        assertFalse("校验失败必须删 staging", staging.exists())
    }

    @Test
    fun `缺条目就失败`() = runBlocking {
        val partial = mapOf("model.bin" to modelBytes)
        val result = downloader(okTransport(zipOf(partial))).download(manifestOf(files), staging)
        val failed = result as DownloadResult.Failed
        assertEquals(DownloadFailure.VERIFY, failed.reason)
        assertTrue(failed.detail.orEmpty().contains("tokens.txt"))
        assertFalse(staging.exists())
    }

    @Test
    fun `不是 zip 就报解包失败`() = runBlocking {
        val garbage = "definitely not a zip".toByteArray()
        val transport = FakeTransport { PackHttpResponse.Ok(ByteArrayInputStream(garbage), garbage.size.toLong()) }
        val result = downloader(transport).download(manifestOf(files), staging)
        val failed = result as DownloadResult.Failed
        assertEquals(DownloadFailure.ARCHIVE, failed.reason)
        assertFalse(staging.exists())
    }

    @Test
    fun `zip 夹带清单外条目被忽略且不越界写盘`() = runBlocking {
        val payload = zipOf(
            files + mapOf(
                "extra.bin" to "extra".toByteArray(),
                "../escape.bin" to "escape".toByteArray(),
            ),
        )
        val result = downloader(okTransport(payload)).download(manifestOf(files), staging)
        assertTrue("清单外条目不该让下载失败：$result", result is DownloadResult.Success)
        val stagingPayload = File(staging, ModelPackStore.PAYLOAD_DIR)
        assertFalse(File(stagingPayload, "extra.bin").exists())
        assertFalse("zip 里的 ../ 不许写到 staging 之外", File(staging.parentFile, "escape.bin").exists())
    }

    @Test
    fun `磁盘余量不足时不发请求直接失败`() = runBlocking {
        val manifest = manifestOf(files)
        val transport = FakeTransport { PackHttpResponse.Failed("不该发请求") }
        // 需要 sizeBytes*1.1 + 20MB
        val need = manifest.sizeBytes + manifest.sizeBytes / 10 + 20L * 1024 * 1024
        val result = downloader(transport, usable = need - 1).download(manifest, staging)
        val failed = result as DownloadResult.Failed
        assertEquals(DownloadFailure.SPACE, failed.reason)
        assertTrue(transport.requested.isEmpty())
        assertFalse(staging.exists())
    }

    @Test
    fun `磁盘余量恰好够时继续下载`() = runBlocking {
        val manifest = manifestOf(files)
        val zip = zipOf(files)
        val need = manifest.sizeBytes + manifest.sizeBytes / 10 + 20L * 1024 * 1024
        val result = downloader(okTransport(zip), usable = need).download(manifest, staging)
        assertTrue(result is DownloadResult.Success)
    }

    @Test
    fun `取消点已置位时一个请求都不发`() = runBlocking {
        val transport = FakeTransport { PackHttpResponse.Failed("不该发请求") }
        val result = downloader(transport).download(
            manifest = manifestOf(files),
            stagingDir = staging,
            isCancelled = { true },
        )
        assertEquals(DownloadResult.Cancelled, result)
        assertTrue(transport.requested.isEmpty())
        assertFalse(staging.exists())
    }

    @Test
    fun `下载中途取消会删 staging 并返回 Cancelled`() = runBlocking {
        // 64KB 不可压缩内容：保证进度回调不止一次
        val big = ByteArray(64 * 1024) { (it * 31 % 251).toByte() }
        val bigFiles = mapOf("model.bin" to big)
        val manifest = manifestOf(bigFiles)
        val zip = zipOf(bigFiles)
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val progress = mutableListOf<Float>()
        val transport = FakeTransport { PackHttpResponse.Ok(ByteArrayInputStream(zip), zip.size.toLong()) }

        val result = downloader(transport).download(
            manifest = manifest,
            stagingDir = staging,
            onProgress = { value ->
                progress += value
                // 第一次真实进度回调就把取消点拨起来
                if (value > 0f) cancel.set(true)
            },
            isCancelled = { cancel.get() },
        )

        assertEquals(DownloadResult.Cancelled, result)
        assertFalse("取消必须删 staging", staging.exists())
        assertTrue("取消前应已收到过进度", progress.isNotEmpty())
    }

    @Test
    fun `未上架包不下载`() = runBlocking {
        val transport = FakeTransport { PackHttpResponse.Failed("不该发请求") }
        val result = downloader(transport).download(manifestOf(files, url = null), staging)
        assertEquals(DownloadResult.Failed(DownloadFailure.NOT_PUBLISHED), result)
        assertTrue(transport.requested.isEmpty())
    }
}
