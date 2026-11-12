import { lookupWord, WORD_BANK, WordItem } from './Words';
import { SignInStats } from './SignIn';

// 每个词的学习状态：Leitner 5 盒
// box 1：今天再见面；box 2：1 天后；box 3：2 天后；box 4：4 天后；box 5：7 天后（已掌握）
export interface SrsCard {
  word: string;
  box: number;
  dueDate: string;
  lastReviewed: string;
  correct: number;
  wrong: number;
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
    return {
      word: word,
      box: 1,
      dueDate: todayIso,
      lastReviewed: todayIso,
      correct: 0,
      wrong: 0
    };
  }

  static intervalForBox(box: number): number {
    let index: number = box - 1;
    if (index < 0) {
      index = 0;
    }
    if (index >= Srs.BOX_INTERVALS.length) {
      index = Srs.BOX_INTERVALS.length - 1;
    }
    return Srs.BOX_INTERVALS[index];
  }

  static nextBox(current: number, remembered: boolean): number {
    if (!remembered) {
      return 1;
    }
    const next: number = current + 1;
    return next > Srs.MAX_BOX ? Srs.MAX_BOX : next;
  }

  static nextDueDate(box: number, todayIso: string): string {
    return SignInStats.addDays(todayIso, Srs.intervalForBox(box));
  }

  static isDue(card: SrsCard, todayIso: string): boolean {
    return card.dueDate <= todayIso;
  }

  static dueCards(cards: SrsCard[], todayIso: string): SrsCard[] {
    const result: SrsCard[] = [];
    for (let i = 0; i < cards.length; i++) {
      if (Srs.isDue(cards[i], todayIso)) {
        result.push(cards[i]);
      }
    }
    result.sort((a: SrsCard, b: SrsCard): number => {
      if (a.dueDate < b.dueDate) {
        return -1;
      }
      if (a.dueDate > b.dueDate) {
        return 1;
      }
      return 0;
    });
    return result;
  }

  static findCard(cards: SrsCard[], word: string): SrsCard | null {
    for (let i = 0; i < cards.length; i++) {
      if (cards[i].word === word) {
        return cards[i];
      }
    }
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
    for (let i = 0; i < WORD_BANK.length && result.length < limit; i++) {
      const word: string = WORD_BANK[i].word;
      if (used.has(word)) {
        continue;
      }
      if (Srs.findCard(cards, word) !== null) {
        continue;
      }
      result.push(WORD_BANK[i]);
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

