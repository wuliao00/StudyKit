import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';

// 本机 OCR 纠错记忆：用户改过的行下次优先复用，不联网、不上传。
export interface OcrCorrection {
  from: string;
  to: string;
  count: number;
  updatedAt: number;
}

interface CorrectionFile {
  version: number;
  items: OcrCorrection[];
}

export class OcrCorrectionStore {
  private static instance: OcrCorrectionStore | null = null;
  private items: OcrCorrection[] = [];
  private filePath: string = '';
  private ready: boolean = false;

  static get(): OcrCorrectionStore {
    if (OcrCorrectionStore.instance === null) {
      OcrCorrectionStore.instance = new OcrCorrectionStore();
    }
    return OcrCorrectionStore.instance;
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    this.filePath = context.filesDir + '/ocr_corrections.json';
    this.ready = true;
    try {
      if (fs.accessSync(this.filePath)) {
        const content: string = fs.readTextSync(this.filePath);
        const parsed: CorrectionFile = JSON.parse(content) as CorrectionFile;
        if (parsed !== null && parsed.items !== undefined) {
          this.items = parsed.items;
        }
      }
    } catch (e) {
      this.items = [];
    }
  }

  private async save(): Promise<void> {
    if (!this.ready || this.filePath.length === 0) return;
    try {
      const data: CorrectionFile = { version: 1, items: this.items };
      const file = fs.openSync(this.filePath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, JSON.stringify(data));
      fs.closeSync(file);
    } catch (e) {
      // 纠错记忆失败不影响主流程
    }
  }

  private ratio(a: string, b: string): number {
    if (a === b) return 1;
    const m: number = a.length; const n: number = b.length;
    if (m === 0 || n === 0) return 0;
    let prev: number[] = [];
    for (let j = 0; j <= n; j++) prev.push(j);
    for (let i = 1; i <= m; i++) {
      const cur: number[] = [i];
      for (let j = 1; j <= n; j++) {
        const cost: number = a.charAt(i - 1) === b.charAt(j - 1) ? 0 : 1;
        cur.push(Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost));
      }
      prev = cur;
    }
    return 1 - prev[n] / Math.max(m, n);
  }

  private bestMatch(line: string): OcrCorrection | null {
    const s: string = line.trim();
    if (s.length === 0) return null;
    let best: OcrCorrection | null = null;
    let bestScore: number = 0;
    for (let i = 0; i < this.items.length; i++) {
      const item: OcrCorrection = this.items[i];
      if (item.from === s) return item;
      const score: number = this.ratio(item.from, s);
      if (score >= 0.84 && score > bestScore) {
        bestScore = score;
        best = item;
      }
    }
    return best;
  }

  applyText(text: string): string {
    if (this.items.length === 0 || text.length === 0) return text;
    const lines: string[] = text.split('\n');
    const out: string[] = [];
    for (let i = 0; i < lines.length; i++) {
      const raw: string = lines[i];
      const hit: OcrCorrection | null = this.bestMatch(raw);
      out.push(hit === null ? raw : hit.to);
    }
    return out.join('\n');
  }

  applyLines(lines: string[]): string[] {
    const out: string[] = [];
    for (let i = 0; i < lines.length; i++) {
      const hit: OcrCorrection | null = this.bestMatch(lines[i]);
      out.push(hit === null ? lines[i] : hit.to);
    }
    return out;
  }

  // 把本次 OCR 行与用户最终编辑文本做行级对齐，学习可复用的修正。
  async learn(originalLines: string[], editedText: string): Promise<number> {
    const edited: string[] = editedText.split('\n').map((s: string) => s.trim()).filter((s: string) => s.length > 1);
    if (edited.length === 0) return 0;
    let learned: number = 0;
    const now: number = Date.now();
    for (let i = 0; i < originalLines.length; i++) {
      const from: string = originalLines[i].trim();
      if (from.length < 2) continue;
      let exact: boolean = false;
      let best: string = '';
      let bestScore: number = 0;
      for (let j = 0; j < edited.length; j++) {
        const to: string = edited[j];
        if (to === from) { exact = true; break; }
        const score: number = this.ratio(from, to);
        if (score > bestScore) { bestScore = score; best = to; }
      }
      if (exact || best.length === 0 || bestScore < 0.55 || bestScore > 0.995) continue;
      let found: boolean = false;
      for (let k = 0; k < this.items.length; k++) {
        if (this.items[k].from === from) {
          this.items[k].to = best;
          this.items[k].count = this.items[k].count + 1;
          this.items[k].updatedAt = now;
          found = true;
          break;
        }
      }
      if (!found) this.items.push({ from: from, to: best, count: 1, updatedAt: now });
      learned++;
    }
    if (learned > 0) {
      this.items = this.items.slice(-400);
      await this.save();
    }
    return learned;
  }

  count(): number { return this.items.length; }

  clear(): void { this.items = []; this.save(); }
}
