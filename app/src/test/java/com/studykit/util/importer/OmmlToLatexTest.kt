package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OMML → LaTeX 片段（v3.5.1）。
 *
 * 这一层的意义是**别把题算错**：只拼 `<m:t>` 字符的话，`1/2` 会变成 `12`、`x²` 会变成 `x2`，
 * 用户看到的是"能读但错"的题，比缺一段更糟。用例都按结构写死，改映射就会红。
 */
class OmmlToLatexTest {

    private fun math(inner: String) = OmmlToLatex.renderMath(inner)

    private fun run(text: String) =
        "<m:r><m:t>$text</m:t></m:r>"

    @Test
    fun 分数会被还原成frac() {
        val inner = "<m:f><m:num>${run("1")}</m:num><m:den>${run("2")}</m:den></m:f>"
        assertEquals("\$\\frac{1}{2}\$", math(inner))
    }

    @Test
    fun 上下标会被还原成花括号() {
        val sup = "<m:sSup><m:e>${run("x")}</m:e><m:sup>${run("2")}</m:sup></m:sSup>"
        assertEquals("\$x^{2}\$", math(sup))

        val sub = "<m:sSub><m:e>${run("a")}</m:e><m:sub>${run("1")}</m:sub></m:sSub>"
        assertEquals("\$a_{1}\$", math(sub))
    }

    @Test
    fun 底数是多字符时会包一层花括号() {
        val sup = "<m:sSup><m:e>${run("ab")}</m:e><m:sup>${run("2")}</m:sup></m:sSup>"
        assertEquals("\${ab}^{2}\$", math(sup))
    }

    @Test
    fun 根式与带次数的根式() {
        val plain = "<m:rad><m:radPr/><m:deg/><m:e>${run("3")}</m:e></m:rad>"
        assertEquals("\$\\sqrt{3}\$", math(plain))

        val cubed = "<m:rad><m:deg>${run("3")}</m:deg><m:e>${run("8")}</m:e></m:rad>"
        assertEquals("\$\\sqrt[3]{8}\$", math(cubed))
    }

    @Test
    fun 括号按OMML的begChr_endChr还原() {
        val d = "<m:d m:begChr=\"[\" m:endChr=\"]\"><m:e>${run("0,1")}</m:e></m:d>"
        assertEquals("\$[0,1]\$", math(d))
    }

    @Test
    fun 求和带上下限() {
        val nary = "<m:nary m:chr=\"∑\">" +
            "<m:sub>${run("i=1")}</m:sub><m:sup>${run("n")}</m:sup><m:e>${run("a")}</m:e></m:nary>"
        assertEquals("\$\\sum_{i=1}^{n}{a}\$", math(nary))
    }

    @Test
    fun 重音与上划线() {
        val hat = "<m:acc m:chr=\"ˆ\"><m:e>${run("x")}</m:e></m:acc>"
        assertEquals("\$\\hat{x}\$", math(hat))

        val bar = "<m:bar><m:e>${run("AB")}</m:e></m:bar>"
        assertEquals("\$\\overline{AB}\$", math(bar))
    }

    @Test
    fun 认不出的标签不丢字() {
        // m:box 之类不认识的结构：按子节点拼接，内容必须还在
        val unknown = "<m:box>${run("k")}${run("g")}</m:box>"
        assertEquals("\$kg\$", math(unknown))
    }

    @Test
    fun 空公式返回空串而不是两个美元符号() {
        assertEquals("", math("<m:radPr/>"))
    }

    @Test
    fun 段落里的公式按结构进正文并计数() {
        val xml = """
            <w:document xmlns:w="w" xmlns:m="m">
              <w:body><w:p>
                <w:r><w:t>中位数为</w:t></w:r>
                <m:oMath><m:f><m:num><m:r><m:t>6,8,4</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den></m:f></m:oMath>
                <w:r><w:t>，选 B</w:t></w:r>
              </w:p></w:body>
            </w:document>
        """.trimIndent()

        val doc = DocumentTextExtractor.docxXmlToDocxText(xml)

        assertEquals(1, doc.nativeFormulas)
        assertTrue("公式要按结构出现：${doc.text}", doc.text.contains("\$\\frac{6,8,4}{2}\$"))
        assertTrue("公式前后的中文不能丢：${doc.text}", doc.text.startsWith("中位数为"))
    }

    @Test
    fun 坏的OMML不抛异常且保留原文() {
        val broken = "<m:f><m:num>abc"
        val out = math(broken)
        assertTrue("解析失败时至少要把字带上：$out", out.contains("abc"))
    }
}
