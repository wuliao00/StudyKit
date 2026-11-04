# StudyKit v3.14.0

> 版本号 3.14.0 / 36。Room 仍 v14（无 schema 变更）。
> 补齐 v3.13 节标题规则的英文与试卷卷头格式。

## 这一版做了什么

### 节标题扩展

- 英文：Section A / Part B（大小写不敏感）结束上一题并跳过；
- 试卷卷头：第Ⅰ卷 / 第 I 卷 / 第 1 卷、Ⅰ卷 / 卷Ⅱ、第一卷（选择题）等；
- 边界收紧：第X卷/卷X 后必须接空白、括号、冒号或行尾，避免误伤「第一卷之后的第一题」这类题干；
- 新增 2 条 QuestionLayoutParserTest。

## 机检

195 测试类 / 1716 用例 / 0 失败（1 条真模型评测在未传 -PocrEvalDir 时跳过）。
  v3.14 新增 2 条英文 Section / 第Ⅰ卷 / 卷Ⅱ 解析单测。

## 真机验证

## 真机验证（vivo V2156A / Android 11）

- **覆盖升级**：3.13.0/35 -> 3.14.0/36 直接覆盖安装成功，versionCode=36、versionName=3.14.0；
  Room 仍 v14，数据在。
- **OCR 抽查**：clearPageIsRecognizedWithReport 3.47s、perspectivePageIsCorrectedBeforeOcr 3.52s，
  2/2 通过；节标题规则本身由 2 条 JVM 单测覆盖。证据见 docs/evidence/v314-ocr-device.md。

## 产物

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| release/StudyKit-v3.14.0-debug.apk | 121388970 | 29dd98cdadd9b71f5bd1c38d06c7be3fc9c86a339a09fc846f6dd12d8244d279 |

## 已知限制

- 只覆盖 Section/Part + 字母数字；Reading Comprehension 等无前缀标题仍可能续进题干；
- 其它 OCR 限制同 v3.13。
