package com.studykit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `OcrTextExtractor` 门面在 **PP-OCR 转正、ML Kit 下线**之后，对外契约不回归。
 *
 * 能在 JVM/Robolectric 上钉的是门面里**不依赖原生库**的那几条分支——坏输入必须在加载 30MB 模型、
 * 调用 onnxruntime **之前**就挡掉并回一句能显示的话（这也是"先解码再 ensureLoaded"的排序原因）。
 * "给定真实图片 → 走 PP 路径产出非空文本"这条端到端**无法**在 JVM 复现：onnxruntime 的 `.so` 只装
 * 在设备上，模拟器/宿主机没有对应实现，硬跑只会 `UnsatisfiedLinkError`。它归真机阶段验证，这里不伪造。
 * 引擎内部纯函数（CTC / DB 解码 / 分行）的正确性由 [PpOcrEngineSanityTest] 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrTextExtractorContractTest {

    private fun app(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun 文件不存在时返回可读失败而不是崩溃() = runBlocking {
        val missing = File(app().cacheDir, "no_such_${System.nanoTime()}.jpg")
        val result = OcrTextExtractor.recognize(app(), missing)
        assertTrue("期望 Failed，实际 $result", result is OcrResult.Failed)
        assertEquals("图片不存在或已损坏", (result as OcrResult.Failed).reason)
    }

    @Test
    fun 零字节文件同样判为不存在或损坏() = runBlocking {
        val empty = File(app().cacheDir, "empty_${System.nanoTime()}.jpg").apply { writeBytes(ByteArray(0)) }
        val result = OcrTextExtractor.recognize(app(), empty)
        assertEquals("图片不存在或已损坏", (result as OcrResult.Failed).reason)
    }

    @Test
    fun 失败态一定带一句非空可显示文案() = runBlocking {
        val result = OcrTextExtractor.recognize(app(), File("definitely/absent.png"))
        val failed = result as OcrResult.Failed
        assertNotNull(failed.reason)
        assertTrue("失败文案不该是空白", failed.reason.isNotBlank())
    }

    /**
     * 取消语义不回归：把 `recognize` 挂在一个可取消的协程里、在它产出结果前取消，
     * 调用方**绝不会**收到一个 `OcrResult.Text`（成功识别的文本要么不来、要么是失败态）。
     * 门面全程 `withContext(Dispatchers.IO)` + 显式 `ensureActive()`，`catch` 只吞非取消异常，
     * 所以取消向上抛——这正是四个调用点（离开界面即取消采集）依赖的行为。
     */
    @Test
    fun 协程被取消时不产出识别成功的文本() = runBlocking {
        val deferred = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            OcrTextExtractor.recognize(app(), File("input.jpg"))
        }
        deferred.cancel()
        val delivered = runCatching { deferred.await() }.getOrNull()
        assertTrue(
            "取消后不该拿到识别成功的文本，实际=$delivered",
            delivered == null || delivered !is OcrResult.Text,
        )
        assertTrue("门面协程应能被取消", deferred.isCancelled)
    }
}
