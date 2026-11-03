# 端侧 OCR 评测报告

- 生成时间：2026-11-03 19:27:46
- Ground truth：tools\ocr_eval\photo_like\ground_truth.json（120 条）
- OCR 输出：tools\ocr_eval\photo_like\out_ocr.json（120 条结果；其中缺失 id 的条目 0 条、重复 id 0 条）
- 参与评估：120 张（缺失 OCR 结果 0 张；OCR 空文本 0 张；OCR 中多余/未知 id 0 条）
- 归一化：NFKC（全角转半角、上标/下标拆成普通字符）+ 去空白 + 中英文标点统一 + 忽略公式记号 ^ 与 _ + 忽略装饰性间隔符（· 等）
- 分类以 ground truth 的 category 为准，OCR 结果里的 category 字段不参与统计

## 总体指标

| 指标 | 数值 |
| --- | --- |
| 平均 CER（逐图平均） | 0.1333 |
| P50 CER | 0.0000 |
| P90 CER | 0.7730 |
| 语料级 CER（Σ编辑距离 / ΣGT 字符数） | 0.1356（编辑距离 4826 / GT 字符 35594） |
| 平均 WER | 0.2495 |
| CER ≤ 0.05 的图 | 100 / 120（83.3%） |
| 平均置信度 | 0.9769（120 条提供 confidence） |

## 分类指标

| 类别 | 数量 | 平均 CER | P50 CER | P90 CER | 平均 WER | CER≤0.05 占比 |
| --- | --- | --- | --- | --- | --- | --- |
| photo_curved（photo_curved） | 20 | 0.7820 | 0.7942 | 0.8546 | 0.8435 | 0.0% |
| photo_perspective（photo_perspective） | 20 | 0.0089 | 0.0000 | 0.0274 | 0.1546 | 100.0% |
| photo_blur（photo_blur） | 20 | 0.0049 | 0.0000 | 0.0224 | 0.1340 | 100.0% |
| photo_fold（photo_fold） | 20 | 0.0031 | 0.0000 | 0.0022 | 0.1364 | 100.0% |
| photo_lowlight（photo_lowlight） | 20 | 0.0009 | 0.0000 | 0.0000 | 0.1218 | 100.0% |
| photo_shadow（photo_shadow） | 20 | 0.0000 | 0.0000 | 0.0000 | 0.1066 | 100.0% |
| **全部** | **120** | **0.1333** | **0.0000** | **0.7730** | **0.2495** | **83.3%** |

## 最差 20 张

| # | id | 类别 | CER | OCR 前 80 字 |
| --- | --- | --- | --- | --- |
| 1 | photo_curved_011 | photo_curved | 0.9800 | C. 迫不急待 |
| 2 | photo_curved_014 | photo_curved | 0.8704 | A.3 B.4 C.5 D.6 C. 迫不急待 A. 正确 B. 错误 A.11 B.14 C.17 D.20 |
| 3 | photo_curved_019 | photo_curved | 0.8528 | A. 11 B.14 C.17 D.20 A.3 B.4 C.5 D.6 A. 正确 B. 错误 A. 正确 B. 错误 |
| 4 | photo_curved_007 | photo_curved | 0.8486 | A. 正确 B. 错误 A.11 B.14 C.17 D.20 A.11 B.14 C.17 D.20 A. 正确 B. 错误 |
| 5 | photo_curved_001 | photo_curved | 0.8459 | A.3 B.4 C.5 D.6 A.3 B.4 C.5 D.6 A. 正确 B. 错误 f(x)=x^2+1 |
| 6 | photo_curved_016 | photo_curved | 0.8364 | A. 正确 B. 错误 A.3 B.4 C.5 D.6 A.3 B.4 C.5 D.6 A.3 B.4 C.5 D.6 |
| 7 | photo_curved_008 | photo_curved | 0.8255 | A. 正确 B. 错误 A.3 B.4 C.5 D.6 A. 再接再厉 B. 走头无路 C. 迫不急待 D.一如继往 A.3 B.4 C.5 D.6 |
| 8 | photo_curved_013 | photo_curved | 0.8159 | A.3 B.4 C.5 D.6 A. 正确 B. 错误 A. 正确 B. 错误 A. 正确 B. 错误 A.3 B.4 C.5 D.6 A. 正确 B. 错误 … |
| 9 | photo_curved_006 | photo_curved | 0.8078 | A.3 B.4 C.5 D.6 A. 正确 B. 错误 A. 正确 B. 错误 A. 正确 B. 错误 sin 30° = 0.5 A.3 B.4 C.5 D.… |
| 10 | photo_curved_000 | photo_curved | 0.7949 | A.3 B.4 C.5 D.6 A. 11 B.14 C.17 D.20 A.3 B.4 C.5 D.6 A. 11 B.14 C.17 D.20 |
| 11 | photo_curved_009 | photo_curved | 0.7936 | A.11 B.14 C.17 D.20 A. 再接再厉 B. 走头无路 C. 迫不急待 D.一如继往 A.11 B.14 C.17 D.20 A. 正确 B. … |
| 12 | photo_curved_010 | photo_curved | 0.7774 | A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 A. 11 B.14 C.17 D.20 f(x)=x^2+1 A. 正确 B. 错误 A.3 … |
| 13 | photo_curved_017 | photo_curved | 0.7725 | A. 11 B.14 C.17 D.20 A. 正确 B. 错误 A.3 B.4 C.5 D.6 A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往… |
| 14 | photo_curved_004 | photo_curved | 0.7556 | A. 正确 B. 错误 A.11 B.14 C.17 D.20 sin 30° = 0.5 A. 正确 B. 错误 A.3 B.4 C.5 D.6 A.11 B… |
| 15 | photo_curved_003 | photo_curved | 0.7288 | A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 A.11 B.14 C.17 D.20 A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一… |
| 16 | photo_curved_015 | photo_curved | 0.7247 | 2. 已知函数f(x）在区间[0，1] 上连续，则下列结论成立的是（） A. 正确 B. 错误 A. 再接再厉 B. 走头无路 C. 迫不急待 D.一如继往 A… |
| 17 | photo_curved_002 | photo_curved | 0.7160 | 3 km/h ≈ 0.83 m/s A. 正确 B. 错误 f(x)=x^2+1 9. 已知函数f(x）在区间[0,1]上连续，则下列结论成立的是（） 第1页共… |
| 18 | photo_curved_005 | photo_curved | 0.6756 | A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 f(x)=x^2+1 A.11 … |
| 19 | photo_curved_018 | photo_curved | 0.6349 | A. 11 B.14 C.17 D.20 A. 再接再厉 B. 走头无路 C. 迫不急待 D. 一如继往 f(x)=x^2+1 A. 再接再厉 B. 走头无路 … |
| 20 | photo_curved_012 | photo_curved | 0.5833 | f(x)=x^2+1 A.3 B.4 C.5 D.6 A.3 B.4 C.5 D.6 A. 正确 B. 错误 f(x)=x^2+1 A.3 B.4 C.5 D.… |

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

