import { allWords, lookupWord, WordItem } from './Words';
import { SignInStats } from './SignIn';
import { Fsrs, FSRS_DEFAULT_PARAMS } from './Fsrs';

// FSRS v6 卡片状态；box 仅为旧 UI 兼容保留。
export interface SrsCard {
  word: string;
  box: number;
  dueDate: string;
  lastReviewed: string;
  correct: number;
  wrong: number;
  stability?: number;
  difficulty?: number;
  reps?: number;
  lapses?: number;
  lastRating?: number;
}

export interface ReviewLog {
  date: string;
  word: string;
  remembered: boolean;
  at: number;
}

export class Srs {
  static readonly MAX_BOX: number = 5;
  static readonly BOX_INTERVALS: number[] = [0, 1, 2, 4, 7];

  static emptyCard(word: string, todayIso: string): SrsCard {
    return { word: word, box: 1, dueDate: todayIso, lastReviewed: todayIso,
      correct: 0, wrong: 0, stability: 0.212, difficulty: 5.0, reps: 0, lapses: 0, lastRating: 0 };
  }

  static intervalForBox(box: number): number {
    const i: number = Math.max(0, Math.min(Srs.BOX_INTERVALS.length - 1, box - 1));
    return Srs.BOX_INTERVALS[i];
  }

  static nextBox(current: number, remembered: boolean): number {
    if (!remembered) return 1;
    return current >= Srs.MAX_BOX ? Srs.MAX_BOX : current + 1;
  }

  static nextDueDate(box: number, todayIso: string): string {
    return SignInStats.addDays(todayIso, Srs.intervalForBox(box));
  }

  // 4 档评分：1 Again / 2 Hard / 3 Good / 4 Easy；保留旧二档调用
  static nextCard(current: SrsCard | null, rating: number, todayIso: string, word: string): SrsCard {
    const w: number[] = FSRS_DEFAULT_PARAMS;
    const g: number = Math.max(1, Math.min(4, Math.round(rating)));
    const prev: SrsCard | null = current;
    const reps: number = prev === null ? 0 : (prev.reps === undefined ? 0 : prev.reps);
    const elapsed: number = prev === null ? 0 : Fsrs.elapsedDays(prev.lastReviewed, todayIso);
    let s: number;
    let d: number;
    if (reps === 0 || prev === null) {
      s = Fsrs.initStability(w, g);
      d = Fsrs.initDifficulty(w, g);
    } else {
      const ps: number = prev.stability === undefined ? 0.212 : prev.stability;
      const pd: number = prev.difficulty === undefined ? 5.0 : prev.difficulty;
      const r: number = Fsrs.recall(w, ps, elapsed);
      d = Fsrs.nextDifficulty(w, pd, g);
      s = g === 1 ? Fsrs.forgetStability(w, ps, pd, r) : Fsrs.recallStability(w, ps, pd, r, g);
    }
    const interval: number = Fsrs.nextIntervalDays(w, s, 0.9);
    const box: number = Math.max(1, Math.min(5, Math.max(1, Math.ceil(Math.log(Math.max(1, interval)) / Math.log(2.0)))));
    return {
      word: prev === null ? word : prev.word,
      box: box,
      dueDate: SignInStats.addDays(todayIso, Math.max(1, Math.round(interval))),
      lastReviewed: todayIso,
      correct: (prev === null ? 0 : prev.correct) + (g >= 2 ? 1 : 0),
      wrong: (prev === null ? 0 : prev.wrong) + (g === 1 ? 1 : 0),
      stability: s,
      difficulty: d,
      reps: reps + 1,
      lapses: (prev === null ? 0 : (prev.lapses === undefined ? 0 : prev.lapses)) + (g === 1 ? 1 : 0),
      lastRating: g
    };
  }

  static isDue(card: SrsCard, todayIso: string): boolean { return card.dueDate <= todayIso; }

  static dueCards(cards: SrsCard[], todayIso: string): SrsCard[] {
    const result: SrsCard[] = [];
    for (let i = 0; i < cards.length; i++) if (Srs.isDue(cards[i], todayIso)) result.push(cards[i]);
    result.sort((a: SrsCard, b: SrsCard): number => a.dueDate < b.dueDate ? -1 : (a.dueDate > b.dueDate ? 1 : 0));
    return result;
  }

  static findCard(cards: SrsCard[], word: string): SrsCard | null {
    for (let i = 0; i < cards.length; i++) if (cards[i].word === word) return cards[i];
    return null;
  }

  static dueCount(cards: SrsCard[], todayIso: string): number {
    let count: number = 0;
    for (let i = 0; i < cards.length; i++) {
      if (Srs.isDue(cards[i], todayIso)) {
        count++;
      }
    }
    return count;
  }

  static masteredCount(cards: SrsCard[]): number {
    let count: number = 0;
    for (let i = 0; i < cards.length; i++) {
      if (cards[i].box >= Srs.MAX_BOX) {
        count++;
      }
    }
    return count;
  }

  static learningCount(cards: SrsCard[]): number {
    return cards.length;
  }

  static totalCorrect(cards: SrsCard[]): number {
    let sum: number = 0;
    for (let i = 0; i < cards.length; i++) {
      sum += cards[i].correct;
    }
    return sum;
  }

  static totalWrong(cards: SrsCard[]): number {
    let sum: number = 0;
    for (let i = 0; i < cards.length; i++) {
      sum += cards[i].wrong;
    }
    return sum;
  }

  // 返回 0-100 的整数；没有作答过时返回 -1（用“—”展示）
  static accuracy(cards: SrsCard[]): number {
    const total: number = Srs.totalCorrect(cards) + Srs.totalWrong(cards);
    if (total <= 0) {
      return -1;
    }
    return Math.round(Srs.totalCorrect(cards) * 100 / total);
  }

  // 今日复习队列：先到期的卡，再补没学过的新词
  static reviewQueue(cards: SrsCard[], todayIso: string, limit: number): WordItem[] {
    const result: WordItem[] = [];
    const used: Set<string> = new Set<string>();
    const due: SrsCard[] = Srs.dueCards(cards, todayIso);
    for (let i = 0; i < due.length && result.length < limit; i++) {
      const item: WordItem | null = lookupWord(due[i].word);
      if (item !== null) {
        result.push(item);
        used.add(item.word);
      }
    }
    const all: WordItem[] = allWords();
    for (let i = 0; i < all.length && result.length < limit; i++) {
      const word: string = all[i].word;
      if (used.has(word)) {
        continue;
      }
      if (Srs.findCard(cards, word) !== null) {
        continue;
      }
      result.push(all[i]);
      used.add(word);
    }
    return result;
  }

  static dueWordNames(cards: SrsCard[], todayIso: string): string[] {
    const names: string[] = [];
    const due: SrsCard[] = Srs.dueCards(cards, todayIso);
    for (let i = 0; i < due.length; i++) {
      names.push(due[i].word);
    }
    return names;
  }

  static friendlyDue(card: SrsCard | null, todayIso: string): string {
    if (card === null) {
      return '新词';
    }
    if (card.dueDate <= todayIso) {
      return '今天';
    }
    if (card.dueDate === SignInStats.addDays(todayIso, 1)) {
      return '明天';
    }
    const parts: string[] = card.dueDate.split('-');
    return Number(parts[1]).toString() + ' 月 ' + Number(parts[2]).toString() + ' 日';
  }
}

