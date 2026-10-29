package com.studykit.util.importer

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 这一份题包是从哪儿来的。界面要把它说出来 —— 「能列出 60 题」不能当成「联网成功」的证据 */
enum class PackOrigin(val label: String) {
    /** 刚从 manifest 里的 `url` 下载的 */
    REMOTE("在线"),
    /** 上次下载留在本机的缓存 */
    CACHE("本机缓存"),
    /** APK 里自带的那份 */
    BUNDLED("内置副本"),
    /** 用户自己贴链接导入的，不来自目录 */
    LINK("链接"),
}

/**
 * 一次题包读取的结果。[warning] 只在线上下载失败、改用缓存/内置兜住时出现，
 * 界面拿它显示一行「已改用本机缓存：…」，而不是假装一切都好。
 */
data class PackLoad(
    val plan: ImportPlan,
    val origin: PackOrigin,
    val warning: String? = null,
)

/**
 * 题库商店的本地仓库：目录随 APK 打包（assets），题包可以来自三处，优先级固定为
 * **在线地址 → 本机缓存 → 内置副本**。
 *
 * 为什么这么排：
 * - 在线优先，题包才能不跟版本走 —— 改一道题不用发 APK；
 * - 缓存第二，同一个包下过一次，断网还能再用，且不用重新解析网络失败；
 * - 内置最后，它是"至少能打开"的兜底，不是主路径。
 *
 * 三处都过同一道闸：[QuestionLineParser] 解析 + manifest 里的 `sha256` 校验。
 * 缓存与在线内容都要对得上 manifest 的摘要，所以题包改了、目录没跟上时，旧缓存会被判无效、
 * 自动落回内置副本，而不是把过期内容悄悄塞进题库。
 */
class QuestionPackStore(private val context: Context) {

    private companion object {
        const val MANIFEST_ASSET = "question_banks/manifest.json"
        const val CACHE_DIR = "question_packs_cache"
    }

    fun loadCatalog(): QuestionPackCatalog {
        val raw = context.assets.open(MANIFEST_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return QuestionPackCatalogParser.parse(raw)
    }

    /**
     * 读一个目录里的题包。**必须在 IO 线程调用**（内部自己 `withContext(IO)`，重复切也没关系）。
     *
     * 任何一处失败都不会直接抛到界面：先记下原因，再依次退到缓存与内置副本；
     * 三处都不行才抛 [QuestionPackException]，消息里带上最后一次在线失败的原因。
     */
    suspend fun loadPack(pack: QuestionPackInfo): PackLoad = withContext(Dispatchers.IO) {
        var failure: String? = null

        val remote = pack.remoteUrl
        if (!remote.isNullOrBlank()) {
            // 只取一次：解析与写缓存用同一份文本，别让"下载成功、缓存又失败"变成两次请求
            runCatching { QuestionPackRemote.fetchText(remote) }
                .onSuccess { text ->
                    runCatching { parseAndCheck(pack, text) }
                        .onSuccess { plan ->
                            writeCache(pack.id, text)
                            return@withContext PackLoad(plan = plan, origin = PackOrigin.REMOTE)
                        }
                        .onFailure { failure = "在线题包不可用：${it.userMessage()}" }
                }
                .onFailure { failure = "在线下载失败：${it.userMessage()}" }
        }

        readCache(pack.id)?.let { cached ->
            runCatching { parseAndCheck(pack, cached) }
                .onSuccess { return@withContext PackLoad(it, PackOrigin.CACHE, failure) }
                .onFailure { failure = (failure ?: "") + "；缓存也不可用：${it.userMessage()}" }
        }

        readAsset(pack)?.let { bundled ->
            runCatching { parseAndCheck(pack, bundled) }
                .onSuccess { return@withContext PackLoad(it, PackOrigin.BUNDLED, failure) }
                .onFailure { failure = (failure ?: "") + "；内置副本也不可用：${it.userMessage()}" }
        }

        throw QuestionPackException(failure?.trimStart('；') ?: "这个题包既没有在线地址，也没有本机副本")
    }

    /**
     * 用户直接粘一个题包链接（v3.5+）。不查 manifest、不校验 sha256 —— 没有目录就无从校验；
     * 仍然只收纯文本、仍然先预览再入库，格式不对由解析器报出来。
     */
    suspend fun loadPackFromUrl(rawUrl: String): PackLoad = withContext(Dispatchers.IO) {
        val text = runCatching { QuestionPackRemote.fetchText(rawUrl) }
            .getOrElse { throw QuestionPackException(it.userMessage()) }
        val plan = QuestionLineParser.parseAll(text)
        if (plan.items.none { it is ImportItem.Question }) {
            throw QuestionPackException(
                "这个链接里没有识别到题目。题包一行一题，以 [阶段·学科·题型] 开头，例如 " +
                    "[初中·数学·单选] 题干|A|B|C|D|B|解析",
            )
        }
        PackLoad(plan = plan, origin = PackOrigin.LINK)
    }

    /** 解析 + 摘要校验。缓存、在线、内置三条路共用，规则只写这一处 */
    private fun parseAndCheck(pack: QuestionPackInfo, text: String): ImportPlan {
        if (pack.sha256.isNotEmpty()) {
            val actual = sha256(text.toByteArray(Charsets.UTF_8))
            if (!actual.equals(pack.sha256, ignoreCase = true)) {
                throw QuestionPackException("题包校验失败：${pack.title}（文件可能已损坏）")
            }
        }
        val plan = QuestionLineParser.parseAll(text)
        if (plan.items.isEmpty()) {
            throw QuestionPackException("题包里没有可解析的题目：${pack.title}")
        }
        return plan
    }

    private fun readAsset(pack: QuestionPackInfo): String? = runCatching {
        context.assets.open(pack.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    private fun cacheFile(id: String): File {
        // id 来自 manifest，这里再兜一层：只留字母数字与下划线，别让目录里的字符串变成路径
        val safe = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "pack" }
        return File(File(context.filesDir, CACHE_DIR).apply { mkdirs() }, "$safe.txt")
    }

    private fun readCache(id: String): String? = runCatching {
        cacheFile(id).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun writeCache(id: String, text: String) {
        runCatching { cacheFile(id).writeText(text, Charsets.UTF_8) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 给界面看的一句话：网络异常类消息本身够具体，其余带上类名便于排查 */
    private fun Throwable.userMessage(): String =
        (message?.takeIf { it.isNotBlank() }) ?: javaClass.simpleName
}

class QuestionPackException(message: String) : Exception(message)
