import image from '@ohos.multimedia.image';
import photoAccessHelper from '@ohos.file.photoAccessHelper';
import fs from '@ohos.file.fs';
import textRecognition from '@hms.ai.ocr.textRecognition';
import { OcrCleaner, CleanedOcr } from './OcrCleaner';
import hilog from '@ohos.hilog';
import { PpOcrEngine, PpOcrOutcome } from './PpOcrEngine';

// 交给 PP-OCR native 的图像长边上限：大截图放大 2x 后 RGBA 缓冲会到 60MB+，
// 既容易触发 readPixelsToBuffer/native 拷贝失败，也没有精度收益（det 内部还会缩到 736 短边）。
export const OCR_MAX_SIDE: number = 2000;

// HMS Core 文字识别模块名（HarmonyOS SDK 集成 HMS Core 后才可用）：
//   '@hms.ai.ocr.textRecognition'
// 当前构建使用 OpenHarmony SDK 5.0.3.135（API 15），SDK 内没有该模块（已用
// E:/dev/ohos-sdk/sdk/15/ets/api、kits 目录扫描确认），因此这里采用「选图 +
// 解码 + 手动粘贴兜底」的可编译实现：
//   - photoAccessHelper 选图、image.createImageSource → PixelMap 已实现并可编译；
//   - 识别文本交给用户手动粘贴/编辑（页面保留 TextArea），保证功能可用；
//   - 集成 HMS Core 后，只需在 recognizeFromImage 里把 PixelMap 传给
//     textRecognition.recognizeText(pixelMap)，再返回 result.value 即可：
//       import { textRecognition } from '@hms.ai.ocr.textRecognition';
//       const result = await textRecognition.recognizeText(pixelMap);
//       return { ok: true, message: '识别完成', lines: splitLines(result.value), engine: 'HMS OCR' };

export const OCR_FALLBACK_HINT: string =
  'HMS OCR 暂未返回文字，已保留图库选图，可手动粘贴题目文本导入；不会伪造识别结果。';

// 逐行二次识别的两个硬上限：命中任意一个立即停止，剩余行保留首次识别结果。
export const OCR_REFINE_MAX_LINES: number = 12;
export const OCR_REFINE_BUDGET_MS: number = 12000;
export const OCR_SUSPICIOUS_HINT: string = '识别可能不准，可逐行修改';

export interface OcrOptions {
  cropTop: number;
  cropBottom: number;
  cropLeft: number;
  cropRight: number;
  scale: number;
  linePass: boolean;
  enhance: boolean;
}

interface OcrPoint {
  x: number;
  y: number;
}

interface OcrLineBox {
  value: string;
  cornerPoints: OcrPoint[];
}

interface OcrBlockLike {
  lines: OcrLineBox[];
}

interface OcrResultLike {
  value: string;
  blocks: OcrBlockLike[];
}

export interface OcrResult {
  ok: boolean;
  message: string;
  lines: string[];
  rawLines: string[];
  filtered: number;
  suspicious: number;
  engine: string;
}

// 逐行二次识别的结果：lines 始终包含全部行；attempted/stoppedByTime 用于向页面说明耗时情况。
interface OcrRefineOutcome {
  lines: string[];
  attempted: number;
  stoppedByTime: boolean;
  cancelled: boolean;
}

export interface QuestionDraft {
  stem: string;
  options: string[];
  answerIndex: number;
  explanation: string;
}

interface NapiHost {
  requireNapi?: (name: string) => HmsOcrModule;
}

interface HmsOcrApi {
  recognizeText(pixelMap: image.PixelMap): Promise<HmsOcrResult>;
}

interface HmsOcrModule {
  textRecognition?: HmsOcrApi;
}

interface HmsOcrResult {
  value: string;
}

export class OcrImport {
  static readonly HMS_OCR_MODULE: string = '@hms.ai.ocr.textRecognition';

  // 拉起系统图库选择一张图片，返回图片 uri；取消选择返回空串
  static async pickSingleImage(): Promise<string> {
    const options = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 1;
    const picker = new photoAccessHelper.PhotoViewPicker();
    const result = await picker.select(options);
    if (result === null || result === undefined || result.photoUris === undefined
      || result.photoUris === null || result.photoUris.length === 0) {
      return '';
    }
    return result.photoUris[0];
  }

  static async loadPixelMap(uri: string): Promise<image.PixelMap> {
    // 图库 URI 在部分系统版本上不能直接被 createImageSource(uri) 打开，
    // 再尝试用文件描述符方式打开；两种都失败才回到手动粘贴。
    // 统一解码成 RGBA_8888：native 侧按 RGBA 字节序取像素，避免不同解码路径的格式差异。
    const decodeOpts: image.DecodingOptions = { desiredPixelFormat: image.PixelMapFormat.RGBA_8888 };
    try {
      const sourceByUri: image.ImageSource = image.createImageSource(uri);
      return await sourceByUri.createPixelMap(decodeOpts);
    } catch (e1) {
      const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      try {
        const sourceByFd: image.ImageSource = image.createImageSource(file.fd);
        return await sourceByFd.createPixelMap(decodeOpts);
      } finally {
        fs.closeSync(file);
      }
    }
  }

  // 扫描件/截图常偏灰；轻度提升对比度，不改文字结构。
  private static async enhanceContrast(pixelMap: image.PixelMap): Promise<void> {
    const info: image.ImageInfo = pixelMap.getImageInfoSync();
    const pixels: number = info.size.width * info.size.height;
    if (pixels <= 0 || pixels > 40000000) return;
    const buffer: ArrayBuffer = new ArrayBuffer(pixels * 4);
    await pixelMap.readPixelsToBuffer(buffer);
    const bytes: Uint8Array = new Uint8Array(buffer);
    for (let i = 0; i + 3 < bytes.length; i += 4) {
      const r: number = bytes[i];
      const g: number = bytes[i + 1];
      const b: number = bytes[i + 2];
      const luma: number = 0.299 * r + 0.587 * g + 0.114 * b;
      let c: number = Math.round((luma - 128) * 1.22 + 128);
      if (c < 0) c = 0;
      if (c > 255) c = 255;
      bytes[i] = c;
      bytes[i + 1] = c;
      bytes[i + 2] = c;
    }
    await pixelMap.writeAllPixelsFromBuffer(buffer);
  }

  private static clamp01(value: number): number {
    if (value < 0) return 0;
    if (value > 0.45) return 0.45;
    return value;
  }

  // 先裁掉截图工具栏/页边，再放大；识别数学题/讲义时比整屏 OCR 稳很多。
  static async preparePixelMap(uri: string, options: OcrOptions): Promise<image.PixelMap> {
    const pixelMap: image.PixelMap = await OcrImport.loadPixelMap(uri);
    const top: number = OcrImport.clamp01(options.cropTop);
    const bottom: number = OcrImport.clamp01(options.cropBottom);
    const left: number = OcrImport.clamp01(options.cropLeft);
    const right: number = OcrImport.clamp01(options.cropRight);
    if (top + bottom < 0.9 && left + right < 0.9) {
      const info: image.ImageInfo = pixelMap.getImageInfoSync();
      const width: number = info.size.width;
      const height: number = info.size.height;
      const px: number = Math.floor(width * left);
      const py: number = Math.floor(height * top);
      const pw: number = Math.max(24, Math.floor(width * (1 - left - right)));
      const ph: number = Math.max(24, Math.floor(height * (1 - top - bottom)));
      const region: image.Region = { size: { width: pw, height: ph }, x: px, y: py };
      try { await pixelMap.crop(region); } catch (e) { /* 保持原图 */ }
    }
    // 先按上限算缩放：小图仍按用户选择放大（2x），大图（相册截图/照片）限制长边 ≤2000。
    const afterCrop: image.ImageInfo = pixelMap.getImageInfoSync();
    const longSide: number = Math.max(afterCrop.size.width, afterCrop.size.height);
    let scale: number = Math.max(1, Math.min(3, options.scale));
    if (longSide > 0 && longSide * scale > OCR_MAX_SIDE) {
      scale = OCR_MAX_SIDE / longSide;
    }
    if (scale > 1.01 || scale < 0.99) {
      try {
        await pixelMap.scale(scale, scale);
      } catch (e) {
        hilog.warn(0x3201, 'OCRPREP', 'scale(%{public}s) failed: %{public}s', scale.toFixed(3),
          PpOcrEngine.describeError(e));
      }
    }
    if (options.enhance) {
      try { await OcrImport.enhanceContrast(pixelMap); } catch (e) { /* 保持原图 */ }
    }
    return pixelMap;
  }

  // API26/HarmonyOS 构建：编译期静态集成 HMS Core OCR。
  private static async hmsRecognize(pixelMap: image.PixelMap): Promise<string> {
    const result = await textRecognition.recognizeText({ pixelMap: pixelMap });
    return result.value;
  }

  private static async hmsRecognizeDetailed(pixelMap: image.PixelMap): Promise<OcrResultLike> {
    const result = await textRecognition.recognizeText({ pixelMap: pixelMap });
    return result as OcrResultLike;
  }

  private static boxesFromResult(result: OcrResultLike): OcrLineBox[] {
    const boxes: OcrLineBox[] = [];
    for (let b = 0; b < result.blocks.length; b++) {
      const block: OcrBlockLike = result.blocks[b];
      for (let l = 0; l < block.lines.length; l++) {
        boxes.push(block.lines[l]);
      }
    }
    return boxes;
  }

  private static boxRegion(box: OcrLineBox, width: number, height: number): image.Region {
    let minX: number = width; let minY: number = height; let maxX: number = 0; let maxY: number = 0;
    for (let i = 0; i < box.cornerPoints.length; i++) {
      const p: OcrPoint = box.cornerPoints[i];
      if (p.x < minX) minX = p.x;
      if (p.y < minY) minY = p.y;
      if (p.x > maxX) maxX = p.x;
      if (p.y > maxY) maxY = p.y;
    }
    const padX: number = Math.max(6, Math.floor((maxX - minX) * 0.04));
    const padY: number = Math.max(5, Math.floor((maxY - minY) * 0.25));
    const x: number = Math.max(0, Math.floor(minX) - padX);
    const y: number = Math.max(0, Math.floor(minY) - padY);
    const w: number = Math.max(12, Math.min(width - x, Math.ceil(maxX - minX) + padX * 2));
    const h: number = Math.max(12, Math.min(height - y, Math.ceil(maxY - minY) + padY * 2));
    return { size: { width: w, height: h }, x: x, y: y };
  }

  // 逐行二次识别：最多 12 行 / 总时限 12s；到达任一上限立即停止，剩余行保留首次结果。
  // 任何异常都释放当前行的 PixelMap；cancel 返回 true 时也立即停止。
  private static async refineByLines(pixelMap: image.PixelMap, result: OcrResultLike,
    fallbackLines: string[], cancel?: () => boolean): Promise<OcrRefineOutcome> {
    const boxes: OcrLineBox[] = OcrImport.boxesFromResult(result);
    const outcome: OcrRefineOutcome = {
      lines: fallbackLines.slice(),
      attempted: 0,
      stoppedByTime: false,
      cancelled: false
    };
    if (boxes.length === 0) return outcome;
    const info: image.ImageInfo = pixelMap.getImageInfoSync();
    const width: number = info.size.width;
    const height: number = info.size.height;
    const startAt: number = Date.now();
    const out: string[] = [];
    const maxLines: number = Math.min(boxes.length, OCR_REFINE_MAX_LINES);
    for (let i = 0; i < maxLines; i++) {
      if (OcrImport.isCancelled(cancel)) {
        outcome.cancelled = true;
        break;
      }
      if (Date.now() - startAt >= OCR_REFINE_BUDGET_MS) {
        outcome.stoppedByTime = true;
        break;
      }
      const box: OcrLineBox = boxes[i];
      const original: string = box.value.trim();
      if (original.length < 2 || original.length > 160) {
        if (original.length > 0) out.push(original);
        continue;
      }
      const region: image.Region = OcrImport.boxRegion(box, width, height);
      if (region.size.width < 20 || region.size.height < 12) {
        out.push(original);
        continue;
      }
      let refined: string = original;
      let linePm: image.PixelMap | null = null;
      try {
        linePm = await pixelMap.clone();
        await linePm.crop(region);
        await linePm.scale(2, 2);
        if (OcrImport.isCancelled(cancel)) {
          outcome.cancelled = true;
          break;
        }
        const text: string = (await OcrImport.hmsRecognize(linePm)).trim();
        if (text.length > 0 && text.length >= Math.floor(original.length * 0.65)) {
          refined = text;
        }
        outcome.attempted++;
      } catch (e) {
        // 失败行保留原行，不影响整体识别
        refined = original;
      } finally {
        if (linePm !== null) {
          try { await linePm.release(); } catch (e) {}
        }
      }
      out.push(refined);
    }
    if (outcome.cancelled) {
      outcome.lines = out;
      return outcome;
    }
    // 达到行数/时间上限后，未 refine 的行保持首次识别结果，避免漏内容
    for (let i = out.length; i < fallbackLines.length; i++) out.push(fallbackLines[i]);
    outcome.lines = out;
    return outcome;
  }

  // 选图 + 解码 + OCR；cancel 返回 true 时尽快停止。无论成败/取消都释放 PixelMap。
  static async recognizeFromImage(uri: string, options?: OcrOptions,
    cancel?: () => boolean): Promise<OcrResult> {
    if (uri.length === 0) {
      return { ok: false, message: '没有选择图片', lines: [], rawLines: [], filtered: 0, suspicious: 0, engine: '' };
    }
    const opts: OcrOptions = options === undefined ? { cropTop: 0, cropBottom: 0, cropLeft: 0, cropRight: 0, scale: 1, linePass: true, enhance: true } : options;
    let pixelMap: image.PixelMap | null = null;
    try {
      try {
        pixelMap = await OcrImport.preparePixelMap(uri, opts);
      } catch (e) {
        const detail: string = PpOcrEngine.describeError(e);
        hilog.error(0x3201, 'OCRPATH', 'preparePixelMap failed: %{public}s', detail);
        return {
          ok: false,
          message: '图片读取失败（' + detail + '），请重新选择图片或手动粘贴文本。',
          lines: [], rawLines: [], filtered: 0, suspicious: 0,
          engine: ''
        };
      }
      if (pixelMap === null || OcrImport.isCancelled(cancel)) {
        return { ok: false, message: '已取消识别', lines: [], rawLines: [], filtered: 0, suspicious: 0, engine: '' };
      }
      const pm: image.PixelMap = pixelMap;
      // 路线 B：默认走端侧 PP-OCR（native ORT，完全离线）；仅当引擎不可用才回退 HMS。
      const engine: PpOcrEngine = PpOcrEngine.get();
      let ppError: string = '';
      if (engine.isReady()) {
        try {
          const pp: PpOcrOutcome = await engine.recognize(pm, opts.linePass);
          if (pp.ok && pp.lines.length > 0) {
            if (OcrImport.isCancelled(cancel)) {
              return { ok: false, message: '已取消识别', lines: [], rawLines: [], filtered: 0, suspicious: 0, engine: 'PP-OCR' };
            }
            const cleanedPp: CleanedOcr = OcrCleaner.clean(pp.lines);
            const suspiciousPp: number = OcrImport.countSuspiciousLines(cleanedPp.usable);
            return {
              ok: true,
              message: 'PP-OCR 识别完成：保留 ' + cleanedPp.usable.length.toString() + ' 行，过滤 '
                + cleanedPp.filtered.toString() + ' 行工具栏/噪声（det ' + pp.detMs.toString() + ' ms / rec '
                + pp.recMs.toString() + ' ms / 合计 ' + pp.totalMs.toString() + ' ms'
                + (pp.retried > 0 ? '，低分重识别 ' + pp.retried.toString() + ' 行' : '') + '）'
                + (suspiciousPp > 0 ? '；可疑行 ' + suspiciousPp.toString() + ' 行（' + OCR_SUSPICIOUS_HINT + '）' : ''),
              lines: cleanedPp.usable,
              rawLines: cleanedPp.all,
              filtered: cleanedPp.filtered,
              suspicious: suspiciousPp,
              engine: 'PP-OCR'
            };
          }
          ppError = pp.error.length > 0 ? pp.error : ('返回 0 行（lineCount=' + pp.lines.length.toString() + '）');
        } catch (e) {
          const detail: string = PpOcrEngine.describeError(e);
          hilog.error(0x3201, 'OCRPATH', 'PP-OCR gallery path failed: %{public}s', detail);
          ppError = 'PP-OCR 调用异常：' + detail;
        }
      } else {
        ppError = engine.initError().length > 0 ? engine.initError() : 'PP-OCR 未初始化';
      }
      // 回退：HMS Core Vision OCR（原有能力，不删除）。
      try {
        const detail: OcrResultLike = await OcrImport.hmsRecognizeDetailed(pm);
        let rawLines: string[] = OcrImport.splitLines(detail.value);
        let refineNote: string = '';
        if (opts.linePass && rawLines.length > 1) {
          const outcome: OcrRefineOutcome = await OcrImport.refineByLines(pm, detail, rawLines, cancel);
          if (outcome.cancelled) {
            return { ok: false, message: '已取消识别', lines: [], rawLines: [], filtered: 0, suspicious: 0, engine: '' };
          }
          rawLines = outcome.lines;
          if (outcome.attempted > 0) {
            refineNote = '，逐行二次识别 ' + outcome.attempted.toString() + ' 行（上限 '
              + OCR_REFINE_MAX_LINES.toString() + ' 行 / '
              + Math.floor(OCR_REFINE_BUDGET_MS / 1000).toString() + ' 秒）';
          }
          if (outcome.stoppedByTime) {
            refineNote += '，已达时间上限，其余行保留首次结果';
          }
        }
        if (OcrImport.isCancelled(cancel)) {
          return { ok: false, message: '已取消识别', lines: [], rawLines: [], filtered: 0, suspicious: 0, engine: '' };
        }
        if (rawLines.length > 0) {
          const cleaned: CleanedOcr = OcrCleaner.clean(rawLines);
          const suspicious: number = OcrImport.countSuspiciousLines(cleaned.usable);
          return {
            ok: true,
            message: 'HMS 回退识别完成（PP-OCR 不可用：' + ppError + '）：保留 '
              + cleaned.usable.length.toString() + ' 行，过滤 ' + cleaned.filtered.toString()
              + ' 行工具栏/噪声' + refineNote
              + (suspicious > 0 ? '；可疑行 ' + suspicious.toString() + ' 行（' + OCR_SUSPICIOUS_HINT + '）' : ''),
            lines: cleaned.usable,
            rawLines: cleaned.all,
            filtered: cleaned.filtered,
            suspicious: suspicious,
            engine: 'HMS OCR'
          };
        }
      } catch (e) {
        // 识别失败，走手动粘贴
      }
      const reason: string = ppError.length > 0 ? ('PP-OCR 不可用：' + ppError + '；') : '';
      return { ok: false, message: reason + OCR_FALLBACK_HINT, lines: [], rawLines: [], filtered: 0, suspicious: 0, engine: '' };
    } finally {
      // 成功、失败、取消任一分支都释放 PixelMap，避免解码占用的内存泄漏
      if (pixelMap !== null) {
        try { await pixelMap.release(); } catch (e) {}
      }
    }
  }

  private static isCancelled(cancel?: () => boolean): boolean {
    if (cancel === undefined) return false;
    try {
      return cancel();
    } catch (e) {
      return true;
    }
  }

  // 长度异常（极短碎片/超长粘连）或连续异常符号，视为「可疑行」，仅提示不改解析逻辑。
  static isSuspiciousLine(line: string): boolean {
    const s: string = line.trim();
    if (s.length === 0) return false;
    if (s.length < 2 || s.length > 120) return true;
    if (/[?？]{3,}|[□■▯▮]{2,}|[_#*~^|\\\/]{3,}|[·．.]{4,}/.test(s)) return true;
    let run: number = 1;
    for (let i = 1; i < s.length; i++) {
      const ch: string = s.charAt(i);
      if (ch === s.charAt(i - 1) && !OcrImport.isPlainChar(ch)) {
        run++;
        if (run >= 4) return true;
      } else {
        run = 1;
      }
    }
    return false;
  }

  static countSuspiciousLines(lines: string[]): number {
    let count: number = 0;
    for (let i = 0; i < lines.length; i++) {
      if (OcrImport.isSuspiciousLine(lines[i])) count++;
    }
    return count;
  }

  private static isPlainChar(ch: string): boolean {
    if (ch.length === 0) return false;
    return /[0-9A-Za-z\u4e00-\u9fa5]/.test(ch);
  }

  static splitLines(text: string): string[] {
    const result: string[] = [];
    if (text === null || text === undefined || text.length === 0) {
      return result;
    }
    const raw: string[] = text.split('\n');
    for (let i = 0; i < raw.length; i++) {
      const line: string = raw[i].trim();
      if (line.length > 0) {
        result.push(line);
      }
    }
    return result;
  }

  private static isOptionLine(line: string): boolean {
    if (line.length < 3) {
      return false;
    }
    const first: string = line.substring(0, 1).toUpperCase();
    if (first < 'A' || first > 'H') {
      return false;
    }
    const second: string = line.substring(1, 2);
    return second === '.' || second === '．' || second === '、' || second === ':' || second === '：'
      || second === ')' || second === '）' || second === ' ';
  }

  private static stripOptionPrefix(line: string): string {
    let start: number = 1;
    while (start < line.length) {
      const ch: string = line.substring(start, start + 1);
      if (ch === '.' || ch === '．' || ch === '、' || ch === ':' || ch === '：'
        || ch === ')' || ch === '）' || ch === ' ') {
        start++;
      } else {
        break;
      }
    }
    const text: string = line.substring(start).trim();
    return text.length > 0 ? text : line.trim();
  }

  // 把一段文本（OCR 结果或手动粘贴）启发式拆成 题干 / 选项 / 答案 / 解析
  static draftFromText(rawText: string): QuestionDraft {
    const lines: string[] = OcrImport.splitLines(rawText);
    const draft: QuestionDraft = { stem: '', options: [], answerIndex: 0, explanation: '' };
    if (lines.length === 0) {
      return draft;
    }
    const optionStart: number = OcrImport.findOptionStart(lines);
    const explanationStart: number = OcrImport.findExplanationStart(lines);
    if (optionStart < 0) {
      let stemParts: string[] = lines;
      if (explanationStart >= 0) {
        stemParts = lines.slice(0, explanationStart);
        draft.explanation = lines.slice(explanationStart).join('\n').replace(/^解析[：:]?\s*/, '');
      }
      draft.stem = stemParts.join(' ');
      return draft;
    }
    draft.stem = lines.slice(0, optionStart).join(' ');
    let optionEnd: number = lines.length;
    if (explanationStart >= 0) {
      optionEnd = explanationStart;
      draft.explanation = lines.slice(explanationStart).join('\n').replace(/^解析[：:]?\s*/, '');
    }
    for (let i = optionStart; i < optionEnd; i++) {
      if (OcrImport.isOptionLine(lines[i])) {
        draft.options.push(OcrImport.stripOptionPrefix(lines[i]));
      }
    }
    draft.answerIndex = OcrImport.findAnswerIndex(lines, draft.options.length);
    return draft;
  }

  private static findOptionStart(lines: string[]): number {
    for (let i = 0; i < lines.length; i++) {
      if (OcrImport.isOptionLine(lines[i])) {
        return i;
      }
    }
    return -1;
  }

  private static findExplanationStart(lines: string[]): number {
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].indexOf('解析') >= 0 || lines[i].indexOf('解答') >= 0) {
        return i;
      }
    }
    return -1;
  }

  private static findAnswerIndex(lines: string[], optionCount: number): number {
    for (let i = 0; i < lines.length; i++) {
      const line: string = lines[i];
      if (line.indexOf('答案') >= 0) {
        for (let j = 0; j < line.length; j++) {
          const ch: string = line.substring(j, j + 1).toUpperCase();
          if (ch >= 'A' && ch <= 'H') {
            const index: number = ch.charCodeAt(0) - 'A'.charCodeAt(0);
            if (index < optionCount) {
              return index;
            }
          }
        }
      }
    }
    return 0;
  }
}
