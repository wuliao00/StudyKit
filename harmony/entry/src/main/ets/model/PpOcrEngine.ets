import common from '@ohos.app.ability.common';
import image from '@ohos.multimedia.image';
import util from '@ohos.util';
import hilog from '@ohos.hilog';
import { BusinessError } from '@ohos.base';
import ppocr from 'libppocr.so';

const TAG: string = 'PPOCR';
const DOMAIN: number = 0x3201;

export interface PpOcrLine {
  text: string;
  score: number;
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export interface PpOcrInitPayload {
  ok: boolean;
  ortVersion?: string;
  apiVersion?: number;
  detMs?: number;
  recMs?: number;
  detIn?: string;
  detOut?: string;
  recIn?: string;
  recOut?: string;
  dictSize?: number;
  classes?: number;
  error?: string;
}

export interface PpOcrRecognizePayload {
  ok: boolean;
  engine?: string;
  lines?: PpOcrLine[];
  lineCount?: number;
  detBoxes?: number;
  retried?: number;
  detMs?: number;
  recMs?: number;
  totalMs?: number;
  imageWidth?: number;
  imageHeight?: number;
  error?: string;
}

export interface PpOcrOutcome {
  ok: boolean;
  engine: string;
  message: string;
  lines: string[];
  boxes: PpOcrLine[];
  detMs: number;
  recMs: number;
  totalMs: number;
  retried: number;
  error: string;
}

export interface PpOcrEvalImage {
  id: string;
  category: string;
  fileName: string;
  expected: string;
}

export interface PpOcrEvalItem {
  id: string;
  category: string;
  ok: boolean;
  engine: string;
  text: string;
  lines: string[];
  lineCount: number;
  cer: number;
  detMs: number;
  recMs: number;
  totalMs: number;
  error: string;
}

export interface PpOcrEvalSummary {
  ok: boolean;
  engine: string;
  ortVersion: string;
  dictSize: number;
  classes: number;
  initMs: number;
  totalMs: number;
  avgCer: number;
  imageCount: number;
  emptyCount: number;
  items: PpOcrEvalItem[];
  startedAt: string;
  error: string;
}

// 固定评测集：10 张（clear_printed 5 + formula 5），来自 tools/ocr_eval/dataset。
export const PPOCR_EVAL_IMAGES: PpOcrEvalImage[] = [
  { id: 'clear_printed_001', category: 'clear_printed', fileName: 'clear_printed_001.png', expected: '' },
  { id: 'clear_printed_002', category: 'clear_printed', fileName: 'clear_printed_002.png', expected: '' },
  { id: 'clear_printed_003', category: 'clear_printed', fileName: 'clear_printed_003.png', expected: '' },
  { id: 'clear_printed_004', category: 'clear_printed', fileName: 'clear_printed_004.png', expected: '' },
  { id: 'clear_printed_005', category: 'clear_printed', fileName: 'clear_printed_005.png', expected: '' },
  { id: 'formula_001', category: 'formula', fileName: 'formula_001.png', expected: '' },
  { id: 'formula_002', category: 'formula', fileName: 'formula_002.png', expected: '' },
  { id: 'formula_003', category: 'formula', fileName: 'formula_003.png', expected: '' },
  { id: 'formula_004', category: 'formula', fileName: 'formula_004.png', expected: '' },
  { id: 'formula_005', category: 'formula', fileName: 'formula_005.png', expected: '' }
];

interface GroundTruthEntry {
  id: string;
  category: string;
  text: string;
  lines: string[];
}

export class PpOcrEngine {
  private static singleton: PpOcrEngine | null = null;
  // 验收用故障注入：aa start --ps ppocr off 时强制让 PP-OCR 初始化失败，
  // 用于证明「引擎不可用 → 明确原因 + HMS/手动粘贴回退」路径，不影响正常启动。
  private static forcedDisabled: string = '';

  static forceDisable(reason: string): void {
    PpOcrEngine.forcedDisabled = reason;
  }

  static forceDisableReason(): string {
    return PpOcrEngine.forcedDisabled;
  }

  private context: common.UIAbilityContext | null = null;
  private ready: boolean = false;
  private initializing: boolean = false;
  private initFailed: boolean = false;
  private errorText: string = '';
  private info: PpOcrInitPayload | null = null;
  private initMs: number = 0;
  private dictSize: number = 0;
  private classes: number = 0;

  // 把 ArkTS 异常（BusinessError/Error/普通对象）转成可读文本；JSON.stringify(Error) 会是 "{}"，不能直接用。
  static describeError(e: Object): string {
    if (e === null || e === undefined) return 'unknown error';
    let codeText: string = '';
    let msgText: string = '';
    try {
      const be = e as BusinessError;
      if (be.code !== undefined && be.code !== null) codeText = 'code=' + be.code.toString();
      if (be.message !== undefined && be.message !== null && be.message.length > 0) msgText = be.message;
    } catch (x) {}
    if (msgText.length === 0) {
      try { msgText = String(e); } catch (x) { msgText = 'unknown error'; }
    }
    return (codeText.length > 0 ? codeText + ' ' : '') + msgText;
  }

  static get(): PpOcrEngine {
    if (PpOcrEngine.singleton === null) {
      PpOcrEngine.singleton = new PpOcrEngine();
    }
    return PpOcrEngine.singleton;
  }

  isReady(): boolean {
    return this.ready;
  }

  initError(): string {
    return this.errorText;
  }

  initInfo(): PpOcrInitPayload | null {
    return this.info;
  }

  isInitializing(): boolean {
    return this.initializing;
  }

  // 引擎初始化（幂等）：rawfile fd 直接交给 native，避免 31MB 模型复制到沙箱。
  async init(context: common.UIAbilityContext): Promise<boolean> {
    if (PpOcrEngine.forcedDisabled.length > 0) {
      this.initFailed = true;
      this.errorText = '测试开关强制禁用：' + PpOcrEngine.forcedDisabled;
      return false;
    }
    if (this.ready) return true;
    if (this.initFailed) return false;
    if (this.initializing) {
      while (this.initializing) {
        await new Promise<void>((resolve: () => void) => setTimeout(resolve, 50));
        if (this.ready) return true;
        if (this.initFailed) return false;
      }
    }
    this.initializing = true;
    this.context = context;
    this.initFailed = false;
    this.errorText = '';
    const started: number = Date.now();
    try {
      const rm = context.resourceManager;
      const det = await rm.getRawFd('models/det_ppocrv6_small.onnx');
      const rec = await rm.getRawFd('models/rec_ppocrv6_small.onnx');
      const dictBytes: Uint8Array = await rm.getRawFileContent('models/ppocr_keys_v6.txt');
      const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
      const dictText: string = decoder.decodeToString(dictBytes);
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 50));
      const raw: string = ppocr.init(det.fd, det.offset, det.length, rec.fd, rec.offset, rec.length, dictText);
      try { await rm.closeRawFd('models/det_ppocrv6_small.onnx'); } catch (e) {}
      try { await rm.closeRawFd('models/rec_ppocrv6_small.onnx'); } catch (e) {}
      const parsed: PpOcrInitPayload = JSON.parse(raw) as PpOcrInitPayload;
      this.info = parsed;
      if (parsed.ok) {
        this.ready = true;
        this.dictSize = parsed.dictSize === undefined ? 0 : parsed.dictSize;
        this.classes = parsed.classes === undefined ? 0 : parsed.classes;
        this.initMs = Date.now() - started;
        return true;
      }
      this.initFailed = true;
      this.errorText = parsed.error === undefined ? 'native init returned not ok' : parsed.error;
      return false;
    } catch (e) {
      this.initFailed = true;
      this.errorText = 'native/OTR 初始化失败：' + JSON.stringify(e);
      return false;
    } finally {
      this.initializing = false;
    }
  }

  engineLabel(): string {
    if (this.ready) {
      return 'PP-OCR · 本机推理' + (this.info !== null && this.info.ortVersion !== undefined ? ' · ORT ' + this.info.ortVersion : '');
    }
    if (this.initFailed) return 'PP-OCR 不可用：' + this.errorText;
    return 'PP-OCR 未初始化';
  }

  private parseOutcome(raw: string): PpOcrOutcome {
    const parsed: PpOcrRecognizePayload = JSON.parse(raw) as PpOcrRecognizePayload;
    const lines: string[] = [];
    const boxes: PpOcrLine[] = [];
    const src: PpOcrLine[] = parsed.lines === undefined ? [] : parsed.lines;
    for (let i = 0; i < src.length; i++) {
      lines.push(src[i].text);
      boxes.push(src[i]);
    }
    return {
      ok: parsed.ok,
      engine: 'PP-OCR',
      message: parsed.ok ? ('PP-OCR 识别完成：' + lines.length.toString() + ' 行') : ('PP-OCR 识别失败：' + (parsed.error === undefined ? '未知错误' : parsed.error)),
      lines: lines,
      boxes: boxes,
      detMs: parsed.detMs === undefined ? 0 : parsed.detMs,
      recMs: parsed.recMs === undefined ? 0 : parsed.recMs,
      totalMs: parsed.totalMs === undefined ? 0 : parsed.totalMs,
      retried: parsed.retried === undefined ? 0 : parsed.retried,
      error: parsed.error === undefined ? '' : parsed.error
    };
  }

  // PixelMap -> 紧凑 RGBA_8888 ArrayBuffer，交给 native 做 BGR/归一化/缩放。
  // 相机/相册解码出的 PixelMap 可能有 rowStride 填充或 BGRA/RGB_565 等格式：
  // - bytes == w*h*4：直接读；BGRA 时交换 R/B；
  // - bytes >  w*h*4 且能整除高度：说明有行填充，按 stride 重新打包；
  // - 其它（RGB_565/NV21/F16 等）：抛带 cause 的明确错误，由上层回退并展示原因。
  async pixelMapToRgba(pixelMap: image.PixelMap): Promise<ArrayBuffer> {
    const info: image.ImageInfo = pixelMap.getImageInfoSync();
    const width: number = info.size.width;
    const height: number = info.size.height;
    if (width <= 0 || height <= 0) {
      throw new Error('invalid pixelMap size ' + width.toString() + 'x' + height.toString());
    }
    const packed: number = width * height * 4;
    let bytes: number = packed;
    try {
      bytes = pixelMap.getPixelBytesNumber();
    } catch (e) {
      bytes = packed;
    }
    const format: number = info.pixelFormat === undefined ? -1 : info.pixelFormat;
    hilog.info(DOMAIN, TAG, 'pixelMap %{public}dx%{public}d format=%{public}d bytes=%{public}d packed=%{public}d',
      width, height, format, bytes, packed);
    if (bytes === packed) {
      const buffer: ArrayBuffer = new ArrayBuffer(packed);
      await pixelMap.readPixelsToBuffer(buffer);
      if (format === image.PixelMapFormat.BGRA_8888) {
        const px = new Uint8Array(buffer);
        for (let i = 0; i + 3 < px.length; i += 4) {
          const r = px[i];
          px[i] = px[i + 2];
          px[i + 2] = r;
        }
      }
      return buffer;
    }
    if (bytes > packed && bytes % height === 0) {
      const stride: number = bytes / height;
      const raw: ArrayBuffer = new ArrayBuffer(bytes);
      await pixelMap.readPixelsToBuffer(raw);
      const src: Uint8Array = new Uint8Array(raw);
      const dst: ArrayBuffer = new ArrayBuffer(packed);
      const out: Uint8Array = new Uint8Array(dst);
      const rowBytes: number = width * 4;
      for (let y = 0; y < height; y++) {
        out.set(src.subarray(y * stride, y * stride + rowBytes), y * rowBytes);
      }
      hilog.info(DOMAIN, TAG, 'pixelMap repacked stride=%{public}d rowBytes=%{public}d', stride, rowBytes);
      return dst;
    }
    throw new Error('unsupported pixel format: format=' + format.toString() + ' bytes=' + bytes.toString()
      + ' expected=' + packed.toString());
  }

  async recognize(pixelMap: image.PixelMap, linePass: boolean): Promise<PpOcrOutcome> {
    if (!this.ready) {
      return {
        ok: false, engine: 'PP-OCR', message: 'PP-OCR 未初始化', lines: [], boxes: [],
        detMs: 0, recMs: 0, totalMs: 0, retried: 0, error: 'engine not ready'
      };
    }
    const started: number = Date.now();
    try {
      const info: image.ImageInfo = pixelMap.getImageInfoSync();
      const rgba: ArrayBuffer = await this.pixelMapToRgba(pixelMap);
      hilog.info(DOMAIN, TAG, 'recognize start %{public}dx%{public}d buffer=%{public}d linePass=%{public}s',
        info.size.width, info.size.height, rgba.byteLength, linePass ? '1' : '0');
      let raw: string = '';
      try {
        raw = await ppocr.recognize(rgba, info.size.width, info.size.height, linePass);
      } catch (e) {
        // NAPI async work 不可用时降级为同步调用（仍可完成识别，只是会占 UI 线程）。
        hilog.warn(DOMAIN, TAG, 'async recognize reject: %{public}s; fallback to sync',
          PpOcrEngine.describeError(e));
        raw = ppocr.recognizeSync(rgba, info.size.width, info.size.height, linePass);
      }
      let outcome: PpOcrOutcome = this.parseOutcome(raw);
      if (!outcome.ok && outcome.error.indexOf('async work') >= 0) {
        outcome = this.parseOutcome(ppocr.recognizeSync(rgba, info.size.width, info.size.height, linePass));
      }
      hilog.info(DOMAIN, TAG, 'recognize done ok=%{public}s lines=%{public}d totalMs=%{public}d elapsed=%{public}d error=%{public}s',
        outcome.ok ? '1' : '0', outcome.lines.length, outcome.totalMs, Date.now() - started, outcome.error);
      return outcome;
    } catch (e) {
      const detail: string = PpOcrEngine.describeError(e);
      hilog.error(DOMAIN, TAG, 'recognize failed before native: %{public}s', detail);
      return {
        ok: false, engine: 'PP-OCR', message: 'PP-OCR 调用异常：' + detail, lines: [], boxes: [],
        detMs: 0, recMs: 0, totalMs: Date.now() - started, retried: 0, error: detail
      };
    }
  }

  private async decodeRawImage(context: common.UIAbilityContext, fileName: string): Promise<image.PixelMap> {
    const bytes: Uint8Array = await context.resourceManager.getRawFileContent('eval/' + fileName);
    const exact: ArrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    const source: image.ImageSource = image.createImageSource(exact);
    const opts: image.DecodingOptions = { desiredPixelFormat: image.PixelMapFormat.RGBA_8888 };
    const pm: image.PixelMap = await source.createPixelMap(opts);
    try { await source.release(); } catch (e) {}
    return pm;
  }

  // 自检：固定 10 张 rawfile 评测图 det+rec，返回逐图结果与平均 CER（口径同 evaluate.py）。
  async runSelfTest(context: common.UIAbilityContext, onProgress?: (done: number, total: number) => void): Promise<PpOcrEvalSummary> {
    const startedAt: string = new Date().toISOString();
    const summary: PpOcrEvalSummary = {
      ok: false, engine: 'PP-OCR', ortVersion: this.info !== null && this.info.ortVersion !== undefined ? this.info.ortVersion : '',
      dictSize: this.dictSize, classes: this.classes, initMs: this.initMs, totalMs: 0, avgCer: 1,
      imageCount: 0, emptyCount: 0, items: [], startedAt: startedAt, error: ''
    };
    const inited: boolean = await this.init(context);
    if (!inited) {
      summary.error = 'PP-OCR 初始化失败：' + this.errorText;
      return summary;
    }
    summary.ortVersion = this.info !== null && this.info.ortVersion !== undefined ? this.info.ortVersion : '';
    // 初始化完成后回填引擎元数据（构造 summary 时引擎还没初始化，直接取会得到 0）。
    summary.dictSize = this.dictSize;
    summary.classes = this.classes;
    summary.initMs = this.initMs;
    let truth: GroundTruthEntry[] = [];
    try {
      const gtBytes: Uint8Array = await context.resourceManager.getRawFileContent('eval/ground_truth_10.json');
      const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
      truth = JSON.parse(decoder.decodeToString(gtBytes)) as GroundTruthEntry[];
    } catch (e) {
      summary.error = '读取 ground truth 失败：' + JSON.stringify(e);
    }
    const started: number = Date.now();
    let cerSum: number = 0;
    let emptyCount: number = 0;
    for (let i = 0; i < PPOCR_EVAL_IMAGES.length; i++) {
      const spec: PpOcrEvalImage = PPOCR_EVAL_IMAGES[i];
      let pm: image.PixelMap | null = null;
      const item: PpOcrEvalItem = {
        id: spec.id, category: spec.category, ok: false, engine: 'PP-OCR', text: '', lines: [],
        lineCount: 0, cer: 1, detMs: 0, recMs: 0, totalMs: 0, error: ''
      };
      try {
        pm = await this.decodeRawImage(context, spec.fileName);
        const out: PpOcrOutcome = await this.recognize(pm, true);
        item.ok = out.ok;
        item.engine = out.engine;
        item.text = out.lines.join('\n');
        item.lines = out.lines;
        item.lineCount = out.lines.length;
        item.detMs = out.detMs;
        item.recMs = out.recMs;
        item.totalMs = out.totalMs;
        item.error = out.error;
        if (!out.ok) item.error = out.error;
      } catch (e) {
        item.error = '解码/识别异常：' + JSON.stringify(e);
      } finally {
        if (pm !== null) {
          try { await pm.release(); } catch (e) {}
        }
      }
      let expected: string = '';
      for (let t = 0; t < truth.length; t++) {
        if (truth[t].id === spec.id) expected = truth[t].text;
      }
      item.cer = PpOcrEngine.cer(expected, item.text);
      if (item.text.trim().length === 0) emptyCount++;
      cerSum += item.cer;
      summary.items.push(item);
      if (onProgress !== undefined) onProgress(i + 1, PPOCR_EVAL_IMAGES.length);
    }
    summary.imageCount = PPOCR_EVAL_IMAGES.length;
    summary.emptyCount = emptyCount;
    summary.avgCer = summary.imageCount === 0 ? 1 : cerSum / summary.imageCount;
    summary.totalMs = Date.now() - started;
    summary.ok = summary.emptyCount === 0 && summary.error.length === 0;
    return summary;
  }

  // 与 tools/ocr_eval/evaluate.py normalize_cer 对齐：NFKC、去零宽、中文标点->ASCII、忽略 ^/_、去空白。
  static normalizeForCer(text: string): string {
    let out: string = text;
    try { out = text.normalize('NFKC'); } catch (e) { out = text; }
    out = out.replace(/[\uFEFF\u200B\u200C\u200D\u2060]/g, '');
    const map: Map<string, string> = new Map<string, string>();
    map.set('。', '.'); map.set('，', ','); map.set('、', ','); map.set('；', ';');
    map.set('：', ':'); map.set('！', '!'); map.set('？', '?'); map.set('（', '(');
    map.set('）', ')'); map.set('【', '['); map.set('】', ']'); map.set('“', '"');
    map.set('”', '"'); map.set('‘', '\''); map.set('’', '\''); map.set('—', '-');
    map.set('–', '-'); map.set('…', '...'); map.set('～', '~'); map.set('·', '');
    map.set('　', ''); map.set('−', '-');
    let mapped: string = '';
    for (let i = 0; i < out.length; i++) {
      const ch: string = out.charAt(i);
      const rep: string | undefined = map.get(ch);
      mapped += (rep === undefined) ? ch : rep;
    }
    mapped = mapped.replace(/\^/g, '').replace(/_/g, '');
    let result: string = '';
    for (let i = 0; i < mapped.length; i++) {
      const ch: string = mapped.charAt(i);
      if (!/\s/.test(ch)) result += ch;
    }
    return result;
  }

  static levenshtein(a: string, b: string): number {
    const n: number = a.length;
    const m: number = b.length;
    if (n === 0) return m;
    if (m === 0) return n;
    let prev: number[] = [];
    for (let j = 0; j <= m; j++) prev.push(j);
    for (let i = 1; i <= n; i++) {
      const cur: number[] = [i];
      for (let j = 1; j <= m; j++) {
        const cost: number = a.charAt(i - 1) === b.charAt(j - 1) ? 0 : 1;
        const del: number = prev[j] + 1;
        const ins: number = cur[j - 1] + 1;
        const sub: number = prev[j - 1] + cost;
        cur.push(Math.min(Math.min(del, ins), sub));
      }
      prev = cur;
    }
    return prev[m];
  }

  static cer(expected: string, actual: string): number {
    const gt: string = PpOcrEngine.normalizeForCer(expected);
    const ocr: string = PpOcrEngine.normalizeForCer(actual);
    if (gt.length === 0) return 0;
    return PpOcrEngine.levenshtein(gt, ocr) / gt.length;
  }
}
