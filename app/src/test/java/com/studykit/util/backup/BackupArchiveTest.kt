package com.studykit.util.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupCodec] 与 [BackupArchive.readManifest] 的纯逻辑单测。
 *
 * ## 为什么只测这几件
 *
 * 导出/恢复的其余部分绑在 `Context` / `ContentResolver` 上，JVM 单测碰不到（本机也没有 Android SDK，
 * CI 是唯一执行机会）。偏偏做错代价最大的三件事是纯逻辑：
 * 放行一个 `../` 条目名就能把文件写到应用私有目录之外；缺字段的 manifest 若按默认值解释，
 * 等于拿一份没有校验和的库去覆盖真库；校验和算错则整套完整性检查退化成摆设。
 * 所以这三件事钉死在这里，剩下的 IO 壳子交给真机验证。
 */
class BackupArchiveTest {

    // region 条目名规范化

    @Test
    fun `Windows 反斜杠条目名被规范化成斜杠`() {
        // zip 规范要求 '/'，但 Windows 打包工具写 '\'：不规范化就落不到 mistake_images/thumb 下
        assertEquals(
            "mistake_images/thumb/a.jpg",
            BackupCodec.canonicalEntryName("mistake_images\\thumb\\a.jpg"),
        )
    }

    @Test
    fun `上级目录穿越一律拒绝`() {
        // 解包目标永远是应用私有目录，出现 .. 段就说明包被改过 —— 整包拒绝，不"帮你算对"
        assertNull(BackupCodec.canonicalEntryName("../evil.db"))
        assertNull(BackupCodec.canonicalEntryName("mistake_images/../../evil.db"))
        assertNull(BackupCodec.canonicalEntryName("mistake_images/a/../../b.jpg"))
        assertNull(BackupCodec.canonicalEntryName("..\\..\\Windows\\system32\\drivers\\etc\\hosts"))
    }

    @Test
    fun `绝对路径与带盘符的条目名被拒绝`() {
        assertNull(BackupCodec.canonicalEntryName("/etc/passwd"))
        assertNull(BackupCodec.canonicalEntryName("C:\\Users\\me\\evil.txt"))
    }

    @Test
    fun `点段与重复斜杠按噪声吃掉而不是报错`() {
        // 这些改变不了归属，判成攻击只会让正常包恢复不了
        assertEquals("mistake_images/a.jpg", BackupCodec.canonicalEntryName("./mistake_images//a.jpg"))
        assertEquals("mistake_images/a.jpg", BackupCodec.canonicalEntryName("mistake_images/./a.jpg"))
    }

    @Test
    fun `目录条目名不算攻击且空名字被拒`() {
        // 目录条目（mistake_images/）由调用方按 ZipEntry.isDirectory 跳过，这里只负责去掉尾斜杠
        assertEquals("mistake_images", BackupCodec.canonicalEntryName("mistake_images/"))
        assertNull(BackupCodec.canonicalEntryName(""))
        assertNull(BackupCodec.canonicalEntryName("/"))
        assertNull(BackupCodec.canonicalEntryName("./"))
    }

    @Test
    fun `条目归类认得自己认领的三样其余当陌生条目`() {
        assertEquals(ZipEntryKind.MANIFEST, BackupCodec.entryKindOf("manifest.json"))
        assertEquals(ZipEntryKind.DB, BackupCodec.entryKindOf("studykit.db"))
        assertEquals(ZipEntryKind.IMAGE, BackupCodec.entryKindOf("mistake_images/thumb/a.jpg"))
        // 陌生条目（新版本加的东西、别的打包器的杂项）恢复时跳过，不是报错
        assertEquals(ZipEntryKind.UNKNOWN, BackupCodec.entryKindOf("settings.json"))
        assertEquals(ZipEntryKind.UNKNOWN, BackupCodec.entryKindOf("mistake_images"))
    }

    @Test
    fun `相对条目名只认根目录之下的路径`() {
        val root = "/data/user/0/com.studykit/files"
        assertEquals("mistake_images/a.jpg", BackupCodec.relativeEntryPath(root, "$root/mistake_images/a.jpg"))
        assertEquals("mistake_images/a.jpg", BackupCodec.relativeEntryPath("$root/", "$root/mistake_images/a.jpg"))
        // 不在根下：宁可返回 null 让调用方跳过，也不能拼出一个往上跑的相对路径
        assertNull(BackupCodec.relativeEntryPath(root, "/data/user/0/com.other/files/x.jpg"))
        assertNull(BackupCodec.relativeEntryPath(root, "/data/user/0/com.studykit/backups/x.jpg"))
        assertNull(BackupCodec.relativeEntryPath(root, root)) // 根自身不算条目
    }

    // endregion

    // region manifest 编解码

    @Test
    fun `manifest 编解码往返一致且容忍多出来的字段`() {
        val original = BackupManifest(
            appVersion = "2.2.0",
            createdAt = 1_760_000_000_000L,
            dbBytes = 12_345_678L,
            dbChecksum = 3_421_780_262L,
            imageCount = 48,
        )
        assertEquals(original, BackupCodec.manifestFrom(BackupCodec.manifestJson(original)))
        // 未来的包多一个字段，旧版本照样得认（不认等于让用户丢掉唯一的退路）
        val withExtra = BackupCodec.manifestJson(original).replace("{\"", "{\"futureFlag\":true,\"")
        assertEquals(original, BackupCodec.manifestFrom(withExtra))
    }

    @Test
    fun `manifest 缺字段返回 null 而不是抛`() {
        // 缺 dbChecksum 还按 0 解释 = 宣布"这份没有校验和的库是好的"，比抛异常更糟。
        // 文本手写而不用 JSONObject 删字段：`remove` 在 Android 的 org.json 里是 API 30 才有的。
        val missingFields = mapOf(
            "appVersion" to """{"createdAt":1,"dbBytes":1024,"dbChecksum":123,"imageCount":2}""",
            "createdAt" to """{"appVersion":"2.2.0","dbBytes":1024,"dbChecksum":123,"imageCount":2}""",
            "dbBytes" to """{"appVersion":"2.2.0","createdAt":1,"dbChecksum":123,"imageCount":2}""",
            "dbChecksum" to """{"appVersion":"2.2.0","createdAt":1,"dbBytes":1024,"imageCount":2}""",
            "imageCount" to """{"appVersion":"2.2.0","createdAt":1,"dbBytes":1024,"dbChecksum":123}""",
        )
        for ((field, text) in missingFields) {
            assertNull("缺 $field 必须判为读不出", BackupCodec.manifestFrom(text))
        }
    }

    @Test
    fun `manifest 字段为空值或类型不对时也只返回 null`() {
        // 显式的 JSON null 不能被读成字符串 "null"，否则一份没有校验和的包会被当成合法包
        assertNull(BackupCodec.manifestFrom(manifestText("dbChecksum", "null")))
        // 类型不对时 org.json 抛异常，必须被吞成 null（这正是"包被人用文本编辑器改过"的形状）
        assertNull(BackupCodec.manifestFrom(manifestText("dbBytes", "\"很多\"")))
        assertNull(BackupCodec.manifestFrom(manifestText("imageCount", "true")))
        assertNull(BackupCodec.manifestFrom("not json at all"))
        assertNull(BackupCodec.manifestFrom(""))
        assertNull(BackupCodec.manifestFrom("{}"))
        // 注：数字大到 Long 之外时 org.json 走 Number.longValue() 静默截断，判不判得出来看运气，
        // 所以这里不测溢出这条不稳的路径。
    }

    @Test
    fun `负数、零与空版本号都被判为不合法`() {
        assertNull(BackupCodec.manifestFrom(manifestText("createdAt", "0")))
        assertNull(BackupCodec.manifestFrom(manifestText("dbBytes", "-1")))
        assertNull(BackupCodec.manifestFrom(manifestText("dbChecksum", "-1")))
        assertNull(BackupCodec.manifestFrom(manifestText("imageCount", "-5")))
        assertNull(BackupCodec.manifestFrom(manifestText("appVersion", "\"  \"")))
    }

    // endregion

    // region 校验和

    @Test
    fun `校验和就是标准 CRC-32`() {
        // 0xCBF43926 是 CRC-32(IEEE) 对 "123456789" 的公认校验值：钉住它，才说明我们没在用自己发明的算法
        assertEquals(0xCBF43926L, BackupCodec.checksum("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0L, BackupCodec.checksum(ByteArray(0)))
    }

    @Test
    fun `整数组与文件与分块三条路径算出同一个值`() {
        // 导出走整数组、恢复走文件流；两条路算出的值不同就等于恢复永远验不过
        val bytes = ByteArray(40 * 1024) { (it % 251).toByte() }
        val single = CRC32().apply { update(bytes) }.value
        assertEquals(single, BackupCodec.checksum(bytes))
        val temp = File.createTempFile("studykit-backup-", ".db")
        try {
            temp.outputStream().use { it.write(bytes) }
            assertEquals(single, BackupCodec.checksumOf(temp))
        } finally {
            assertTrue(temp.delete())
        }
    }

    @Test
    fun `改一个字节校验和就不同`() {
        val original = ByteArray(20 * 1024) { (it % 7).toByte() }
        val tampered = original.copyOf().also { it[12345] = (it[12345] + 1).toByte() }
        val expected = BackupCodec.checksum(original)
        assertNotEquals(expected, BackupCodec.checksum(tampered))
        // 少一截（包被截断）也判得出来
        assertNotEquals(expected, BackupCodec.checksum(original.copyOfRange(0, original.size - 1)))
    }

    // endregion

    // region 从真 zip 里读 manifest

    @Test
    fun `readManifest 从整包里读出备份自述`() {
        val manifest = BackupManifest("2.2.0", 1_760_000_000_000L, 4L, BackupCodec.checksum(byteArrayOf(1, 2, 3, 4)), 1)
        val archive = zipOf(
            BackupCodec.MANIFEST_ENTRY to BackupCodec.manifestJson(manifest).toByteArray(Charsets.UTF_8),
            BackupCodec.DB_ENTRY to byteArrayOf(1, 2, 3, 4),
            "mistake_images/a.jpg" to byteArrayOf(9),
        )
        assertEquals(manifest, BackupArchive.readManifest(archive))
    }

    @Test
    fun `manifest 条目写成 Windows 风格路径也还认得`() {
        val manifest = BackupManifest("2.2.0", 1L, 0L, 0L, 0)
        val archive = zipOf(
            ".\\${BackupCodec.MANIFEST_ENTRY}" to BackupCodec.manifestJson(manifest).toByteArray(Charsets.UTF_8),
        )
        assertEquals(manifest, BackupArchive.readManifest(archive))
    }

    @Test
    fun `坏包与恶意包都只得到 null 而不是异常`() {
        // 预览一张备份卡时抛异常会把设置页崩掉；这两种情况都该显示成"不可恢复"
        assertNull(BackupArchive.readManifest(zipOf(BackupCodec.DB_ENTRY to byteArrayOf(1, 2))))
        assertNull(BackupArchive.readManifest(ByteArray(0)))
        assertNull(BackupArchive.readManifest("这不是 zip".toByteArray(Charsets.UTF_8)))
        // 带 ../ 的 manifest 条目不被认领：规范化直接拒掉，读不到就等于不恢复
        val json = BackupCodec.manifestJson(BackupManifest("2.2.0", 1L, 0L, 0L, 0)).toByteArray(Charsets.UTF_8)
        assertNull(BackupArchive.readManifest(zipOf("../manifest.json" to json)))
    }

    @Test
    fun `字段不全的 manifest 在预览阶段就被判不可用`() {
        // 这条钉的是"readManifest 与 manifestFrom 是同一套判断"，否则预览说能恢复、点下去才失败
        val broken = """{"appVersion":"2.2.0","createdAt":1,"dbBytes":4}"""
        val archive = zipOf(BackupCodec.MANIFEST_ENTRY to broken.toByteArray(Charsets.UTF_8))
        assertNull(BackupArchive.readManifest(archive))
        val wholeText = BackupCodec.manifestJson(BackupManifest("2.2.0", 1L, 4L, 0L, 0))
        val whole = zipOf(BackupCodec.MANIFEST_ENTRY to wholeText.toByteArray(Charsets.UTF_8))
        assertNotNull(BackupArchive.readManifest(whole))
    }

    // endregion

    /** 字段齐全的基线 manifest 文本；改坏一处就能造出各种坏包 */
    private val baseManifest =
        """{"appVersion":"2.2.0","createdAt":1760000000000,"dbBytes":1024,"dbChecksum":123,"imageCount":2}"""

    /** 把基线里某个字段换成给定字面量 */
    private fun manifestText(name: String, literal: String): String =
        baseManifest.replace(Regex("\"$name\":\\s*[^,}]+"), "\"$name\":$literal")

    /** 内存里造 zip：条目名原样写入，用来验规范化与归类在真条目上成立 */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return sink.toByteArray()
    }
}
