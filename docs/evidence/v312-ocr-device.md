# v3.12 待校对题练习门禁 真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.11.0 一致。

## 1. 覆盖升级

- 设备原版本：3.11.0 / 33。
- 安装 3.12.0 / 34 后：versionCode=34、versionName=3.12.0。
- Room 仍是 v14（本版无 schema 变更），数据存活：questions 71 条。

## 2. 门禁走查

临时注入一条待校对题：subject=门禁测试学科，uuid=v312-gate，needs_review=1。

1. 打开「题库练习」：阶段/题型计数显示「全部 71、未标注 11」，学科卡只有数学/英语/语文，
   **没有「门禁测试学科」**（v312-device-quiz-before-confirm.png）；
2. 回首页点「待校对题目」->「确认无误」，再打开「题库练习」并下滑学科列表：
   **「门禁测试学科」出现**（v312-device-quiz-after-confirm.png）；
3. 验证后删除测试题，库恢复 71 题 / pending 0。

## 3. 材料

- 截图：v312-device-quiz-before-confirm.png、v312-device-quiz-after-confirm.png
- JVM 全量：195 测试类 / 1711 用例 / 0 失败（1 条真模型评测无数据集时跳过）
