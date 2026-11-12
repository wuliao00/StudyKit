import { WORD_BANK, WordItem, lookupWord } from './Words';

export interface QuizQuestion {
  word: string;
  phonetic: string;
  meaning: string;
  example: string;
  options: string[];
  answerIndex: number;
  fromWrong: boolean;
}

export interface QuizAnswer {
  date: string;
  word: string;
  correct: boolean;
  at: number;
}

export interface QuizSession {
  date: string;
  total: number;
  correct: number;
  at: number;
}

export interface WrongWord {
  word: string;
  date: string;
  wrongCount: number;
}

export class QuizBuilder {
  static readonly GROUP_SIZE: number = 5;

  // priorityWords：错题优先；不够 5 题时用当前词书其余词补满。
  // pool：出题范围（某个词书的词），不传则用全部词库。
  static build(count: number, priorityWords: string[], pool?: WordItem[]): QuizQuestion[] {
    const bank: WordItem[] = (pool === undefined || pool.length === 0) ? WORD_BANK : pool;
    const targets: string[] = [];
    const used: Set<string> = new Set<string>();
    for (let i = 0; i < priorityWords.length && targets.length < count; i++) {
      const item: WordItem | null = QuizBuilder.findIn(bank, priorityWords[i]);
      if (item !== null && !used.has(item.word)) {
        targets.push(item.word);
        used.add(item.word);
      }
    }
    if (targets.length < count) {
      const rest: string[] = [];
      for (let i = 0; i < bank.length; i++) {
        if (!used.has(bank[i].word)) {
          rest.push(bank[i].word);
        }
      }
      QuizBuilder.shuffleStrings(rest);
      for (let i = 0; i < rest.length && targets.length < count; i++) {
        targets.push(rest[i]);
        used.add(rest[i]);
      }
    }
    const questions: QuizQuestion[] = [];
    for (let i = 0; i < targets.length; i++) {
      const item: WordItem | null = lookupWord(targets[i]);
      if (item === null) {
        continue;
      }
      questions.push(QuizBuilder.makeQuestion(item, priorityWords.indexOf(item.word) >= 0, bank));
    }
    return questions;
  }

  private static findIn(bank: WordItem[], word: string): WordItem | null {
    for (let i = 0; i < bank.length; i++) {
      if (bank[i].word === word) {
        return bank[i];
      }
    }
    return null;
  }

  private static makeQuestion(item: WordItem, fromWrong: boolean, bank: WordItem[]): QuizQuestion {
    const options: string[] = [item.meaning];
    const taken: Set<string> = new Set<string>();
    taken.add(item.meaning);
    const source: WordItem[] = bank.length >= 4 ? bank : WORD_BANK;
    const start: number = Math.floor(Math.random() * source.length);
    for (let step = 0; step < source.length * 2 && options.length < 4; step++) {
      const candidate: WordItem = source[(start + step * 7) % source.length];
      if (candidate.word === item.word) {
        continue;
      }
      if (taken.has(candidate.meaning)) {
        continue;
      }
      options.push(candidate.meaning);
      taken.add(candidate.meaning);
    }
    QuizBuilder.shuffleStrings(options);
    let answerIndex: number = 0;
    for (let i = 0; i < options.length; i++) {
      if (options[i] === item.meaning) {
        answerIndex = i;
      }
    }
    return {
      word: item.word,
      phonetic: item.phonetic,
      meaning: item.meaning,
      example: item.example,
      options: options,
      answerIndex: answerIndex,
      fromWrong: fromWrong
    };
  }

  static shuffleStrings(values: string[]): void {
    for (let i = values.length - 1; i > 0; i--) {
      const j: number = Math.floor(Math.random() * (i + 1));
      const tmp: string = values[i];
      values[i] = values[j];
      values[j] = tmp;
    }
  }
}
