package com.studykit.util.importer

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** 在线题包拉取失败：网络、HTTP 状态、内容不像题包。界面只需要一句能看懂的原因 */
class QuestionPackRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 题包的在线下载（v3.5+）。
 *
 * 为什么要它能单独存在：题库商店原先的六个包随 APK 打包，改一道题就得发一个版本。
 * 现在 manifest 里每个包可以带一个 `url`，商店先按地址下载、再走同一套预览入库；
 * 下载失败时依次回落到本机缓存和 APK 里的内置副本（见 [QuestionPackStore]），
 * 所以"联网拿最新的、断网用旧的"这两件事互不牵连。
 *
 * 几条刻意的取舍：
 * - **零新依赖**：`HttpURLConnection` 与词库那边（`DictRemote`）同一套手法，不引 OkHttp。
 * - **只收文本**：题包是纯文本，2 MB 上限足够放几千道题，也能挡住"把地址贴成了安装包"。
 * - **重定向自己跟**：`HttpURLConnection` 只自动跟随同协议跳转，Gitee/GitHub 的 raw 会
 *   在 http→https、加域名前缀这类地方跳一次，交给它跟就会把跳转当成内容读进解析器。
 * - **网页要认出来**：代理页、登录页、404 页返回的都是 HTML，解析器只会报"没有可解析的题"，
 *   用户看不出是地址错了。这里先看开头几个字符，明确告诉他是"打开了网页"。
 */
object QuestionPackRemote {

    /** 题包是纯文本：六个包合起来约 40 KB。给 2 MB，够放几千道题 */
    const val MAX_BYTES: Int = 2 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 4
    private const val USER_AGENT = "StudyKit-QuestionPack"

    /**
     * 把用户粘进来的地址收拾成可请求的 URL：补 `https://`、去首尾空白、容忍 `//host/…`。
     *
     * 认不出来就返回 null（调用方据此提示"地址不完整"），不在这一层做网络。
     * 允许 `http://`：自建站点、校园网内的地址经常只有明文；是不是真有文件由请求结果说话。
     */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trim('"', '\'', ' ')
        if (trimmed.isEmpty()) return null
        // 先挡掉非 http(s) 的协议：`file:///sdcard/x.txt` 如果直接补 https:// 会变成
        // `https://file:///sdcard/x.txt`，URL 解析不报错（host=file），一路错到请求才炸。
        // 只列常见的坏协议而不是"任意 scheme:"：`example.com:8080/a.txt` 是主机带端口，不能误杀。
        if (NON_WEB_SCHEME.containsMatchIn(trimmed)) return null
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "https://$trimmed"
        }
        val parsed = runCatching { URL(withScheme) }.getOrNull() ?: return null
        if (parsed.host.isNullOrBlank()) return null
        // 只认 http(s)：`file://`、`content://` 这类地址不该从这里进来
        if (!parsed.protocol.equals("http", true) && !parsed.protocol.equals("https", true)) return null
        return withScheme
    }

    private val NON_WEB_SCHEME =
        Regex("^(file|javascript|data|content|ftp|about|chrome|intent|mailto):", RegexOption.IGNORE_CASE)

    /**
     * 下载并解码成文本。**必须在 IO 线程调用**。
     *
     * 失败一律抛 [QuestionPackRemoteException]，消息直接能显示给用户。
     */
    fun fetchText(url: String, maxBytes: Int = MAX_BYTES): String {
        val start = normalizeUrl(url)
            ?: throw QuestionPackRemoteException("地址不完整，试试以 https:// 开头")
        var current = start
        var redirects = 0
        while (true) {
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw QuestionPackRemoteException("下载地址跳转到了空位置（HTTP $code）")
                    if (redirects++ >= MAX_REDIRECTS) {
                        throw QuestionPackRemoteException("下载地址跳转次数过多，可能被拦截了")
                    }
                    current = resolveLocation(location, current)
                        ?: throw QuestionPackRemoteException("下载地址跳转到了无法识别的位置")
                    continue
                }
                if (code !in 200..299) {
                    throw QuestionPackRemoteException(
                        when (code) {
                            401, 403 -> "这个地址需要登录或没有公开访问权限（HTTP $code）"
                            404 -> "这个地址没有文件（HTTP 404），检查一下链接"
                            else -> "下载失败（HTTP $code）"
                        },
                    )
                }
                val text = readCapped(conn, maxBytes)
                if (looksLikeHtml(text)) {
                    throw QuestionPackRemoteException(
                        "这个地址返回的是网页，不是题包文本（可能需要点进「原始文件」或 raw 链接）",
                    )
                }
                if (text.isBlank()) throw QuestionPackRemoteException("下载到的内容是空的")
                return text
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 代理页/登录页/404 页都是 HTML；题包第一行必须是 `[阶段·学科·题型]` */
    internal fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(200).lowercase()
        if (head.isEmpty()) return false
        return head.startsWith("<!doctype") ||
            head.startsWith("<html") ||
            head.startsWith("<?xml") ||
            (head.startsWith("<") && head.contains("<body"))
    }

    private fun open(url: String): HttpURLConnection = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/plain,application/octet-stream,*/*")
        }
    } catch (e: Exception) {
        throw QuestionPackRemoteException("地址打不开：${e.javaClass.simpleName}", e)
    }

    private fun readCapped(conn: HttpURLConnection, maxBytes: Int): String {
        val buffer = ByteArrayOutputStream()
        try {
            conn.inputStream.use { input ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > maxBytes) {
                        throw QuestionPackRemoteException("文件超过 2 MB，不像是一份题包")
                    }
                }
            }
        } catch (e: QuestionPackRemoteException) {
            throw e
        } catch (e: UnknownHostException) {
            throw QuestionPackRemoteException("连不上这个域名（检查网络或地址拼写）", e)
        } catch (e: SocketTimeoutException) {
            throw QuestionPackRemoteException("下载超时，网络可能不通", e)
        } catch (e: Exception) {
            throw QuestionPackRemoteException("下载中断：${e.javaClass.simpleName}", e)
        }
        return decodeUtf8(buffer.toByteArray())
    }

    /** UTF-8 解码并去掉 BOM：题包在 Windows 上存过一版带 BOM 的，首行带 BOM 会让前缀匹配失败 */
    private fun decodeUtf8(bytes: ByteArray): String {
        val offset = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            3
        } else {
            0
        }
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }

    /** 相对 `Location` 要按当前地址拼；绝对地址直接用 */
    private fun resolveLocation(location: String, base: String): String? {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        return runCatching { URL(URL(base), trimmed).toString() }.getOrNull()
    }
}
