package com.studykit.util.importer

/**
 * `.doc/.wps` 扩展名并不可靠：金山 WPS 与不少导出工具会用同一后缀装 zip(docx)、
 * OLE2 旧 Word、RTF 甚至 HTML。先看文件头，能救回“其实是 docx 的 .doc”，
 * 也避免把 OLE2 硬塞给 ZipInputStream。
 *
 * 纯函数、无 Android 依赖，JVM 单测直接覆盖。
 */
internal enum class LegacyWordKind {
    /** `PK\x03\x04`：zip 容器，按 docx 的 `word/document.xml` 读 */
    ZIP_CONTAINER,

    /** `D0 CF 11 E0 A1 B1 1A E1`：真正的旧版 Word/WPS 二进制 */
    OLE2_BINARY,

    /** `{\rtf`：RTF 文本 */
    RTF,

    /** 认不出来：不猜，交给调用方给“另存为”提示 */
    UNKNOWN,
}

internal object LegacyWordSniffer {

    fun sniff(header: ByteArray): LegacyWordKind = when {
        header.startsWith(0x50, 0x4B, 0x03, 0x04) ||
            header.startsWith(0x50, 0x4B, 0x05, 0x06) ||
            header.startsWith(0x50, 0x4B, 0x07, 0x08) -> LegacyWordKind.ZIP_CONTAINER

        header.startsWith(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) ->
            LegacyWordKind.OLE2_BINARY

        header.startsWith(0x7B, 0x5C, 0x72, 0x74, 0x66) -> LegacyWordKind.RTF

        else -> LegacyWordKind.UNKNOWN
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean {
        if (size < expected.size) return false
        return expected.indices.all { this[it] == expected[it].toByte() }
    }
}
