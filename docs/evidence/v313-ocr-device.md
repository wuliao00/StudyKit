# v3.13 节标题/分值行真机补验

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.12.0 一致。

## 1. 覆盖升级

- 设备原版本：3.12.0 / 34。
- 安装 3.13.0 / 35 后：versionCode=35、versionName=3.13.0。
- Room 仍 v14（本版无 schema 变更），数据存活：questions 71 条 / pending 0。

## 2. OCR 设备用例（v3.13 补验）

重启设备并重新授权 USB 调试后，OcrPipelineDeviceTest 五条用例逐条执行，5/5 通过：

| 用例 | 结果 | 耗时 |
| --- | --- | --- |
| clearPageIsRecognizedWithReport | OK | 4.17s |
| tiltedPageIsDeskewedBeforeRecognition | OK | 4.35s |
| blurryPageIsFlaggedBeforeOcr | OK | 0.16s |
| darkPageIsFlaggedBeforeOcr | OK | 0.17s |
| perspectivePageIsCorrectedBeforeOcr | OK | 4.37s |

说明：一次串行跑多条时 PowerShell + am instrument 的输出管道会挂住（设备端测试实际已结束），
改为每条用例单独执行后稳定；整类一次跑仍会被 vivo fast_freezer 冻结，属既有设备行为。

## 3. 本版验证边界

节标题/分值行规则是纯 JVM 解析逻辑，真机没有对应 UI 入口；解析正确性由
QuestionLayoutParserTest 的 3 条新用例覆盖，设备侧只确认升级与 OCR 主链无回归。
