# StudyKit v3.8.0

> 版本号 3.8.0 / 30。Room v12 -> v13（questions 只加列，老数据不变）。
> 本版按《优化 studykit》文档继续推进「题目结构化 / 人工确认 / 公式与图形占位 / 评测集」。

## 这一版做了什么

### 1. 规则版面切题：把一页题变成结构化题目

拍照/相册识别出文本后，如果解析不了「题干 | 选项 | 答案」的竖线格式，会再尝试规则切题：

- **阅读顺序**：按 OCR 框识别双栏（左栏先读完再右栏），单栏按行聚类；
- **题号/选项/答案/解析**：支持 1. / 2、/ 第 3 题、A. / B、四类选项标记、单行内联选项、逐行选项、
  多行题干、英文续行；
- **答案解析**：A-H 字母 -> 单选/多选；正确/错误 -> 判断；短答案 -> 填空；长答案 -> 简答/主观参考答案；
- **宁少勿错**：缺答案或答案格式不认识的题不自动导入，页面提示「N 道题没找到答案，去编辑页补」；
- 仍未切出的页继续走「题库编辑页」兜底，不丢原文。

### 2. 待校对队列：错误内容不再静默进复习

- questions 新增 needs_review / ocr_confidence / ocr_page_path / ocr_layout_json 四列（Room v13，纯加列）；
- 拍照/相册导入时，低置信度、公式/符号风险、照片质量问题的题目自动带 needs_review 与置信度；
- 学习页出现「待校对题目 N 道」入口，先核对再进复习；
- 校对页支持：
  - **确认无误**：清标记，题目回到正常复习池；
  - **编辑**：改题干/选项/答案/解析，保存后自动清标记（答案按题型校验，格式不对会提示）；
  - **删除**：连 OCR 来源页一起删除；
  - **看原图**：有来源页的题可点开整页照片，核对公式/图形/表格。

### 3. 公式与图形：保留原图，不硬转 LaTeX

- FormulaDetector 用符号密度/中日韩占比/化学式记号等启发式挑出「疑似公式/符号行」；
- 这些题的 ocr_layout_json 记录疑似公式原文，校对页提示「比对原图核对」；
- 来源页整页保存（filesDir/ocr_pages），图形/表格/复杂公式以原图为准，不生成可能错误的 LaTeX；
- 已知限制：本版不接公式识别模型，手写公式更不在范围内。

### 4. 300 张 OCR 评测集 + CER 回归

- tools/ocr_eval/generate_dataset.py：固定种子生成 300 张 900x1200 页面，覆盖 clear_printed /
  screenshot / low_light / blur / tilt / two_column / formula / table 八类，附 ground_truth.json；
- tools/ocr_eval/evaluate.py：逐图 CER/WER、分类 mean/P50/P90、最差清单，输出 markdown；
- app 的 OcrJvmEvalTest 在宿主 JVM 用桌面版 onnxruntime 跑真模型（同一份权重/字典），
  用 -PocrEvalDir 指定数据集后执行，输出 JSON 给评估脚本。
- **结果**：300 张合成评测集实测：平均 CER 0.83%（语料级 0.65%）、P50 0%、P90 2.73%、WER 6.47%、空文本 0/300；分类见 docs/evidence/ocr-eval-v3.8.md。
- 复现命令与指标定义见 tools/ocr_eval/README.md 与 docs/ocr-layout-and-review.md。

## 机检

187 测试类 / 1684 用例 / 0 失败（1 条真模型评测在未传 -PocrEvalDir 时跳过）。
  新增 Migration12To13Test、OcrReviewEditTest、LayoutImportConversionTest、OcrLayoutJsonTest、
  QuestionLayoutParserTest、OcrJvmEvalTest；300 张合成评测集实测见 docs/evidence/ocr-eval-v3.8.md。

## 真机验证（vivo V2156A / Android 11）

- **覆盖升级**：3.7.0/29 -> 3.8.0/30 直接覆盖（debug 签名一致），Room v12 -> v13 迁移成功
  （PRAGMA user_version=13），数据仍在（单词 1165、今日待复习 2、已掌握 6、questions 71）。
- **待校对队列走查**：临时注入一条 needs_review 测试题后，学习页出现「待校对题目」入口；
  待校对页显示置信度 55%、疑似公式提示、来源页缩略图与确认/编辑/删除；编辑对话框可改题干/选项/答案/解析，
  保存后回首页入口消失，库内 needs_review 行数归 0。验证后已恢复原库并删除测试页图。
- **OCR 设备用例**：OcrPipelineDeviceTest 按方法逐条复跑 **4/4 通过**（清晰 3.94s、6 度摆正 4.05s、
  模糊门控 0.17s、暗图 0.21s）。一次跑整类仍会被 vivo fast_freezer 冻结，属设备 vendor 行为。
- 证据：docs/evidence/v380-ocr-device.md 与 5 张设备截图。

## 已知限制

- 只做角度摆正，不做透视校正与弯曲书页展平；
- 不用 YOLO/版面模型，复杂版面（跨栏公式、多列表格、混排图片）仍可能漏切或串行；
- 不识别公式/化学式 LaTeX，只做疑似提示 + 原图兜底；
- 缺答案题目不会自动入库，需要人工补答案；
- 评测集是合成页面，不代表真实拍照分布；真实拍照集仍需后续积累；
- Room v13 首次升级会给老题目落 needs_review=0，不影响既有复习。

## 产物

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| release/StudyKit-v3.8.0-debug.apk | 121333196 | a1e09b06896adab4b04bf1663509eae92d75a87121fa31f01b9aedd37838fdfa |
