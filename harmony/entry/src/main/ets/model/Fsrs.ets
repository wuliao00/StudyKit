// FSRS v6 纯公式（从 Android FsrsModel.kt 逐项移植；21 参默认值）
export const FSRS_DEFAULT_PARAMS: number[] = [
  0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
  1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
  1.8729, 0.5425, 0.0912, 0.0658, 0.1542
];
const MIN_S: number = 0.001;
const MAX_S: number = 36500.0;
const MAX_INTERVAL: number = 365.0;
const DAY_MS: number = 24 * 60 * 60 * 1000;

export class Fsrs {
  static decay(w: number[]): number { return -w[20]; }
  static factor(w: number[]): number { return Math.pow(0.9, 1.0 / Fsrs.decay(w)) - 1.0; }
  static recall(w: number[], s: number, elapsedDays: number): number {
    return Math.pow(1.0 + Fsrs.factor(w) * elapsedDays / s, Fsrs.decay(w));
  }
  static initStability(w: number[], g: number): number {
    const idx: number = Math.max(0, Math.min(3, g - 1));
    return Math.max(MIN_S, w[idx]);
  }
  static initDifficulty(w: number[], g: number): number {
    return Math.min(10, Math.max(1, w[4] - Math.exp(w[5] * (g - 1.0)) + 1.0));
  }
  static nextDifficulty(w: number[], d: number, g: number): number {
    const delta: number = -w[6] * (g - 3.0);
    const arg2: number = d + (10.0 - d) * delta / 9.0;
    const arg1: number = w[4] - Math.exp(w[5] * 3.0) + 1.0;
    return Math.min(10, Math.max(1, w[7] * arg1 + (1.0 - w[7]) * arg2));
  }
  static recallStability(w: number[], s: number, d: number, r: number, rating: number): number {
    const hard: number = rating === 2 ? w[15] : 1.0;
    const easy: number = rating === 4 ? w[16] : 1.0;
    const inc: number = Math.exp(w[8]) * (11.0 - d) * Math.pow(s, -w[9])
      * (Math.exp((1.0 - r) * w[10]) - 1.0) * hard * easy;
    return Math.min(MAX_S, Math.max(MIN_S, s * (inc + 1.0)));
  }
  static forgetStability(w: number[], s: number, d: number, r: number): number {
    const long: number = w[11] * Math.pow(d, -w[12]) * (Math.pow(s + 1.0, w[13]) - 1.0)
      * Math.exp((1.0 - r) * w[14]);
    const shortCap: number = s / Math.exp(w[17] * w[18]);
    return Math.max(MIN_S, Math.min(long, shortCap));
  }
  static nextIntervalDays(w: number[], s: number, target: number): number {
    const dr: number = Math.min(0.99, Math.max(0.5, target));
    const raw: number = s / Fsrs.factor(w) * (Math.pow(dr, 1.0 / Fsrs.decay(w)) - 1.0);
    return Math.min(MAX_INTERVAL, Math.max(1.0, raw));
  }
  static elapsedDays(lastReviewed: string, todayIso: string): number {
    if (lastReviewed.length < 10) return 0;
    const a: number = Date.parse(lastReviewed + 'T00:00:00') ;
    const b: number = Date.parse(todayIso + 'T00:00:00');
    if (isNaN(a) || isNaN(b)) return 0;
    return Math.max(0, (b - a) / DAY_MS);
  }
}
