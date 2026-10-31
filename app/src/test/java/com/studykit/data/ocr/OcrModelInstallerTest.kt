package com.studykit.data.ocr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OcrModelInstaller] 的 JVM 用例。
 *
 * 不联网：下载器走构造器注入，返回内存里的三段字节。Context 只用 Robolectric 的
 * ApplicationProvider —— 安装器对 Context 的依赖只有 `filesDir` 与 `assets`，
 * 用真实 Application 比手写一个几十个抽象方法的 Context 子类更稳，也能顺便验证
 * `assets/models/...` 的探测路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrModelInstallerTest {

    private lateinit var context: Context

    private val modelsDir: File
        get() = File(context.filesDir, OcrModelInstaller.DIR)

    private val target: File
        get() = File(modelsDir, "$MODEL_ID.onnx")

    private val temp: File
        get() = File(modelsDir, "$MODEL_ID.onnx.tmp")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        modelsDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    @Test
    fun `三段拼接且总 sha 正确时落盘并进入 READY_DOWNLOADED`() = runBlocking {
        val payloads = listOf(payload(700, seed = 1), payload(900, seed = 2), payload(400, seed = 3))
        val spec = specOf(payloads)
        val installer = installer(spec, fetchFrom(payloads))

        assertTrue(installer.install())

        val status = installer.status.value
        assertEquals(OcrModelState.READY_DOWNLOADED, status.state)
        assertEquals(spec.totalBytes, status.downloadedBytes)
        assertEquals(spec.totalBytes, status.totalBytes)
        assertNull(status.error)

        val installed = installer.installedFile()
        assertNotNull(installed)
        assertEquals("$MODEL_ID.onnx", installed!!.name)
        assertArrayEquals(concat(payloads), installed.readBytes())
        assertFalse("校验通过后临时文件必须消失", temp.exists())
    }

    @Test
    fun `分片 sha 不对时失败且不留下任何文件`() = runBlocking {
        val payloads = listOf(payload(300, seed = 4), payload(500, seed = 5), payload(200, seed = 6))
        val badPartShas = listOf(sha256(payloads[0]), "0".repeat(64), sha256(payloads[2]))
        val installer = installer(specOf(payloads, partShas = badPartShas), fetchFrom(payloads))

        assertFalse(installer.install())

        val status = installer.status.value
        assertEquals(OcrModelState.FAILED, status.state)
        assertEquals("校验失败", status.error)
        assertFalse("坏段不能留下临时文件", temp.exists())
        assertFalse("未通过校验的模型不能出现在正式路径", target.exists())
        assertNull(installer.installedFile())
    }

    @Test
    fun `总 sha 不对时失败且不留下任何文件`() = runBlocking {
        val payloads = listOf(payload(320, seed = 7), payload(480, seed = 8), payload(160, seed = 9))
        // 每段都对、拼起来也对不上：模拟"分片清单被换过，但总清单没更新"
        val installer = installer(specOf(payloads, totalSha = "f".repeat(64)), fetchFrom(payloads))

        assertFalse(installer.install())

        val status = installer.status.value
        assertEquals(OcrModelState.FAILED, status.state)
        assertEquals("校验失败", status.error)
        assertFalse(temp.exists())
        assertFalse(target.exists())
    }

    @Test
    fun `失败后重试成功会从 FAILED 回到 READY_DOWNLOADED`() = runBlocking {
        val payloads = listOf(payload(256, seed = 10), payload(384, seed = 11), payload(128, seed = 12))
        val spec = specOf(payloads)
        var failSecondPart = true
        var calls = 0
        val bytesByUrl = urls.zip(payloads).toMap()
        val flakyFetch: suspend (String, (Long) -> Unit) -> ByteArray = { url, onProgress ->
            calls++
            if (failSecondPart && url == urls[1]) throw IOException("下载失败（HTTP 500）")
            val bytes = bytesByUrl.getValue(url)
            onProgress(bytes.size.toLong())
            bytes
        }
        val installer = installer(spec, flakyFetch)

        assertFalse(installer.install())
        assertEquals(OcrModelState.FAILED, installer.status.value.state)
        assertEquals("下载失败（HTTP 500）", installer.status.value.error)
        assertFalse(temp.exists())

        failSecondPart = false
        assertTrue(installer.install())

        val status = installer.status.value
        assertEquals(OcrModelState.READY_DOWNLOADED, status.state)
        assertNull(status.error)
        assertEquals(spec.totalBytes, status.downloadedBytes)
        assertArrayEquals(concat(payloads), installer.installedFile()!!.readBytes())
        assertTrue("重试必须真的重新下载，而不是复用了半成品", calls > payloads.size)
    }

    @Test
    fun `进度是已完成分片加当前分片已收字节且单调递增`() = runBlocking {
        val payloads = listOf(payload(700, seed = 13), payload(900, seed = 14), payload(400, seed = 15))
        val spec = specOf(payloads)
        val bytesByUrl = urls.zip(payloads).toMap()
        val seen = mutableListOf<Long>()
        lateinit var installer: OcrModelInstaller
        // 每段回报两次（中点和终点），并在回调返回后立刻读状态，等价于 UI 在 collect 时看到的值。
        val twoProgressPerPart: suspend (String, (Long) -> Unit) -> ByteArray = { url, onProgress ->
            val bytes = bytesByUrl.getValue(url)
            onProgress(bytes.size / 2L)
            seen += installer.status.value.downloadedBytes
            onProgress(bytes.size.toLong())
            seen += installer.status.value.downloadedBytes
            bytes
        }
        installer = installer(spec, twoProgressPerPart)

        assertTrue(installer.install())

        assertEquals("三段各两次进度", 6, seen.size)
        assertTrue("进度必须严格递增：$seen", seen.zipWithNext().all { (before, after) -> before < after })
        assertEquals("最终进度必须等于总字节", spec.totalBytes, seen.last())
        assertEquals(spec.totalBytes, installer.status.value.downloadedBytes)
    }

    @Test
    fun `已安装文件校验通过时 install 直接秒传不调用下载器`() = runBlocking {
        val payloads = listOf(payload(200, seed = 16), payload(300, seed = 17), payload(100, seed = 18))
        val spec = specOf(payloads)
        assertTrue(modelsDir.mkdirs() || modelsDir.isDirectory)
        target.writeBytes(concat(payloads))

        var fetchCalls = 0
        val forbiddenFetch: suspend (String, (Long) -> Unit) -> ByteArray = { _, _ ->
            fetchCalls++
            throw AssertionError("已安装且校验通过时不应再下载")
        }
        val installer = installer(spec, forbiddenFetch)

        // 构造时就已经惰性校验过，状态不应停在 MISSING。
        assertEquals(OcrModelState.READY_DOWNLOADED, installer.status.value.state)
        assertTrue(installer.install())

        assertEquals(0, fetchCalls)
        assertEquals(OcrModelState.READY_DOWNLOADED, installer.status.value.state)
        assertEquals(spec.totalBytes, installer.status.value.downloadedBytes)
    }

    @Test
    fun `内置模型存在时初始状态是 READY_BUNDLED 且 install 不下载`() = runBlocking {
        val payloads = listOf(payload(120, seed = 19), payload(180, seed = 20), payload(60, seed = 21))
        var fetchCalls = 0
        val forbiddenFetch: suspend (String, (Long) -> Unit) -> ByteArray = { _, _ ->
            fetchCalls++
            throw AssertionError("有内置模型时不应下载")
        }
        // 用仓里已有且体积很小的 asset 代指内置模型，只验证"能 open 就算有"这条探测逻辑。
        val installer = OcrModelInstaller(
            context = context,
            spec = specOf(payloads),
            fetch = forbiddenFetch,
            assetName = "ppocr_keys_v6.txt",
        )

        assertTrue(installer.bundledAvailable())
        assertEquals(OcrModelState.READY_BUNDLED, installer.status.value.state)

        assertTrue(installer.install())
        assertEquals(0, fetchCalls)
        assertEquals(OcrModelState.READY_BUNDLED, installer.status.value.state)
    }

    @Test
    fun `删除只删下载版并回到 MISSING`() = runBlocking {
        val payloads = listOf(payload(100, seed = 22), payload(150, seed = 23), payload(50, seed = 24))
        val installer = installer(specOf(payloads), fetchFrom(payloads))
        assertTrue(installer.install())
        assertTrue(target.exists())

        installer.delete()

        assertFalse(target.exists())
        assertFalse(temp.exists())
        assertNull(installer.installedFile())
        assertEquals(OcrModelState.MISSING, installer.status.value.state)
        assertEquals(0L, installer.status.value.downloadedBytes)
    }

    // ── 测试脚手架 ───────────────────────────────────────────────────────

    private fun installer(
        spec: OcrModelSpec,
        fetch: suspend (String, (Long) -> Unit) -> ByteArray,
    ): OcrModelInstaller = OcrModelInstaller(
        context = context,
        spec = spec,
        fetch = fetch,
        // 大多数用例要的是"没有内置模型"，指向一个不存在的 asset 即可。
        assetName = "no_such_bundled_model.onnx",
    )

    private fun fetchFrom(payloads: List<ByteArray>): suspend (String, (Long) -> Unit) -> ByteArray {
        val bytesByUrl = urls.zip(payloads).toMap()
        return { url, onProgress ->
            val bytes = bytesByUrl.getValue(url)
            onProgress(bytes.size / 2L)
            onProgress(bytes.size.toLong())
            bytes
        }
    }

    private fun specOf(
        payloads: List<ByteArray>,
        partShas: List<String>? = null,
        totalSha: String? = null,
    ): OcrModelSpec {
        val effectivePartShas = partShas ?: payloads.map { sha256(it) }
        return OcrModelSpec(
            id = MODEL_ID,
            totalBytes = payloads.sumOf { it.size.toLong() },
            sha256 = totalSha ?: sha256(concat(payloads)),
            parts = payloads.mapIndexed { index, bytes ->
                OcrModelPart(
                    name = "part-${index + 1}",
                    url = urls[index],
                    bytes = bytes.size.toLong(),
                    sha256 = effectivePartShas[index],
                )
            },
        )
    }

    private fun payload(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed * 17) and 0xFF).toByte() }

    private fun concat(payloads: List<ByteArray>): ByteArray {
        val out = ByteArray(payloads.sumOf { it.size })
        var offset = 0
        for (part in payloads) {
            part.copyInto(out, destinationOffset = offset)
            offset += part.size
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digits = "0123456789abcdef"
        val out = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xFF
            out[index * 2] = digits[value ushr 4]
            out[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(out)
    }

    private companion object {
        const val MODEL_ID = "ppocrv6_small_test"
        val urls = listOf(
            "https://cdn.jsdelivr.net/test/ppocrv6.part1",
            "https://cdn.jsdelivr.net/test/ppocrv6.part2",
            "https://cdn.jsdelivr.net/test/ppocrv6.part3",
        )
    }
}
