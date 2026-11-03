# v3.11 纠错导出 / 弯曲去展平原型 真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408。构建：assembleDebug，debug 签名与设备 3.10.0 一致。

## 1. 覆盖升级

- 设备原版本：3.10.0 / 32。
- 安装 3.11.0 / 33 后：versionCode=33、versionName=3.11.0。
- Room 仍是 v14（本版无 schema 变更），数据存活：questions 71 条、单词 1165。

## 2. 纠错记录导出

临时注入 1 条纠错记录（questionId 87，C -> B，置信度 0.62）和 1 条待校对题：

- 进入待校对页 -> 右上角「记录」弹窗，显示「11-15 06:13 C -> B」与改前题干
  （v311-device-corrections.png）；
- 点「导出 JSON」，系统分享面板弹出，显示「1 个项目」，可选微信/云盘等目标
  （v311-device-export-share.png）；JSON 写在本机 cache，由 FileProvider 临时授权，不自动上传；
- 验证后删除临时题目与纠错记录，库恢复 71 题 / pending 0 / corrections 0。

## 3. OCR 主链回归

OcrPipelineDeviceTest 抽查 2 条（v3.11 未接去弯曲，OCR 主链与 v3.10 相同）：

| 用例 | 结果 | 耗时 |
| --- | --- | --- |
| clearPageIsRecognizedWithReport | OK | 4.00s |
| perspectivePageIsCorrectedBeforeOcr | OK | 4.16s |

## 4. 弯曲去展平（原型，未启用）

- CurvedPageDetector / CurvedPageDewarper 的几何单测（合成弯曲边界、横线拉直、合成映射）通过；
- 但把去弯曲接入识别后：整体 CER 0.0901、photo_curved 0.522；不接入：整体 0.1333、photo_curved 0.782；
  仍远高于可用阈值，且真机弯纸页只读出下半页，故本版不接入识别管线，留作下一步。

## 5. 材料

- 截图：v311-device-corrections.png、v311-device-export-share.png
- JVM 全量：194 测试类 / 1709 用例 / 0 失败（1 条真模型评测无数据集时跳过）
- 120 张拍照仿真集：默认构建平均 CER 13.33%，photo_curved 0.782（docs/evidence/ocr-eval-photo-like-v3.9.md）
