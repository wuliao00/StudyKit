package com.studykit.data.remote

import android.content.Context
import com.studykit.util.importer.DictBookInfo
import com.studykit.util.importer.DictBookParser
import com.studykit.util.importer.ImportPlan
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
 * 三条实测事实决定了这段形状：
 * 1. 目录里 `offlinedata` 已经是 zip 的**绝对地址**，不需要自己拼 GitHub raw 路径；
 *    但它是 `http://` 且该主机不提供 HTTPS，所以清单里给这一个域名开了明文
 *    （见 `res/xml/network_security_config.xml`）；
 * 2. 词表 zip 里只有一个 `<id>.json`，内容是 NDJSON ⇒ 解压时取第一个 .json 条目即可；
 * 3. 目录 68KB、词表 zip 实测 40KB–800KB，手机网络可接受，但必须给进度回调，
 *    否则用户在最大那本上会以为点了没反应。
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

    /** 下载整本词表并解析；[onProgress] 取值 0f..1f，服务端不给 Content-Length 时恒为 0f */
    suspend fun downloadBook(book: DictBookInfo, onProgress: (Float) -> Unit): ImportPlan =
        withContext(Dispatchers.IO) {
            val connection = open(book.downloadUrl, timeoutMillis = 20_000)
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
                DictBookParser.parseNdjson(json)
            } catch (error: Exception) {
                throw DictRemoteException("词表下载失败：${book.title}", error)
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

    private fun open(url: String, timeoutMillis: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = true
            // `requestProperties` 在 Kotlin 看来是 val（Java 侧只有 getter），要设头得走 setter 方法
            setRequestProperty("User-Agent", "StudyKit")
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                throw DictRemoteException("HTTP $code")
            }
        }

    private companion object {
        const val CATALOGUE_URL = "https://raw.githubusercontent.com/kajweb/dict/master/bookLists.txt"
        const val CATALOGUE_ASSET = "dict/booklists.json"
    }
}
