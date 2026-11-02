#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""StudyKit 端侧 OCR 合成评测集生成脚本（300 张 PNG + ground_truth.json）。

用法::

    python tools/ocr_eval/generate_dataset.py

特性
----
- 固定随机种子 SEED：重复运行生成的图片与 ground_truth.json 完全一致。
- 8 个类别、每类 37-38 张、共 300 张；分辨率 900x1200；白底黑字为主。
- 文字全部用 Windows 系统字体渲染（微软雅黑 / Arial / Consolas / Cambria），
  公式与表格也使用“普通字体 + 纯文本记号”（如 x^2、a_n、√、∫），
  不用 LaTeX，保证 OCR 有可读的 ground truth。
- 输出::

      tools/ocr_eval/dataset/<id>.png     300 张图片（dataset/.gitignore 已忽略）
      tools/ocr_eval/ground_truth.json    评测标注（提交到仓库）

字体缺失时可设置环境变量 OCR_EVAL_FONT_DIR 指向字体目录。
"""

from __future__ import annotations

import json
import os
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---------------------------------------------------------------------------
# 全局配置
# ---------------------------------------------------------------------------

SEED = 20250101                      # 固定随机种子（改这里会得到另一套数据集）
CANVAS_W, CANVAS_H = 900, 1200       # 统一分辨率

SCRIPT_DIR = Path(__file__).resolve().parent
DATASET_DIR = SCRIPT_DIR / "dataset"
GROUND_TRUTH_PATH = SCRIPT_DIR / "ground_truth.json"

# 各类别数量：4 类 38 张 + 4 类 37 张 = 300 张
CATEGORY_COUNTS = {
    "clear_printed": 38,
    "screenshot": 38,
    "low_light": 37,
    "blur": 38,
    "tilt": 37,
    "two_column": 37,
    "formula": 38,
    "table": 37,
}
CATEGORY_ORDER = list(CATEGORY_COUNTS)

BLUR_RADII = (1.2, 2.0, 3.0)         # blur 类别轮换使用
TILT_ANGLES = (2, 4, 6, 8)           # tilt 类别轮换使用（方向随机）

INK = (20, 20, 20)                   # 正文颜色
INK_SOFT = (95, 95, 95)              # 表头/说明文字
INK_FOOTER = (115, 115, 115)         # 页脚

# ---------------------------------------------------------------------------
# 字体
# ---------------------------------------------------------------------------

FONT_DIR = Path(os.environ.get("OCR_EVAL_FONT_DIR", r"C:\Windows\Fonts"))
FONT_CANDIDATES = {
    "cjk": ["msyh.ttc", "simhei.ttf", "simsun.ttc"],
    "cjk_bold": ["msyhbd.ttc", "msyh.ttc", "simhei.ttf"],
    "latin": ["arial.ttf", "segoeui.ttf", "calibri.ttf"],
    "latin_bold": ["arialbd.ttf", "segoeuib.ttf", "calibrib.ttf"],
    "mono": ["consola.ttf", "cour.ttf"],
    "math": ["cambria.ttc", "times.ttf", "segoeui.ttf"],
}

_font_paths: dict = {}
_font_cache: dict = {}


def font_path(kind: str) -> str:
    """返回某类字体的实际路径；找不到时抛出带提示的异常。"""
    if kind not in _font_paths:
        for name in FONT_CANDIDATES[kind]:
            candidate = FONT_DIR / name
            if candidate.is_file():
                _font_paths[kind] = str(candidate)
                break
        else:
            raise FileNotFoundError(
                "找不到字体类别 %r：%s（字体目录：%s；可用 OCR_EVAL_FONT_DIR 覆盖）"
                % (kind, FONT_CANDIDATES[kind], FONT_DIR)
            )
    return _font_paths[kind]


def get_font(kind: str, size: int) -> ImageFont.FreeTypeFont:
    size = int(size)
    key = (kind, size)
    font = _font_cache.get(key)
    if font is None:
        font = ImageFont.truetype(font_path(kind), size)
        _font_cache[key] = font
    return font


def check_fonts() -> None:
    """启动时一次性检查所有字体，缺失时给出清晰错误。"""
    missing = []
    for kind in FONT_CANDIDATES:
        try:
            font_path(kind)
        except FileNotFoundError as exc:
            missing.append(str(exc))
    if missing:
        raise FileNotFoundError("\n".join(missing))


def has_cjk(text: str) -> bool:
    for ch in text:
        code = ord(ch)
        if 0x4E00 <= code <= 0x9FFF or 0x3400 <= code <= 0x4DBF or 0xF900 <= code <= 0xFAFF:
            return True
    return False


# ---------------------------------------------------------------------------
# 绘制工具
# ---------------------------------------------------------------------------


def draw_line(draw, x, y, text, kind, size, fill, max_width, align="left", min_size=14):
    """画一行文字；超宽时自动缩小字号。返回实际使用的字号。"""
    font = get_font(kind, size)
    while size > min_size and draw.textlength(text, font=font) > max_width:
        size -= 1
        font = get_font(kind, size)
    width = draw.textlength(text, font=font)
    if align == "center":
        x = x + (max_width - width) / 2.0
    elif align == "right":
        x = x + max_width - width
    draw.text((x, y), text, font=font, fill=fill)
    return size


def body_kind(rng: random.Random, text: str) -> str:
    """正文行字体：中文用雅黑，纯拉丁随机用 Arial / Consolas。"""
    if has_cjk(text):
        return "cjk"
    return "mono" if rng.random() < 0.30 else "latin"


# ---------------------------------------------------------------------------
# 文本素材池（“固定模板 + 随机组合”，每类内容都足够多样）
# ---------------------------------------------------------------------------

TITLES_CLEAR = [
    "期中测试卷（一）",
    "2025 学年第一学期月考",
    "课后练习：函数与导数",
    "单元测试：细胞与代谢",
    "物理专项训练（第 3 周）",
    "英语词汇与语法练习",
    "语文阅读理解训练",
    "化学基础题精选",
    "历史选择题 50 题",
    "地理综合题（一）",
    "生物必修一复习",
    "每日一练（第 28 天）",
    "数学思维拓展训练",
    "政治生活第一单元测试",
    "数学模拟试卷 A 卷",
    "期末复习综合练习",
]

HEADERS_CLEAR = [
    "姓名：李华    班级：高三（2）班",
    "考试时间：120 分钟    满分：150 分",
    "学号：20250118    得分：138",
    "命题人：王老师    审核人：李老师",
    "日期：2025 年 3 月 14 日    天气：晴",
    "注意事项：请用黑色签字笔作答",
    "总分：120 分    建议用时：90 分钟",
    "答题前请仔细阅读题目要求，保持卷面整洁",
]

CHINESE_STEMS = [
    "下列关于细胞结构的叙述，正确的是（ ）",
    "已知函数 f(x) 在区间 [0, 1] 上连续，则下列结论成立的是（ ）",
    "阅读下面的文字，完成后面的题目。",
    "某同学用天平测量物体的质量，记录数据如下。",
    "计算下列各式的值，并写出必要的步骤。",
    "根据材料，概括作者的主要观点。",
    "在直角坐标系中，点 A 的坐标为（2, 3）。",
    "下列词语中没有错别字的一项是（ ）",
    "将下列句子翻译成现代汉语。",
    "一个物体做匀速直线运动，速度为 5 m/s。",
    "实验室制取氧气时，常用的药品是（ ）",
    "关于电磁感应现象，下列说法正确的是（ ）",
    "设等差数列 {a_n} 的首项为 a_1，公差为 d。",
    "请在答题卡上作答，保持卷面整洁。",
    "如图所示，光滑水平面上有一质量为 m 的小球。",
    "下列化学方程式书写正确的是（ ）",
    "分析文中画线句子的表达效果。",
    "求函数 y = x^2 - 4x + 3 的最小值。",
]

ENGLISH_LINES = [
    "photosynthesis / respiration / diffusion",
    "Vocabulary: abandon, ability, absorb",
    "The quick brown fox jumps over the lazy dog.",
    "Read the passage and answer the questions.",
    "Chapter 4 Review Exercises",
    "equilibrium, experiment, environment",
    "True or False: Water boils at 100 degrees.",
    "Fill in the blanks with the words below.",
    "Grammar focus: present perfect tense",
    "Translate the sentences into English.",
    "Writing Task: My Favourite Season",
    "Unit 5 Vocabulary Review",
    "Please write your answers on the answer sheet.",
    "The experiment shows that light is necessary.",
]

NUMBER_LINES = [
    "1, 2, 3, 4, 5, 6, 7, 8, 9, 0",
    "3.1415926    2.7182818",
    "√2 ≈ 1.414, √3 ≈ 1.732",
    "75% of 240 = 180",
    "2024-2025 学年第一学期",
    "12.5 × 4 = 50",
    "0.75 + 0.25 = 1.00",
    "1/2 + 1/3 = 5/6",
    "10^3 = 1000",
    "sin 30° = 0.5",
    "3 km/h ≈ 0.83 m/s",
    "100 ÷ 4 = 25",
    "第 3 题 分值：12 分",
    "总分 150 分  及格线 90 分",
]

MC_SETS = [
    ("下列各式中，正确的是（ ）", ("2^3 = 6", "3^2 = 9", "4^0 = 0", "5^-1 = 5")),
    ("下列词语中，没有错别字的是（ ）", ("再接再厉", "走头无路", "迫不急待", "一如继往")),
    ("下列属于可再生能源的是（ ）", ("煤炭", "石油", "太阳能", "天然气")),
    ("函数 y = 2x + 1 的图像经过点（ ）", ("(0, 1)", "(1, 1)", "(0, 0)", "(2, 3)")),
    ("植物进行光合作用的场所是（ ）", ("线粒体", "叶绿体", "细胞核", "液泡")),
    ("电流表应如何接入电路（ ）", ("串联在电路中", "并联在电路中", "单独使用", "任意连接")),
    ("下列单词中表示“环境”的是（ ）", ("environment", "equipment", "experiment", "energy")),
    ("等差数列 2, 5, 8, ... 的第 5 项是（ ）", ("11", "14", "17", "20")),
    ("下列物质中属于混合物的是（ ）", ("氧气", "空气", "蒸馏水", "二氧化碳")),
    ("下列事件发生在 1919 年的是（ ）", ("五四运动", "辛亥革命", "南昌起义", "七七事变")),
    ("下列动物中属于哺乳动物的是（ ）", ("青蛙", "鲸", "鲨鱼", "蜥蜴")),
    ("若 a + b = 5, ab = 6, 则 a^2 + b^2 =（ ）", ("11", "13", "15", "17")),
    ("关于安全用电，说法正确的是（ ）", ("湿手拔插头", "用铜丝代替保险丝", "发现触电先切断电源", "电器着火先用水浇")),
    ("化简 √18 的结果是（ ）", ("3√2", "2√3", "9√2", "6√3")),
    ("下列关于细胞膜的叙述正确的是（ ）", ("具有全透性", "由磷脂和蛋白质组成", "不含糖类", "只存在于动物细胞")),
    ("下列句子中，标点符号使用正确的是（ ）", ("他说：“我明天去。”", "他说，“我明天去”。", "他说“我明天去”。", "他说：我明天去。")),
]

FOOTERS_CLEAR = [
    "第 1 页 共 2 页",
    "第 2 页 共 2 页",
    "—— 试卷结束 ——",
    "答题卡另附，请勿折叠",
    "第 1 页 共 1 页",
]

# 截图类：模拟 App 浅色/深色界面
SCREEN_TAB_LABELS = ("首页", "题库", "错题", "我的")

SCREENS = [
    {
        "title": "错题本",
        "section": "全部错题",
        "rows": [
            ("第 12 题 函数与导数", "2025-03-14 · 数学 · 未掌握"),
            ("第 8 题 立体几何", "2025-03-13 · 数学 · 复习中"),
            ("第 21 题 电磁感应", "2025-03-11 · 物理 · 已掌握"),
            ("第 5 题 化学方程式", "2025-03-10 · 化学 · 未掌握"),
            ("第 17 题 完形填空", "2025-03-09 · 英语 · 复习中"),
            ("第 3 题 古诗文默写", "2025-03-08 · 语文 · 已掌握"),
            ("第 9 题 遗传规律", "2025-03-07 · 生物 · 复习中"),
            ("共 36 道错题待复习", "本周新增 8 道"),
        ],
    },
    {
        "title": "练习记录",
        "section": "最近练习",
        "rows": [
            ("第三章 函数综合练习", "得分 86 · 32 分钟"),
            ("Unit 4 词汇专项", "得分 92 · 18 分钟"),
            ("力学综合测试 A 卷", "得分 74 · 45 分钟"),
            ("阅读理解训练（二）", "得分 88 · 25 分钟"),
            ("化学方程式配平", "得分 95 · 12 分钟"),
            ("历史选择题 50 题", "得分 81 · 22 分钟"),
            ("本周累计练习 6 次", "共 186 分钟"),
        ],
    },
    {
        "title": "学习统计",
        "section": "本周概览",
        "rows": [
            ("本周学习时长", "7 小时 25 分"),
            ("累计做题", "1286 道"),
            ("整体正确率", "78.5%"),
            ("连续打卡", "12 天"),
            ("掌握知识点", "86 个"),
            ("待复习卡片", "42 张"),
        ],
    },
    {
        "title": "拍照搜题",
        "section": "识别工具",
        "rows": [
            ("拍摄题目", "自动识别文字并搜索"),
            ("从相册选择", "支持 JPG 与 PNG"),
            ("搜题历史", "最近 20 条记录"),
            ("使用帮助", "查看常见问题"),
            ("离线识别", "无需网络即可使用"),
            ("识别语言", "中文 + English"),
        ],
    },
    {
        "title": "我的收藏",
        "section": "全部收藏",
        "rows": [
            ("牛顿第二定律", "物理 · 2025-02-11"),
            ("二次函数顶点式", "数学 · 2025-02-09"),
            ("氧化还原反应", "化学 · 2025-02-06"),
            ("虚拟语气用法", "英语 · 2025-02-03"),
            ("细胞呼吸过程", "生物 · 2025-01-28"),
            ("平面向量数量积", "数学 · 2025-01-22"),
        ],
    },
    {
        "title": "设置",
        "section": "通用设置",
        "rows": [
            ("账号与安全", "已登录"),
            ("离线题库", "已下载 3 个"),
            ("识别语言", "中文 + English"),
            ("清除缓存", "128 MB"),
            ("关于学习助手", "版本 3.0.0"),
            ("隐私政策", "已同意"),
        ],
    },
    {
        "title": "复习计划",
        "section": "今日任务",
        "rows": [
            ("今日待复习", "18 张卡片"),
            ("明日待复习", "24 张卡片"),
            ("本周完成率", "86%"),
            ("已完成计划", "函数专题 · 5 天"),
            ("进行中计划", "力学专题 · 第 2 天"),
            ("已归档计划", "12 个"),
        ],
    },
    {
        "title": "知识点掌握",
        "section": "数学",
        "rows": [
            ("函数与导数", "掌握度 72%"),
            ("立体几何", "掌握度 55%"),
            ("数列与不等式", "掌握度 81%"),
            ("概率与统计", "掌握度 63%"),
            ("解析几何", "掌握度 48%"),
            ("三角函数", "掌握度 77%"),
        ],
    },
    {
        "title": "题库分类",
        "section": "全部科目",
        "rows": [
            ("数学 · 1200 题", "选择 / 填空 / 解答"),
            ("物理 · 860 题", "选择 / 实验 / 计算"),
            ("化学 · 640 题", "选择 / 填空 / 推断"),
            ("英语 · 980 题", "完形 / 阅读 / 写作"),
            ("语文 · 720 题", "阅读 / 默写 / 作文"),
            ("生物 · 430 题", "选择 / 实验 / 简答"),
        ],
    },
    {
        "title": "试卷分析",
        "section": "2025 届高三一模",
        "rows": [
            ("总分 128 / 150", "班级排名 12 / 48"),
            ("选择题 48 / 50", "用时 18 分钟"),
            ("填空题 20 / 25", "用时 12 分钟"),
            ("解答题 60 / 75", "用时 55 分钟"),
            ("薄弱知识点", "立体几何 与 数列"),
            ("建议练习", "空间向量专题 20 题"),
        ],
    },
    {
        "title": "搜索历史",
        "section": "最近搜索",
        "rows": [
            ("二次函数值域", "3 天前"),
            ("氧化还原反应配平", "5 天前"),
            ("虚拟语气练习题", "1 周前"),
            ("三角函数图像变换", "1 周前"),
            ("细胞呼吸的过程", "2 周前"),
            ("平面向量的数量积", "3 周前"),
        ],
    },
    {
        "title": "每日打卡",
        "section": "今日学习",
        "rows": [
            ("今天已学习", "42 分钟"),
            ("今日目标", "60 分钟"),
            ("连续打卡", "12 天"),
            ("累计学习", "86 小时"),
            ("今日完成题数", "36 道"),
            ("明日提醒", "19:30"),
        ],
    },
]

# 双栏类
TITLES_TWO_COL = [
    "阅读理解练习（一）",
    "英语语法专项练习",
    "物理计算题（A 卷）",
    "两栏排版测试",
    "期末复习材料",
    "函数与几何综合练习",
    "Unit 5 Vocabulary and Grammar",
    "选择题专项训练",
    "实验报告：测量物体的密度",
    "化学方程式练习",
    "古诗文默写与鉴赏",
    "每日一练（第 12 天）",
]

LEFT_POOL = [
    "已知函数 f(x)=2x+1",
    "求 f(3) 的值。",
    "质量为 2 kg 的物体",
    "受到 10 N 的水平拉力",
    "求物体在 3 s 内的位移",
    "阅读下面的文字。",
    "作者表达了怎样的情感？",
    "电流表应与用电器串联",
    "电压表应与用电器并联",
    "计算 3/4 + 1/6 的值",
    "已知 a=3, b=4",
    "请将句子翻译成英文。",
    "实验中应控制哪些变量？",
    "匀速直线运动的速度公式",
    "写出该反应的化学方程式",
    "分析此句在文中的作用",
]

RIGHT_POOL = [
    "Vocabulary Review",
    "photosynthesis 光合作用",
    "The results are shown below.",
    "Answer the questions.",
    "Chapter 4 Exercises",
    "1. 2. 3. 4. 5.",
    "√2 ≈ 1.414",
    "π ≈ 3.1416",
    "a^2 + b^2 = c^2",
    "True or False",
    "Fill in the blanks.",
    "Choose the best answer.",
    "Unit 5 Grammar",
    "Date: 2025-03-14",
    "Read and match.",
    "Total: 20 points",
]

# 公式类：普通字体 + 纯文本记号（不用 LaTeX）
TITLES_FORMULA = [
    "数学公式练习（一）",
    "公式识别测试 A",
    "一元二次方程",
    "函数与导数公式",
    "三角函数公式复习",
    "积分与根式",
    "初中数学公式汇总",
    "指数与对数运算",
    "物理公式练习",
    "数列公式",
    "概率与统计公式",
    "公式默写（第 3 组）",
]

FORMULA_SUBTITLES = [
    "请写出下列公式的结果",
    "姓名：李华    得分：95",
    "完成下列计算，写出必要步骤",
    "公式专项训练（限时 20 分钟）",
    "注意：结果保留两位小数",
    "请直接写出答案",
]

FORMULA_LINES = [
    "f(x) = x^2 + 1",
    "1/2 + 1/3 = 5/6",
    "√(a^2 + b^2)",
    "∫ 2x dx = x^2 + C",
    "a_n = a_1 + (n - 1)d",
    "x^2 - 5x + 6 = 0",
    "sin^2 θ + cos^2 θ = 1",
    "(x + 1)(x - 1) = x^2 - 1",
    "√2 ≈ 1.414",
    "∫_0^1 x dx = 1/2",
    "S = πr^2",
    "x = (-b ± √(b^2 - 4ac)) / 2a",
    "lim(x→0) sin x / x = 1",
    "log_2 8 = 3",
    "3/4 ÷ 2/5 = 15/8",
    "a^2 + b^2 = c^2",
    "1.5 × 10^3 = 1500",
    "y = 2x + 1, 当 x = 3 时 y = 7",
]

# 表格类：表格模板 + 表格后的简答题
TABLE_TEMPLATES = [
    {
        "caption": "表 1 实验数据记录",
        "header": ["实验编号", "质量/g", "体积/cm^3"],
        "rows": [["1", "78.2", "10.0"], ["2", "156.4", "20.0"], ["3", "234.6", "30.0"], ["4", "312.8", "40.0"]],
    },
    {
        "caption": "表 2 期中考试成绩",
        "header": ["姓名", "语文", "数学", "英语"],
        "rows": [["李华", "112", "135", "128"], ["王芳", "105", "121", "132"], ["张伟", "118", "140", "125"], ["刘洋", "98", "116", "120"]],
    },
    {
        "caption": "表 3 本周课程安排",
        "header": ["时间", "周一", "周二"],
        "rows": [["08:00", "数学", "英语"], ["10:00", "物理", "化学"], ["14:00", "语文", "生物"], ["16:00", "自习", "体育"]],
    },
    {
        "caption": "表 4 元素信息",
        "header": ["元素", "符号", "原子序数"],
        "rows": [["氢", "H", "1"], ["氦", "He", "2"], ["碳", "C", "6"], ["氧", "O", "8"]],
    },
    {
        "caption": "表 5 物理量单位",
        "header": ["物理量", "符号", "单位"],
        "rows": [["长度", "l", "m"], ["质量", "m", "kg"], ["时间", "t", "s"], ["速度", "v", "m/s"]],
    },
    {
        "caption": "表 6 实验记录",
        "header": ["次数", "温度/℃", "时间/s"],
        "rows": [["1", "20.5", "30"], ["2", "25.0", "45"], ["3", "28.5", "60"]],
    },
    {
        "caption": "表 7 体育测试成绩",
        "header": ["项目", "成绩", "等级"],
        "rows": [["立定跳远", "2.35 m", "优秀"], ["50 米跑", "7.8 s", "良好"], ["引体向上", "12 个", "良好"]],
    },
    {
        "caption": "表 8 阅读记录",
        "header": ["日期", "书目", "页数"],
        "rows": [["03-11", "《骆驼祥子》", "32"], ["03-12", "《边城》", "28"], ["03-13", "《活着》", "45"]],
    },
    {
        "caption": "表 9 单词表",
        "header": ["英文", "词性", "中文"],
        "rows": [["absorb", "v.", "吸收"], ["benefit", "n.", "好处"], ["contain", "v.", "包含"], ["decline", "v.", "下降"]],
    },
    {
        "caption": "表 10 化学反应",
        "header": ["反应物", "生成物", "条件"],
        "rows": [["氢气 + 氧气", "水", "点燃"], ["碳 + 氧气", "二氧化碳", "点燃"], ["碳酸钙", "氧化钙 + 二氧化碳", "高温"]],
    },
    {
        "caption": "表 11 购物清单",
        "header": ["商品", "单价/元", "数量"],
        "rows": [["笔记本", "8.50", "3"], ["中性笔", "3.00", "10"], ["文件夹", "12.00", "2"]],
    },
    {
        "caption": "表 12 学习计划",
        "header": ["时间", "科目", "任务"],
        "rows": [["周一", "数学", "函数专题"], ["周三", "物理", "力学复习"], ["周五", "英语", "完形填空"]],
    },
    {
        "caption": "表 13 温度记录",
        "header": ["时刻", "室内/℃", "室外/℃"],
        "rows": [["08:00", "18.5", "12.0"], ["12:00", "22.0", "19.5"], ["18:00", "20.5", "15.0"]],
    },
    {
        "caption": "表 14 电路参数",
        "header": ["元件", "电压/V", "电流/A"],
        "rows": [["电阻 R1", "6.0", "0.30"], ["电阻 R2", "9.0", "0.45"], ["电源", "12.0", "0.60"]],
    },
]

TABLE_QUESTIONS = [
    "根据表中数据，计算物体的密度。",
    "分析表中数据的变化规律。",
    "计算这组数据的平均值。",
    "补全表格中缺少的数据。",
    "根据表格回答下面的问题。",
    "表中哪一组数据的误差最大？",
    "请写出实验的结论。",
    "比较两次实验结果的差异。",
    "画出数据对应的折线图。",
    "计算质量与体积的比值。",
    "说明实验需要控制的变量。",
    "根据记录完成填空。",
]

TABLE_FOOTERS = [
    "请把答案填写在答题卡上。",
    "结果保留两位小数。",
    "第 1 页 共 1 页",
]

# ---------------------------------------------------------------------------
# 各类别内容构造
# ---------------------------------------------------------------------------


def build_clear_content(rng: random.Random, compact: bool = False):
    """构造“清晰页面”的行内容：[(text, style), ...]。

    style 取值：title / header / body / option / footer。
    """
    entries = []
    entries.append((rng.choice(TITLES_CLEAR), "title"))
    entries.append((rng.choice(HEADERS_CLEAR), "header"))

    for text in rng.sample(CHINESE_STEMS, 2):
        entries.append((text, "body"))

    english = rng.sample(ENGLISH_LINES, 1 if compact else rng.randint(1, 2))
    for text in english:
        entries.append((text, "body"))

    numbers = rng.sample(NUMBER_LINES, 1 if compact else rng.randint(1, 2))
    for text in numbers:
        entries.append((text, "body"))

    question, options = rng.choice(MC_SETS)
    entries.append((question, "body"))
    for letter, option in zip("ABCD", options):
        entries.append(("%s. %s" % (letter, option), "option"))

    entries.append((rng.choice(FOOTERS_CLEAR), "footer"))
    return entries


def render_text_page(rng, entries, *, compact=False, darken=False, blur_radius=0.0, tilt_angle=0.0):
    """渲染“白底黑字”的试卷页（clear_printed / low_light / blur / tilt 共用）。"""
    image = Image.new("RGB", (CANVAS_W, CANVAS_H), (255, 255, 255))
    draw = ImageDraw.Draw(image)

    if compact:
        margin, y = 108, 168
        title_size, header_size = 34, 22
        anchor_body, anchor_option, anchor_footer = 27, 26, 20
        body_gap, option_indent = 1.55, 28
    else:
        margin, y = 72, 104
        title_size, header_size = 42, 25
        anchor_body, anchor_option, anchor_footer = 32, 31, 22
        body_gap, option_indent = 1.62, 38

    max_width = CANVAS_W - 2 * margin

    for text, style in entries:
        if style == "title":
            draw_line(draw, 0, y, text, "cjk_bold", title_size, INK, CANVAS_W, align="center")
            y += int(title_size * 1.55)
        elif style == "header":
            draw_line(draw, margin, y, text, "cjk", header_size, INK_SOFT, max_width)
            y += int(header_size * 1.6)
            draw.line((margin, y, CANVAS_W - margin, y), fill=(178, 178, 178), width=2)
            y += 28
        elif style == "option":
            draw_line(draw, margin + option_indent, y, text, "cjk", anchor_option, INK, max_width - option_indent)
            y += int(anchor_option * body_gap)
        elif style == "footer":
            y += int(anchor_footer * 1.2)
            draw_line(draw, 0, y, text, "cjk", anchor_footer, INK_FOOTER, CANVAS_W, align="center")
            y += int(anchor_footer * 1.6)
        else:
            kind = body_kind(rng, text)
            size = draw_line(draw, margin, y, text, kind, anchor_body, INK, max_width)
            y += int(size * body_gap)

    if darken:
        image = _darken(image, rng)
    if blur_radius:
        image = image.filter(ImageFilter.GaussianBlur(radius=blur_radius))
    if tilt_angle:
        image = image.rotate(tilt_angle, resample=Image.BICUBIC, expand=False, fillcolor=(255, 255, 255))
    return image


def _darken(image: Image.Image, rng: random.Random) -> Image.Image:
    """整体压暗（低光模拟）：亮度衰减 + 冷色偏移 + 光照不均 + 传感器噪声 + 轻微模糊。

    所有随机量都来自传入的 rng（含 np 噪声的独立子种子），保证重复运行结果一致。
    """
    factor = rng.uniform(0.22, 0.38)
    blue_gain = rng.uniform(1.00, 1.08)
    arr = np.asarray(image, dtype=np.float32)
    arr[..., 0] *= factor * 0.97
    arr[..., 1] *= factor
    arr[..., 2] *= factor * blue_gain

    # 光照不均：从随机一角向外轻微衰减（0.84 ~ 1.0）
    yy, xx = np.mgrid[0 : arr.shape[0], 0 : arr.shape[1]]
    cx, cy = rng.uniform(0.0, 1.0), rng.uniform(0.0, 1.0)
    dist = np.sqrt(((xx / arr.shape[1]) - cx) ** 2 + ((yy / arr.shape[0]) - cy) ** 2)
    dist = dist / dist.max()
    arr *= (1.0 - 0.16 * dist)[..., None]

    # 传感器噪声（独立子种子，确定性）：先在 1/4 分辨率生成再放大，
    # 避免逐像素随机噪声让 PNG 体积膨胀（低光图能从 ~1.6 MB 降到 ~0.2 MB）
    noise_rng = np.random.default_rng(rng.getrandbits(32))
    sigma = rng.uniform(3.0, 6.0)
    small_h = max(1, arr.shape[0] // 4)
    small_w = max(1, arr.shape[1] // 4)
    coarse = noise_rng.normal(0.0, sigma, (small_h, small_w))
    coarse_img = Image.fromarray(np.clip(coarse + 128.0, 0, 255).astype(np.uint8), "L")
    coarse_img = coarse_img.resize((arr.shape[1], arr.shape[0]), Image.BICUBIC)
    noise = np.asarray(coarse_img, dtype=np.float32) - 128.0
    arr += noise[..., None]

    arr = np.clip(arr, 0, 255).astype(np.uint8)
    image = Image.fromarray(arr, "RGB")
    return image.filter(ImageFilter.GaussianBlur(radius=0.6))


# ---------------------------------------------------------------------------
# 截图类
# ---------------------------------------------------------------------------


def render_screenshot_page(rng: random.Random):
    """模拟手机 App 截图：浅色/深色主题 + 状态栏 + 标题栏 + 卡片列表 + 底部标签栏。"""
    screen = rng.choice(SCREENS)
    dark = rng.random() < 0.45

    if dark:
        palette = {
            "bg": (13, 13, 17),
            "card": (27, 27, 33),
            "header": (21, 21, 26),
            "search": (22, 22, 28),
            "ink": (236, 236, 240),
            "sub": (148, 151, 160),
            "accent": (88, 160, 255),
            "tab": (17, 17, 21),
            "line": (52, 52, 60),
        }
    else:
        palette = {
            "bg": (241, 242, 245),
            "card": (255, 255, 255),
            "header": (255, 255, 255),
            "search": (233, 236, 241),
            "ink": (28, 30, 34),
            "sub": (108, 114, 124),
            "accent": (46, 106, 229),
            "tab": (255, 255, 255),
            "line": (224, 227, 232),
        }

    image = Image.new("RGB", (CANVAS_W, CANVAS_H), palette["bg"])
    draw = ImageDraw.Draw(image)
    lines = []

    # 状态栏：时间 + 装饰性信号/电量图标（图标不含文字）
    draw.text((46, 20), "9:41", font=get_font("latin_bold", 25), fill=palette["ink"])
    lines.append("9:41")
    for i in range(4):
        height = 8 + i * 4
        draw.rectangle((706 + i * 11, 42 - height, 713 + i * 11, 42), fill=palette["ink"])
    draw.rounded_rectangle((776, 22, 846, 44), radius=6, outline=palette["ink"], width=2)
    draw.rectangle((780, 26, 834, 40), fill=palette["ink"])

    # 顶部标题栏
    draw.rounded_rectangle((28, 74, 872, 214), radius=26, fill=palette["header"])
    title = screen["title"]
    draw.text((62, 118), title, font=get_font("cjk_bold", 42), fill=palette["ink"])
    lines.append(title)

    y = 246
    if rng.random() < 0.42:
        draw.rounded_rectangle((40, y, 860, y + 70), radius=18, fill=palette["search"])
        draw.ellipse((70, y + 24, 94, y + 48), outline=palette["sub"], width=3)
        draw.line((90, y + 44, 100, y + 54), fill=palette["sub"], width=3)
        draw.text((120, y + 22), "搜索题目或知识点", font=get_font("cjk", 25), fill=palette["sub"])
        lines.append("搜索题目或知识点")
        y += 92

    section = screen["section"]
    draw.text((58, y), section, font=get_font("cjk_bold", 25), fill=palette["sub"])
    lines.append(section)
    y += 48

    tab_top = CANVAS_H - 116
    rows = rng.sample(screen["rows"], k=min(len(screen["rows"]), rng.randint(5, 7)))
    gap = 12
    available = tab_top - 22 - y
    card_h = int((available - (len(rows) - 1) * gap) / len(rows))
    card_h = max(84, min(card_h, 102))
    while len(rows) > 3 and y + len(rows) * (card_h + gap) - gap > tab_top - 22:
        rows.pop()

    for primary, secondary in rows:
        draw.rounded_rectangle((40, y, 860, y + card_h), radius=16, fill=palette["card"])
        draw.rounded_rectangle((40, y + 18, 47, y + card_h - 18), radius=3, fill=palette["accent"])
        draw_line(draw, 76, y + 16, primary, "cjk", 30, palette["ink"], 744)
        draw_line(draw, 76, y + 54, secondary, "cjk", 23, palette["sub"], 744)
        lines.append(primary)
        lines.append(secondary)
        y += card_h + gap

    # 底部标签栏
    draw.rectangle((0, tab_top, CANVAS_W, CANVAS_H), fill=palette["tab"])
    draw.line((0, tab_top, CANVAS_W, tab_top), fill=palette["line"], width=2)
    draw.rounded_rectangle(
        (CANVAS_W // 2 - 72, CANVAS_H - 16, CANVAS_W // 2 + 72, CANVAS_H - 8),
        radius=4,
        fill=palette["line"],
    )
    active = rng.randrange(len(SCREEN_TAB_LABELS))
    xs = [CANVAS_W * 0.125, CANVAS_W * 0.375, CANVAS_W * 0.625, CANVAS_W * 0.875]
    for index, label in enumerate(SCREEN_TAB_LABELS):
        font = get_font("cjk", 24)
        width = draw.textlength(label, font=font)
        color = palette["accent"] if index == active else palette["sub"]
        draw.text((xs[index] - width / 2, tab_top + 30), label, font=font, fill=color)
        lines.append(label)

    return image, lines


# ---------------------------------------------------------------------------
# 双栏类
# ---------------------------------------------------------------------------


def render_two_column_page(rng: random.Random):
    """双栏排版：标题 + 左右两栏，每条视觉行先左后右，GT 按视觉行序排列。"""
    image = Image.new("RGB", (CANVAS_W, CANVAS_H), (255, 255, 255))
    draw = ImageDraw.Draw(image)

    title = rng.choice(TITLES_TWO_COL)
    draw_line(draw, 0, 88, title, "cjk_bold", 40, INK, CANVAS_W, align="center")
    lines = [title]

    n_rows = rng.randint(10, 13)
    left = rng.sample(LEFT_POOL, n_rows)
    right = rng.sample(RIGHT_POOL, n_rows)

    col_width = 360
    x_left, x_right = 60, 480
    body = rng.randint(27, 30)
    row_gap = 1.85
    y = 212

    draw.line((450, 200, 450, 1064), fill=(205, 205, 205), width=2)
    for i in range(n_rows):
        height = 0
        size = draw_line(draw, x_left, y, left[i], "cjk", body, INK, col_width)
        height = max(height, size * row_gap)
        lines.append(left[i])

        kind = "cjk" if has_cjk(right[i]) else "latin"
        size = draw_line(draw, x_right, y, right[i], kind, body - 2, INK, col_width)
        height = max(height, size * row_gap)
        lines.append(right[i])

        y += height

    footer = "第 1 页 共 1 页"
    draw_line(draw, 0, 1104, footer, "cjk", 22, INK_FOOTER, CANVAS_W, align="center")
    lines.append(footer)
    return image, lines


# ---------------------------------------------------------------------------
# 公式类
# ---------------------------------------------------------------------------


def render_formula_page(rng: random.Random):
    """公式页：标题 + 可选副标题 + 居中公式行（纯文本记号，Cambria/雅黑渲染）。"""
    image = Image.new("RGB", (CANVAS_W, CANVAS_H), (255, 255, 255))
    draw = ImageDraw.Draw(image)

    title = rng.choice(TITLES_FORMULA)
    draw_line(draw, 0, 96, title, "cjk_bold", 40, INK, CANVAS_W, align="center")
    lines = [title]

    y = 200
    if rng.random() < 0.72:
        subtitle = rng.choice(FORMULA_SUBTITLES)
        draw_line(draw, 0, 172, subtitle, "cjk", 26, INK_SOFT, CANVAS_W, align="center")
        lines.append(subtitle)
        y = 248

    formulas = rng.sample(FORMULA_LINES, rng.randint(7, 9))
    size = rng.randint(32, 37)
    for formula in formulas:
        kind = "cjk" if has_cjk(formula) else "math"
        actual = draw_line(draw, 60, y, formula, kind, size, INK, CANVAS_W - 120, align="center")
        lines.append(formula)
        y += int(actual * 2.05)

    return image, lines


# ---------------------------------------------------------------------------
# 表格类
# ---------------------------------------------------------------------------


def render_grid_table(rng: random.Random):
    """带网格线的简单表格 + 表后简答题。"""
    template = rng.choice(TABLE_TEMPLATES)
    caption = template["caption"]
    header = template["header"]
    rows = template["rows"]
    n_cols = len(header)

    image = Image.new("RGB", (CANVAS_W, CANVAS_H), (255, 255, 255))
    draw = ImageDraw.Draw(image)

    draw_line(draw, 0, 108, caption, "cjk_bold", 34, INK, CANVAS_W, align="center")
    lines = [caption]

    size = rng.randint(27, 29)
    pad = 22
    widths = None
    while size >= 17:
        font = get_font("cjk", size)
        natural = []
        for col in range(n_cols):
            cells = [header[col]] + [row[col] for row in rows]
            natural.append(max(draw.textlength(cell, font=font) for cell in cells) + 2 * pad)
        total = sum(natural)
        if total <= 782:
            extra = (782 - total) / n_cols
            widths = [w + extra for w in natural]
            break
        size -= 1
    if widths is None:
        size = 17
        widths = [782.0 / n_cols] * n_cols

    x0 = (CANVAS_W - sum(widths)) / 2.0
    row_h = rng.randint(66, 78)
    header_h = row_h + 4
    table_h = header_h + row_h * len(rows)
    y0 = max(196, (CANVAS_H - table_h) // 2 - 70)
    total_w = sum(widths)

    # 表头底色
    draw.rectangle((x0, y0, x0 + total_w, y0 + header_h), fill=(232, 236, 242))
    # 数据行底色（隔行浅色）
    for row_index in range(len(rows)):
        top = y0 + header_h + row_index * row_h
        if row_index % 2 == 1:
            draw.rectangle((x0, top, x0 + total_w, top + row_h), fill=(250, 250, 252))

    def draw_cell(text, x, y, w, h, bold=False):
        kind = "cjk_bold" if bold else "cjk"
        draw_line(
            draw,
            x + 8,
            y + (h - size) / 2 - 3,
            text,
            kind,
            size,
            INK,
            w - 16,
            align="center",
            min_size=12,
        )

    for col in range(n_cols):
        cell_x = x0 + sum(widths[:col])
        draw_cell(header[col], cell_x, y0, widths[col], header_h, bold=True)
    for row_index, row in enumerate(rows):
        top = y0 + header_h + row_index * row_h
        for col in range(n_cols):
            cell_x = x0 + sum(widths[:col])
            draw_cell(row[col], cell_x, top, widths[col], row_h)

    # 网格线
    grid_color = (60, 60, 60)
    draw.rectangle((x0, y0, x0 + total_w, y0 + table_h), outline=grid_color, width=3)
    for row_index in range(1, len(rows) + 1):
        line_y = y0 + header_h + row_index * row_h
        draw.line((x0, line_y, x0 + total_w, line_y), fill=grid_color, width=2)
    for col in range(1, n_cols):
        line_x = x0 + sum(widths[:col])
        draw.line((line_x, y0, line_x, y0 + table_h), fill=grid_color, width=2)

    # 表格 GT 行
    lines.extend([" ".join(header)])
    for row in rows:
        lines.append(" ".join(row))

    # 表后简答题
    question = rng.choice(TABLE_QUESTIONS)
    question_y = y0 + table_h + 64
    draw_line(draw, 70, question_y, question, "cjk", 31, INK, CANVAS_W - 140)
    lines.append(question)

    footer = rng.choice(TABLE_FOOTERS)
    draw_line(draw, 0, 1108, footer, "cjk", 22, INK_FOOTER, CANVAS_W, align="center")
    lines.append(footer)
    return image, lines


def render_option_table(rng: random.Random):
    """表格类的选择题样式：标题 + 题面 + A/B/C/D 选项（带浅色面板）。"""
    question, options = rng.choice(MC_SETS)
    caption = "选择题训练（第 %d 组）" % rng.randint(1, 30)
    footer = rng.choice(TABLE_FOOTERS)

    image = Image.new("RGB", (CANVAS_W, CANVAS_H), (255, 255, 255))
    draw = ImageDraw.Draw(image)

    draw_line(draw, 0, 110, caption, "cjk_bold", 36, INK, CANVAS_W, align="center")
    lines = [caption]

    panel = (60, 190, 840, 900)
    draw.rounded_rectangle(panel, radius=22, fill=(248, 249, 252), outline=(175, 180, 190), width=2)

    q_size = draw_line(draw, 92, 228, question, "cjk", 32, INK, 700)
    lines.append(question)

    y = 228 + int(q_size * 1.9)
    for letter, option in zip("ABCD", options):
        draw.text((100, y), "%s." % letter, font=get_font("cjk_bold", 30), fill=(40, 40, 40))
        draw_line(draw, 150, y, option, "cjk", 30, INK, 640)
        lines.append("%s. %s" % (letter, option))
        y += 94

    draw_line(draw, 0, 1108, footer, "cjk", 22, INK_FOOTER, CANVAS_W, align="center")
    lines.append(footer)
    return image, lines


def render_table_page(rng: random.Random):
    if rng.random() < 0.62:
        return render_grid_table(rng)
    return render_option_table(rng)


# ---------------------------------------------------------------------------
# 生成入口
# ---------------------------------------------------------------------------


def generate_image(category: str, index: int, rng: random.Random):
    """生成一张图片，返回 (PIL.Image, ground truth 行列表)。"""
    if category == "screenshot":
        return render_screenshot_page(rng)
    if category == "two_column":
        return render_two_column_page(rng)
    if category == "formula":
        return render_formula_page(rng)
    if category == "table":
        return render_table_page(rng)

    # clear_printed / low_light / blur / tilt：同一套试卷内容，不同图像退化
    # tilt 用紧凑排版（字号更小、左右边距更大），旋转后内容不会被裁切
    entries = build_clear_content(rng, compact=False)
    lines = [text for text, _style in entries]
    if category == "blur":
        radius = BLUR_RADII[index % len(BLUR_RADII)]
        image = render_text_page(rng, entries, blur_radius=radius)
    elif category == "tilt":
        magnitude = TILT_ANGLES[index % len(TILT_ANGLES)]
        angle = magnitude * rng.choice((-1, 1))
        image = render_text_page(rng, entries, compact=True, tilt_angle=angle)
    elif category == "low_light":
        image = render_text_page(rng, entries, darken=True)
    else:
        image = render_text_page(rng, entries)
    return image, lines


def main() -> int:
    try:
        check_fonts()
    except FileNotFoundError as exc:
        print("[gen][ERROR] 字体检查失败：\n%s" % exc, file=sys.stderr)
        return 2

    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    # 清理旧的 png，保证重复运行后目录内恰好是本次生成的 300 张
    for old_png in sorted(DATASET_DIR.glob("*.png")):
        old_png.unlink()

    rng = random.Random(SEED)
    records = []
    for category in CATEGORY_ORDER:
        count = CATEGORY_COUNTS[category]
        for index in range(count):
            image, lines = generate_image(category, index, rng)
            image_id = "%s_%03d" % (category, index + 1)
            filename = "%s.png" % image_id
            image.save(DATASET_DIR / filename, format="PNG")
            records.append(
                {
                    "id": image_id,
                    "category": category,
                    "image": "dataset/%s" % filename,
                    "lines": lines,
                    "text": "\n".join(lines),
                }
            )
        print("[gen] %-13s %3d 张" % (category, count))

    if len(records) != 300:
        print("[gen][ERROR] 记录数不是 300：%d" % len(records), file=sys.stderr)
        return 1

    payload = json.dumps(records, ensure_ascii=False, indent=2) + "\n"
    GROUND_TRUTH_PATH.write_text(payload, encoding="utf-8")

    print("[gen] 总计 %d 张图片 -> %s" % (len(records), DATASET_DIR))
    print("[gen] ground truth -> %s (seed=%d)" % (GROUND_TRUTH_PATH, SEED))
    return 0


if __name__ == "__main__":
    sys.exit(main())
