import image from '@ohos.multimedia.image';
import photoAccessHelper from '@ohos.file.photoAccessHelper';

declare function requireNapi(moduleName: string): HmsOcrModule;

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
  '当前工程使用 OpenHarmony SDK，未集成 HMS Core OCR（@hms.ai.ocr.textRecognition），已自动切换为手动粘贴文本导入；图库选图功能仍可用。';

export interface OcrResult {
  ok: boolean;
  message: string;
  lines: string[];
  engine: string;
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
    const source: image.ImageSource = image.createImageSource(uri);
    const pixelMap: image.PixelMap = await source.createPixelMap();
    return pixelMap;
  }

  // 运行期加载 HMS OCR：OpenHarmony API15 编译期没有该模块，但设备若装了 HMS Core，
  // 可通过 requireNapi 动态取到；取不到就走手动粘贴。
  private static loadHmsOcr(): HmsOcrModule | null {
    try {
      return requireNapi(OcrImport.HMS_OCR_MODULE);
    } catch (e) {
      return null;
    }
  }

  // 选图 + 解码：图片链路可用的证明；HMS OCR 不可用时返回 ok=false 让页面走手动粘贴
  static async recognizeFromImage(uri: string): Promise<OcrResult> {
    if (uri.length === 0) {
      return { ok: false, message: '没有选择图片', lines: [], engine: '' };
    }
    let pixelMap: image.PixelMap | null = null;
    try {
      pixelMap = await OcrImport.loadPixelMap(uri);
    } catch (e) {
      return {
        ok: false,
        message: '图片读取失败，请重新选择图片或手动粘贴文本。',
        lines: [],
        engine: ''
      };
    }
    // 图片已经成功解码为 PixelMap；尝试运行期 HMS OCR
    try {
      const ocr: HmsOcrModule | null = OcrImport.loadHmsOcr();
      if (ocr !== null && ocr.textRecognition !== undefined) {
        const result: HmsOcrResult = await ocr.textRecognition.recognizeText(pixelMap);
        const lines: string[] = OcrImport.splitLines(result.value);
        if (lines.length > 0) {
          return { ok: true, message: 'HMS OCR 识别完成，共 ' + lines.length.toString() + ' 行', lines: lines, engine: 'HMS OCR' };
        }
      }
    } catch (e) {
      // 模块存在但识别失败，走手动粘贴
    }
    try {
      await pixelMap.release();
    } catch (e) {
      // 忽略释放异常
    }
    return { ok: false, message: OCR_FALLBACK_HINT, lines: [], engine: '' };
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
