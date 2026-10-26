package com.studykit.util.importer

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 题库商店的本地仓库：目录与题包目前都随 APK 打包（assets），点“下载”只是按包解析，
 * 不联网、不申请存储权限。
 *
 * 为什么先做离线：真·高考/考研/考公真题与教材题的再分发权不在应用手里；
 * 当前六个包是原创示例，许可证逐包写在 manifest 里。以后换成远程源时，
 * 只需把 [QuestionPackInfo.path] 拼到远端 base，不用改目录/预览/入库任何一层。
 *
 * `sha256` 是防“包被替换/改坏”的完整性校验；manifest 里没写就跳过校验。
 */
class QuestionPackStore(private val context: Context) {

    private companion object {
        const val MANIFEST_ASSET = "question_banks/manifest.json"
    }

    fun loadCatalog(): QuestionPackCatalog {
        val raw = context.assets.open(MANIFEST_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return QuestionPackCatalogParser.parse(raw)
    }

    suspend fun loadPack(pack: QuestionPackInfo): ImportPlan = withContext(Dispatchers.IO) {
        val text = context.assets.open(pack.assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
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
        plan
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

class QuestionPackException(message: String) : Exception(message)
