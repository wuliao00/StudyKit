package com.studykit

/**
 * 语义组轮的嵌入提供方（v3.1 Task 3）：一段文本 → 一条向量；**返回 null = 当前不可用**。
 *
 * 放在 `com.studykit` 而不是 `ui.study`，是因为 [AppContainer] 是 public 类，
 * 它的构造子不能暴露 `internal` 类型。实现方在失败时必须用 null 表达，**不许把异常甩给组轮入口**：
 * 调用方（`planQuestionQueue`）据此回退词面相似度，用户不该因为模型加载失败而开不了练习。
 */
fun interface EmbeddingProvider {

    fun embed(text: String): FloatArray?

    companion object {
        /**
         * 默认实现：永远不可用。
         *
         * 这是**刻意**的默认值：JVM 单测用 `AppContainer(db, fakeRepo)` 只想要一只不碰模型的容器，
         * 若默认去加载 24MB 模型，每条组轮测试都会平白多出一次资产拷贝 + ORT session。
         * 生产走 `AppContainer(context)`，那里才注入真正的 `BgeEmbeddingProvider`。
         */
        val Disabled: EmbeddingProvider = EmbeddingProvider { null }
    }
}
