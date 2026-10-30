package com.studykit.util.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextExtractorTest {

    @Test
    fun decodePlainText_utf8Bom_keepsContentAndDropsBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "[初中·数学·单选] 题干|A|B|A".toByteArray(Charsets.UTF_8)

        val text = DocumentTextExtractor.decodePlainText(bytes)

        assertEquals("[初中·数学·单选] 题干|A|B|A", text)
    }

    @Test
    fun decodePlainText_gb18030_isDecodedInsteadOfReplacementChars() {
        val source = "第一行题目\n第二行题目"
        val bytes = source.toByteArray(Charset.forName("GB18030"))

        val text = DocumentTextExtractor.decodePlainText(bytes)

        assertEquals(source, text)
        assertTrue("不能留下替换符", '\uFFFD' !in text)
    }

    @Test
    fun decodePlainText_utf16LeBom_isDecoded() {
        val source = "[高中·物理·判断] 题干|正确"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            source.toByteArray(Charsets.UTF_16LE)

        assertEquals(source, DocumentTextExtractor.decodePlainText(bytes))
    }

    @Test
    fun extractDocx_restoresParagraphsRunsTabsAndEntities() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>[初中·数学·单选] 1+1=?</w:t></w:r></w:p>
                <w:p>
                  <w:r><w:t xml:space="preserve">|A. 1</w:t></w:r>
                  <w:r><w:tab/></w:r>
                  <w:r><w:t>|B. 2</w:t></w:r>
                  <w:br/>
                  <w:r><w:t>|B</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>&lt;解析&gt; &amp; 结论</w:t></w:r></w:p>
                <w:p/>
              </w:body>
            </w:document>
        """.trimIndent()

        val text = DocumentTextExtractor.docxXmlToText(xml)

        assertEquals(
            "[初中·数学·单选] 1+1=?\n|A. 1\t|B. 2\n|B\n<解析> & 结论",
            text,
        )
    }

    @Test
    fun docxXml_paragraphPropertiesDoNotAddBlankLines() {
        val xml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一行</w:t></w:r></w:p>
                <w:p><w:pPr><w:spacing w:after="120"/></w:pPr><w:r><w:t>第二行</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        assertEquals("第一行\n第二行", DocumentTextExtractor.docxXmlToText(xml))
    }

    @Test
    fun docxXml_tableRowsBecomePipeSeparatedLines() {
        // 用户在 Word/Excel 里把题目排成表格，是最常见的题库来源之一。
        // 一行表格必须还原成 `题干|选项|选项`，而不是每个单元格一行。
        val xml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:tbl>
                  <w:tblPr/>
                  <w:tr>
                    <w:tc><w:tcPr/><w:p><w:r><w:t>题干一</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>题干二</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>D</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                <w:p><w:r><w:t>表后正文</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val text = DocumentTextExtractor.docxXmlToText(xml)

        assertTrue(text, text.contains("题干一|A|B"))
        assertTrue(text, text.contains("题干二|C|D"))
        assertTrue("表格结束后正文要另起一行", text.contains("\n表后正文"))
        assertFalse("行与行之间不要再留空行", text.contains("\n\n"))
    }

    @Test
    fun extractDocx_readsWordDocumentZipEntryOnly() {
        val documentXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>[大学·概率论·填空] 题干|1/2</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent()
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/styles.xml"))
                zip.write("<styles/>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()

        val text = DocumentTextExtractor.extractDocx(ByteArrayInputStream(zipBytes))

        assertEquals("[大学·概率论·填空] 题干|1/2", text)
    }

    @Test
    fun extractDocx_missingWordDocument_returnsEmpty() {
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("docProps/core.xml"))
                zip.write("<core/>".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        assertEquals("", DocumentTextExtractor.extractDocx(ByteArrayInputStream(zipBytes)))
    }

    // ── v3.5.1：公式与图片取不出来时，要留记号并计数，不能静默丢 ──────────────────

    @Test
    fun docx_嵌入公式对象_留占位并计数() {
        val xml = """
            <w:document xmlns:w="w">
              <w:body><w:p>
                <w:r><w:t>已知函数</w:t></w:r>
                <w:r><w:object><v:shape><v:imagedata r:id="rId9"/></v:shape><o:OLEObject ProgID="Equation.3"/></w:object></w:r>
                <w:r><w:t>的最小值为</w:t></w:r>
              </w:p></w:body>
            </w:document>
        """.trimIndent()

        val doc = DocumentTextExtractor.docxXmlToDocxText(xml)

        assertEquals(1, doc.equations)
        assertEquals("已知函数⟦公式⟧的最小值为", doc.text)
    }

    @Test
    fun docx_原生公式OMML_文字能取到() {
        val xml = """
            <w:document xmlns:w="w" xmlns:m="m">
              <w:body><w:p>
                <w:r><w:t>当</w:t></w:r>
                <m:oMath><m:r><m:t>x</m:t></m:r><m:r><m:t>+1</m:t></m:r></m:oMath>
                <w:r><w:t>大于零时成立</w:t></w:r>
              </w:p></w:body>
            </w:document>
        """.trimIndent()

        val doc = DocumentTextExtractor.docxXmlToDocxText(xml)

        assertEquals(1, doc.nativeFormulas)
        assertEquals("当\$x+1\$大于零时成立", doc.text)
    }

    @Test
    fun docx_行内图片留占位_水印与文本框跳过() {
        val xml = """
            <w:document xmlns:w="w">
              <w:body>
                <w:p><w:r><w:t>如图</w:t></w:r><w:r><w:drawing><wp:inline><a:graphic/></wp:inline></w:drawing></w:r><w:r><w:t>所示</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><wp:anchor><a:graphic/></wp:anchor></w:drawing></w:r><w:r><w:t>正文</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><wp:inline><wps:txbx><w:txbxContent><w:p><w:r><w:t>文本框里的题干</w:t></w:r></w:p></w:txbxContent></wps:txbx></wp:inline></w:drawing></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val doc = DocumentTextExtractor.docxXmlToDocxText(xml)

        assertEquals("行内图只数一次，锚定水印不算", 1, doc.inlineImages)
        val lines = doc.text.split('\n')
        assertEquals("如图⟦图⟧所示", lines[0])
        assertEquals("锚定水印不能留占位", "正文", lines[1])
        assertEquals("文本框里的文字要读出来，且不加占位", "文本框里的题干", lines[2])
    }

    @Test
    fun docx_连续两个公式占位合并成一个() {
        val xml = """
            <w:document xmlns:w="w"><w:body><w:p>
              <w:r><w:t>求</w:t></w:r>
              <w:object><v:shape/></w:object><w:object><v:shape/></w:object>
              <w:r><w:t>的值</w:t></w:r>
            </w:p></w:body></w:document>
        """.trimIndent()

        val doc = DocumentTextExtractor.docxXmlToDocxText(xml)

        assertEquals("计数按实际处数算", 2, doc.equations)
        assertEquals("求⟦公式⟧的值", doc.text)
    }
}
