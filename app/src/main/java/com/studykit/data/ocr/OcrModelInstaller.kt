package com.studykit.data.ocr

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一段分片 */
data class OcrModelPart(val name: String, val url: String, val bytes: Long, val sha256: String)

/** 一个模型的完整描述（由上层从 manifest 常量填） */
data class OcrModelSpec(
    val id: String,
    val totalBytes: Long,
    val sha256: String,
    val parts: List<OcrModelPart>,
)

enum class OcrModelState {
    /** 还没下，APK 里也没有 */
    MISSING,

    /** 用 APK 内置的那份（老用户/兼容构建） */
    READY_BUNDLED,

    /** 已下载并通过校验 */
    READY_DOWNLOADED,

    /** 上次下载失败，error 里有原因 */
    FAILED,
}

data class OcrModelStatus(
    val state: OcrModelState,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

/**
 * OCR 模型的按需安装器：分段下载 → 顺序拼接 → sha256 校验 → 原子落盘。
 *
 * ## 为什么先写 `*.tmp` 再 rename
 *
 * 下载中断、进程被杀、校验失败时，正式文件必须**从未存在过**。否则引擎会把一个被截断的
 * ONNX 当成"已安装"，报出来的错是 ORT 的模型解析失败，排查时完全看不出是下载问题。
 * 同一目录内 rename 在 Android 文件系统上是原子的，UI 只会看到"没有"或"完整"。
 *
 * ## 为什么进度期间 state 保持 MISSING
 *
 * 调用方的既有约定是 `downloadedBytes > 0` 代表"正在下"，没有额外的 DOWNLOADING 状态。
 * 硬加一个状态会让已经接好的 UI 落到 else 分支、把下载中误显示成失败。这里只改字节数，
 * 让状态流在下载期间稳定地停在 MISSING。
 *
 * ## 为什么分片也要验
 *
 * 总 sha 只有在三段都拼完后才说话，那时已经浪费了 21MB 流量与等待。逐段先验能把
 * "第 2 段坏了"尽早定位，并且在写盘前就丢弃坏段。
 *
 * ## 线程
 *
 * [install] 内部切到 IO；[installedFile] 是同步 API，第一次调用会做全文件 sha256
 * （之后用缓存），不要在 Compose 每帧里调用它。
 */
class OcrModelInstaller(
    private val context: Context,
    private val spec: OcrModelSpec,
    /** 可注入的下载器：给 URL 和进度回调，返回整段字节。生产默认实现见下 */
    private val fetch: suspend (url: String, onProgress: (received: Long) -> Unit) -> ByteArray = { url, onProgress ->
        defaultFetch(url, onProgress)
    },
    /**
     * APK 内置模型的 asset 文件名（不含 `models/` 前缀）。默认对应 `models/rec_ppocrv6_small.onnx`；
     * 不需要内置兼容包时传一个不存在的名字即可让 [bundledAvailable] 返回 false。
     */
    private val assetName: String = "rec_ppocrv6_small.onnx",
) {

    /** 同一模型只允许一个安装流程，连点两下时第二次等待第一次的结果，而不是再下一遍 21MB。 */
    private val installMutex = Mutex()

    @Volatile
    private var verifiedCache: File? = null

    @Volatile
    private var verifiedComputed = false

    private val _status = MutableStateFlow(initialStatus())

    val status: StateFlow<OcrModelStatus> = _status.asStateFlow()

    /**
     * 下载并安装。已就绪直接返回 true。失败不抛异常，把原因写进 [status] 的 error。
     */
    suspend fun install(): Boolean = installMutex.withLock {
        // 文件读写与哈希都可能到几十 MB，全部挪出调用线程；Mutex 保证这里串行。
        withContext(Dispatchers.IO) {
            installLocked()
        }
    }

    /**
     * 已安装且校验通过的模型文件；没有则 null。
     *
     * 哈希只算一次：21MB 的 sha256 在低端机上也能被感知到，状态条刷新时反复算会卡 UI。
     * 文件不存在或长度不对时不读内容，直接返回 null（便宜路径）。
     */
    @Synchronized
    fun installedFile(): File? {
        if (verifiedComputed) return verifiedCache
        val file = targetFile()
        val verified = if (
            file.isFile &&
            file.length() == spec.totalBytes &&
            sha256OfFile(file).equals(spec.sha256, ignoreCase = true)
        ) {
            file
        } else {
            null
        }
        verifiedCache = verified
        verifiedComputed = true
        return verified
    }

    /** APK 里是否带了这个模型（assets/models/[assetName] 能打开就算有）。 */
    fun bundledAvailable(): Boolean = runCatching {
        context.assets.open("models/$assetName").use { true }
    }.getOrDefault(false)

    /** 删除已下载的模型（不影响内置的）。 */
    fun delete() {
        targetFile().delete()
        tempFile().delete()
        synchronized(this) {
            verifiedCache = null
            // 置回 false 而不是"缓存 null"：删完后用户可能再次下载，下一次 installedFile 必须重新看盘。
            verifiedComputed = false
        }
        publish(
            state = if (bundledAvailable()) OcrModelState.READY_BUNDLED else OcrModelState.MISSING,
            downloadedBytes = 0L,
        )
    }

    // ── 内部：安装流程 ───────────────────────────────────────────────────

    private suspend fun installLocked(): Boolean {
        // 先认已下载的（惰性哈希的缓存入口），再认 APK 内置的；两者都没有才联网。
        if (installedFile() != null) {
            publish(OcrModelState.READY_DOWNLOADED, spec.totalBytes)
            return true
        }
        if (bundledAvailable()) {
            publish(OcrModelState.READY_BUNDLED, 0L)
            return true
        }

        val dir = targetDir()
        val target = targetFile()
        val temp = tempFile()
        try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("无法创建模型目录")
            }
            // 崩溃残留的 tmp 不可信，直接覆盖；绝不能让它参与拼接。
            temp.delete()

            publish(OcrModelState.MISSING, 0L)

            var completed = 0L
            val totalDigest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).buffered().use { out ->
                for (part in spec.parts) {
                    // 每段开始都广播一次：上一段结束后的值在这里也是"当前段已收 0 字节"的起点。
                    publish(OcrModelState.MISSING, completed)

                    val base = completed
                    val bytes = fetch(part.url) { received ->
                        // fetch 约定回报的是"本段累计"；夹在 [0, part.bytes] 内，
                        // 防止实现方把块大小当累计值或超发时进度倒退/越过 100%。
                        val inPart = received.coerceIn(0L, part.bytes)
                        publish(OcrModelState.MISSING, base + inPart)
                    }

                    if (
                        bytes.size.toLong() != part.bytes ||
                        !sha256Hex(bytes).equals(part.sha256, ignoreCase = true)
                    ) {
                        throw VerificationFailure()
                    }

                    out.write(bytes)
                    totalDigest.update(bytes)
                    completed += bytes.size
                    publish(OcrModelState.MISSING, completed)
                }
                out.flush()
            }

            // 分段长度和总长对不上，说明 manifest 本身就坏了；即便总 sha 碰巧对上也不装。
            if (completed != spec.totalBytes) throw VerificationFailure()
            if (!totalDigest.digest().toHex().equals(spec.sha256, ignoreCase = true)) {
                throw VerificationFailure()
            }

            // 目标位可能有一份"长度对但内容坏"的旧文件（installedFile 已判它不合格），
            // 同目录 rename 要求目标不存在，所以先移除；新文件已完整校验，此时删旧是安全的。
            if (target.exists() && !target.delete()) {
                throw IOException("无法替换旧模型文件")
            }
            if (!temp.renameTo(target)) {
                throw IOException("保存模型文件失败")
            }

            verifiedCache = target
            verifiedComputed = true
            publish(OcrModelState.READY_DOWNLOADED, spec.totalBytes)
            return true
        } catch (cancelled: CancellationException) {
            // 协程取消不是"下载失败"，不能写 FAILED；清掉半成品后原样上抛让调用方收尾。
            temp.delete()
            throw cancelled
        } catch (failure: VerificationFailure) {
            temp.delete()
            publish(OcrModelState.FAILED, 0L, error = "校验失败")
            return false
        } catch (failure: Exception) {
            temp.delete()
            val reason = failure.message?.takeIf { it.isNotBlank() } ?: "下载失败"
            publish(OcrModelState.FAILED, 0L, error = reason)
            return false
        }
    }

    private fun initialStatus(): OcrModelStatus {
        // 先看下载版（会惰性哈希一次），再看内置版。状态里 READY_DOWNLOADED 的含义必须是真的校验过。
        val installed = installedFile()
        val state = when {
            installed != null -> OcrModelState.READY_DOWNLOADED
            bundledAvailable() -> OcrModelState.READY_BUNDLED
            else -> OcrModelState.MISSING
        }
        return OcrModelStatus(
            state = state,
            downloadedBytes = if (state == OcrModelState.READY_DOWNLOADED) spec.totalBytes else 0L,
            totalBytes = spec.totalBytes,
        )
    }

    private fun publish(
        state: OcrModelState,
        downloadedBytes: Long,
        error: String? = null,
    ) {
        _status.value = OcrModelStatus(
            state = state,
            downloadedBytes = downloadedBytes.coerceIn(0L, spec.totalBytes),
            totalBytes = spec.totalBytes,
            error = error,
        )
    }

    private fun targetDir(): File = File(context.filesDir, DIR)

    private fun targetFile(): File = File(targetDir(), "${spec.id}.onnx")

    private fun tempFile(): File = File(targetDir(), "${spec.id}.onnx.tmp")

    private class VerificationFailure : Exception("校验失败")

    companion object {
        const val DIR = "ocr_models"

        /**
         * 生产下载器：jsDelivr 的分片是纯 HTTPS 静态文件，用最薄的 HttpURLConnection 即可，
         * 不为一个下载器引入 OkHttp（本仓已有零新依赖的约定）。
         *
         * 只接受 https：模型字节会直接喂给 ONNX Runtime，明文传输意味着同网段的人可以把
         * "模型"换成任意内容；虽然还有 sha256 兜底，但让明文根本发不出去更干净。
         */
        private suspend fun defaultFetch(
            url: String,
            onProgress: (received: Long) -> Unit,
        ): ByteArray = withContext(Dispatchers.IO) {
            val parsed = try {
                URL(url)
            } catch (bad: Exception) {
                throw IOException("下载地址无效", bad)
            }
            if (!parsed.protocol.equals("https", ignoreCase = true)) {
                throw IOException("只支持 HTTPS 下载")
            }

            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                // 服务端/排查时能一眼认出是谁在拉 21MB 的模型
                setRequestProperty("User-Agent", "StudyKit-OcrModel")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IOException("下载失败（HTTP $code）")
                }
                val sink = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var received = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        sink.write(buffer, 0, read)
                        received += read
                        onProgress(received)
                    }
                }
                sink.toByteArray()
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        out[index * 2] = digits[value ushr 4]
        out[index * 2 + 1] = digits[value and 0x0F]
    }
    return String(out)
}
