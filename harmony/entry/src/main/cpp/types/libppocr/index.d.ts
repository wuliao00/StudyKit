/**
 * PP-OCRv6 端侧引擎 NAPI 声明（libppocr.so）。
 * 返回值为 JSON 字符串：{ ok, engine, lines:[{text,score,x0,y0,x1,y1}], detMs, recMs, totalMs, ... }
 */
export interface PpOcrInitResult {
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

export interface PpOcrLine {
  text: string;
  score: number;
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export interface PpOcrResult {
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

declare const ppocr: {
  version(): string;
  init(detFd: number, detOffset: number, detLen: number, recFd: number, recOffset: number,
    recLen: number, dict: string): string;
  probe(): string;
  recognize(rgba: ArrayBuffer, width: number, height: number, linePass?: boolean): Promise<string>;
  recognizeSync(rgba: ArrayBuffer, width: number, height: number, linePass?: boolean): string;
  abi(): string;
  release(): string;
};

export default ppocr;
