# 端侧 OCR 评测报告

- 生成时间：2026-11-03 00:27:42
- Ground truth：tools\ocr_eval\photo_like\ground_truth.json（100 条）
- OCR 输出：tools\ocr_eval\photo_like\out_ocr.json（100 条结果；其中缺失 id 的条目 0 条、重复 id 0 条）
- 参与评估：100 张（缺失 OCR 结果 0 张；OCR 空文本 0 张；OCR 中多余/未知 id 0 条）
- 归一化：NFKC（全角转半角、上标/下标拆成普通字符）+ 去空白 + 中英文标点统一 + 忽略公式记号 ^ 与 _ + 忽略装饰性间隔符（· 等）
- 分类以 ground truth 的 category 为准，OCR 结果里的 category 字段不参与统计

## 总体指标

| 指标 | 数值 |
| --- | --- |
| 平均 CER（逐图平均） | 0.0036 |
| P50 CER | 0.0000 |
| P90 CER | 0.0185 |
| 语料级 CER（Σ编辑距离 / ΣGT 字符数） | 0.0038（编辑距离 112 / GT 字符 29605） |
| 平均 WER | 0.1307 |
| CER ≤ 0.05 的图 | 100 / 100（100.0%） |
| 平均置信度 | 0.9768（100 条提供 confidence） |

## 分类指标

| 类别 | 数量 | 平均 CER | P50 CER | P90 CER | 平均 WER | CER≤0.05 占比 |
| --- | --- | --- | --- | --- | --- | --- |
| photo_perspective（photo_perspective） | 20 | 0.0089 | 0.0000 | 0.0274 | 0.1546 | 100.0% |
| photo_blur（photo_blur） | 20 | 0.0049 | 0.0000 | 0.0224 | 0.1340 | 100.0% |
| photo_fold（photo_fold） | 20 | 0.0031 | 0.0000 | 0.0022 | 0.1364 | 100.0% |
| photo_lowlight（photo_lowlight） | 20 | 0.0009 | 0.0000 | 0.0000 | 0.1218 | 100.0% |
| photo_shadow（photo_shadow） | 20 | 0.0000 | 0.0000 | 0.0000 | 0.1066 | 100.0% |
| **全部** | **100** | **0.0036** | **0.0000** | **0.0185** | **0.1307** | **100.0%** |

## 最差 20 张

| # | id | 类别 | CER | OCR 前 80 字 |
| --- | --- | --- | --- | --- |
| 1 | photo_perspective_014 | photo_perspective | 0.0475 | 单元测试：细胞与代谢 学号：20253853 得分：106 Unit 5 Vocabulary Review f(x)=x^2+1 A.3 B.4 C.5 D.… |
| 2 | photo_perspective_011 | photo_perspective | 0.0458 | 每日一练（第12天） 学号:20257147 得分:140 f(x)=x^2+1 A.3 B.4 C.5 D.6 2. 分析文中画线句子的表达效果。 3. 一个… |
| 3 | photo_blur_001 | photo_blur | 0.0407 | 数学模拟试卷 A 卷 学号：20253684 得分:126 3 km/h ≈ 0.83 m/s A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 … |
| 4 | photo_fold_002 | photo_fold | 0.0400 | 单元测试：细胞与代谢 学号：20255562 得分：94 f(x)=x^2+1 A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 f(x)=x^2… |
| 5 | photo_blur_000 | photo_blur | 0.0358 | 每日一练（第12天） 学号：20251075 得分：136 a_n = a_1 + (n-1)d A.11 B.14 C.17 D.20 Unit 5 Voca… |
| 6 | photo_perspective_010 | photo_perspective | 0.0254 | 单元测试：细胞与代谢 学号：20255960 得分：105 1. 实验室制取氧气时，常用的药品是（） A.3 B.4 C.5 D.6 2. 一个物体做匀速直线运… |
| 7 | photo_perspective_019 | photo_perspective | 0.0241 | 期末复习综合练习 学号:20252492 得分:140 1. 在直角坐标系中，点 A 的坐标为（2，3)。 Translate the sentences in… |
| 8 | photo_fold_008 | photo_fold | 0.0219 | 期末复习综合练习 学号:20254842 得分:138 1. 下列词语中，没有错别字的是（） A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 2… |
| 9 | photo_blur_019 | photo_blur | 0.0209 | 每日一练（第12天） 学号：20255895 得分:112 3 km/h ≈ 0.83 m/s f(x)=x^2+1 A. 再接再厉 B. 走头无路 C. 迫不… |
| 10 | photo_perspective_017 | photo_perspective | 0.0201 | 物理专项训练（第 3 周） 学号:20252979 得分:149 1. 实验室制取氧气时，常用的药品是（） A. 再接再厉 B. 走头无路 C. 迫不急待 D.… |
| 11 | photo_lowlight_014 | photo_lowlight | 0.0183 | 每日一练（第12天） 学号：20258735 得分：99 The quick brown fox jumps over the lazy dog. Transl… |
| 12 | photo_perspective_009 | photo_perspective | 0.0156 | 每日一练（第12天） 学号:20258316 得分：129 1. 下列词语中，没有错别字的是（） Choose the best answer for each… |
| 13 | photo_blur_002 | photo_blur | 0.0000 | 数学模拟试卷 A 卷 学号:20255848 得分:144 1. 某同学用天平测量物体的质量，记录数据如下。 2. 某同学用天平测量物体的质量，记录数据如下。 … |
| 14 | photo_blur_003 | photo_blur | 0.0000 | 每日一练（第12天） 学号：20252603 得分:111 Translate the sentences into English. A.3 B.4 C.5 … |
| 15 | photo_blur_004 | photo_blur | 0.0000 | 每日一练（第12天) 学号：20258615 得分：141 1. 下列词语中，没有错别字的是（） A. 正确 B. 错误 2. 已知函数 f(x) 在区间[0，… |
| 16 | photo_blur_005 | photo_blur | 0.0000 | 单元测试：细胞与代谢 学号:20257872 得分:95 1. 下列关于细胞结构的叙述，正确的是（） sin 30° = 0.5 A.11 B.14 C.17 … |
| 17 | photo_blur_006 | photo_blur | 0.0000 | 数学模拟试卷 A 卷 学号：20252110 得分:127 Please write your answers on the answer sheet. A.1… |
| 18 | photo_blur_007 | photo_blur | 0.0000 | 物理专项训练（第3 周） 学号：20256300 得分：113 sin 30° = 0.5 2. 下列关于细胞结构的叙述，正确的是（） A.3 B.4 C.5 … |
| 19 | photo_blur_008 | photo_blur | 0.0000 | 每日一练（第12天） 学号：20253246得分：133 1. 下列词语中，没有错别字的是（） Translate the sentences into Eng… |
| 20 | photo_blur_009 | photo_blur | 0.0000 | 每日一练（第12天） 学号：20252942 得分：95 sin 30° = 0.5 The quick brown fox jumps over the la… |

## 说明与已知局限

1. CER 定义：先按上面的归一化规则处理 GT 与 OCR 文本，再计算 Levenshtein 字符编辑距离，除以 GT 字符数；任何插入、删除、替换都按 1 计。中文按单字比较。
2. 空文本处理：OCR 文本为空（或该 id 没有 OCR 结果）时，编辑距离退化为 GT 全长，CER 记为 1.0；缺失结果与空文本会单独计数，不会从平均值里剔除。GT 本身为空的情况本数据集不会出现。
3. WER 定义：中日韩字符逐字成词，英文/数字按空白分词（连续非空白字符算一个词）；WER = 词级编辑距离 / GT 词数。纯中文行因此与 CER 接近，英文行为真正的词级 WER。
4. P50/P90 是逐图 CER 的线性插值分位数（numpy.percentile），不是语料级分位数；语料级指标见总体指标表中的「语料级 CER」。
5. 双栏类别：GT 按「视觉行序」排列（同一水平带先左栏后右栏），与常见 OCR 从上到下、从左到右的输出顺序一致；若按人类分栏阅读顺序（先读完左栏）重排 GT，本类别 CER 会显著不同。
6. 表格类别：GT 每行单元格用单个空格连接；归一化去空白后等价于按行拼接单元格，因此对单元格识别正确但顺序/换行不同较宽容，对单元格文字错误的敏感度与普通行一致。
7. 公式类别使用普通字体 + 纯文本记号（如 f(x) = x^2 + 1、a_n、√、∫），评分时忽略 ^ 与 _；这与真实试卷的 LaTeX/上下标排版仍有较大差异，公式类 CER 不代表真实公式识别能力。
8. 合成数据（系统字体渲染，无透视、反光、阴影、折痕、摩尔纹、真实纸张纹理）不能替代真实拍照集；本数据集的绝对值只适合同一数据集内的版本回归对比，不能直接当作线上准确率。
9. 各类别难度与内容长度差异很大，分类 CER 不能直接横向比较；建议分别看各类别的均值与 P90，并关注整体均值的变化。
10. 大小写敏感：不做 casefold，英文大小写错误会计入 CER/WER；如不需要可自行在 normalize_text 中加 lower()。

