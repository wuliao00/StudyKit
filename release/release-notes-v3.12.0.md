# StudyKit v3.12.0

> 版本号 3.12.0 / 34。Room 仍 v14（无 schema 变更）。
> 本版修一个复习正确性口子：待校对题不再被题库练习/模考抽到。

## 这一版做了什么

### 1. 待校对题练习门禁

- 题库练习（getBySubject / getBySubjectFiltered）、模考（getBySubjectAndTypes）、
  按题型取题（getBySubjectAndType）、同考点变式（getByConceptTag）全部加 needs_review = 0；
- 学科列表、学科×题型计数、学科×阶段×题型计数、阶段题量也同步过滤，避免出现
  「列表里没有但计数里有」的错位；
- 校对页 / 待校对列表 / getById 不受影响；点「确认无误」或编辑保存清标记后，题目立刻回到练习池；
- 新增 QuestionReviewGateTest，用 Room 真查询覆盖 8 条路径，并验证确认后放行。

## 机检

195 测试类 / 1711 用例 / 0 失败（1 条真模型评测在未传 -PocrEvalDir 时跳过）。
  新增 QuestionReviewGateTest：8 条取题/计数/列表路径 + getById 保留待校对题。

## 真机验证

## 真机验证（vivo V2156A / Android 11）

- **覆盖升级**：3.11.0/33 -> 3.12.0/34 直接覆盖（debug 签名一致），Room 仍 v14，数据仍在（71 题）。
- **门禁走查**：注入 subject=门禁测试学科 的待校对题后，「题库练习」计数为全部 71 / 未标注 11，
  学科列表没有「门禁测试学科」；点「确认无误」后再进题库练习并下滑，该学科出现（计数恢复 72）。
  验证后已删测试题，库恢复 71 题 / pending 0。
- 证据：docs/evidence/v312-ocr-device.md 与 2 张设备截图。

## 产物

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| release/StudyKit-v3.12.0-debug.apk | 121388970 | a8809b588c9e575239795aaf4a41704b33724f23a59d68553a8fa972c20fc5bc |

## 已知限制

- 只做数据层过滤，练习入口没有强制「先校对」弹窗；
- 待校对期间不会产生练习记录，补算逻辑未做；
- 其它 OCR 限制同 v3.11（去弯曲原型未启用、合成评测集等）。
