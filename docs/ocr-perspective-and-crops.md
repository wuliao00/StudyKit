# 透视校正、公式/图形裁片与纠错日志（v3.9）

> 本文记录 v3.8 之后补的四件事：透视校正、公式/图形裁片、纠错日志、拍照仿真评测集。
> 对应《优化 studykit》文档里「透视校正 / 公式占位 / 评测集」中此前明确列为未做的工程项。

## 1. 透视校正（PageDetector + PerspectiveCorrector）

### 检测

1. 缩到 480 长边灰度图；
2. Otsu 阈值分「纸（亮）/桌面（暗）」；
3. 边框亮像素占比 > 60% 直接返回 null —— 截图、白底扫描、整页占满画面的图不需要校正；
4. 亮区取 4 邻域最大连通域，面积必须占画面 28%..97%；
5. 用四个线性泛函极值取角点：min(x+y)=左上、max(x+y)=右下、max(x-y)=右上、min(x-y)=左下；
   对凸四边形，这四个点恰好是四个顶点，比 minAreaRect 更稳；
6. 校验：叉积同号（凸）、每条边 > 0.18 对角线、面积占比合法、四角在画面内；不合格返回 null。

### 校正

- 目标尺寸 = 四边形对边长度均值，长边封顶 2400；
- android.graphics.Matrix.setPolyToPoly(src, dst, 4) + Canvas 一次双线性重采样；
- 输出白底，避免边缘透明；
- 管线位置：EXIF 转正 -> 透视校正 -> PhotoQualityAnalyzer -> Deskew -> OCR；
- OcrReport.perspectiveCorrected = true 表示本图做过透视校正，质量/设备断言可看它。

### 为什么不是 OpenCV / 模型

端侧不引 native OpenCV，也不用分割模型：几何页检测用灰度统计就够常见场景，
失败时返回 null 不校正，比把正常截图裁坏安全。弯曲书页、厚书折痕仍不在范围内（下一步做 dewarp）。

## 2. 公式/图形裁片

### 公式

- 规则切题时，每条疑似公式行同时记录文本框（LayoutQuestion.formulaBoxes）；
- QuestionPhotoImport 用 OcrTextExtractor.prepareWorkingBitmap 重建同一张工作图
  （EXIF -> 透视 -> 摆正），按框裁小图存到 filesDir/ocr_pages；
- OcrLayoutJson 新增 formulaCrops（相对路径列表），校对页展示缩略图，点开看整页原图；
- 仍不转 LaTeX：公式文本保留，裁片只用于人工核对。

### 图形/表格

- FigureDetector：480 长边灰度 + 16px 网格；
- 先剔除 OCR 文本框覆盖的格子（外扩 2px），避免把文字边缘当图形；
- 网格内梯度 > 42 的像素占比 >= 10% 视为边缘格；
- 8 邻域连通，过滤格子数 < 6、面积占比 < 0.6% 或 > 35%、长宽比 < 0.12 或 > 8 的区域；
- 最多保留 4 块，按面积排序；裁片写 figureCrops，与公式裁片一起进校对页；
- 只找「有图」的区域，不识别图形语义（函数图/电路图/表格都统一叫图形区域）。

## 3. 纠错日志（Room v14）

表 ocr_corrections：

| 列 | 用途 |
| --- | --- |
| id | 自增主键 |
| question_id | 对应题目 |
| created_at | 毫秒时间戳 |
| before_stem / after_stem | 改前/改后题干 |
| before_answer / after_answer | 答案摘要（选择题字母、填空分号拼接、简答参考答案） |
| ocr_confidence | 原 OCR 置信度，便于按质量分层分析 |
| source_page | 来源页相对路径 |

- 只在「内容真变了」时记录；确认无误、原样保存不写；
- 本地 append-only，不导出、不联网；暂未做查看/导出 UI；
- 后续可分析「哪类页面/哪个题型最容易错」，用于调规则/阈值或训练数据。

## 4. 拍照仿真评测集（100 张）

- tools/ocr_eval/generate_photo_like.py；独立目录 photo_like/，不影响 v3.8 的 300 张合成集；
- 五类各 20 张：photo_perspective / photo_shadow / photo_fold / photo_lowlight / photo_blur；
- 1000x1300 JPEG：深色桌面、透视纸页、纸纹、阴影/高光/折痕、低光、轻微失焦；
- GT 包含标题/学号/选项/页脚；
- 真模型（桌面 ORT）跑完用 evaluate.py 评：平均 CER 0.36%、P90 1.85%、CER<=0.05 100/100（v3.9 首测）；
  完整分类见 docs/evidence/ocr-eval-photo-like-v3.9.md；
- 与真实手机拍照仍有差距（无摩尔纹/真实纸张/相机 ISP），只作为同一数据集内的回归基线。

## 5. 复现

~~~powershell
cd tools/ocr_eval
py -3 generate_dataset.py          # v3.8 300 张
py -3 generate_photo_like.py       # v3.9 100 张拍照仿真

# 在仓库根目录跑真模型（JVM 桌面 ORT）
gradlew.bat :app:testDebugUnitTest --tests "com.studykit.ocr.eval.OcrJvmEvalTest" ^
  -PocrEvalDir=<repo>/tools/ocr_eval ^
  -PocrEvalOut=<repo>/tools/ocr_eval/out/ocr_output.json
gradlew.bat :app:testDebugUnitTest --tests "com.studykit.ocr.eval.OcrJvmEvalTest" ^
  -PocrEvalDir=<repo>/tools/ocr_eval/photo_like ^
  -PocrEvalOut=<repo>/tools/ocr_eval/photo_like/out_ocr.json

py -3 evaluate.py out/ocr_output.json --out ../../docs/evidence/ocr-eval-v3.8.md
py -3 evaluate.py photo_like/out_ocr.json --gt photo_like/ground_truth.json ^
  --out ../../docs/evidence/ocr-eval-photo-like-v3.9.md
~~~

## 6. 已知限制

- 透视为平面四边形，不做弯曲书页展平；
- 不接 YOLO/版面模型，跨栏公式、多列表格仍可能漏切/串行；
- 不做公式 LaTeX 识别，图形只做区域裁片；
- 拍照仿真集不是真实用户拍照集，绝对值不代表线上准确率；
- 纠错日志没有 UI/导出，后续需要时再做。
