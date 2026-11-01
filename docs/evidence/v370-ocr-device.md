# v3.7 OCR 管线真机验证

> 设备：vivo V2156A / Android 11 / 1080x2408（USB 调试）。
> 构建：本机 assembleDebug（内置模型完整包），debug 签名与设备上 v3.6.0 一致
> （证书 SHA-256 f86c8a85...），因此可以直接覆盖安装、不清数据。

## 1. 覆盖升级

- 设备原版本：3.6.0 / 28（首次安装 2026-10-25）。
- 安装 3.7.0 / 29 后：versionCode=29、versionName=3.7.0；adb install -r 成功，无需卸载。
- 数据存活（升级后首页实拍）：单词 1165、今日待复习 2、已掌握 6、今日待办 0/2 —— 与升级前一致。
  取证图：v370-device-home.png（本目录）。
- 设备安装器会弹「安全守护」二次确认（未知来源安装），与 App 无关；
  自动化安装时需要点一次「已了解应用的风险检测结果 + 继续安装」。

## 2. OCR 管线设备用例（androidTest）

测试类：com.studykit.util.OcrPipelineDeviceTest。
测试图：PIL + 微软雅黑渲染的 700x900 页面（清晰 / 倾斜 3 度 / 倾斜 6 度 / 模糊 / 暗图 / 白页），
与 JVM 侧 PhotoQualityRealImageTest 同一份资源。

执行命令（按方法逐条跑）：

~~~
adb shell am instrument -w -e class "com.studykit.util.OcrPipelineDeviceTest#clearPageIsRecognizedWithReport" com.studykit.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class "com.studykit.util.OcrPipelineDeviceTest#tiltedPageIsDeskewedBeforeRecognition" com.studykit.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class "com.studykit.util.OcrPipelineDeviceTest#blurryPageIsFlaggedBeforeOcr" com.studykit.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class "com.studykit.util.OcrPipelineDeviceTest#darkPageIsFlaggedBeforeOcr" com.studykit.test/androidx.test.runner.AndroidJUnitRunner
~~~

结果（2026-11-01 20:56 - 20:58）：

| 用例 | 结果 | 耗时 | 说明 |
| --- | --- | --- | --- |
| clearPageIsRecognizedWithReport | OK | 4.10s | 清晰页走完 PP-OCRv6 det+rec，文本含 StudyKit，report.meanScore > 0.30 |
| tiltedPageIsDeskewedBeforeRecognition | OK | 3.81s | 6 度倾斜页估计角落在 4~8 度，摆正后仍能识别出文字 |
| blurryPageIsFlaggedBeforeOcr | OK | 0.18s | 跑模型之前质量体检判定 BLURRY |
| darkPageIsFlaggedBeforeOcr | OK | 0.18s | 质量体检判定 TOO_DARK |

4/4 通过。**如实说明**：一次性跑整个测试类时，设备把测试进程当成后台缓存进程冻结
（logcat 里 am_app_frozen / vendor fast_freezer），instrumentation 会挂住或报 Process crashed；
同一批用例按方法逐条执行全部通过。这是设备侧 vendor 行为，不是 OCR 管线崩溃。
测试前已执行 settings put global cached_apps_freezer disabled 仍复现，故验收按方法执行。

## 3. 真机 UI 走查（人工）

- 学习页 ->「＋录入」->「拍照 / 相册录题」：先弹来源选择（拍照 / 从相册选择），与 v3.6 一致；
- 相册入口走系统文档选择器（Android 11 无 Photo Picker，回退 OPEN_DOCUMENT），
  能选到推到 /sdcard/Download 的测试图；质量弹窗与预览页的截图受系统选择器交互限制，未纳入自动化证据；
- 质量门控/低置信度剔除的判定逻辑由 QuestionPhotoImportTest（routePhotoOcr）与
  OcrReportTest、PhotoQualityRealImageTest 在 JVM 侧钉住。

## 4. 复现材料

- 测试图：app/src/androidTest/assets/ocr/*.png（clear / blurred / dark / tilted3 / tilted6 / blank）
- JVM 全量：1659 用例 / 181 类 / 0 失败（2026-11-01）
- 构建产物：C:/sk_build/app/build/outputs/apk/debug/app-debug.apk（121,261,302 B）
