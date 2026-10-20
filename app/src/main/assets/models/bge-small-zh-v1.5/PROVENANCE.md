# bge-small-zh-v1.5（ONNX INT8）来源与许可证

本目录是端侧语义交错（v3.1）随包下发的嵌入模型。**它不是本仓库的源码**，
在此仅作资产入库，许可证与著作权归上游作者。

## 文件

| 文件 | 字节数 | sha256 |
|---|---|---|
| `model_int8.onnx` | 23,903,394 | `b9837c19ce154ff0726d398ee77abbc03a7faf0476c6f93016c84e531be7ebb5` |
| `vocab.txt` | 109,540 | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c`（21128 行） |

两个 sha256 是 2026-10-20 从上游下载后在本机用 `sha256sum` 核出来的；
拷入 `app/src/main/assets/` 后再次核对，值相同。

## 来源

- 原模型：**BAAI/bge-small-zh-v1.5**，北京智源人工智能研究院（BAAI）。
  上游仓库：<https://huggingface.co/BAAI/bge-small-zh-v1.5>
- 本次下载的是其 **ONNX 转换版**：ModelScope 上的 `Xenova/bge-small-zh-v1.5`，
  <https://modelscope.cn/models/Xenova/bge-small-zh-v1.5>（转换方：Xenova，
  即 Transformers.js 的 ONNX 导出）。下载日期 2026-10-20。
  选用 ModelScope 而非 HuggingFace 的原因：下载当天本机访问 huggingface.co 不通，
  ModelScope 可达；两者是同一份权重的不同分发点。
- 该转换仓同时提供 `tokenizer.json`、`config.json` 等文件；本仓库只取
  `model_int8.onnx` 与 `vocab.txt` 两个运行时需要的文件，分词规则由
  `BertWordPieceTokenizer` 按 BERT WordPiece 复现（参数见下）。

## 许可证

**MIT License**。BAAI/bge-small-zh-v1.5 的模型卡在 §License 明确写的是 MIT，
允许商用与再分发；许可证原文见上游仓库页面（<https://huggingface.co/BAAI/bge-small-zh-v1.5>）
与 ModelScope 同名页面。随包下发时保留本文件即保留出处与许可声明。

## 运行时契约（实测，2026-10-20）

- 输入：`input_ids` / `attention_mask` / `token_type_ids`，均 `int64`，形状 `[1, seq]`。
- 输出：`last_hidden_state`，`float32`，形状 `[1, seq, 512]`。
- 池化：取 **CLS**（`last_hidden_state[0, 0]`）再 L2 归一化；
  本机 Python ORT 1.31 探针实测同文余弦 1.0000、近义句 0.9271、无关句 0.2809/0.3782。
- 分词（来自转换仓的 `tokenizer.json`）：BERT WordPiece，`do_lower_case=false`，
  `tokenize_chinese_chars=true`，中文按字切、标点单独成 token、最长匹配续片用 `##`，
  `[CLS]=101`、`[SEP]=102`、`[UNK]=100`、`[PAD]=0`。

## 体积

模型 23.9 MB（INT8 量化）。这是 v3.1 相对 v3.0 安装包增大的主要来源；
真机装机与冷启动读数在验收时另行记录，本文件不写未实测的数字。
