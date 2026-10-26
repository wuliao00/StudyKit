package com.studykit.util.importer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件导入的真机（非 Robolectric）冒烟：只证明两件在 JVM 上测不到的事——
 * Android 侧 pdfbox 的 asset 初始化能跑通，以及 docx/pdf 的 SAF Uri 读取路径能出文本。
 *
 * 这不是格式细节测试（那些在 `test/` 的纯 JVM 用例里），而是“装到手机上还活着”的证据。
 */
class ImportDocumentReaderDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun docxTable_readsAsPipeSeparatedText() = runBlocking {
        val file = File(context.cacheDir, "device-fixture.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:tbl>
                    <w:tr>
                      <w:tc><w:p><w:r><w:t>[高中·数学·单选] 下列哪个是偶数？</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc>
                    </w:tr>
                  </w:tbl></w:body>
                </w:document>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }

        val result = ImportDocumentReader.read(context, Uri.fromFile(file))

        assertTrue("docx 应读成文本，实际=$result", result is DocumentReadResult.Text)
        assertTrue(
            "Word 表格必须还原成竖线分列",
            (result as DocumentReadResult.Text).value.contains("下列哪个是偶数？|1|2|B"),
        )
    }

    @Test
    fun pdfTextLayer_readsWithPdfBoxAssets() = runBlocking {
        val file = File(context.cacheDir, "device-fixture.pdf")
        // androidTest 源集的 assets 在测试 APK 里，不在被测应用的 target context 里。
        InstrumentationRegistry.getInstrumentation().context.assets.open("import-fixture.pdf").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val result = ImportDocumentReader.read(context, Uri.fromFile(file))

        assertTrue("pdf 应读成文本，实际=$result", result is DocumentReadResult.Text)
        val text = (result as DocumentReadResult.Text).value
        assertTrue("PDF 文字层应包含题干，实际=$text", text.contains("下列哪个是偶数"))
        assertTrue("PDF 文字层应包含判断答案，实际=$text", text.contains("力是物体间的相互作用"))
    }
}
