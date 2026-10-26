package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyWordSnifferTest {

    @Test
    fun sniff_recognizesZipDocxOle2AndRtf() {
        assertEquals(
            LegacyWordKind.ZIP_CONTAINER,
            LegacyWordSniffer.sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)),
        )
        assertEquals(
            LegacyWordKind.OLE2_BINARY,
            LegacyWordSniffer.sniff(
                byteArrayOf(
                    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
                    0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
                ),
            ),
        )
        assertEquals(
            LegacyWordKind.RTF,
            LegacyWordSniffer.sniff("{\\rtf1\\ansi".toByteArray()),
        )
    }

    @Test
    fun sniff_shortOrUnknown_isUnknown() {
        assertEquals(LegacyWordKind.UNKNOWN, LegacyWordSniffer.sniff(ByteArray(0)))
        assertEquals(LegacyWordKind.UNKNOWN, LegacyWordSniffer.sniff("hello".toByteArray()))
        assertEquals(
            LegacyWordKind.UNKNOWN,
            LegacyWordSniffer.sniff(byteArrayOf(0x50, 0x4B)),
        )
    }
}
