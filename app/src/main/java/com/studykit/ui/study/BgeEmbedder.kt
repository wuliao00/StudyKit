package com.studykit.ui.study

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * bge-small-zh-v1.5（INT8 ONNX）端侧句向量：**CLS 池化 + L2 归一化**，512 维。
 *
 * 模型 IO 以 2026-10-20 的 Python ORT 探针实测为准：
 * 输入 `input_ids` / `attention_mask` / `token_type_ids`，均为 int64 `[1, seq]`；
 * 输出 `last_hidden_state`，float32 `[1, seq, 512]`。取 `[0, 0]`（`[CLS]` 位）后做 L2 归一化，
 * 于是任意两段文本的余弦相似度就是两个向量的点积。
 *
 * 分词固定 `maxLength = 64`（含 `[CLS]`/`[SEP]`）。这是**检索侧与索引侧必须共用**的口径：
 * 同一段文本被截到不同长度，算出来的向量就不可比。
 *
 * [fromAssets] 是唯一的入口：模型资产 24MB 不能留在内存里反复转发，先落到应用缓存目录
 * 再以**文件路径**建 session（`OrtEnvironment.createSession(String, ...)`）；任何一步失败
 * 都返回 `null` 并记一条 warning，调用方据此退回词面相似度，而不是把异常甩到 UI 线程。
 */
internal class BgeEmbedder(
    private val session: OrtSession,
    private val tokenizer: BertWordPieceTokenizer,
) {

    /**
     * [text] → 512 维单位向量。
     *
     * 不做输入缓存：同一 session 上重复前向是确定性的，缓存留给调用方按业务决定。
     * 空串也能跑：分词只产出 `[CLS][SEP]`，输出仍是合法单位向量。
     */
    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text, MAX_SEQ_LEN)
        val seqLen = ids.size
        val shape = longArrayOf(1L, seqLen.toLong())
        val inputIds = LongArray(seqLen) { ids[it].toLong() }
        // 分词器不补 PAD，所以每个位置都是真实 token，mask 全 1、segment 全 0。
        val attentionMask = LongArray(seqLen) { 1L }
        val tokenTypeIds = LongArray(seqLen) { 0L }
        val env = OrtEnvironment.getEnvironment()

        val cls: FloatArray
        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape).use { idsTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape).use { maskTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape).use { typesTensor ->
                    val inputs = mapOf(
                        INPUT_IDS to idsTensor,
                        ATTENTION_MASK to maskTensor,
                        TOKEN_TYPE_IDS to typesTensor,
                    )
                    session.run(inputs).use { result ->
                        val hiddenState = result.get(OUTPUT_LAST_HIDDEN_STATE)
                            .orElseThrow {
                                IllegalStateException("模型缺少输出 $OUTPUT_LAST_HIDDEN_STATE")
                            }
                        @Suppress("UNCHECKED_CAST")
                        val hidden = hiddenState.value as Array<Array<FloatArray>>
                        // [CLS] 是整个序列的聚合表示，直接取第 0 个 token。
                        cls = hidden[0][0].copyOf()
                    }
                }
            }
        }
        l2NormalizeInPlace(cls)
        return cls
    }

    /**
     * 全零向量没有方向，保持原样返回；否则 `0/0` 会变成 NaN，污染下游所有余弦。
     * 真实模型对 `[CLS][SEP]` 不会给全零，这只是防御分支。
     */
    private fun l2NormalizeInPlace(vector: FloatArray) {
        var sumSquares = 0.0
        for (v in vector) sumSquares += v.toDouble() * v.toDouble()
        val norm = sqrt(sumSquares)
        if (norm == 0.0) return
        val inv = (1.0 / norm).toFloat()
        for (i in vector.indices) vector[i] *= inv
    }

    companion object {
        private const val TAG = "BgeEmbedder"
        private const val ASSET_DIR = "models/bge-small-zh-v1.5"
        private const val MODEL_ASSET = "$ASSET_DIR/model_int8.onnx"
        private const val VOCAB_ASSET = "$ASSET_DIR/vocab.txt"

        /**
         * 缓存文件名带模型版本。模型换版时改这里即可让旧缓存自然失效，不必猜"缓存是不是旧的"。
         */
        private const val MODEL_FILE_NAME = "model_int8-v1.onnx"
        private const val CACHE_DIR = "bge-small-zh-v1.5"

        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_TYPE_IDS = "token_type_ids"
        private const val OUTPUT_LAST_HIDDEN_STATE = "last_hidden_state"

        private const val MAX_SEQ_LEN = 64

        /** 同进程内只让一个线程拷贝 24MB 资产、建 session；避免并发重复落盘。 */
        private val LOAD_LOCK = Any()

        /**
         * 从 assets 加载词表与模型。**任何失败都返回 `null`，不抛**（资产缺失、缓存目录不可写、
         * 原生库加载失败、模型与 ORT 版本不兼容……）。失败只记 warning，由调用方决定降级方式。
         */
        fun fromAssets(context: Context): BgeEmbedder? = try {
            synchronized(LOAD_LOCK) {
                val vocab = context.assets.open(VOCAB_ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readLines() }
                val tokenizer = BertWordPieceTokenizer(vocab)
                val modelFile = ensureModelFile(context)
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    // 与 OCR 引擎同一取向：端侧小模型不值得开满线程，2 线程足够且更省电。
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                }
                val session = try {
                    env.createSession(modelFile.absolutePath, options)
                } finally {
                    options.close()
                }
                BgeEmbedder(session, tokenizer)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bge-small-zh 加载失败，语义交错需退回词面相似度", t)
            null
        }

        /**
         * assets → `cacheDir/bge-small-zh-v1.5/model_int8-v1.onnx`。
         *
         * 先写 `.tmp` 再 rename：拷贝中途崩溃最多留下一个半截临时文件，目标文件要么不存在、
         * 要么是完整的一份，不会出现"长度 > 0 但内容残缺"的缓存被 [fromAssets] 当成好文件。
         * 缓存被系统清掉后下次调用自动重拷。
         */
        private fun ensureModelFile(context: Context): File {
            val dir = File(context.cacheDir, CACHE_DIR)
            val target = File(dir, MODEL_FILE_NAME)
            if (target.isFile && target.length() > 0L) return target

            dir.mkdirs()
            val temp = File(dir, "$MODEL_FILE_NAME.tmp")
            context.assets.open(MODEL_ASSET).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                // 某些文件系统不允许 rename 覆盖/跨卷；退回复制，保证至少有一种路径能成。
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        }
    }
}
