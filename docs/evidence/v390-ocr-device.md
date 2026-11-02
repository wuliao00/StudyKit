# v3.9 透视校正 / 公式图形裁片 / 纠错日志真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408（USB 调试）。
> 构建：本机 assembleDebug，debug 签名与设备上 3.8.0 一致，可直接覆盖安装。

## 1. 覆盖升级与 Room v14 迁移

- 设备原版本：3.8.0 / 30。
- 安装 3.9.0 / 31 后：versionCode=31、versionName=3.9.0（设置页实拍 v390-device-version.png）。
- 首次启动 Room v13 -> v14 迁移成功：拉库 PRAGMA user_version=14，ocr_corrections 表存在。
- 数据存活：单词 1165、questions 71 条；升级前后一致（v390-device-home.png）。

## 2. OCR 设备用例（v3.9 新增透视）

OcrPipelineDeviceTest 五条用例按方法逐条执行，5/5 通过：

| 用例 | 结果 | 耗时 |
| --- | --- | --- |
| perspectivePageIsCorrectedBeforeOcr（新增） | OK | 4.18s |
| clearPageIsRecognizedWithReport | OK | 3.94s |
| tiltedPageIsDeskewedBeforeRecognition | OK | 3.91s |
| blurryPageIsFlaggedBeforeOcr | OK | 0.19s |
| darkPageIsFlaggedBeforeOcr | OK | 0.19s |

透视用例使用 assets/ocr/perspective_page.png（深色桌面 + 透视纸页），断言 OcrReport.perspectiveCorrected=true
且校正后仍能读出 StudyKit。整类一次跑仍会被 vivo fast_freezer 冻结，按方法执行。

## 3. 公式/图形裁片 + 纠错日志走查

临时向设备库注入一条 needs_review 测试题（uuid v38-review-demo），ocr_layout_json 带
formulaCrops / figureCrops 与一张来源页图；验证后已删除测试题、裁片、来源页和纠错记录，
库恢复到 71 题 / pending 0 / corrections 0。

1. 学习页出现「待校对题目」，点入后校对页显示：
   - 置信度 62%、题干、疑似公式提示「已留 1 张裁片」；
   - 公式裁片（f(x)=x^2+1 局部）与图形裁片（三角形）并排展示，点开可看原图；
   - 来源页缩略图与确认/编辑/删除动作（v390-device-review-crops.png）；
2. 点「编辑」，把正确答案从 C 改成 B，保存并确认；
3. 返回后待校对页显示「没有待校对题目」；
4. 拉库校验：needs_review=0、answer_index=1（B）；ocr_corrections 多出 1 行：
   question_id=88、before_answer=C、after_answer=B、ocr_confidence=0.62、source_page=ocr_pages/v390_page.png。

## 4. 材料

- 截图：v390-device-home.png / v390-device-version.png / v390-device-review-crops.png
- 裁片原图：docs/evidence/v390-crops/formula_crop.png、figure_crop.png
- JVM 全量：192 测试类 / 1699 用例 / 0 失败（1 条真模型评测无数据集时跳过）
- 300 张合成集 CER 0.83%、100 张拍照仿真集 CER 0.36%（docs/evidence/ocr-eval-*.md）
