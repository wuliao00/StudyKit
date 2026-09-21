package com.studykit.data.remote

import android.content.Context
import com.studykit.util.importer.DictBookInfo
import com.studykit.util.importer.DictBookParser
import com.studykit.util.importer.DictMirror
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.PortalDetect
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 词库拉取失败（网络、格式、zip 结构）统一成一种异常，界面只需展示可重试空态 */
class DictRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 目录来源必须说出来。回落快照与在线目录内容一模一样，界面若不区分，
 * 用户（和排查中的我）就会把"能列出 81 本"当成"联网成功"的证据。
 */
data class CatalogueResult(val books: List<DictBookInfo>, val fromNetwork: Boolean)

/**
 * 在线词库网络层。刻意零新依赖（spec §5.2）：`HttpURLConnection` + `ZipInputStream`。
 *
 * 四条实测事实决定了这段形状：
 * 1. 目录里 `offlinedata` 已经是 zip 的**绝对地址**，不需要自己拼 GitHub raw 路径；
 *    但它是 `http://` 且该主机不提供 HTTPS，而明文在现代网络路径上处处被卡（真机定性见 [DictMirror] 的 KDoc），
 *    所以下载按「先 HTTPS 镜像、后原址」两跳走（见 [downloadBook]），明文例外只服务回落那一跳；
 * 2. 词表 zip 里只有一个 `<id>.json`，内容是 NDJSON ⇒ 解压时取第一个 .json 条目即可；
 * 3. 目录 68KB、词表 zip 实测 40KB–800KB，手机网络可接受，但必须给进度回调，
 *    否则用户在最大那本上会以为点了没反应；
 * 4. 重定向必须由本类自己跟：`instanceFollowRedirects` 会把「未认证门户把明文请求 302 走」这件事
 *    伪装成 zip 结构错误（见 [PortalDetect]）。
 *
 * 目录失败时回落 `assets/dict/booklists.json` 快照：词库商店"至少能打开"比"永远转圈"重要。
 */
class DictRemote(private val context: Context) {

    suspend fun loadCatalogue(): CatalogueResult = withContext(Dispatchers.IO) {
        val online = runCatching { readText(CATALOGUE_URL, timeoutMillis = 15_000) }.getOrNull()
        if (online != null) return@withContext CatalogueResult(DictBookParser.parseCatalogue(online), true)
        val snapshot = runCatching {
            context.assets.open(CATALOGUE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: throw DictRemoteException("目录获取失败且无本地快照")
        CatalogueResult(DictBookParser.parseCatalogue(snapshot), fromNetwork = false)
    }

    /**
     * 下载整本词表并解析；[onProgress] 取值 0f..1f，服务端不给 Content-Length 时恒为 0f。
     *
     * **两跳顺序：先 HTTPS 镜像，再目录原址。** 镜像规则见 [DictMirror]，
     * 它存在的理由是 2026-09-21 那次真机定性 —— 目录给的是明文 `http://<bucket>.nos.netease.com/…`，
     * 而手机两条网络路径都不放行明文 HTTP（借道电脑时 80 端口回 0 字节；WiFi 直连时被校园网门户 302）。
     * 原址仍要留着：在普通网络下它是通的，而镜像只是"同一份对象的另一种寻址"，不是官方承诺的长期入口。
     *
     * 两跳的进度回调共用同一个 [onProgress]：第二跳从 0 重来，界面因此会看见进度退回起点 ——
     * 那是**对的**，它如实反映了"换了个地址重新下"。
     */
    suspend fun downloadBook(book: DictBookInfo, onProgress: (Float) -> Unit): ImportPlan =
        withContext(Dispatchers.IO) {
            val original = book.downloadUrl
            val mirror = DictMirror.httpsUrlOf(original)
            val attempts = listOf(mirror, original).distinct()
            var firstError: Throwable? = null
            for (url in attempts) {
                val outcome = runCatching { fetchAndParse(url, onProgress) }
                if (outcome.isSuccess) return@withContext outcome.getOrThrow()
                if (firstError == null) firstError = outcome.exceptionOrNull()
            }
            // 只报第一跳（镜像）的原因不够：真正决定"这台设备能不能下"的往往是原址那一跳。
            // 两跳各带一句，界面上的 rootMessage() 会取到最深那层。
            throw DictRemoteException(
                "词表下载失败：${book.title}（已试 ${attempts.joinToString(" → ") { it.hostPart() }}）",
                firstError,
            )
        }

    /** 单次尝试：打开连接 → 读全字节 → 取 zip 里那份 JSON → 解析 */
    private fun fetchAndParse(url: String, onProgress: (Float) -> Unit): ImportPlan {
        val connection = open(url, timeoutMillis = 20_000)
        try {
            val total = connection.contentLength.takeIf { it > 0 } ?: 0
            val json = connection.inputStream.buffered().use { stream ->
                val bytes = ByteArrayOutputStream().also { sink ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var done = 0L
                    while (stream.read(buffer).also { read = it } >= 0) {
                        sink.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }.toByteArray()
                extractFirstJson(bytes)
            }
            return DictBookParser.parseNdjson(json)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 取 zip 里第一个 .json 条目（实测每本词表恰好一个）。
     * `nextEntry` 只能推进一次，所以用 `generateSequence` 而不是 `while (zip.nextEntry != null)`。
     */
    private fun extractFirstJson(zipBytes: ByteArray): String {
        ByteArrayInputStream(zipBytes).use { bytes ->
            ZipInputStream(BufferedInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name.endsWith(".json", ignoreCase = true) }
                    ?.let { return zip.reader(Charsets.UTF_8).readText() }
            }
        }
        throw DictRemoteException("压缩包里没有找到词表 JSON")
    }

    private fun readText(url: String, timeoutMillis: Int): String =
        open(url, timeoutMillis).let { connection ->
            try {
                connection.inputStream.buffered().use { it.reader(Charsets.UTF_8).readText() }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * 打开连接并**自己跟重定向**（最多 [MAX_REDIRECTS] 跳）。
     *
     * 为什么不用 `instanceFollowRedirects = true`：那个开关会让 3xx 对上层彻底不可见，
     * 于是"未认证校园网把明文请求 302 到登录门户"这件事，最终表现成读到一页 HTML 去解 zip，
     * 报出来的错和真·坏包一模一样（见 [PortalDetect]）。逐跳自己看，才知道被谁接走了。
     */
    private fun open(url: String, timeoutMillis: Int): HttpURLConnection {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = connect(target, timeoutMillis)
            val code = connection.responseCode
            if (code in 200..299) return connection
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                PortalDetect.hint(location, expectedHost = target.hostPart())?.let { reason ->
                    throw DictRemoteException(reason)
                }
                val next = resolveLocation(location, base = target)
                    ?: throw DictRemoteException("HTTP $code，但服务端没给可跟随的重定向地址")
                target = next
                return@repeat
            }
            connection.disconnect()
            throw DictRemoteException("HTTP $code")
        }
        throw DictRemoteException("重定向超过 $MAX_REDIRECTS 次仍未拿到内容")
    }

    private fun connect(url: String, timeoutMillis: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            // 跳转由本类的 open() 逐跳处理，这里必须关掉，否则 302 在连接内部就被吃掉了
            instanceFollowRedirects = false
            // `requestProperties` 在 Kotlin 看来是 val（Java 侧只有 getter），要设头得走 setter 方法
            setRequestProperty("User-Agent", "StudyKit")
        }

    /**
     * 把 `Location` 头补成绝对地址。三种合法写法都要认（HTTP 规范允许相对）：
     * 绝对 URL、协议相对 `//host/path`、以及从 `/` 起或干脆是相对当前目录的路径。
     */
    private fun resolveLocation(location: String?, base: String): String? {
        val target = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val schemeEnd = target.indexOf("://")
        return when {
            schemeEnd >= 0 -> target
            target.startsWith("//") -> base.substringBefore("//", "") + target
            target.startsWith("/") -> base.substringBefore("://") + "://" + base.hostPart() + target
            else -> base.substringBeforeLast('/') + "/" + target
        }
    }

    /** 取 URL 的主机名（不含端口）；解析不出来时返回原串，只用于拼诊断文案 */
    private fun String.hostPart(): String {
        val schemeEnd = indexOf("://")
        if (schemeEnd < 0) return this
        val afterScheme = substring(schemeEnd + 3)
        val host = afterScheme.substringBefore('/').substringBefore(':')
        return host.ifEmpty { this }
    }

    private companion object {
        const val CATALOGUE_URL = "https://raw.githubusercontent.com/kajweb/dict/master/bookLists.txt"
        const val CATALOGUE_ASSET = "dict/booklists.json"

        /** 跟跳上限。门户类网关偶尔会连环跳，5 次还没落地就当它坏了 */
        const val MAX_REDIRECTS = 5
    }
}
