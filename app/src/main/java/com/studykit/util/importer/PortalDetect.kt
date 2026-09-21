package com.studykit.util.importer

/**
 * 「请求被网络中途接走」的判定。纯字符串处理，零 Android 依赖。
 *
 * ## 它要解决的问题
 *
 * `HttpURLConnection` 的 `instanceFollowRedirects` 一旦为真，3xx 就对上层不可见：
 * 未认证的校园网会把明文请求 302 到登录门户
 * （实测 `Location: http://100.100.9.2/gportal/web/login?wlanuserip=10.12.109.242&...`，
 * `Server: NetEngine Server 1.0`），App 于是读到一页 HTML 去喂 `ZipInputStream`，
 * 报出来的却是「压缩包里没有找到词表 JSON」—— 和真·坏包一模一样的症状。
 *
 * 所以网络层关掉自动跳转，逐跳自己跟；每跳先看 `Location` 落在谁那儿。
 *
 * ## 判定口径
 *
 * 只有「目标主机不是预期主机、也不是预期主机的子域」才算被接管。
 * 刻意**不**按私有网段列清单：门户常用的 `100.100.x.x` 并不在 RFC 1918 / CGNAT 段里
 * （`100.64.0.0/10` 才是），按网段判会漏掉真机实际撞到的那一例。
 */
object PortalDetect {

    /**
     * 是重定向且落到了不该去的主机 → 返回一句能直接给用户看的诊断；否则 null。
     *
     * [location] 为 null / 空白时返回 null：那只是「3xx 没给目标」，该由调用方按状态码报，
     * 不是网络接管，别把两种病压成一句。
     */
    fun hint(location: String?, expectedHost: String): String? {
        val trimmed = location?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // 相对 Location（`/gportal/...`）跟着当前主机走，不构成"换了一家"
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return null
        val host = hostOf(trimmed.substring(schemeEnd + 3)) ?: return null
        val expected = expectedHost.trim().lowercase()
        if (host == expected || host.endsWith(".$expected")) return null
        return "请求被网络重定向到了 $host（不是词库服务器），通常是所连网络要求先完成网页认证，或被代理接管的明文流量"
    }

    /** `host[:port][/path]` → 小写主机名；空主机返回 null */
    private fun hostOf(afterScheme: String): String? {
        val authority = afterScheme.substringBefore('/').trim()
        if (authority.isEmpty()) return null
        val host = authority.substringBefore(':').trim().lowercase()
        return host.takeIf { it.isNotEmpty() }
    }
}
