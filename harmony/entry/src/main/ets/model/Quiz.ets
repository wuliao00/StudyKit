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

  // priorityWords：错题优先；不够 5 题时用词库里其余词补满
  static build(count: number, priorityWords: string[]): QuizQuestion[] {
    const targets: string[] = [];
    const used: Set<string> = new Set<string>();
    for (let i = 0; i < priorityWords.length && targets.length < count; i++) {
      const item: WordItem | null = lookupWord(priorityWords[i]);
      if (item !== null && !used.has(item.word)) {
        targets.push(item.word);
        used.add(item.word);
      }
    }
    if (targets.length < count) {
      const pool: string[] = [];
      for (let i = 0; i < WORD_BANK.length; i++) {
        if (!used.has(WORD_BANK[i].word)) {
          pool.push(WORD_BANK[i].word);
        }
      }
      QuizBuilder.shuffleStrings(pool);
      for (let i = 0; i < pool.length && targets.length < count; i++) {
        targets.push(pool[i]);
        used.add(pool[i]);
      }
    }
    const questions: QuizQuestion[] = [];
    for (let i = 0; i < targets.length; i++) {
      const item: WordItem | null = lookupWord(targets[i]);
      if (item === null) {
        continue;
      }
      questions.push(QuizBuilder.makeQuestion(item, priorityWords.indexOf(item.word) >= 0));
    }
    return questions;
  }

  private static makeQuestion(item: WordItem, fromWrong: boolean): QuizQuestion {
    const options: string[] = [item.meaning];
    const taken: Set<string> = new Set<string>();
    taken.add(item.meaning);
    const start: number = Math.floor(Math.random() * WORD_BANK.length);
    for (let step = 0; step < WORD_BANK.length * 2 && options.length < 4; step++) {
      const candidate: WordItem = WORD_BANK[(start + step * 7) % WORD_BANK.length];
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
