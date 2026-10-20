package com.studykit.ui.mistake

import java.io.File

/**
 * 题库「拍照录题」→ 错题本「拍照录入」页的一次性图片交接。
 *
 * 背景：题库那条路只收「题干 | 选项… | 答案」的四选一格式，一页教科书文字永远解析不出条目。
 * 那种情况下这张照片和 OCR 文本要原样转进错题本（错题本只要图片 + 自由文本，不需要选项与答案），
 * 而两条路是两个页面，中间需要一个承载物。
 *
 * **为什么不走导航参数**：一页书 OCR 出来的文本可能上千字，把文件路径和整段文本拼进路由串
 * 会撞上 Binder 事务上限（约 1MB），而且是不可控的输入长度；本仓 [com.studykit.ui.nav.ImportRoutes]
 * 的注释已经为「把 3000 条塞进导航参数」写过同一个理由。应用内单槽不序列化任何东西，
 * 文本多长都装得下，也顺带免了中文与特殊字符的转义问题。
 *
 * 语义是**单槽、消费即清空**：[stash] 覆盖上一次未取走的内容；[take] 取走后立刻清空，
 * 于是转屏重建、重复导航都不会把同一张图消费两次（照片归消费方所有，槽位不再持有）。
 */
internal object MistakeCaptureHandoff {
    private var file: File? = null
    private var prefillText: String = ""

    /** 记下待转交的照片与 OCR 文本；后一次覆盖前一次（单槽语义）。 */
    @Synchronized
    fun stash(file: File, text: String) {
        this.file = file
        this.prefillText = text
    }

    /** 取走并清空；没有待交接内容时返回 null。 */
    @Synchronized
    fun take(): Pair<File, String>? {
        val pending = file ?: return null
        val text = prefillText
        file = null
        prefillText = ""
        return pending to text
    }
}
