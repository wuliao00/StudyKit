package com.studykit.data

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 版本检测：读 GitHub Releases 的 latest，与本机 `versionName` 比较。
 *
 * ## 两道护栏（这是"有新版本就拦"这个档位下必须有的）
 *
 * 1. **取不到就放行**：没网、被限流（GitHub 匿名 API 每小时 60 次）、返回解析不了、
 *    超时 —— 任何一种失败都返回 [UpdateCheck.Unknown]，界面当作"无需升级"。
 *    把用户锁在门外的最短路径，就是让一次网络抖动具备阻断能力。
 * 2. **只有带 APK 附件的 Release 才算数**：否则一条只写了说明、没传包的 release，
 *    或者发完又撤掉的 tag，会把所有人永久拦住。判据是 assets 里存在 `.apk`。
 *
 * ## 为什么不用仓库里那套 HTTPS 镜像改写
 *
 * 词库那条线的镜像改写是为**被墙的 CDN**准备的。GitHub API 走的是 api.github.com，
 * 用仓库自己的直连更直白；换域名的收益不明确，而一旦镜像挂了反而会把升级检查变成永久失败。
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/wuliao00/StudyKit/releases/latest"

    /** 检测结果。三种状态都必须能被调用方区分，不能只给"有没有新版" */
    sealed interface UpdateCheck {
        /** 本机已是最新（或远端不比自己新） */
        data object UpToDate : UpdateCheck

        /** 有更新的版本，且那个 Release 带着可下载的 APK */
        data class Newer(val version: String, val releaseUrl: String) : UpdateCheck

        /** 没查成：网络/解析失败、或那个 Release 没有 APK 附件。**一律放行** */
        data class Unknown(val reason: String) : UpdateCheck
    }

    /**
     * 同步执行，**必须在 IO 线程调用**（`viewModelScope` + `Dispatchers.IO`）。
     * 超时收得比较紧：这是启动路径上的检查，不该让人等它。
     */
    fun check(currentVersion: String): UpdateCheck {
        val body = try {
            httpGet(LATEST_RELEASE_API)
        } catch (t: Throwable) {
            return UpdateCheck.Unknown("请求失败：${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val htmlUrl = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                .orEmpty()

            if (tag.isBlank()) {
                return UpdateCheck.Unknown("响应里没有 tag_name")
            }
            if (apkUrl.isBlank()) {
                // 关键护栏：没有包就不拦。宁可让人用一个旧版，也不能把人锁死。
                return UpdateCheck.Unknown("Release $tag 没有 APK 附件，不拦")
            }
            if (compareVersions(tag, currentVersion) > 0) {
                UpdateCheck.Newer(version = tag.removePrefix("v"), releaseUrl = apkUrl)
            } else {
                UpdateCheck.UpToDate
            }
        } catch (t: Throwable) {
            UpdateCheck.Unknown("解析失败：${t.javaClass.simpleName}")
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/vnd.github+json")
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
