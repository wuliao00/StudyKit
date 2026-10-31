package com.studykit.data.ocr

import android.content.Context

/**
 * OCR 两个模型的在线清单（v3.6）。
 *
 * 模型一共约 31MB，继续塞进 APK 只会让每次升级都多下 31MB。这里把它们挪到 jsDelivr：
 * 应用按段落下载、校验、落盘，之后引擎优先读下载版，读不到再退回 APK 内置的那份
 * （兼容包与老用户靠这条兜底）。
 *
 * `rec` 之所以切成三段：jsDelivr 单文件上限 20MB，21,234,383 B 的识别模型直接 403。
 * 三段各 8MB 左右，拼回去的 sha256 与原件一致（拆分脚本与清单在仓库 `models-host/ocr/`）。
 *
 * 数值来自 `models-host/ocr/manifest.json`；改模型时**必须同步两边**，
 * 否则表现为"下载完了但校验失败"，用户只会看到一句"校验失败"。
 */
object OcrModels {

    private const val BASE = "https://fastly.jsdelivr.net/gh/wuliao00/StudyKit@main"

    /** 文本检测（9.9MB，单段） */
    val DET = OcrModelSpec(
        id = "det_ppocrv6_small",
        totalBytes = 9_929_594,
        sha256 = "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f",
        parts = listOf(
            OcrModelPart(
                name = "det.onnx",
                url = "$BASE/app/src/main/assets/models/det_ppocrv6_small.onnx",
                bytes = 9_929_594,
                sha256 = "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f",
            ),
        ),
    )

    /** 文本识别（21.2MB，切成三段） */
    val REC = OcrModelSpec(
        id = "rec_ppocrv6_small",
        totalBytes = 21_234_383,
        sha256 = "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884",
        parts = listOf(
            OcrModelPart(
                name = "rec.part1",
                url = "$BASE/models-host/ocr/rec.part1",
                bytes = 8_388_608,
                sha256 = "09552cc476f5547366c54cfe9976907577d0cbe4a8bc4b5c9ce8ea0a094c8e37",
            ),
            OcrModelPart(
                name = "rec.part2",
                url = "$BASE/models-host/ocr/rec.part2",
                bytes = 8_388_608,
                sha256 = "3a246978906479451f1a6ba2ec7090295c5033bb130388164ec41eea622a612d",
            ),
            OcrModelPart(
                name = "rec.part3",
                url = "$BASE/models-host/ocr/rec.part3",
                bytes = 4_457_167,
                sha256 = "d6f55e81226a3791863a2f22d3ae64082a0b5b6d76f415f4597c6fd1d9ee82d3",
            ),
        ),
    )

    /** 两个模型总大小：31,163,977 B ≈ 29.7 MB，设置页用它显示「约 31 MB」 */
    val TOTAL_BYTES: Long = DET.totalBytes + REC.totalBytes

    // 安装器要缓存：`installedFile()` 首次调用会对 21MB 做一遍 sha256，
    // 每跑一次 OCR 都新建一个实例 = 每次都重算。applicationContext 是单例，缓存安全。
    @Volatile private var detCache: OcrModelInstaller? = null
    @Volatile private var recCache: OcrModelInstaller? = null

    fun detInstaller(context: Context): OcrModelInstaller =
        detCache ?: synchronized(this) {
            detCache ?: OcrModelInstaller(
                context.applicationContext,
                DET,
                assetName = "det_ppocrv6_small.onnx",
            ).also { detCache = it }
        }

    fun recInstaller(context: Context): OcrModelInstaller =
        recCache ?: synchronized(this) {
            recCache ?: OcrModelInstaller(
                context.applicationContext,
                REC,
                assetName = "rec_ppocrv6_small.onnx",
            ).also { recCache = it }
        }

    fun installers(context: Context): List<OcrModelInstaller> =
        listOf(detInstaller(context), recInstaller(context))

    /**
     * 读模型字节：**优先已下载并校验过的那份，其次 APK 内置**。
     *
     * 两边都没有时抛一句能照做的错：精简包（不带模型）在没下载前跑到 OCR，
     * 用户看到的应该是"去设置里下载模型"，而不是 ORT 的模型解析失败。
     */
    fun readBytes(context: Context, installer: OcrModelInstaller, assetName: String, label: String): ByteArray {
        // 读盘也可能失败（刚被 delete() 删掉、被系统清理）：绝不能因此抛一个 FileNotFoundException
        // 而不是走内置回退 —— 那是把"这个文件没了"错报成"模型不可用"。
        runCatching { installer.installedFile()?.readBytes() }.getOrNull()?.let { return it }
        runCatching { context.assets.open("models/$assetName").use { it.readBytes() } }
            .onSuccess { return it }
        throw IllegalStateException("${label}未安装。去「设置 → OCR 模型」下载后即可使用离线识别。")
    }
}
