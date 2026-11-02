# OCR 评测集与评估脚本（tools/ocr_eval）

StudyKit 端侧 OCR（PP-OCRv6，中文 + 英文）的离线合成评测集，用于「拍照 / 相册录题」链路的
**CER 回归**和**分类型统计**（清晰 / 低光 / 模糊 / 倾斜 / 截图 / 双栏 / 公式 / 表格）。

本目录只包含数据生成与评估脚本，不修改 App 源码，不依赖 gradle。

---

## 一、概览

- **300 张** 900×1200 PNG（RGB），8 个类别各 37–38 张（4 类 38 + 4 类 37）。
- 固定随机种子 **SEED = 20250101**，同一脚本重复运行生成**完全一致**的图片与标注。
- 文字全部用 Windows 系统字体渲染：微软雅黑（msyh.ttc / msyhbd.ttc）、Arial（arial.ttf）、
  Consolas（consola.ttf）、Cambria（cambria.ttc）；公式/表格也是**普通字体 + 纯文本记号**，
  不使用 LaTeX，保证 OCR 有可读的 ground truth。
- 标注 **ground_truth.json**（300 条，约 294 KB）与脚本一起提交。
- **dataset/ 下的 300 张图片不提交 git**（dataset/.gitignore 内容为 `*` 与 `!.gitignore`），
  克隆后运行生成脚本即可重建（约 42 MB，1–2 分钟）。

---

## 二、目录结构

```
tools/ocr_eval/
├── generate_dataset.py   # 生成 300 张图 + ground_truth.json
├── evaluate.py           # CER/WER 评估 + markdown 报告
├── ground_truth.json     # 标注（提交；300 条，约 294 KB）
├── README.md             # 本文件
└── dataset/
    ├── .gitignore        # 忽略本目录全部图片（只保留 .gitignore）
    └── <id>.png          # 300 张图片，git 忽略
```

---

## 三、数据集类别

| 类别（category） | 数量 | 生成方式 | 主要难点 |
| --- | --- | --- | --- |
| `clear_printed` 清晰印刷体 | 38 | 白底黑字试卷页：标题、姓名/分数栏、中文题干、英文单词句、数字/公式行、选择题 A–D、页脚；正文 27–34 px | 基准类别，应接近零错误 |
| `screenshot` 截图 | 38 | 模拟 App 截图：状态栏（9:41）、标题栏、可选搜索框、分组标题、5–7 张卡片列表（主标题 + 副标题）、底部标签栏；深色主题约 45% | 深色底、UI 小字、图标旁文字 |
| `low_light` 低光 | 37 | 同清晰页内容后整体压暗：亮度 ×0.22–0.38、冷色偏移、光照不均（随机角 0.84–1.0）、传感器噪声（1/4 分辨率生成后放大）、轻微模糊 | 低亮度 + 噪声 + 对比度下降 |
| `blur` 模糊 | 38 | 高斯模糊半径 **1.2 / 2.0 / 3.0** 轮换（各约 1/3） | 小字号笔画粘连 |
| `tilt` 倾斜 | 37 | 紧凑排版内容旋转 **2 / 4 / 6 / 8 度**（方向随机 ±），白底旋转、不裁切 | 检测框倾斜、行切分 |
| `two_column` 双栏 | 37 | 标题 + 左右两栏各 10–13 行（左栏中文短句、右栏英文/数字/短公式混合） | 双栏行序与切分 |
| `formula` 公式 | 38 | 标题 + 可选副标题 + 7–9 行公式，普通字体渲染 `x^2`、`a_n`、√、∫、π、θ、±、≈、→ 等 | 上下标、特殊符号、居中对齐 |
| `table` 表格/选项 | 37 | 62% 网格表格（14 套模板：表头 + 3–4 行数据 + 表后简答题 + 页脚）；38% 选择题面板（题面 + A/B/C/D） | 网格线、单元格切分、多栏阅读顺序 |

各类别内容都来自**固定模板池 + 随机组合**（如中文题干池 18 条、英文行 14 条、数字行 14 条、
选择题 16 套、截图 12 个界面、公式行 18 条、表格 14 套），同一类别内不同图片的内容组合不同，
不是 300 张同一个模板。完整素材池见 `generate_dataset.py` 顶部常量。

---

## 四、重新生成数据集

环境要求：Windows 10/11，Python 3.10+，已安装 **Pillow** 与 **numpy**（本机已装，无其它依赖）。

> 注意：本机 PATH 上的 `python` 可能是 Windows 应用商店占位符（运行返回退出码 9009），请使用 Python 启动器 `py -3`，或显式写自己解释器的完整路径。已验证 `py -3`（Python 3.13.5 + Pillow 12.3.0 + numpy 2.5.1）与 bundled Python 3.12 生成结果逐字节一致。

```powershell
cd E:\项目\studykit
py -3 tools/ocr_eval/generate_dataset.py
```

预期输出（每类数量 + 总计）：

```text
[gen] clear_printed  38 张
[gen] screenshot     38 张
[gen] low_light      37 张
[gen] blur           38 张
[gen] tilt           37 张
[gen] two_column     37 张
[gen] formula        38 张
[gen] table          37 张
[gen] 总计 300 张图片 -> E:\项目\studykit\tools\ocr_eval\dataset
[gen] ground truth -> E:\项目\studykit\tools\ocr_eval\ground_truth.json (seed=20250101)
```

说明：

- 脚本**先清理 dataset/*.png 再生成**，重复运行不会残留旧图；**同 SEED 结果可复现**。
  想换一套数据集，改脚本顶部的 `SEED`（例如 `SEED = 20250102`），
  旧的 ground_truth.json 会被覆盖。
- 字体默认从 `C:\Windows\Fonts` 读取；缺字体时可设置环境变量
  `OCR_EVAL_FONT_DIR` 指向其它字体目录（脚本启动时会一次性检查并给出清晰报错）。
- 磁盘占用约 **42 MB**（900×1200×300 PNG：清晰/表格类约 40–80 KB/张，低光类约 0.5 MB/张，
  已对低光噪声做降采样优化，否则会膨胀到约 80 MB）。
- 如要保留某一版数据集做历史对比，先复制整个 `dataset/` 目录再重新生成。

---

## 五、ground_truth.json 格式

数组，每项一条图片标注：

```json
[
  {
    "id": "clear_printed_001",
    "category": "clear_printed",
    "image": "dataset/clear_printed_001.png",
    "lines": [
      "每日一练（第 28 天）",
      "考试时间：120 分钟    满分：150 分",
      "下列关于细胞结构的叙述，正确的是（ ）",
      "......"
    ],
    "text": "每日一练（第 28 天）\n考试时间：120 分钟    满分：150 分\n下列关于细胞结构的叙述，正确的是（ ）\n......"
  }
]
```

- `id`：全局唯一，格式 `<category>_<三位序号>`（从 001 起）。
- `category`：8 个类别之一（见上表），用于分组统计。
- `image`：相对路径，基准目录是 `tools/ocr_eval/`（可选字段，评估不读取图片像素）。
- `lines`：图像中自上而下的纯文本行（双栏类别按“视觉行”排列，同一水平带先左栏后右栏）。
- `text`：`"\n".join(lines)`，评估脚本实际比较的字段。

---

## 六、OCR 输出 JSON 格式

evaluate.py 读取的格式（由 App / 评测脚本导出）：

```json
{
  "results": [
    {
      "id": "clear_printed_001",
      "category": "clear_printed",
      "text": "每日一练（第 28 天）\n考试时间：120 分钟    满分：150 分\n...",
      "lines": ["每日一练（第 28 天）", "考试时间：120 分钟    满分：150 分", "..."],
      "confidence": 0.93
    }
  ]
}
```

字段规则：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 与 ground truth 的 id 一致；缺失 id 的条目会被计为无效条目 |
| `text` | 二选一 | 识别出的完整文本；优先使用 |
| `lines` | 二选一 | 按行文本；`text` 为空时回退用它拼接 |
| `category` | 否 | 仅作参考，**统计以 ground truth 的分类为准** |
| `confidence` | 否 | 数值，用于计算整体平均置信度 |

- 顶层也可以直接是数组 `[{...}, {...}]`。
- 文件编码建议 UTF-8；evaluate.py 读取时兼容带 BOM 的 UTF-8（utf-8-sig）。
- GT 中有、OCR 中缺失的 id：按“空文本”处理（CER = 1.0）并单独计数。
- OCR 中多出来、GT 不存在的 id：忽略并计数。
- 重复 id：后出现的覆盖先出现的，并计数。
- 建议把端侧 OCR 输出按版本命名（如 `ocr_v3.0.0.json`），报告与 JSON 一起归档，便于回归 diff。

---

## 七、运行评估

```powershell
cd E:\项目\studykit\tools\ocr_eval

# 1) 报告打印到 stdout
py -3 evaluate.py path\to\ocr_output.json

# 2) 写 markdown 文件（同时打印关键指标）
py -3 evaluate.py path\to\ocr_output.json --out report.md

# 3) 完整参数
py -3 evaluate.py --ocr ocr_output.json --gt ground_truth.json --worst 30
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `ocr` / `--ocr` | （必填） | OCR 输出 JSON 路径，位置参数或 `--ocr` 二选一 |
| `--gt` | tools/ocr_eval/ground_truth.json | ground truth 路径 |
| `--out` | 无（打印到 stdout） | markdown 报告输出文件 |
| `--worst` | 20 | “最差 N 张”清单条数 |

退出码：`0` 成功；`2` 输入文件不存在 / JSON 结构不符 / GT 为空。

报告包含：

1. **基本信息**：GT/OCR 路径与条数、缺失/空文本/多余/重复计数、归一化规则说明。
2. **总体指标表**：逐图平均 CER、P50、P90、语料级 CER、平均 WER、CER ≤ 0.05 占比、平均置信度。
3. **分类指标表**：每个类别的数量、平均 CER、P50、P90、平均 WER、CER ≤ 0.05 占比。
4. **最差 N 张清单**：id、类别、CER、OCR 前 80 字（空文本显示“（空文本）”）。
5. **说明与已知局限**（见下文）。

---

## 八、指标定义

### 归一化（CER / WER 共用）

1. **NFKC**：全角 ASCII 转半角（Ａ→A、，→,），上标/下标拆成普通字符（x²→x2、aₙ→an、₁→1），
   带圈数字转普通数字（①→1）。
2. **去掉零宽字符**（U+FEFF / U+200B–200D / U+2060）。
3. **中英文标点统一**：。→.、，、→,、；→;、：→:、！→!、？→?、（）→()、【】→[]、《》→<>、
   “”‘’→" '、—–→-、…→...、～→~。
4. **忽略装饰性间隔符**：· / ・ / •（截图、目录行的分隔点不作为识别错误）。
5. **忽略公式记号** `^` 与 `_`：同一公式可能被 OCR 输出成 `x^2` / `x²` / `x2`，
   避免记号差异被误算为错误。
6. **大小写敏感**：不做 casefold，英文大小写错误会计入 CER/WER。

### CER（Character Error Rate，逐图）

```text
CER(image) = Levenshtein(GT归一化文本, OCR归一化文本) / len(GT归一化文本)
```

- 中文按单字比较；插入、删除、替换都按 1 计（未归一化文本的空白不参与比较）。
- 编辑距离用纯 Python 两行滚动 DP 实现（300 张短文本足够快），不依赖 python-Levenshtein。
- **空文本 / 缺失结果**：OCR 归一化文本为空时，编辑距离退化为 GT 全长，**CER = 1.0**；
  这些图不从平均值中剔除，报告里单独计数。GT 本身为空的情况本数据集不会出现。
- **语料级 CER** = Σ编辑距离 / ΣGT 字符数（长文本权重更大，作为逐图平均的补充）。

### WER（Word Error Rate）

- 切词规则：**中日韩字符逐字成词**；英文/数字按空白分词（连续非空白字符算一个词）。
  例如 `sin 30° = 0.5` → `["sin", "30°", "=", "0.5"]`；`中文 test` → `["中", "文", "test"]`。
- `WER = 词级编辑距离 / GT 词数`（空文本规则同 CER，记 1.0）。
- 纯中文行 WER 与 CER 接近，英文行才是真正的词级 WER。

### 汇总

- **P50 / P90**：逐图 CER 的 numpy 线性插值分位数（不是语料级分位数）。
- **CER ≤ 0.05 占比**：逐图 CER 不超过 0.05 的图片占比，便于回归时看“通过率”。
- 报告按平均 CER 从高到低排列类别（越靠前越差），另有“全部”行。

---

## 九、已知局限（重要）

1. **合成数据不能替代真实拍照集**。本数据集是系统字体渲染，没有透视、反光、阴影、折痕、
   纸张纹理、摩尔纹、对焦失败、运动模糊方向性、真实光照色温等；**绝对值不能当作线上准确率**，
   只适合同一数据集、同一评测流程下的版本回归对比。
2. **字体单一**（微软雅黑/宋体等少数系统字体），与真实印刷体、手写体分布差异大；
   PP-OCRv6 的训练分布不一定匹配这些渲染图片。
3. **截图非真实 App 渲染**：UI 是脚本自绘（卡片、Tab 栏等），深色主题占比约 45%，
   与真实用户截图分布不同；深色底文本对部分 OCR 预处理不友好，属有意设计。
4. **公式类**用 `^` / `_` 纯文本记号，评分时忽略这两个符号；与 LaTeX/真实上下标排版差异大，
   `formula` 的 CER 不能代表真实公式识别能力。
5. **双栏 GT 按视觉行序**（同一水平带先左后右）排列，与常见 OCR 从上到下、从左到右的输出顺序一致；
   若真实 OCR 输出按“先读完左栏再读右栏”，本类别 CER 会明显不同。需要按人类阅读顺序评估时，
   请自行重排 GT 的行序。
6. **表格 GT** 每行单元格用单个空格连接，归一化去空白后等价于按行拼接单元格；
   对单元格顺序/换行差异较宽容，对单元格文字错误与普通行同样敏感。
7. **标点统一后**，中英文标点混用不再算错，也不会单独评估标点识别质量。
8. **文字内容模板化**：素材来自固定池（题干/单词/公式/表格等），与真实题库的词汇、图形、题图无关；
   题目里没有图片/图形题，与真实试卷差异大。
9. **类别难度不可横向比较**（截图/表格行数多、公式特殊符号多），建议看各类别的均值与 P90 趋势，
   以及整体均值的变化；300 张样本量下单类 P90 会有一定波动。
10. **空文本记 1.0** 是惩罚性假设，适合回归时避免“识别失败被平均掉”；如果只想看成功识别的质量，
    需要自行过滤空结果。

---

## 十、校验数据集

```powershell
cd E:\项目\studykit\tools\ocr_eval

# 1) 图片数量（期望 300）
(Get-ChildItem dataset\*.png).Count

# 2) 标注条数与分类分布（期望 300，38/38/37/38/37/37/38/37）
py -3 -c "import json, collections; d=json.load(open('ground_truth.json', encoding='utf-8')); print(len(d)); print(collections.Counter(x['category'] for x in d))"

# 3) 随机抽 5 张用 PIL 打开，检查尺寸（期望都是 (900, 1200)）
py -3 -c "import json, random; from PIL import Image; d=json.load(open('ground_truth.json', encoding='utf-8')); [print(x['id'], Image.open(x['image']).size) for x in random.sample(d, 5)]"

# 4) 跑一次评估冒烟（没有真实 OCR 输出时，可用空结果验证链路，期望 CER=1.0000）
py -3 -c "import json; json.dump({'results': []}, open('_smoke_empty.json', 'w'))"
py -3 evaluate.py _smoke_empty.json --worst 3
del _smoke_empty.json
```

---

## 十一、接入端侧 OCR 的建议流程

1. 把 `dataset/*.png` 按 id（文件名去扩展名）喂给 App 的 PP-OCRv6 端侧管线；
2. 每条结果导出为 `{"id": "<文件名>", "text": "...", "confidence": 0.9}`，
   汇总成 `{"results": [...]}`；
3. 运行 `py -3 evaluate.py ocr_v<版本>.json --out report_v<版本>.md`；
4. 对比不同版本的 report：整体平均 CER、各类别均值/P90、最差清单是否收敛；
   建议把 OCR JSON 与 report 一起作为 CI artifact 归档；
5. 本流程**不需要改 App 源码、不需要 gradle**，OCR 输出可由现有调试/导出入口离线取得。

---

## 十二、版本与维护

- 生成脚本、评估脚本、ground_truth.json、README 需要提交；`dataset/*.png` 由 dataset/.gitignore 排除。
- 修改 `generate_dataset.py` 的素材池或 `SEED` 后，务必重新生成并重新提交 ground_truth.json
  （两者必须来自同一次运行），否则评估结果无意义。
- 评估脚本只依赖 Python 标准库 + numpy；CER 的 Levenshtein 为纯 Python 实现，不需要新增三方库。
- 可复现性已验证：同 SEED 连续两次运行、以及 Python 3.12 / 3.13 两套解释器（Pillow 12.3.0）生成的图片与 ground_truth.json 逐字节一致；若升级 Pillow/字体版本导致像素级差异，请同时更新 ground_truth.json。
