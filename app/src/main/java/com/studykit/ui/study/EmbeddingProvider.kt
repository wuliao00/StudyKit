package com.studykit.ui.study

import android.content.Context
import com.studykit.EmbeddingProvider

/**
 * 生产用的嵌入提供方：把 [BgeEmbedder.fromAssets] 的加载推迟到**第一次真正嵌入**时。
 *
 * 失败语义按 Task 3 的裁定：
 *  - 加载失败（资产缺失、缓存不可写、ORT/模型不兼容……）返回 null，**缓存这次失败**、不重试、不抛；
 *  - 单次前向抛错也只把这一条当失败（返回 null），交给装配层回退词面，不把异常带上组轮入口。
 *
 * [embed] 加 `@Synchronized`：`BgeEmbedder` 只有一只 ORT session，而组轮可能从不同协程线程进来；
 * 串行化前向比让 ORT 自己撞并发更稳，而且一次会话最多二十来条，省下的并行度换不到什么。
 */
internal class BgeEmbeddingProvider(private val context: Context) : EmbeddingProvider {

    private var embedder: BgeEmbedder? = null
    private var loadAttempted = false

    @Synchronized
    override fun embed(text: String): FloatArray? {
        if (!loadAttempted) {
            loadAttempted = true
            embedder = BgeEmbedder.fromAssets(context)
        }
        val current = embedder ?: return null
        return runCatching { current.embed(text) }.getOrNull()
    }
}
