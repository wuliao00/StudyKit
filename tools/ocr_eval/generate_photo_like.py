# -*- coding: utf-8 -*-
"""v3.9 拍照仿真评测集：100 张（5 类 x 20），比 v3.8 的合成页面更接近真实拍照。

生成的是「深色桌面 + 透视纸页 + 光照/阴影/折痕/模糊/低光」的图片，
仍不是真实手机拍照，但能把透视校正/低质量页面纳入 CER 回归。
运行：py -3 tools/ocr_eval/generate_photo_like.py
"""
import json
import os
import random

import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

SEED = 20250901
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "photo_like")
FONT_DIR = os.environ.get("OCR_EVAL_FONT_DIR", r"C:\Windows\Fonts")
FONT = os.path.join(FONT_DIR, "msyh.ttc")
BOLD = os.path.join(FONT_DIR, "msyhbd.ttc")

CN_TITLES = ["每日一练（第 12 天）", "单元测试：细胞与代谢", "数学模拟试卷 A 卷", "期末复习综合练习", "物理专项训练（第 3 周）"]
CN_STEMS = [
    "下列关于细胞结构的叙述，正确的是（ ）",
    "已知函数 f(x) 在区间 [0, 1] 上连续，则下列结论成立的是（ ）",
    "实验室制取氧气时，常用的药品是（ ）",
    "一个物体做匀速直线运动，速度为 5 m/s。",
    "设等差数列的首项为 2，公差为 3。",
    "下列词语中，没有错别字的是（ ）",
    "分析文中画线句子的表达效果。",
    "计算下列各式的值，并写出必要的步骤。",
    "某同学用天平测量物体的质量，记录数据如下。",
    "在直角坐标系中，点 A 的坐标为（2，3）。",
]
EN_LINES = [
    "Unit 5 Vocabulary Review",
    "Please write your answers on the answer sheet.",
    "The quick brown fox jumps over the lazy dog.",
    "Translate the sentences into English.",
    "Choose the best answer for each question.",
]
MATH_LINES = ["sin 30° = 0.5", "3 km/h ≈ 0.83 m/s", "f(x)=x^2+1", "a_n = a_1 + (n-1)d", "1/2 + 1/3 = 5/6"]
OPTION_SETS = [
    ["A. 再接再厉", "B. 走头无路", "C. 迫不急待", "D. 一如继往"],
    ["A. 3", "B. 4", "C. 5", "D. 6"],
    ["A. 11", "B. 14", "C. 17", "D. 20"],
    ["A. 正确", "B. 错误"],
]
CATEGORIES = ["photo_perspective", "photo_shadow", "photo_fold", "photo_lowlight", "photo_blur"]
PER_CATEGORY = 20


def font(path, size):
    return ImageFont.truetype(path, size)


def render_page(rng):
    w = rng.randint(820, 980)
    h = rng.randint(1120, 1320)
    img = Image.new("RGB", (w, h), (247, 246, 240))
    d = ImageDraw.Draw(img)
    margin = 58
    y = 54
    title = rng.choice(CN_TITLES)
    d.text((margin, y), title, font=font(BOLD, 36), fill=(20, 20, 20))
    lines = [title]
    y += 66
    header = "学号：2025" + str(rng.randint(1000, 9999)) + "    得分：" + str(rng.randint(90, 150))
    d.text((margin, y), header, font=font(FONT, 20), fill=(60, 60, 60))
    lines.append(header)
    y += 54
    pools = [CN_STEMS, CN_STEMS, EN_LINES, MATH_LINES]
    count = rng.randint(8, 12)
    for i in range(count):
        pool = rng.choice(pools)
        line = rng.choice(pool)
        # 题干池重复时加题号前缀，避免同一页全是同一句
        if pool is CN_STEMS:
            line = str(i + 1) + ". " + line
        lines.append(line)
        max_width = w - margin * 2
        f = font(FONT, 23)
        # 太长就按字符硬折（中文可断行）
        while d.textlength(line, font=f) > max_width and len(line) > 8:
            cut = len(line) - 1
            while cut > 4 and d.textlength(line[:cut], font=f) > max_width:
                cut -= 1
            part, line = line[:cut], line[cut:]
            d.text((margin, y), part, font=f, fill=(28, 28, 28))
            lines.append(part)
            y += 38
        d.text((margin, y), line, font=f, fill=(28, 28, 28))
        y += 38
        if rng.random() < 0.45:
            for opt in rng.choice(OPTION_SETS):
                d.text((margin + 26, y), opt, font=font(FONT, 22), fill=(35, 35, 35))
                lines.append(opt)
                y += 34
        if y > h - 120:
            break
    footer = "第 1 页 共 1 页"
    d.text((margin, h - 54), footer, font=font(FONT, 18), fill=(90, 90, 90))
    lines.append(footer)
    return np.array(img), [ln for ln in lines if ln.strip()]


def make_photo(rng, np_rng, category):
    page, lines = render_page(rng)
    ph, pw = page.shape[:2]
    W, H = 1000, 1300
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    base = 48 + 20 * (yy / H) + 14 * (xx / W)
    canvas = np.stack([base * 0.72, base * 0.70, base * 0.66], axis=2)
    canvas += np_rng.normal(0, 7, (H, W, 3))
    canvas = np.clip(canvas, 0, 255).astype(np.uint8)

    # 透视四角：四边都留出桌面
    top = rng.randint(60, 130)
    bottom = rng.randint(H - 130, H - 50)
    left_top = rng.randint(50, 130)
    right_top = rng.randint(W - 130, W - 50)
    left_bottom = rng.randint(40, 120)
    right_bottom = rng.randint(W - 120, W - 40)
    jitter = rng.randint(-50, 50)
    dst = np.float32([
        [left_top, top],
        [right_top, top + jitter],
        [right_bottom, bottom],
        [left_bottom, bottom - jitter],
    ])
    src = np.float32([[0, 0], [pw, 0], [pw, ph], [0, ph]])
    m = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(page, m, (W, H), flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    mask = cv2.warpPerspective(np.full((ph, pw), 255, np.uint8), m, (W, H), flags=cv2.INTER_LINEAR)
    mask_f = (mask.astype(np.float32) / 255.0)[..., None]
    canvas = (warped.astype(np.float32) * mask_f + canvas.astype(np.float32) * (1 - mask_f)).astype(np.uint8)

    # 阴影：纸页向外扩散 + 方向偏移
    shadow = cv2.GaussianBlur(mask, (61, 61), 0).astype(np.float32)
    shifted = np.roll(np.roll(shadow, rng.randint(6, 22), axis=0), rng.randint(4, 18), axis=1)
    shadow_f = 0.62 + 0.38 * (shifted / 255.0)
    canvas = np.clip(canvas.astype(np.float32) * shadow_f[..., None], 0, 255).astype(np.uint8)

    # 纸面纹理 + 折痕
    paper_tex = np_rng.normal(0, 4, (H, W, 1)).repeat(3, axis=2)
    canvas = np.clip(canvas.astype(np.float32) + paper_tex * mask_f, 0, 255).astype(np.uint8)
    if category == "photo_fold":
        fx = rng.randint(250, 750)
        fold = np.exp(-((xx - fx) ** 2) / (2 * 18.0 ** 2)) * 26
        canvas = np.clip(canvas.astype(np.float32) - fold[..., None] * mask_f, 0, 255).astype(np.uint8)
        canvas = np.clip(canvas.astype(np.float32) + np.roll(fold, 5, axis=1)[..., None] * 0.7 * mask_f, 0, 255)

    # 高光 / 低光
    gx, gy = rng.randint(300, 800), rng.randint(150, 600)
    glow = np.clip(1.0 - ((xx - gx) ** 2 + (yy - gy) ** 2) / (W * W * 0.35), 0, 1)
    gain = 42 if category != "photo_lowlight" else 12
    canvas = np.clip(canvas.astype(np.float32) + (glow * gain)[..., None], 0, 255).astype(np.uint8)
    if category == "photo_lowlight":
        canvas = np.clip(canvas.astype(np.float32) * rng.uniform(0.42, 0.62), 0, 255).astype(np.uint8)
    if category == "photo_shadow":
        sx = rng.randint(0, W)
        hand = np.exp(-(((xx - sx) ** 2) / (2 * 160.0 ** 2) + ((yy - H * 0.55) ** 2) / (2 * 260.0 ** 2)))
        canvas = np.clip(canvas.astype(np.float32) * (1.0 - 0.55 * hand[..., None]), 0, 255).astype(np.uint8)
    if category == "photo_blur":
        canvas = cv2.GaussianBlur(canvas, (0, 0), rng.uniform(0.6, 1.3))
    canvas = np.clip(canvas.astype(np.float32) + np_rng.normal(0, 5, (H, W, 3)), 0, 255).astype(np.uint8)
    return Image.fromarray(canvas), lines


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.lower().endswith((".jpg", ".png")):
            os.remove(os.path.join(OUT, f))
    rng = random.Random(SEED)
    np_rng = np.random.RandomState(SEED)
    gt = []
    index = 0
    for category in CATEGORIES:
        for i in range(PER_CATEGORY):
            image, lines = make_photo(rng, np_rng, category)
            index += 1
            name = category + "_%03d.jpg" % (index)
            image.save(os.path.join(OUT, name), quality=88, optimize=True)
            gt.append({
                "id": category + "_%03d" % i,
                "category": category,
                "image": name,
                "lines": lines,
                "text": "\n".join(lines),
            })
            if index % 20 == 0:
                print("generated", index, "/", len(CATEGORIES) * PER_CATEGORY)
    with open(os.path.join(OUT, "ground_truth.json"), "w", encoding="utf-8") as f:
        json.dump(gt, f, ensure_ascii=False, indent=2)
    print("done:", index, "images ->", OUT)


if __name__ == "__main__":
    main()
