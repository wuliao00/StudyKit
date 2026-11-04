# v3.14 英文 Section / 卷头真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.13.0 一致。

## 1. 覆盖升级

- 设备原版本：3.13.0 / 35。
- 安装 3.14.0 / 36 后：versionCode=36、versionName=3.14.0。
- Room 仍 v14（本版无 schema 变更），数据存活：questions 71 条 / pending 0。

## 2. 验证

- 节标题规则（Section A / Part B / 第Ⅰ卷 / 卷Ⅱ）是纯 JVM 解析逻辑，JVM 新增 2 条
  QuestionLayoutParserTest 并全量通过；
- 设备侧抽查 OCR 主链：clearPageIsRecognizedWithReport OK 3.47s，
  perspectivePageIsCorrectedBeforeOcr OK 3.52s；
- 设备上逐条执行 instrument 稳定；串行多条输出管道仍会挂住（设备端实际已结束），属既有现象。

## 3. 材料

- JVM 全量：195 测试类 / 1716 用例 / 0 失败（1 条真模型评测无数据集时跳过）
