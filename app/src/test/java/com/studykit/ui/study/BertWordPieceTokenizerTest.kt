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

/**
 * `BertWordPieceTokenizer` 对着**真词表**（`assets/models/bge-small-zh-v1.5/vocab.txt`，21128 行）钉行为。
 *
 * 三条金标准来自 2026-10-20 本机 Python ORT 探针对同一份 `tokenizer.json` 的实测输出，
 * 必须逐位复现。金标准末尾的 `0` 是探针把序列补到 `maxlen=32` 的 `[PAD]`；
 * `encode` **不补 PAD**，所以这里直接比到 `[SEP]` 为止的完整前缀。
 * 句中的 `100` 是 `[UNK]`（`A`、`x²` 里的 `²` 等在词表里没有独立词条），那是上游行为，不是缺陷。
 *
 * 词表直接从合并后的 assets 读：本仓 `testOptions.unitTests.isIncludeAndroidResources = true`，
 * Robolectric 能拿到 debug 变体 assets。不把 109KB 词表复制进 test resources。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BertWordPieceTokenizerTest {

    private lateinit var tokenizer: BertWordPieceTokenizer

    @Before
    fun setUp() {
        val vocab = ApplicationProvider.getApplicationContext<Context>()
            .assets.open("models/bge-small-zh-v1.5/vocab.txt")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }
        tokenizer = BertWordPieceTokenizer(vocab)
    }

    @Test
    fun `金标准一 下列句子中没有语病的一项是`() {
        assertArrayEquals(
            intArrayOf(
                101, 678, 1154, 1368, 2094, 704, 3766, 3300,
                6427, 4567, 4638, 671, 7555, 3221, 102,
            ),
            tokenizer.encode("下列句子中没有语病的一项是"),
        )
    }

    @Test
    fun `金标准二 设集合 A 等于 x 绝对值 x 平方小于 4 求集合 A`() {
        assertArrayEquals(
            intArrayOf(
                101, 6392, 7415, 1394, 100, 134, 169, 166, 170, 166,
                11699, 133, 125, 171, 8024, 3724, 7415, 1394, 100, 102,
            ),
            tokenizer.encode("设集合 A={x|x²<4}，求集合 A"),
        )
    }

    @Test
    fun `金标准三 velocity 变化越快`() {
        assertArrayEquals(
            intArrayOf(101, 12810, 8897, 10747, 1359, 1265, 6632, 2571, 102),
            tokenizer.encode("velocity 变化越快"),
        )
    }

    @Test
    fun `超长截断后仍以 SEP 结尾且长度不超过 maxLength`() {
        val maxLength = 8
        val ids = tokenizer.encode("下列句子中没有语病的一项是".repeat(8), maxLength)
        assertTrue("长度 ${ids.size} 超过 maxLength=$maxLength", ids.size <= maxLength)
        assertEquals("截断后必须钉满 maxLength", maxLength, ids.size)
        assertEquals("首 token 必须是 [CLS]", 101, ids.first())
        assertEquals("末 token 必须是 [SEP]", 102, ids.last())
    }

    @Test
    fun `空串只有 CLS 与 SEP`() {
        assertArrayEquals(intArrayOf(101, 102), tokenizer.encode(""))
    }
}
