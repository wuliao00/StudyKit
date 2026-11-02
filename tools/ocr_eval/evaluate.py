#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""StudyKit 端侧 OCR 评测脚本（CER / WER + 分类型报告）。

用法::

    python tools/ocr_eval/evaluate.py ocr_output.json
    python tools/ocr_eval/evaluate.py ocr_output.json --out report.md
    python tools/ocr_eval/evaluate.py --ocr ocr_output.json --gt ground_truth.json

输入
----
ground_truth.json（generate_dataset.py 生成）::

    [
      {"id": "clear_printed_001", "category": "clear_printed",
       "image": "dataset/clear_printed_001.png",
       "lines": ["第一行", "第二行"], "text": "第一行\n第二行"}
    ]

OCR 输出 JSON（由 App / 评测管线导出）::

    {"results": [
        {"id": "clear_printed_001", "category": "clear_printed",
         "text": "识别文本", "lines": ["..."], "confidence": 0.93}
    ]}

指标与局限详见同目录 README.md。
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import unicodedata
from datetime import datetime
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_GT = SCRIPT_DIR / "ground_truth.json"

CATEGORY_LABELS = {
    "clear_printed": "清晰印刷体",
    "screenshot": "截图（深/浅色 UI）",
    "low_light": "低光",
    "blur": "模糊",
    "tilt": "倾斜",
    "two_column": "双栏",
    "formula": "公式",
    "table": "表格/选项",
}

# 中文/全角标点 -> ASCII 标点（NFKC 之后仍会残留的中文标点在这里统一）
PUNCT_MAP = {
    "。": ".",
    "，": ",",
    "、": ",",
    "；": ";",
    "：": ":",
    "！": "!",
    "？": "?",
    "（": "(",
    "）": ")",
    "【": "[",
    "】": "]",
    "《": "<",
    "》": ">",
    "「": '"',
    "」": '"',
    "『": '"',
    "』": '"',
    "“": '"',
    "”": '"',
    "‘": "'",
    "’": "'",
    "—": "-",
    "–": "-",
    "…": "...",
    "～": "~",
    "·": "",
    "・": "",
    "•": "",
    "−": "-",
}

ZERO_WIDTH = dict.fromkeys(map(ord, "\ufeff\u200b\u200c\u200d\u2060"))


def normalize_text(text: str) -> str:
    """统一全半角/标点/零宽字符，并忽略公式记号 ^ 与 _。

    - NFKC：全角 ASCII 变半角、上标 ² 变 2、下标 ₙ 变 n 等；
    - 去掉零宽字符；
    - 中文标点统一成 ASCII 标点；
    - 去掉 ^ 与 _：同一公式可能被 OCR 输出成 x^2 / x² / x2，避免记号差异被算成错误；
    - 不修改大小写（OCR 大小写错误应当计入 CER）。
    """
    if not text:
        return ""
    out = unicodedata.normalize("NFKC", text)
    out = out.translate(ZERO_WIDTH)
    out = "".join(PUNCT_MAP.get(ch, ch) for ch in out)
    out = out.replace("^", "").replace("_", "")
    return out


def normalize_cer(text: str) -> str:
    """CER 用的归一化文本：在 normalize_text 基础上去掉所有空白。"""
    return "".join(ch for ch in normalize_text(text) if not ch.isspace())


def _is_cjk(ch: str) -> bool:
    code = ord(ch)
    return (
        0x4E00 <= code <= 0x9FFF
        or 0x3400 <= code <= 0x4DBF
        or 0xF900 <= code <= 0xFAFF
        or 0x3040 <= code <= 0x30FF
    )


def tokenize_wer(text: str):
    """WER 切词：中日韩字符逐字成词，其余连续非空白字符组成一个词。"""
    tokens = []
    buffer = []
    for ch in normalize_text(text):
        if ch.isspace():
            if buffer:
                tokens.append("".join(buffer))
                buffer = []
        elif _is_cjk(ch):
            if buffer:
                tokens.append("".join(buffer))
                buffer = []
            tokens.append(ch)
        else:
            buffer.append(ch)
    if buffer:
        tokens.append("".join(buffer))
    return tokens


def edit_distance(a, b) -> int:
    """Levenshtein 编辑距离（纯 Python DP，两行滚动数组）。

    a / b 可以是 str（按字符比较）或 list（按词比较）。先裁剪公共前后缀加速。
    """
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)

    start = 0
    limit = min(len(a), len(b))
    while start < limit and a[start] == b[start]:
        start += 1
    end_a, end_b = len(a), len(b)
    while end_a > start and end_b > start and a[end_a - 1] == b[end_b - 1]:
        end_a -= 1
        end_b -= 1
    left = a[start:end_a]
    right = b[start:end_b]
    if not left:
        return len(right)
    if not right:
        return len(left)

    previous = list(range(len(right) + 1))
    for i, ca in enumerate(left, 1):
        current = [i]
        for j, cb in enumerate(right, 1):
            current.append(
                min(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + (ca != cb),
                )
            )
        previous = current
    return previous[-1]


def _load_ground_truth(path: Path):
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, list):
        raise ValueError("ground truth 必须是数组（由 generate_dataset.py 生成）")
    records = []
    seen = set()
    for index, item in enumerate(data):
        if not isinstance(item, dict):
            raise ValueError("ground truth 第 %d 条不是对象" % index)
        record_id = str(item.get("id", "")).strip()
        if not record_id:
            raise ValueError("ground truth 第 %d 条缺少 id" % index)
        if record_id in seen:
            raise ValueError("ground truth 中 id 重复：%s" % record_id)
        seen.add(record_id)

        lines = item.get("lines")
        if not isinstance(lines, list):
            lines = []
        lines = [str(line) for line in lines]
        text = item.get("text")
        if not isinstance(text, str):
            text = "\n".join(lines)

        records.append(
            {
                "id": record_id,
                "category": str(item.get("category") or "unknown"),
                "image": str(item.get("image") or ""),
                "lines": lines,
                "text": text,
            }
        )
    return records


def _load_ocr_results(path: Path):
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(data, dict):
        results = data.get("results")
        if results is None:
            raise ValueError("OCR JSON 需要 { results: [...] } 结构，或直接是结果数组")
    elif isinstance(data, list):
        results = data
    else:
        raise ValueError("OCR JSON 顶层必须是对象或数组")

    if not isinstance(results, list):
        raise ValueError("OCR JSON 的 results 必须是数组")

    by_id = {}
    invalid = 0
    duplicates = 0
    for item in results:
        if not isinstance(item, dict):
            invalid += 1
            continue
        record_id = item.get("id")
        if record_id is None:
            invalid += 1
            continue
        record_id = str(record_id)
        if record_id in by_id:
            duplicates += 1
        by_id[record_id] = item
    return by_id, len(results), invalid, duplicates


def _result_text(result) -> str:
    """从 OCR 结果取文本：优先 text，其次 lines 拼接；都为空返回空串。"""
    if not isinstance(result, dict):
        return ""
    text = result.get("text")
    if isinstance(text, str) and text.strip():
        return text
    lines = result.get("lines")
    if isinstance(lines, list) and lines:
        return "\n".join(str(line) for line in lines)
    return text if isinstance(text, str) else ""


def _result_confidence(result):
    if not isinstance(result, dict):
        return None
    value = result.get("confidence")
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    return None


def _preview(text: str, limit: int = 80) -> str:
    flat = " ".join(text.split())
    if not flat:
        return "（空文本）"
    if len(flat) > limit:
        flat = flat[:limit] + "…"
    return flat.replace("|", "\\|").replace("`", "'")


def _summarize(values):
    if not values:
        return {"n": 0, "mean": 0.0, "p50": 0.0, "p90": 0.0}
    arr = np.asarray(values, dtype=np.float64)
    return {
        "n": len(values),
        "mean": float(np.mean(arr)),
        "p50": float(np.percentile(arr, 50)),
        "p90": float(np.percentile(arr, 90)),
    }


def evaluate(gt_records, ocr_by_id, worst_limit=20):
    """逐图计算 CER/WER，并按类别汇总。返回统计结构。"""
    per_category = {}
    rows = []
    missing = 0
    empty_ocr = 0
    total_gt_chars = 0
    total_edit = 0
    confidences = []

    for record in gt_records:
        category = record["category"]
        result = ocr_by_id.get(record["id"])
        ocr_text = _result_text(result) if result is not None else ""
        if result is None:
            missing += 1
        confidence = _result_confidence(result) if result is not None else None
        if confidence is not None:
            confidences.append(confidence)

        gt_norm = normalize_cer(record["text"])
        ocr_norm = normalize_cer(ocr_text)
        if result is not None and not ocr_norm:
            # 只统计“有 OCR 结果但文本为空”的图；缺失结果单独计数（同样按 CER=1 处理）
            empty_ocr += 1

        if not gt_norm:
            # GT 为空（本数据集不应出现）：OCR 也空记 0，否则记 1
            distance = 0 if not ocr_norm else len(ocr_norm)
            cer = 0.0 if not ocr_norm else 1.0
        else:
            distance = edit_distance(gt_norm, ocr_norm)
            cer = distance / float(len(gt_norm))

        gt_tokens = tokenize_wer(record["text"])
        ocr_tokens = tokenize_wer(ocr_text)
        if not gt_tokens:
            wer = 0.0 if not ocr_tokens else 1.0
        else:
            wer = edit_distance(gt_tokens, ocr_tokens) / float(len(gt_tokens))

        total_gt_chars += len(gt_norm)
        total_edit += distance

        row = {
            "id": record["id"],
            "category": category,
            "cer": cer,
            "wer": wer,
            "ocr_text": ocr_text,
            "gt_chars": len(gt_norm),
        }
        rows.append(row)
        per_category.setdefault(category, []).append(row)

    worst = sorted(rows, key=lambda r: (-r["cer"], r["id"]))[:worst_limit]

    categories = []
    for category, cat_rows in per_category.items():
        cer_values = [r["cer"] for r in cat_rows]
        wer_values = [r["wer"] for r in cat_rows]
        stats = _summarize(cer_values)
        stats["category"] = category
        stats["wer_mean"] = float(np.mean(wer_values)) if wer_values else 0.0
        stats["pass_rate"] = sum(1 for v in cer_values if v <= 0.05) / float(len(cer_values))
        categories.append(stats)
    categories.sort(key=lambda s: (-s["mean"], s["category"]))

    overall = _summarize([r["cer"] for r in rows])
    overall["wer_mean"] = float(np.mean([r["wer"] for r in rows])) if rows else 0.0
    overall["pass_count"] = sum(1 for r in rows if r["cer"] <= 0.05)
    overall["corpus_cer"] = (total_edit / float(total_gt_chars)) if total_gt_chars else 0.0

    return {
        "rows": rows,
        "categories": categories,
        "overall": overall,
        "worst": worst,
        "missing": missing,
        "empty_ocr": empty_ocr,
        "total_gt_chars": total_gt_chars,
        "total_edit": total_edit,
        "confidences": confidences,
    }


def _fmt(value, digits=4):
    return ("%." + str(digits) + "f") % value


def _pct(value, digits=1):
    return ("%." + str(digits) + "f%%") % (value * 100.0)


def build_report(gt_path, ocr_path, gt_records, ocr_by_id, ocr_raw_count, invalid, duplicates, stats, worst_limit):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    overall = stats["overall"]
    extra = len(set(ocr_by_id) - {record["id"] for record in gt_records})
    confidences = stats["confidences"]

    lines = []
    lines.append("# 端侧 OCR 评测报告")
    lines.append("")
    lines.append("- 生成时间：%s" % now)
    lines.append("- Ground truth：%s（%d 条）" % (gt_path, len(gt_records)))
    lines.append(
        "- OCR 输出：%s（%d 条结果；其中缺失 id 的条目 %d 条、重复 id %d 条）"
        % (ocr_path, ocr_raw_count, invalid, duplicates)
    )
    lines.append(
        "- 参与评估：%d 张（缺失 OCR 结果 %d 张；OCR 空文本 %d 张；OCR 中多余/未知 id %d 条）"
        % (len(gt_records), stats["missing"], stats["empty_ocr"], extra)
    )
    lines.append(
        "- 归一化：NFKC（全角转半角、上标/下标拆成普通字符）+ 去空白 + 中英文标点统一 + 忽略公式记号 ^ 与 _ + 忽略装饰性间隔符（· 等）"
    )
    lines.append("- 分类以 ground truth 的 category 为准，OCR 结果里的 category 字段不参与统计")
    lines.append("")

    lines.append("## 总体指标")
    lines.append("")
    lines.append("| 指标 | 数值 |")
    lines.append("| --- | --- |")
    lines.append("| 平均 CER（逐图平均） | %s |" % _fmt(overall["mean"]))
    lines.append("| P50 CER | %s |" % _fmt(overall["p50"]))
    lines.append("| P90 CER | %s |" % _fmt(overall["p90"]))
    lines.append(
        "| 语料级 CER（Σ编辑距离 / ΣGT 字符数） | %s（编辑距离 %d / GT 字符 %d） |"
        % (_fmt(overall["corpus_cer"]), stats["total_edit"], stats["total_gt_chars"])
    )
    lines.append("| 平均 WER | %s |" % _fmt(overall["wer_mean"]))
    lines.append(
        "| CER ≤ 0.05 的图 | %d / %d（%s） |"
        % (overall["pass_count"], len(gt_records), _pct(overall["pass_count"] / float(len(gt_records)) if gt_records else 0.0))
    )
    if confidences:
        lines.append(
            "| 平均置信度 | %s（%d 条提供 confidence） |"
            % (_fmt(statistics.fmean(confidences)), len(confidences))
        )
    else:
        lines.append("| 平均置信度 | —（OCR 输出未提供 confidence） |")
    lines.append("")

    lines.append("## 分类指标")
    lines.append("")
    lines.append("| 类别 | 数量 | 平均 CER | P50 CER | P90 CER | 平均 WER | CER≤0.05 占比 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for item in stats["categories"]:
        label = CATEGORY_LABELS.get(item["category"], item["category"])
        lines.append(
            "| %s（%s） | %d | %s | %s | %s | %s | %s |"
            % (
                label,
                item["category"],
                item["n"],
                _fmt(item["mean"]),
                _fmt(item["p50"]),
                _fmt(item["p90"]),
                _fmt(item["wer_mean"]),
                _pct(item["pass_rate"]),
            )
        )
    lines.append(
        "| **全部** | **%d** | **%s** | **%s** | **%s** | **%s** | **%s** |"
        % (
            len(gt_records),
            _fmt(overall["mean"]),
            _fmt(overall["p50"]),
            _fmt(overall["p90"]),
            _fmt(overall["wer_mean"]),
            _pct(overall["pass_count"] / float(len(gt_records)) if gt_records else 0.0),
        )
    )
    lines.append("")

    lines.append("## 最差 %d 张" % worst_limit)
    lines.append("")
    lines.append("| # | id | 类别 | CER | OCR 前 80 字 |")
    lines.append("| --- | --- | --- | --- | --- |")
    worst = stats["worst"]
    if not worst:
        lines.append("| - | - | - | - | — |")
    else:
        for index, row in enumerate(worst, 1):
            label = CATEGORY_LABELS.get(row["category"], row["category"])
            lines.append(
                "| %d | %s | %s | %s | %s |"
                % (index, row["id"], label, _fmt(row["cer"]), _preview(row["ocr_text"]))
            )
    lines.append("")

    lines.append("## 说明与已知局限")
    lines.append("")
    lines.append(
        "1. CER 定义：先按上面的归一化规则处理 GT 与 OCR 文本，再计算 Levenshtein 字符编辑距离，"
        "除以 GT 字符数；任何插入、删除、替换都按 1 计。中文按单字比较。"
    )
    lines.append(
        "2. 空文本处理：OCR 文本为空（或该 id 没有 OCR 结果）时，编辑距离退化为 GT 全长，CER 记为 1.0；"
        "缺失结果与空文本会单独计数，不会从平均值里剔除。GT 本身为空的情况本数据集不会出现。"
    )
    lines.append(
        "3. WER 定义：中日韩字符逐字成词，英文/数字按空白分词（连续非空白字符算一个词）；"
        "WER = 词级编辑距离 / GT 词数。纯中文行因此与 CER 接近，英文行为真正的词级 WER。"
    )
    lines.append(
        "4. P50/P90 是逐图 CER 的线性插值分位数（numpy.percentile），不是语料级分位数；"
        "语料级指标见总体指标表中的「语料级 CER」。"
    )
    lines.append(
        "5. 双栏类别：GT 按「视觉行序」排列（同一水平带先左栏后右栏），与常见 OCR 从上到下、从左到右的输出顺序一致；"
        "若按人类分栏阅读顺序（先读完左栏）重排 GT，本类别 CER 会显著不同。"
    )
    lines.append(
        "6. 表格类别：GT 每行单元格用单个空格连接；归一化去空白后等价于按行拼接单元格，"
        "因此对单元格识别正确但顺序/换行不同较宽容，对单元格文字错误的敏感度与普通行一致。"
    )
    lines.append(
        "7. 公式类别使用普通字体 + 纯文本记号（如 f(x) = x^2 + 1、a_n、√、∫），"
        "评分时忽略 ^ 与 _；这与真实试卷的 LaTeX/上下标排版仍有较大差异，公式类 CER 不代表真实公式识别能力。"
    )
    lines.append(
        "8. 合成数据（系统字体渲染，无透视、反光、阴影、折痕、摩尔纹、真实纸张纹理）不能替代真实拍照集；"
        "本数据集的绝对值只适合同一数据集内的版本回归对比，不能直接当作线上准确率。"
    )
    lines.append(
        "9. 各类别难度与内容长度差异很大，分类 CER 不能直接横向比较；"
        "建议分别看各类别的均值与 P90，并关注整体均值的变化。"
    )
    lines.append(
        "10. 大小写敏感：不做 casefold，英文大小写错误会计入 CER/WER；如不需要可自行在 normalize_text 中加 lower()。"
    )
    lines.append("")

    return "\n".join(lines) + "\n"


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="StudyKit 端侧 OCR 评测：CER/WER + 分类型 markdown 报告",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python evaluate.py ocr_output.json\n"
            "  python evaluate.py ocr_output.json --out report.md\n"
            "  python evaluate.py --ocr ocr_output.json --gt ground_truth.json --worst 30\n"
        ),
    )
    parser.add_argument("ocr", nargs="?", help="OCR 输出 JSON 路径（也可用 --ocr 指定）")
    parser.add_argument("--ocr", dest="ocr_opt", metavar="PATH", help="OCR 输出 JSON 路径")
    parser.add_argument(
        "--gt",
        default=str(DEFAULT_GT),
        metavar="PATH",
        help="ground_truth.json 路径（默认：tools/ocr_eval/ground_truth.json）",
    )
    parser.add_argument("--out", default=None, metavar="PATH", help="markdown 报告输出文件；省略时打印到 stdout")
    parser.add_argument("--worst", type=int, default=20, help="最差清单条数（默认 20）")
    args = parser.parse_args(argv)
    if not args.ocr_opt and not args.ocr:
        parser.error("需要 OCR 输出 JSON：位置参数或 --ocr 二选一")
    if args.worst < 0:
        parser.error("--worst 不能为负数")
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    gt_path = Path(args.gt)
    ocr_path = Path(args.ocr_opt or args.ocr)

    if not gt_path.is_file():
        print("[eval][ERROR] 找不到 ground truth：%s" % gt_path, file=sys.stderr)
        print("[eval][HINT] 先运行 generate_dataset.py 生成数据集与标注", file=sys.stderr)
        return 2
    if not ocr_path.is_file():
        print("[eval][ERROR] 找不到 OCR 输出：%s" % ocr_path, file=sys.stderr)
        return 2

    try:
        gt_records = _load_ground_truth(gt_path)
    except (json.JSONDecodeError, ValueError) as exc:
        print("[eval][ERROR] ground truth 解析失败：%s" % exc, file=sys.stderr)
        return 2
    if not gt_records:
        print("[eval][ERROR] ground truth 为空数组", file=sys.stderr)
        return 2

    try:
        ocr_by_id, ocr_raw_count, invalid, duplicates = _load_ocr_results(ocr_path)
    except (json.JSONDecodeError, ValueError) as exc:
        print("[eval][ERROR] OCR JSON 解析失败：%s" % exc, file=sys.stderr)
        return 2

    stats = evaluate(gt_records, ocr_by_id, worst_limit=args.worst)
    report = build_report(
        gt_path,
        ocr_path,
        gt_records,
        ocr_by_id,
        ocr_raw_count,
        invalid,
        duplicates,
        stats,
        args.worst,
    )

    if args.out:
        out_path = Path(args.out)
        if out_path.parent and not out_path.parent.exists():
            out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(report, encoding="utf-8")
        overall = stats["overall"]
        print("[eval] 报告已写入 %s" % out_path)
        print(
            "[eval] 平均 CER=%s  P50=%s  P90=%s  平均 WER=%s  空文本=%d/%d"
            % (
                _fmt(overall["mean"]),
                _fmt(overall["p50"]),
                _fmt(overall["p90"]),
                _fmt(overall["wer_mean"]),
                stats["empty_ocr"],
                len(gt_records),
            )
        )
    else:
        print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
