# StudyKit v3.16.0

> 版本号 3.16.0 / 38。Room 仍 v14（无 schema 变更）。
> 本版继续修规则切题的边界：页中答案区、英文 Passage 标题与题组说明。

## 这一版做了什么

### 页中答案区
答案区识别后不再默认吞到页尾；如果后面重新出现「6. 完整题干」或新节标题，答案区结束，
后面的题继续切；答案项「1.A」不会被误判成新题（要求题号后剩余内容 >=6 字且答案对 <=1）。

### 英文标题与题组说明
- Reading Passage N / Reading Text / Passage N / Text N 独立标题跳过；
- Questions 1-5 are based on... 这类题组说明跳过。

### 单测
新增 2 条：页中答案区不吞后续题、英文 Passage + 题组说明跳过。

## 机检

195 测试类 / 1720 用例 / 0 失败（1 条真模型评测在未传 -PocrEvalDir 时跳过）。
  v3.16 新增 2 条页中答案区 / 英文 Passage / 题组说明解析单测。

## 真机验证

## 真机验证（vivo V2156A / Android 11）

- **覆盖升级**：3.15.0/37 -> 3.16.0/38 直接覆盖安装成功，versionCode=38、versionName=3.16.0；
  Room 仍 v14，数据在。
- **OCR 抽查**：clearPageIsRecognizedWithReport OK 3.62s；本版只改切题解析，规则由 2 条 JVM 单测覆盖。
  证据见 docs/evidence/v316-ocr-device.md。

## 产物

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| release/StudyKit-v3.16.0-debug.apk | 121388970 | 6d6a85c1906b0e4d65a6b4f39321b4f5c426afb768463ec256443cde6268d5f5 |

## 已知限制

- 页中答案区结束条件依赖「题号后 >=6 字」启发式；极短题干可能仍被当答案项；
- 其它 OCR 限制同 v3.15。
