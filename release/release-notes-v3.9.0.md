# StudyKit v3.9.0

> 版本号 3.9.0 / 31。Room v13 -> v14（新增 ocr_corrections 纠错记录表，纯加表）。
> v3.9 在 v3.8「规则切题 + 待校对」之上，补透视校正、公式/图形裁片、纠错日志与拍照仿真评测。

## 这一版做了什么

### 1. 透视校正：把斜拍/桌面上的纸页先摆正

- 新增 PageDetector：Otsu 分桌面/纸面 -> 最大亮区 -> 四极值角点 -> 凸性/边长/面积校验；
- 新增 PerspectiveCorrector：Matrix.setPolyToPoly 把四边形重采样成矩形，长边最多 2400；
- 识别管线顺序：EXIF 转正 -> 透视校正 -> 质量体检 -> 角度摆正 -> OCR；
- 截图/白底图边框全亮时直接跳过，不会把正常截图裁坏；OcrReport 增加 perspectiveCorrected 标记；
- 已知限制：只做四边形透视，不做弯曲书页展平（dewarp）。

### 2. 公式/图形裁片：原图留证，而不是假装识别

- 规则切题时记录疑似公式行的框（formulaBoxes），OcrPageStore 把每行裁成小图；
- 新增 FigureDetector：网格边缘密度 + 排除 OCR 文本框，找出图形/表格区域并裁片（最多 4 块）；
- OcrLayoutJson 新增 formulaCrops / figureCrops，校对页展示缩略图，点开可看整页原图；
- 仍不做公式 LaTeX 识别；裁片是为了让用户核对，不是自动理解图形。

### 3. 纠错日志：本地记录用户改了什么

- Room v14 新增 ocr_corrections：question_id / created_at / before_stem / after_stem /
  before_answer / after_answer / ocr_confidence / source_page；
- 校对页保存时只有内容真的变了才记一条（确认无误不记）；本地 append-only，不联网、不导出；
- 暂不提供查看/导出界面，后续可用于分析「哪类页面/哪个题型容易错」。

### 4. 100 张拍照仿真评测集

- tools/ocr_eval/generate_photo_like.py：五类（photo_perspective / photo_shadow / photo_fold /
  photo_lowlight / photo_blur）各 20 张，1000x1300 JPEG；
- 生成深色桌面、透视纸页、纸纹/阴影/高光/折痕、低光/失焦，GT 包含标题/学号/页脚；
- 走真模型（桌面 ORT）跑 CER，结果：100 张拍照仿真集（真模型、含透视校正）实测：平均 CER 0.36%（语料级 0.38%），P50 0%、P90 1.85%，
  CER<=0.05 占比 100%，平均 WER 13.07%；分类见 docs/evidence/ocr-eval-photo-like-v3.9.md。
- 注意：仍是渲染文本 + 合成退化，不是真实用户拍照集，绝对值只适合同数据集回归。

## 机检

192 测试类 / 1699 用例 / 0 失败（1 条真模型评测在未传 -PocrEvalDir 时跳过）。
  新增 PageGeometryTest、PerspectiveBitmapTest、FigureDetectorTest、Migration13To14Test、
  OcrCorrectionLogTest，并补 OcrLayoutJson crop、QuestionLayoutParser formulaBoxes 断言；
  100 张拍照仿真集 CER 报告见 docs/evidence/ocr-eval-photo-like-v3.9.md。

## 真机验证（vivo V2156A / Android 11）

- **覆盖升级**：3.8.0/30 -> 3.9.0/31 直接覆盖（debug 签名一致），Room v13 -> v14 迁移成功
  （PRAGMA user_version=14，ocr_corrections 表存在），数据仍在（单词 1165、questions 71）。
- **OCR 设备用例**：OcrPipelineDeviceTest 5/5 通过，新增透视用例 4.18s（断言 perspectiveCorrected=true
  且校正后读出 StudyKit）；清晰 3.94s、6 度摆正 3.91s、模糊门控 0.19s、暗图 0.19s。
- **裁片与纠错日志**：注入带 formulaCrops/figureCrops 的待校对题后，校对页显示公式/图形裁片与来源页；
  编辑答案 C -> B 保存后 needs_review 归 0，ocr_corrections 落 1 行（before C / after B / 置信度 0.62 /
  来源页路径），验证后已清理测试题、裁片与纠错记录。证据见 docs/evidence/v390-ocr-device.md。

## 产物

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| release/StudyKit-v3.9.0-debug.apk | 121372590 | 322b19ad434594662b9fa3a58141b2f8a298cda702708b81b1ef0515e677c632 |

## 已知限制

- 只做透视四边形校正，不做弯曲/折痕展平；
- 不接 YOLO 版面模型、不接公式 LaTeX 识别模型；
- 图形裁片只找区域，不理解图形语义；
- 拍照仿真集不能替代真实拍照评测；
- 纠错日志没有 UI/导出，默认只在本机数据库。
