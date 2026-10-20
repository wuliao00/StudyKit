package com.studykit.ui.study

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * `BgeEmbedder` 的真模型测试：Robolectric 读合并后的 assets（24MB `model_int8.onnx` + 21128 行
 * `vocab.txt`），走桌面版 ORT 的原生前向，**不 mock 任何一层**。
 *
 * 断言里的 512、1e-5、0.85、0.45、1e-6 都是 brief 给定的字面量，不从生产常量取值 ——
 * 否则实现把常量改小，测试会跟着一起"变绿"。
 *
 * 语义阈值刻意留了余量（本机实测近义 0.927、异题 0.281），只钉"模型/池化/归一化没接反"，
 * 不做逐位数值回归：INT8 量化模型的输出允许随 ORT 版本有微小浮动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BgeEmbedderRobolectricTest {

    private lateinit var embedder: BgeEmbedder

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        embedder = requireNotNull(BgeEmbedder.fromAssets(context)) {
            "BgeEmbedder.fromAssets 返回 null：模型/词表加载失败（见 logcat/控制台异常）"
        }
    }

    @Test
    fun `维度为 512`() {
        val v = embedder.embed("小球的速度变化越快")
        println("BGE_READING dim=${v.size}")
        assertEquals(512, v.size)
    }

    @Test
    fun `自身点积为 1`() {
        val v = embedder.embed("小球的速度变化越快")
        var dot = 0.0
        for (x in v) dot += x.toDouble() * x.toDouble()
        println("BGE_READING selfDot=%.9f".format(java.util.Locale.ROOT, dot))
        // 已做 L2 归一化，自点积就是单位长度。
        assertEquals(1.0, dot, 1e-5)
    }

    @Test
    fun `近义句余弦大于 0点85`() {
        val a = embedder.embed("小球的速度变化越快")
        val b = embedder.embed("小球的加速度越大")
        val cosine = cosine(a, b)
        println("BGE_READING nearCosine=%.9f".format(java.util.Locale.ROOT, cosine))
        assertTrue("余弦 = $cosine，应 > 0.85", cosine > 0.85)
    }

    @Test
    fun `异题余弦小于 0点45`() {
        val a = embedder.embed("求下列函数的导数")
        val b = embedder.embed("英语虚拟语气的用法")
        val cosine = cosine(a, b)
        println("BGE_READING farCosine=%.9f".format(java.util.Locale.ROOT, cosine))
        assertTrue("余弦 = $cosine，应 < 0.45", cosine < 0.45)
    }

    @Test
    fun `同一输入两次调用逐元素相等`() {
        val first = embedder.embed("小球的速度变化越快")
        val second = embedder.embed("小球的速度变化越快")
        var maxDiff = 0f
        for (i in first.indices) maxDiff = maxOf(maxDiff, abs(first[i] - second[i]))
        println("BGE_READING repeatMaxAbsDiff=%.9f".format(java.util.Locale.ROOT, maxDiff))
        assertArrayEquals(first, second, 1e-6f)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        return dot / (sqrt(na) * sqrt(nb))
    }
}
