# 题库文件导入：能力边界与取舍

> 适用版本：StudyKit 3.4。这里记录“为什么不做某些格式”，以及调研过的替代方案。
> 目标不是把所有 Office/PDF 都吃进来，而是让导入**可解释、可核对、不假装成功**。

## 当前能力

| 格式 | 读什么 | 明确不读 |
|---|---|---|
| `.txt/.md/.csv` | UTF-8 / UTF-16 BOM / GB18030；行内制表符保留 | — |
| `.docx/.docm` | `word/document.xml` 的正文、段落、换行、制表符；表格还原成 `单元格|单元格` | 图片、公式、批注、页眉页脚、修订删除文本 |
| `.pdf` 文字层 | 按坐标排序的文字 | 图片、公式、表格边框/合并单元格 |
| `.pdf` 扫描件 | `PdfRenderer` 渲染前 20 页 + PP-OCRv6 | 版式结构、表格线、手写 |
| `.doc/.wps` | 先看文件头：zip/docx 改后缀可直接读 | 真 OLE2 二进制、RTF（提示另存） |

单文件上限 40MB；DOCX 只解压 `word/document.xml`（8M 字符上限）；PDF OCR 最多 20 页。

## 为什么旧版 `.doc` 不直接引 Apache POI

- `org.apache.poi:poi-scratchpad:5.5.1`（Apache-2.0）是唯一活跃、纯 JVM 的 HWPF 解析器。
- 代价：debug 包约 +6MB（poi + scratchpad + math3/collections4/io/codec/log4j-api），
  且 POI 历史上让 R8 变慢/卡死（Google issue b/295349278）；还需排除 `java.awt` 警告。
- 结论：**不做全量支持**。先加零依赖的 magic sniff，能救“伪 `.doc`（其实是 docx/zip）”；
  真 OLE2 明确提示另存。若将来用户量证明 `.doc` 是主路径，再单独评估 POI，而不是现在塞进 120MB 的包。

## 为什么 PDF 表格不自动插竖线

- pdfbox-android 只有 `TextPosition`，没有表格模型；`PDFTextStripperByArea` 需要预先知道区域。
- Tabula-java（MIT）的几何类依赖 `java.awt.geom`，Android 没有，必须 fork；
  poppler 的 `pdftotext -layout` 是 GPL；Camelot 需要 Python；RapidTable/SLANet 要再带 6.8MB 模型 + OpenCV。
- 启发式（按 x 聚类列、列网格投票）可行，但两端对齐、双栏、单元格内换行都会误插 `|`；
  一旦把题干切断，用户看到的是“坏行”而不是“表格”。
- 结论：**当前不自动插**。PDF 里请保留 `|` 分列，或用 Word 表格另存 docx；
  DOCX 表格已经能稳定还原成 `|`。

## 为什么扫描版不做版面理解

- OCR 输出是“检测框 + 文本”；当前按 y 排序拼行，能读顺，但不知道哪一列是选项、哪一行是答案。
- 端侧表格结构模型（SLANet/RapidTable）需要额外模型与预处理，对“一行一题”的题库性价比低；
  公式 OCR（LaTeX-OCR 约 102MB、Nougat 权重约 1.4GB）更不适合 120MB 的安装包。
- 结论：扫描件只把文字送进编辑框，**竖线分列与公式改写由用户核对**；这不是自动判卷。

## 失败时用户看到什么

- 超限：`文件超过 40MB`。
- 加密 PDF：`这份 PDF 有密码`。
- 扫描件无文字：`OCR 也没认出内容，可以改用拍照录入`。
- 真 OLE2：`这是旧版 Word 二进制文件，请另存为 .docx 或 PDF`。
- 读不出来：页面内联错误条（不是一闪而过的 Toast），可继续编辑或重选。

## 调研来源

- [pdfbox-android](https://github.com/tomroush/pdfbox-android)（Apache-2.0）
- [Apache PDFTextStripperByArea](https://pdfbox.apache.org/docs/2.0.7/javadocs/org/apache/pdfbox/text/PDFTextStripperByArea.html)
- [tabula-java](https://github.com/tabulapdf/tabula-java)（MIT，依赖 java.awt）
- [RapidTable](https://github.com/RapidAI/RapidTable) / [RapidTable4j](https://github.com/hzkitty/RapidTable4j)
- [Apache POI](https://poi.apache.org/) / [poi-scratchpad 5.5.1](https://javadoc.io/doc/org.apache.poi/poi-scratchpad/latest/index.html)
- [R8 + POI issue b/295349278](https://issuetracker.google.com/issues/295349278)
- [LaTeX-OCR](https://github.com/lukas-blecher/LaTeX-OCR) / [Nougat](https://github.com/facebookresearch/nougat)
