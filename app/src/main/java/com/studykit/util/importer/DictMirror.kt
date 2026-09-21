package com.studykit.util.importer

/**
 * 在线词库下载地址的 HTTPS 镜像改写。纯字符串处理，零 Android 依赖。
 *
 * ## 为什么需要它
 *
 * 目录（kajweb/dict 的 `bookLists.txt`）里每本书的 `offlinedata` 给的是**明文**地址
 * `http://<bucket>.nos.netease.com/<object>`，而那个桶主机不提供 HTTPS（实测 TLS 握手直接失败）。
 * 2026-09-21 真机定性：手机的两条网络路径都不放行明文 HTTP ——
 *
 * - 借道电脑（gnirehtet 接管默认路由）时，80 端口 TCP 能连上但**回 0 字节**，
 *   `www.baidu.com:80` 与 `github.com:80` 同样如此（同机 443 一切正常）；
 * - 走 WiFi 直连时 80 端口活了，但该主机被校园网门户 302 到 `http://100.100.9.2/gportal/web/login?...`。
 *
 * 同一份对象存在 HTTPS 镜像：`https://nos.netease.com/<bucket>/<object>`（NetEase NOS 的
 * 「虚拟主机式」与「路径式」两种寻址互为等价）。6 本跨类别抽样（CET4luan / Level4luan / GRE /
 * PEPXiaoXue5 / PEPGaoZhong_11 / reciteWord_BeiShiGaoZhong_11）逐字验证均为 `206`。
 *
 * ## 为什么规则收窄成这一对主机
 *
 * 通用「把 http 升成 https」在这里是有害的：镜像不存在时会把一个**本来可能通**的明文地址
 * （用户在家用普通 WiFi 时明文是通的）改成一个必然失败的地址。所以只认
 * `<bucket>.nos.netease.com` 与 `nos.netease.com` 这一族，其余原样返回，
 * 由 [com.studykit.data.remote.DictRemote] 按「先镜像、后原址」两跳去试。
 */
object DictMirror {

    /** 镜像主机（路径式寻址），同时也是目录里封面图用的那台 */
    const val MIRROR_HOST = "nos.netease.com"

    private const val HTTP_PREFIX = "http://"

    /**
     * 明文 NOS 地址 → HTTPS 镜像地址；**拿不准的一律原样返回**。
     *
     * 不改动的情形：已是 https、非 NOS 主机、带显式端口（不确定对端是否监听 443）、
     * 桶名为空（`http://.nos.netease.com/…` 这类畸形值）、无协议前缀的垃圾串。
     */
    fun httpsUrlOf(raw: String): String {
        if (!raw.startsWith(HTTP_PREFIX)) return raw
        val afterScheme = raw.removePrefix(HTTP_PREFIX)
        if (afterScheme.isEmpty()) return raw
        val slash = afterScheme.indexOf('/')
        val host = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
        val pathAndQuery = if (slash < 0) "" else afterScheme.substring(slash)
        // 显式端口：镜像主机不一定在同一端口上服务，不猜
        if (host.contains(':')) return raw
        return when {
            host == MIRROR_HOST -> "https://$MIRROR_HOST$pathAndQuery"
            host.endsWith(".$MIRROR_HOST") -> {
                val bucket = host.removeSuffix(".$MIRROR_HOST")
                if (bucket.isEmpty()) raw else "https://$MIRROR_HOST/$bucket$pathAndQuery"
            }
            else -> raw
        }
    }
}
