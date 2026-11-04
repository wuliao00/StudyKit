# v3.16 页中答案区 / 英文 Passage 真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.15.0 一致。

## 1. 覆盖升级

- 设备原版本：3.15.0 / 37。
- 安装 3.16.0 / 38 后：versionCode=38、versionName=3.16.0。
- Room 仍 v14（本版无 schema 变更），数据存活：questions 71 条 / pending 0。

## 2. 验证

- 页中答案区、Reading Passage / Passage N、Questions 1-5 题组说明均为纯 JVM 解析规则：
  新增 2 条 QuestionLayoutParserTest，全量 195 类 / 1720 用例通过；
- 设备侧抽查 OCR 主链：clearPageIsRecognizedWithReport OK 3.62s；
- 本版只改切题解析，没有新 UI；真机确认升级与 OCR 主链无回归。
