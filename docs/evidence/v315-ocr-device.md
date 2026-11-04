# v3.15 英文板块标题 / 大题说明行 真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.14.0 一致。

## 1. 覆盖升级

- 设备原版本：3.14.0 / 36。
- 安装 3.15.0 / 37 后：versionCode=37、versionName=3.15.0。
- Room 仍 v14（本版无 schema 变更），数据存活：questions 71 条 / pending 0。

## 2. 验证

- 独立英文板块标题（Reading Comprehension / Vocabulary Review 等）与「本大题共 X 小题」
  说明行是纯 JVM 解析规则：新增 2 条 QuestionLayoutParserTest，全量通过；
- 设备侧抽查 OCR 主链：clearPageIsRecognizedWithReport OK，3.55s；
- 设备上单条 instrument 稳定，串行多条仍有输出管道挂住现象（设备端实际结束）。

## 3. 材料

- JVM 全量：195 测试类 / 1718 用例 / 0 失败（1 条真模型评测无数据集时跳过）
