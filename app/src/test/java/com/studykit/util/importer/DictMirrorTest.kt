package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DictMirror] 的纯逻辑单测 —— 词表下载地址的 HTTPS 镜像改写。
 *
 * 钉住的是 2026-09-21 那次真机定性：词表 zip 目录里给的是**明文** `http://<bucket>.nos.netease.com/<object>`，
 * 而手机的两条网络路径都不放行明文 HTTP（借道电脑时 80 端口回 0 字节；走 WiFi 直连时被校园网门户 302 进登录页），
 * 同一份对象在 `https://nos.netease.com/<bucket>/<object>` 上存在（6 本跨类别抽样全 206）。
 *
 * 因此改写规则必须**保守**：只认这一对主机形态。凡是拿不准的一律原样返回，
 * 让上层按「先镜像后原址」的两跳顺序去试，而不是把地址改成一个不存在的对象。
 */
class DictMirrorTest {

    @Test
    fun `明文词表地址改写成镜像主机`() {
        assertEquals(
            "https://nos.netease.com/ydschool-online/1523620217431_CET4luan_1.zip",
            DictMirror.httpsUrlOf("http://ydschool-online.nos.netease.com/1523620217431_CET4luan_1.zip"),
        )
    }

    @Test
    fun `多级路径整段跟在桶名后面`() {
        assertEquals(
            "https://nos.netease.com/ydschool-online/a/b/GRE_2.zip",
            DictMirror.httpsUrlOf("http://ydschool-online.nos.netease.com/a/b/GRE_2.zip"),
        )
    }

    @Test
    fun `查询串原样保留`() {
        assertEquals(
            "https://nos.netease.com/ydschool-online/x.zip?e=1899999999&token=abc",
            DictMirror.httpsUrlOf("http://ydschool-online.nos.netease.com/x.zip?e=1899999999&token=abc"),
        )
    }

    @Test
    fun `已经是 https 的一分不动`() {
        val url = "https://nos.netease.com/ydschool-online/x.zip"
        assertEquals(url, DictMirror.httpsUrlOf(url))
    }

    @Test
    fun `镜像主机自己走 http 时只升协议不叠桶名`() {
        assertEquals(
            "https://nos.netease.com/ydschool-online/x.zip",
            DictMirror.httpsUrlOf("http://nos.netease.com/ydschool-online/x.zip"),
        )
    }

    @Test
    fun `非 nos 主机一律不碰`() {
        val url = "http://raw.githubusercontent.com/kajweb/dict/bookLists.txt"
        assertEquals(url, DictMirror.httpsUrlOf(url))
    }

    @Test
    fun `带显式端口的不猜`() {
        val url = "http://ydschool-online.nos.netease.com:8080/x.zip"
        assertEquals(url, DictMirror.httpsUrlOf(url))
    }

    @Test
    fun `没有路径的桶名主机也能改写`() {
        assertEquals(
            "https://nos.netease.com/ydschool-online",
            DictMirror.httpsUrlOf("http://ydschool-online.nos.netease.com"),
        )
    }

    @Test
    fun `空串与畸形输入不抛异常只原样返回`() {
        assertEquals("", DictMirror.httpsUrlOf(""))
        assertEquals("not a url", DictMirror.httpsUrlOf("not a url"))
        assertEquals("http://", DictMirror.httpsUrlOf("http://"))
        // 桶名为空（host 恰为 .nos.netease.com）属于畸形，不改写
        assertEquals("http://.nos.netease.com/x.zip", DictMirror.httpsUrlOf("http://.nos.netease.com/x.zip"))
    }
}
