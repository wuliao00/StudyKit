package com.studykit.data

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * 版本检测：读 **Gitee** 的 tag 列表，与本机 `versionName` 比较。
 *
 * ## 为什么从 GitHub 换成 Gitee（2026-09-26 实测）
 *
 * 用户的手机上 `api.github.com` **根本解析不了** —— 设置页「检查更新」原样报回
 * `UnknownHostException: Unable to resolve host "api.github.com"`，
 * 同一个网络里 `github.com` 在浏览器里也只是白屏。
 * 后果是原来的 GitHub 实现会永远走"取不到 ⇒ 放行"，**连"有新版本"的提醒都不会有**，
 * 这个功能等于不存在。Gitee 在这台设备上可达，且仓库本来就有 Gitee 镜像。
 *
 * ## 为什么读 tags 而不是 releases（也是实测出来的）
 *
 * Gitee 的 `/releases/latest` 对本站返回 **404**、`/releases` 返回**空数组** ——
 * 这个镜像只有 tag，没有建过 Release。照搬 GitHub 那套 `/releases/latest` 会永远取不到，
 * 换了源等于没换。tags 接口实测可用（`[{"name":"v2.4.1",...}, …]`）。
 *
 * ## 护栏（"有新版本就拦"这个档位下必须有）
 *
 * 1. **取不到就放行**：没网、被限流、返回解析不了、超时 —— 一律 [UpdateCheck.Unknown]，
 *    界面当作"无需升级"。让一次网络抖动具备"把用户锁在门外"的能力是不可接受的。
 * 2. **只认形如版本号的 tag**（`v2.4.5` / `2.4.5`）：tags 里可能混进 `test-xxx` 这类标记，
 *    而它们没法参与版本比较。这条替代了原先"必须带 APK 附件"那道护栏 ——
 *    Gitee 侧没有 Release 也就没有附件可查，判据落到 tag 名字本身。
 *
 * ## 下载去向
 *
 * 判定有新版时给的是 **Gitee 的 releases 页**。注意这一页现在还是空的
 * （本站只推了 tag），所以「去下载」要真的能下到东西，需要在 Gitee 上建一次 Release
 * 并挂上 APK；在那之前它只会打开一个空页面。这一条写在下面 [DOWNLOAD_PAGE] 的注释里，
 * 免得后来人以为"能跳转"就等于"能下载"。
 */
object UpdateChecker {

    private const val GITEE_TAGS_API =
        "https://gitee.com/api/v5/repos/wuliao11541/studykit/tags?per_page=100"

    /**
     * 「去下载」打开的地方。
     * **它是空的，直到 Gitee 侧建过 Release 并上传 APK** —— 跳转成功不等于有东西可下。
     */
    private const val DOWNLOAD_PAGE = "https://gitee.com/wuliao11541/studykit/releases"

    /** 只认这两种形状：`v1.2.3` 与 `1.2.3`。其余 tag（`test-…`）不参与版本比较 */
    private val VERSION_TAG = Regex("^v?\\d+(\\.\\d+)*$")

    /** 检测结果。三种状态都必须能被调用方区分，不能只给"有没有新版" */
    sealed interface UpdateCheck {
        /** 本机已是最新（或远端不比自己新） */
        data object UpToDate : UpdateCheck

        /** 有更新的版本 */
        data class Newer(val version: String, val downloadUrl: String) : UpdateCheck

        /** 没查成：网络/解析失败、或远端没有任何版本号形状的 tag。**一律放行** */
        data class Unknown(val reason: String) : UpdateCheck
    }

    /**
     * 同步执行，**必须在 IO 线程调用**（`viewModelScope` + `Dispatchers.IO`）。
     * 超时收得比较紧：这是启动路径上的检查，不该让人等它。
     */
    fun check(currentVersion: String): UpdateCheck {
        val body = try {
            httpGet(GITEE_TAGS_API)
        } catch (t: Throwable) {
            return UpdateCheck.Unknown("请求失败：${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
        return try {
            val tags = JSONArray(body)
            val tagNames = (0 until tags.length())
                .mapNotNull { tags.optJSONObject(it)?.optString("name") }
            val newest = newestVersion(tagNames)
            if (newest == null) {
                // 关键护栏的另一面：远端一条版本号形状的 tag 都没有 ⇒ 不拦
                return UpdateCheck.Unknown("远端没有形如版本号的 tag（拿到 ${tagNames.size} 个）")
            }
            if (compareVersions(newest, currentVersion) > 0) {
                UpdateCheck.Newer(
                    version = newest.removePrefix("v"),
                    downloadUrl = DOWNLOAD_PAGE,
                )
            } else {
                UpdateCheck.UpToDate
            }
        } catch (t: Throwable) {
            UpdateCheck.Unknown("解析失败：${t.javaClass.simpleName}")
        }
    }

    /**
     * 从一串 tag 名里挑出**最高版本**；没有形如版本号的 tag 时返回 null。
     *
     * 抽成纯函数是为了能测：它是"有新版就拦"这条链上唯一的**判别**步骤
     * （网络那一步没法在单测里跑），而它判错的代价是"把用户锁在门外"或"新版本没人升"。
     * 两条性质各有用例：非版本 tag（`test-…`）被忽略、`2.4.10` 要大于 `2.4.9`。
     */
    internal fun newestVersion(tagNames: List<String>): String? =
        tagNames.filter { VERSION_TAG.matches(it) }
            .maxWithOrNull { a, b -> compareVersions(a, b) }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            // Gitee 的公开接口不强制 token，但给了 UA 更稳
            setRequestProperty("User-Agent", "StudyKit-UpdateCheck")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 版本号比较，`v` 前缀与不足的位数都容忍：`"v2.4.10"` > `"2.4.9"` > `"2.4"`。
     * 用数字段逐段比，**不能**拿字符串比 —— 那会让 2.4.10 排在 2.4.9 前面。
     */
    internal fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }
}
