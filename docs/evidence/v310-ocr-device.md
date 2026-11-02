# v3.10 答案区回填 / 批量确认 / 纠错记录真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.9.0 一致。

## 1. 覆盖升级

- 设备原版本：3.9.0 / 31。
- 安装 3.10.0 / 32 后：versionCode=32、versionName=3.10.0。
- Room 仍是 v14（本版无 schema 变更），数据存活：questions 71 条、单词 1165。

## 2. 纠错记录查看

临时注入一条待校对题（uuid v310-demo-2，语文单选，答案 B），在校对页把答案 B 改成 A 保存：

- 首页待校对入口消失（该题 needs_review 归 0）；
- 拉库检查 ocr_corrections 落 1 行：before_answer=B、after_answer=A；
- 待校对页右上角「记录」弹窗显示「11-3 01:53 B -> A」+ 改前题干（v310-device-history.png）。

## 3. 批量确认

再注入一条待校对题（uuid v310-batch-demo，数学单选）：

- 校对页出现「全部确认」按钮（1 道）；
- 点按钮弹出「确认全部 1 道？」二次确认，说明只清标记不改内容（v310-device-batch-confirm.png）；
- 确认后页面变成「0 道 / 没有待校对题目」（v310-device-batch-done.png）。

## 4. 清理

验证后删除临时题目与纠错记录、清空 ocr_pages 测试裁片；库恢复为 71 题 / pending 0 / corrections 0。

## 5. 材料

- 截图：v310-device-history.png、v310-device-batch-confirm.png、v310-device-batch-done.png
- JVM 全量：192 测试类 / 1702 用例 / 0 失败（1 条真模型评测无数据集时跳过）
- 答案区解析新增 3 条单测（集中答案/区间答案/内联选项不误判）
