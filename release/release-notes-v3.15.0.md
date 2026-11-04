# StudyKit v3.15.0

> 版本号 3.15.0 / 37。Room 仍 v14（无 schema 变更）。
> 本版继续修规则切题脏数据：独立英文板块标题与大题说明行不再续进题干。

## 这一版做了什么

### 独立英文板块标题
Reading Comprehension / Vocabulary (Review) / Grammar / Writing / Listening / Translation /
Cloze (Test) / Unit N (Review) 整行出现时结束上一题并跳过；题干里出现 Writing 等单词不误伤。

### 大题说明行
本大题/本部分/本卷/本组 共 X 小题/题、共 X 小题，… 这类说明整行跳过，不再进题干/选项。

### 单测
新增 2 条 QuestionLayoutParserTest：英文标题+大题说明联动、题干含标题单词不误伤。

## 机检

195 测试类 / 1718 用例 / 0 失败（1 条真模型评测在未传 -PocrEvalDir 时跳过）。
  v3.15 新增 2 条独立英文标题 / 大题说明解析单测。

## 真机验证

## 真机验证（vivo V2156A / Android 11）

- **覆盖升级**：3.14.0/36 -> 3.15.0/37 直接覆盖安装成功，versionCode=37、versionName=3.15.0；
  Room 仍 v14，数据在。
- **OCR 抽查**：clearPageIsRecognizedWithReport OK 3.55s；英文标题/大题说明规则由 2 条 JVM 单测覆盖。
  证据见 docs/evidence/v315-ocr-device.md。

## 产物

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| release/StudyKit-v3.15.0-debug.apk | 121388970 | 25c4edc3422165504a7c107f08096851d1684a19fbcaf779a552de35eff55266 |

## 已知限制

- 英文标题是白名单，未列出的标题（Reading Passage 等）仍可能续进题干；
- 大题说明只覆盖「本大题/本部分/本卷/本组 共…」格式；
- 其它 OCR 限制同 v3.14。
