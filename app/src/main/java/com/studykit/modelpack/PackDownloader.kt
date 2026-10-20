package com.studykit.modelpack

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 下载失败的种类。
 *
 * 为什么是 enum 而不是一句现成的人话：用户看到的那句话必须由 [ModelPackCopy] 统一给出
 * （文案单一来源是硬约束）；下载器只负责说清"哪一类"，[detail] 保留给排查用的技术细节。
 */
enum class DownloadFailure {
    /** 种子还没有上架（manifest.downloadUrl 为空）—— 计划 §3.10：占位不许假装能下。 */
    NOT_PUBLISHED,

    /** 磁盘余量不够，**没发请求**就失败（计划 §3.6）。 */
    SPACE,

    /** 网络/IO 异常（连接失败、超时、流被掐）。 */
    NETWORK,

    /** 服务端回了不可接受的状态码 / 重定向跟不下去。 */
    HTTP,

    /** 下载到的东西不是能解开的包（zip 结构错误）。 */
    ARCHIVE,

    /** 解压后逐文件 sha256/长度与清单不符（计划 §3.6：不匹配就删 staging）。 */
    VERIFY,
}

/** 一次下载的结果。[Success] 只代表"staging 里是校验通过的文件"，正式安装由 ModelPackStore.commit 完成。 */
sealed interface DownloadResult {
    data class Success(val stagingDir: File) : DownloadResult
    data class Failed(val reason: DownloadFailure, val detail: String? = null) : DownloadResult
    data object Cancelled : DownloadResult
}

/**
 * 一次 HTTP "打开"的结果。
 *
 * 3xx 的**跟随逻辑**不在这里：transport 只把"服务端让我去哪"原样交回，跟不跟、跟几次由
 * [PackDownloader] 决定 —— 这样单测用假 transport 就能精确造 404 / 302 / 跳转环，
 * 而不用起一个真实 HTTP 服务器（CI 上也不依赖外网）。
 */
sealed interface PackHttpResponse {
    /** 2xx：拿到内容流与长度（无 Content-Length 时 [contentLength] <= 0）。 */
    class Ok(val stream: InputStream, val contentLength: Long) : PackHttpResponse

    /** 3xx：[location] 可能为空（服务端没给地址）。 */
    class Redirect(val location: String?, val code: Int) : PackHttpResponse

    /** 非 2xx/3xx，或跟跳期间网络层失败。 */
    data class Failed(val reason: String) : PackHttpResponse
}

/** 网络接缝：生产用 [UrlPackHttpTransport]，单测用假实现（零真实外网请求是 T4 的验收条件）。 */
fun interface PackHttpTransport {
    fun open(url: String): PackHttpResponse
}

/**
 * 生产网络实现：`HttpURLConnection` + 手动逐跳跟 3xx（照 `DictRemote` 的手法）。
 *
 * `instanceFollowRedirects` 必须关：开着的时候 3xx 在连接内部就被吃掉，上层看到的错误
 * 与"真·坏包"分不开（DictRemote 为此烧过一次真机定性）。这里我们把 Location 交回给
 * [PackDownloader]，跟跳次数由它统一限制。
 */
class UrlPackHttpTransport(
    private val timeoutMillis: Int = PackDownloader.TIMEOUT_MILLIS,
) : PackHttpTransport {

    override fun open(url: String): PackHttpResponse {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", PackDownloader.USER_AGENT)
            }
        } catch (e: IOException) {
            return PackHttpResponse.Failed("URL 打不开：${e.message}")
        }
        return try {
            when (val code = connection.responseCode) {
                in 200..299 -> PackHttpResponse.Ok(
                    stream = DisconnectingStream(connection.inputStream, connection),
                    contentLength = connection.contentLengthLong,
                )
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    PackHttpResponse.Redirect(location = location, code = code)
                }
                else -> {
                    connection.disconnect()
                    PackHttpResponse.Failed("HTTP $code")
                }
            }
        } catch (e: IOException) {
            connection.disconnect()
            PackHttpResponse.Failed("网络异常：${e.message}")
        }
    }

    /** 内容流关闭时顺手断开连接；否则 OkHttp 之外的连接池虽不存在，socket 也可能挂到 GC。 */
    private class DisconnectingStream(
        stream: InputStream,
        private val connection: HttpURLConnection,
    ) : FilterInputStream(stream) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * 模型包下载器（计划 §3.6）：下载 → 解包 → 逐文件 sha256 → 把干净的 staging 交给
 * [ModelPackStore.commit]。**它自己从不触碰正式目录**，因此"下载失败/取消/校验不过"永远
 * 不会留下半安装态。
 *
 * ## 包形态（v1 的显式假设，留给 D3 托管决策）
 * `manifest.downloadUrl` 指向**一个 zip**，zip 内条目路径 == `manifest.files[].path`（相对
 * payload 根）。这是唯一能在"单 URL + 多文件 sha256"的 schema 下说清楚的打包方式。
 * 计划 §1.4 实测到的真实模型多是 .tar.bz2（sherpa）或逐文件直链（MNN），上架前要么重新打包成
 * zip、要么给 manifest 加打包格式字段 —— **v1 不假装支持 bz2**，写明在这里而不是留个隐式约定。
 *
 * ## 为什么不做断点续传
 * 计划 §3.6 明确 v1 不做：最大 433MB，Wi-Fi 场景重下可接受；续传要处理 Range/206/偏移校验/
 * 服务端不支持 Range 的回落，复杂度与收益不成比例。失败 = 删 staging 重来。
 *
 * ## 可测性
 * 网络走 [PackHttpTransport] 接缝、磁盘余量走 [usableSpace] 探针、取消走 [download] 的
 * `isCancelled` 回调：单测用假 transport 覆盖成功/404/302/跳转超限/校验不过/取消/空间不足，
 * 全程零真实外网请求（计划 R3）。
 *
 * @param transport 网络接缝；默认 [UrlPackHttpTransport]。
 * @param usableSpace 磁盘余量探针（入参是 staging 目录）；单测注入 0 即可验证"不发请求直接失败"。
 */
open class PackDownloader(
    private val transport: PackHttpTransport = UrlPackHttpTransport(),
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {

    /**
     * 把 [manifest] 对应的包下载并校验到 [stagingDir]（调用方给 `
     * ModelPackStore.stagingDirOf(id)`；下载器不猜路径）。
     *
     * @param onProgress 0f..1f 单调；服务端不给 Content-Length 时只在开始/结束各回调一次
     *   （与 `DictRemote` 同口径，UI 显示 indeterminate）。
     * @param isCancelled 协作式取消探针：返回 true 时清理 staging 并返回 [DownloadResult.Cancelled]。
     *   协程自身的取消（`CancellationException`）会清理后**继续抛出** —— 吞取消是协程纪律错误，
     *   由调用方（ViewModel）把 job 取消映射成界面状态。
     */
    open suspend fun download(
        manifest: PackManifest,
        stagingDir: File,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val url = manifest.downloadUrl
            ?: return@withContext DownloadResult.Failed(DownloadFailure.NOT_PUBLISHED)
        try {
            if (isCancelled()) return@withContext cancelAndClean(stagingDir)
            stagingDir.deleteRecursively()
            val payload = File(stagingDir, ModelPackStore.PAYLOAD_DIR)
            if (!payload.mkdirs() && !payload.isDirectory) {
                return@withContext DownloadResult.Failed(DownloadFailure.NETWORK, "建不了暂存目录：${stagingDir.path}")
            }
            onProgress(0f)

            // 磁盘余量预检：计划 §3.6 的 sizeBytes * 1.1 + 20MB；不够就直接失败，一个字节都不下载
            val available = usableSpace(stagingDir)
            val needBytes = manifest.sizeBytes + manifest.sizeBytes / 10 + SPACE_MARGIN_BYTES
            if (available < needBytes) {
                stagingDir.deleteRecursively()
                return@withContext DownloadResult.Failed(
                    DownloadFailure.SPACE,
                    "可用 ${available / MB}MB，预计需要 ${needBytes / MB}MB",
                )
            }

            val response = openWithRedirects(url)
            if (response !is PackHttpResponse.Ok) {
                stagingDir.deleteRecursively()
                return@withContext when (response) {
                    is PackHttpResponse.Failed -> DownloadResult.Failed(DownloadFailure.HTTP, response.reason)
                    is PackHttpResponse.Redirect ->
                        DownloadResult.Failed(DownloadFailure.HTTP, "重定向后仍没有内容（HTTP ${response.code}）")
                    is PackHttpResponse.Ok -> error("上面已经排除了 Ok")
                }
            }

            CountingInputStream(response.stream).use { counting ->
                extract(manifest, counting, response.contentLength, stagingDir, onProgress, isCancelled)
            }
            verifyPayloadFiles(stagingDir, manifest)?.let { reason ->
                stagingDir.deleteRecursively()
                return@withContext DownloadResult.Failed(DownloadFailure.VERIFY, reason)
            }
            onProgress(1f)
            DownloadResult.Success(stagingDir)
        } catch (e: CancellationException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: PackCancelledException) {
            cancelAndClean(stagingDir)
        } catch (e: PackArchiveException) {
            stagingDir.deleteRecursively()
            DownloadResult.Failed(DownloadFailure.ARCHIVE, e.message)
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            DownloadResult.Failed(DownloadFailure.NETWORK, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun cancelAndClean(stagingDir: File): DownloadResult {
        stagingDir.deleteRecursively()
        return DownloadResult.Cancelled
    }

    /**
     * 跟 3xx（最多 [MAX_REDIRECTS] 跳）直到拿到内容或失败。
     *
     * 相对 Location 的四种写法（绝对 URL / 协议相对 / 以 / 开头 / 相对当前目录）都要认，
     * 见 [resolveRedirectLocation] —— 服务端给相对地址是 HTTP 规范允许的。
     */
    private fun openWithRedirects(url: String): PackHttpResponse {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            when (val response = transport.open(target)) {
                is PackHttpResponse.Redirect -> {
                    val next = resolveRedirectLocation(target, response.location)
                        ?: return PackHttpResponse.Failed("HTTP ${response.code}，但服务端没给可跟随的重定向地址")
                    target = next
                }
                else -> return response
            }
        }
        return PackHttpResponse.Failed("重定向超过 $MAX_REDIRECTS 次仍未拿到内容")
    }

    /** 流式解 zip：只落 manifest 列出的条目；夹带条目直接忽略（zip 里夹私货写不进来）。 */
    private suspend fun extract(
        manifest: PackManifest,
        counting: CountingInputStream,
        contentLength: Long,
        stagingDir: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val expected = manifest.files.associateBy { it.path }
        val payload = File(stagingDir, ModelPackStore.PAYLOAD_DIR)
        val buffered = BufferedInputStream(counting, BUFFER_BYTES)
        // zip 的本地文件头必须以 "PK" 开头。先验魔数再交给 ZipInputStream：
        // 一串非 zip 字节会被 ZipInputStream 当成"空压缩包"静默结束（nextEntry 返回 null），
        // 症状会变成"缺文件/校验失败"而不是"下载到的不是包"——错误归因会把人带偏。
        buffered.mark(ZIP_MAGIC_SIZE)
        val magic = ByteArray(ZIP_MAGIC_SIZE)
        val magicRead = buffered.read(magic)
        buffered.reset()
        val looksLikeZip = magicRead == ZIP_MAGIC_SIZE &&
            magic[0] == 'P'.code.toByte() &&
            magic[1] == 'K'.code.toByte()
        if (!looksLikeZip) throw PackArchiveException("下载到的内容不是 zip 包（缺少 PK 头）")
        ZipInputStream(buffered).use { zip ->
            while (true) {
                if (isCancelled()) throw PackCancelledException()
                coroutineContext.ensureActive()
                val entry = try {
                    zip.nextEntry
                } catch (e: Exception) {
                    throw PackArchiveException("zip 结构错误：${e.message}")
                } ?: break
                if (entry.isDirectory) continue
                val spec = expected[entry.name] ?: continue
                val target = resolvePayloadFileUnder(payload, spec.path)
                    ?: throw PackArchiveException("条目路径越界：${spec.path}")
                target.parentFile?.mkdirs()
                target.outputStream().buffered(BUFFER_BYTES).use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (isCancelled()) throw PackCancelledException()
                        coroutineContext.ensureActive()
                        val read = try {
                            zip.read(buffer)
                        } catch (e: Exception) {
                            throw PackArchiveException("读 zip 失败：${e.message}")
                        }
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        if (contentLength > 0) {
                            onProgress((counting.count.toFloat() / contentLength).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    /** 只计数不缓冲：进度按网络侧真实读到的字节算（压缩包长度）。 */
    private class CountingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) count += 1 }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = delegate.close()
    }

    private class PackCancelledException : Exception("下载已取消")

    private class PackArchiveException(message: String) : Exception(message)

    companion object {
        /** 跟跳上限：与 DictRemote 同一口径（5 次还没落地就当它坏了）。 */
        const val MAX_REDIRECTS = 5

        /** 连接/读取超时（计划 §3.6：20s）。 */
        const val TIMEOUT_MILLIS = 20_000

        /** UA 带产品名：托管方排查时能认出来源。 */
        const val USER_AGENT = "StudyKit-PackDownloader"

        private const val BUFFER_BYTES = 8 * 1024

        /** zip 本地文件头的魔数长度（"PK"；完整签名 4 字节，认前 2 字节足够区分"不是 zip"）。 */
        private const val ZIP_MAGIC_SIZE = 2
        private const val MB = 1024L * 1024L

        /** 空间余量：计划 §3.6 的 "+20MB"。 */
        private const val SPACE_MARGIN_BYTES = 20L * 1024L * 1024L
    }
}

/**
 * 把 `Location` 头补成绝对地址。四种合法写法都要认（HTTP 规范允许相对地址）：
 * 绝对 URL、协议相对双斜杠形式、从 `/` 起、相对当前目录。
 *
 * 与 `DictRemote.resolveLocation` 同一套规则（两份实现各自简单，计划 §3.6 明确"不必抽共用抽象"）。
 */
internal fun resolveRedirectLocation(base: String, location: String?): String? {
    val target = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val schemeEnd = target.indexOf("://")
    return when {
        schemeEnd >= 0 -> target
        target.startsWith("//") -> base.substringBefore("://") + ":" + target
        target.startsWith("/") -> base.substringBefore("://") + "://" + base.hostPart() + target
        else -> base.substringBeforeLast('/') + "/" + target
    }
}

/** 取 URL 的主机名（不含端口）；解析不出来时返回原串，只用于拼诊断文案。 */
private fun String.hostPart(): String {
    val schemeEnd = indexOf("://")
    if (schemeEnd < 0) return this
    val afterScheme = substring(schemeEnd + 3)
    val host = afterScheme.substringBefore('/').substringBefore(':')
    return host.ifEmpty { this }
}
