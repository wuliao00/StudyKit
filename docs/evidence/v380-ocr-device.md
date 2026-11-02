# v3.8 版面切题 / 待校对队列真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408（USB 调试）。
> 构建：本机 assembleDebug，debug 签名与设备上 3.7.0 一致（证书 SHA-256 f86c8a85...），可直接覆盖安装。

## 1. 覆盖升级与 Room v13 迁移

- 设备原版本：3.7.0 / 29。
- 安装 3.8.0 / 30 后：versionCode=30、versionName=3.8.0（设置页「当前版本」实拍见 v380-device-version.png）。
- 首次启动 Room v12 -> v13 迁移成功：拉取数据库后 PRAGMA user_version=13，questions 四列
  needs_review / ocr_confidence / ocr_page_path / ocr_layout_json 均在。
- 数据存活：单词 1165、今日待复习 2、已掌握 6、questions 71 条，升级前后一致（v380-device-home.png）。

## 2. 待校对队列真机走查

为了验证「待校对 -> 编辑 -> 清标记」这条链，临时向设备数据库写入一条 needs_review 测试题
（uuid v38-review-demo，置信度 0.55，带疑似公式提示与一张 900x1200 来源页）；验证后已恢复原库并删除测试页图。

1. 启动后学习页出现「待校对题目」入口（v380-device-home-pending.png）；
2. 点入待校对页：学科·题型、置信度 55%、题干、疑似公式提示、来源页缩略图、确认/编辑/删除三个动作都在
   （v380-device-review.png）；
3. 点「编辑」：对话框包含题干、选项（每行一个）、答案、解析字段，题型显示「单选」
   （v380-device-review-edit.png）；
4. 点「保存并确认」后回到首页，待校对入口消失；重新拉库检查 needs_review=1 的行数为 0，
   测试题 row 的 needs_review 已变 0、answer_index 保持 2（选项 C）。

## 3. OCR 管线设备回归（v3.8 改动后复跑）

OcrPipelineDeviceTest 四条用例按方法逐条执行，4/4 通过：

| 用例 | 结果 | 耗时 |
| --- | --- | --- |
| clearPageIsRecognizedWithReport | OK | 3.94s |
| tiltedPageIsDeskewedBeforeRecognition | OK | 4.05s |
| blurryPageIsFlaggedBeforeOcr | OK | 0.17s |
| darkPageIsFlaggedBeforeOcr | OK | 0.21s |

一次性跑整个测试类仍会被 vivo fast_freezer 把测试进程冻结（v3.7 已记录），验收按方法执行；
测试前已执行 settings put global cached_apps_freezer disabled 仍复现。

## 4. 材料

- 截图：v380-device-home.png / v380-device-home-pending.png / v380-device-review.png /
  v380-device-review-edit.png / v380-device-version.png
- JVM 全量：187 测试类 / 1684 用例 / 0 失败（1 条真模型评测无数据集时跳过）
- 300 张合成评测集：平均 CER 0.83%、P90 2.73%、空文本 0/300（docs/evidence/ocr-eval-v3.8.md）
