import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';
import { BookItem, ExcerptItem } from './BookStore';

// 书摘复习 + 章节自测的本机数据（独立文件 filesDir/book_study.json）。
// 只读 BookStore 的类型；不写 book_data.json，书摘本身仍由 BookStore 公开 API 增删。
// 复习节奏：新书摘今天到期；记住后按 1 / 2 / 4 / 7 天往后排；没记住回到第一档、今天再来看。

const STUDY_FILE: string = 'book_study.json';
const MAX_TESTS: number = 100;

export interface ExcerptReview {
  excerptId: string;
  bookId: string;
  /** 0 = 待首次复习；1..4 = 已排 1/2/4/7 天 */
  stage: number;
  /** YYYY-MM-DD，到期日 <= 今天即进入今天的复习队列 */
  dueAt: string;
  lastReviewed: string;
  correct: number;
  wrong: number;
}

export interface ChapterTestRecord {
  id: string;
  bookId: string;
  chapterIndex: number;
  chapterSize: number;
  total: number;
  correct: number;
  at: number;
}

export interface ChoiceQuestion {
  prompt: string;
  options: string[];
  answerIndex: number;
  explanation: string;
}

export interface ChapterSpec {
  index: number;
  startPage: number;
  endPage: number;
  label: string;
}

interface BookStudyData {
  reviews: ExcerptReview[];
  tests: ChapterTestRecord[];
}

function pad2(value: number): string {
  return value < 10 ? '0' + value.toString() : value.toString();
}

export function bookTodayIso(): string {
  const d: Date = new Date();
  return d.getFullYear().toString() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

export function bookAddDays(iso: string, delta: number): string {
  const parts: string[] = iso.split('-');
  const d: Date = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
  d.setDate(d.getDate() + delta);
  return d.getFullYear().toString() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

function shortDate(iso: string): string {
  const parts: string[] = iso.split('-');
  return Number(parts[1]).toString() + ' 月 ' + Number(parts[2]).toString() + ' 日';
}

function shuffleStrings(values: string[]): string[] {
  const out: string[] = values.slice(0);
  for (let i = out.length - 1; i > 0; i--) {
    const j: number = Math.floor(Math.random() * (i + 1));
    const tmp: string = out[i];
    out[i] = out[j];
    out[j] = tmp;
  }
  return out;
}

function cleanSnippet(content: string, start: number, length: number): string {
  // 手写空白归一（不依赖正则字面量，ArkTS 全版本可用）
  let text: string = '';
  let lastWasSpace: boolean = false;
  for (let i = 0; i < content.length; i++) {
    const ch: string = content.charAt(i);
    const isSpace: boolean = ch === ' ' || ch === '\n' || ch === '\r' || ch === '\t';
    if (isSpace) {
      if (!lastWasSpace && text.length > 0) {
        text = text + ' ';
      }
      lastWasSpace = true;
    } else {
      text = text + ch;
      lastWasSpace = false;
    }
  }
  if (text.length > 0 && text.charAt(text.length - 1) === ' ') {
    text = text.substring(0, text.length - 1);
  }
  if (text.length <= start) {
    return text;
  }
  let value: string = text.substring(start, start + length);
  if (start + length < text.length) {
    value = value + '…';
  }
  return value;
}

export class BookStudyStore {
  static readonly INTERVALS: number[] = [1, 2, 4, 7];
  static readonly MAX_STAGE: number = BookStudyStore.INTERVALS.length;

  private static instance: BookStudyStore = new BookStudyStore();
  private filePath: string = '';
  private data: BookStudyData = { reviews: [], tests: [] };
  private inited: boolean = false;

  static get(): BookStudyStore {
    return BookStudyStore.instance;
  }

  static newId(prefix: string): string {
    return prefix + '-' + Date.now().toString() + '-' + Math.floor(Math.random() * 10000).toString();
  }

  /** stage 0 表示待首次复习（今天）；1..4 → 1/2/4/7 天。 */
  static intervalForStage(stage: number): number {
    if (stage <= 0) {
      return 0;
    }
    let index: number = stage - 1;
    if (index >= BookStudyStore.INTERVALS.length) {
      index = BookStudyStore.INTERVALS.length - 1;
    }
    return BookStudyStore.INTERVALS[index];
  }

  static stageText(stage: number): string {
    if (stage <= 0) {
      return '待首次复习';
    }
    let safe: number = stage;
    if (safe > BookStudyStore.MAX_STAGE) {
      safe = BookStudyStore.MAX_STAGE;
    }
    return '第 ' + safe.toString() + ' 档 · ' + BookStudyStore.intervalForStage(safe).toString() + ' 天';
  }

  /** 记住：往后一档；没记住：回到第一档（1 天后再看）。新书摘的 stage 0 不会由复习产生。 */
  static nextStage(remembered: boolean, stage: number): number {
    if (!remembered) {
      return 1;
    }
    const next: number = stage + 1;
    return next > BookStudyStore.MAX_STAGE ? BookStudyStore.MAX_STAGE : next;
  }

  static dueText(dueAt: string, today: string): string {
    if (dueAt <= today) {
      const parts: string[] = today.split('-');
      const todayDate: Date = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
      const dp: string[] = dueAt.split('-');
      const dueDate: Date = new Date(Number(dp[0]), Number(dp[1]) - 1, Number(dp[2]));
      const days: number = Math.round((todayDate.getTime() - dueDate.getTime()) / 86400000);
      return days <= 0 ? '今天到期' : '逾期 ' + days.toString() + ' 天';
    }
    return shortDate(dueAt) + ' 到期';
  }

  static chaptersOf(book: BookItem, chapterSize: number): ChapterSpec[] {
    const result: ChapterSpec[] = [];
    let size: number = chapterSize;
    if (size < 1) {
      size = 20;
    }
    let total: number = book.totalPages;
    if (total < 1) {
      total = 1;
    }
    // 页数很大时按上限减少章节数，避免页面一次画几千个 chip（自测按调整后的页段切章）
    const maxChapters: number = 60;
    if (Math.ceil(total / size) > maxChapters) {
      size = Math.ceil(total / maxChapters);
    }
    let start: number = 1;
    let index: number = 1;
    while (start <= total) {
      let end: number = start + size - 1;
      if (end > total) {
        end = total;
      }
      result.push({
        index: index,
        startPage: start,
        endPage: end,
        label: '第 ' + index.toString() + ' 章 · 第 ' + start.toString() + '–' + end.toString() + ' 页'
      });
      start = end + 1;
      index++;
    }
    return result;
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.inited) {
      return;
    }
    this.filePath = context.filesDir + '/' + STUDY_FILE;
    this.load();
    this.inited = true;
  }

  private load(): void {
    try {
      if (!fs.accessSync(this.filePath)) {
        this.data = { reviews: [], tests: [] };
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: BookStudyData = JSON.parse(text) as BookStudyData;
      const out: BookStudyData = { reviews: [], tests: [] };
      if (raw !== null && raw !== undefined) {
        if (raw.reviews !== undefined && raw.reviews !== null) {
          out.reviews = raw.reviews;
        }
        if (raw.tests !== undefined && raw.tests !== null) {
          out.tests = raw.tests;
        }
      }
      this.data = out;
    } catch (e) {
      this.data = { reviews: [], tests: [] };
    }
  }

  private save(): void {
    if (this.filePath.length === 0) {
      return;
    }
    try {
      const file = fs.openSync(this.filePath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, JSON.stringify(this.data));
      } finally {
        fs.closeSync(file);
      }
    } catch (e) {
      // 本地写失败不阻塞复习页
    }
  }

  reviewOf(excerptId: string): ExcerptReview | null {
    for (let i = 0; i < this.data.reviews.length; i++) {
      if (this.data.reviews[i].excerptId === excerptId) {
        return this.data.reviews[i];
      }
    }
    return null;
  }

  reviewCount(bookId: string): number {
    let count: number = 0;
    for (let i = 0; i < this.data.reviews.length; i++) {
      if (bookId.length === 0 || this.data.reviews[i].bookId === bookId) {
        count++;
      }
    }
    return count;
  }

  /** 新书摘进入复习：今天到期、第 0 档。已存在的书摘不重复建条目。 */
  ensureReview(excerpt: ExcerptItem): ExcerptReview {
    const existing: ExcerptReview | null = this.reviewOf(excerpt.id);
    if (existing !== null) {
      return existing;
    }
    const item: ExcerptReview = {
      excerptId: excerpt.id,
      bookId: excerpt.bookId,
      stage: 0,
      dueAt: bookTodayIso(),
      lastReviewed: '',
      correct: 0,
      wrong: 0
    };
    this.data.reviews.push(item);
    this.save();
    return item;
  }

  removeReview(excerptId: string): void {
    const next: ExcerptReview[] = [];
    let removed: boolean = false;
    for (let i = 0; i < this.data.reviews.length; i++) {
      if (this.data.reviews[i].excerptId === excerptId) {
        removed = true;
      } else {
        next.push(this.data.reviews[i]);
      }
    }
    if (removed) {
      this.data.reviews = next;
      this.save();
    }
  }

  /** 书摘被删掉后清掉悬空复习状态（BookStore 删书会连带删书摘）。 */
  pruneMissing(existingIds: Set<string>): void {
    const next: ExcerptReview[] = [];
    let changed: boolean = false;
    for (let i = 0; i < this.data.reviews.length; i++) {
      if (existingIds.has(this.data.reviews[i].excerptId)) {
        next.push(this.data.reviews[i]);
      } else {
        changed = true;
      }
    }
    if (changed) {
      this.data.reviews = next;
      this.save();
    }
  }

  dueReviews(bookId: string, today: string): ExcerptReview[] {
    const result: ExcerptReview[] = [];
    for (let i = 0; i < this.data.reviews.length; i++) {
      const item: ExcerptReview = this.data.reviews[i];
      if (bookId.length > 0 && item.bookId !== bookId) {
        continue;
      }
      if (item.dueAt <= today) {
        result.push(item);
      }
    }
    result.sort((a: ExcerptReview, b: ExcerptReview): number => {
      if (a.dueAt < b.dueAt) {
        return -1;
      }
      if (a.dueAt > b.dueAt) {
        return 1;
      }
      return 0;
    });
    return result;
  }

  dueCount(bookId: string, today: string): number {
    return this.dueReviews(bookId, today).length;
  }

  /** 未来 days 天的到期分布，文字直接给复习页/详情页用。 */
  upcomingLine(bookId: string, today: string, days: number): string {
    const parts: string[] = [];
    for (let offset = 0; offset <= days; offset++) {
      const day: string = bookAddDays(today, offset);
      let count: number = 0;
      for (let i = 0; i < this.data.reviews.length; i++) {
        const item: ExcerptReview = this.data.reviews[i];
        if (bookId.length > 0 && item.bookId !== bookId) {
          continue;
        }
        if (item.dueAt === day) {
          count++;
        }
      }
      if (count === 0) {
        continue;
      }
      let label: string = day;
      if (offset === 0) {
        label = '今天';
      } else if (offset === 1) {
        label = '明天';
      } else {
        label = shortDate(day);
      }
      parts.push(label + ' ' + count.toString() + ' 张');
    }
    if (parts.length === 0) {
      return '未来 ' + days.toString() + ' 天没有新的到期书摘。';
    }
    return '未来到期：' + parts.join(' · ');
  }

  /** 记住 / 没记住：remembered 后按下一档排期，没记住回到第 0 档（今天再看）。 */
  markReviewed(excerptId: string, remembered: boolean, today: string): ExcerptReview | null {
    const review: ExcerptReview | null = this.reviewOf(excerptId);
    if (review === null) {
      return null;
    }
    review.stage = BookStudyStore.nextStage(remembered, review.stage);
    if (remembered) {
      review.correct = review.correct + 1;
    } else {
      review.wrong = review.wrong + 1;
    }
    review.lastReviewed = today;
    review.dueAt = bookAddDays(today, BookStudyStore.intervalForStage(review.stage));
    this.save();
    return review;
  }

  nextScheduleText(review: ExcerptReview, remembered: boolean): string {
    const stage: number = BookStudyStore.nextStage(remembered, review.stage);
    const interval: number = BookStudyStore.intervalForStage(stage);
    return interval.toString() + ' 天后再看';
  }

  recordTest(bookId: string, chapterIndex: number, chapterSize: number, total: number,
    correct: number): void {
    const record: ChapterTestRecord = {
      id: BookStudyStore.newId('t'),
      bookId: bookId,
      chapterIndex: chapterIndex,
      chapterSize: chapterSize,
      total: total,
      correct: correct,
      at: Date.now()
    };
    this.data.tests.push(record);
    if (this.data.tests.length > MAX_TESTS) {
      this.data.tests = this.data.tests.slice(this.data.tests.length - MAX_TESTS);
    }
    this.save();
  }

  lastTest(bookId: string): ChapterTestRecord | null {
    for (let i = this.data.tests.length - 1; i >= 0; i--) {
      if (this.data.tests[i].bookId === bookId) {
        return this.data.tests[i];
      }
    }
    return null;
  }
}

/**
 * 从书摘生成章节自测选择题（最多 5 题）：
 * - 带页码的书摘先出「记在第几页」；
 * - 章节数 >= 4 时再出「属于哪一章」；
 * - 还不够 5 题时用同一条书摘的不同片段补足，保证每道题都是真实书摘、不编造原文。
 * 没有任何带页码书摘时返回空数组（页面据此提示先去记书摘），不硬造题。
 */
export class ChapterQuizBuilder {
  static readonly MAX_QUESTIONS: number = 5;

  static build(book: BookItem, excerpts: ExcerptItem[], chapterSize: number, chapterIndex: number): ChoiceQuestion[] {
    let size: number = chapterSize;
    if (size < 1) {
      size = 20;
    }
    let totalPages: number = book.totalPages;
    if (totalPages < 1) {
      totalPages = 1;
    }
    const chapters: ChapterSpec[] = BookStudyStore.chaptersOf(book, size);
    let selectedChapter: number = chapterIndex;
    if (selectedChapter > chapters.length) {
      selectedChapter = -1;
    }
    const pool: ExcerptItem[] = [];
    for (let i = 0; i < excerpts.length; i++) {
      const item: ExcerptItem = excerpts[i];
      if (item.pageNo <= 0) {
        continue;
      }
      if (selectedChapter > 0) {
        const chapter: ChapterSpec = chapters[selectedChapter - 1];
        if (item.pageNo < chapter.startPage || item.pageNo > chapter.endPage) {
          continue;
        }
      }
      pool.push(item);
    }
    if (pool.length === 0) {
      return [];
    }
    const allExcerpts: ExcerptItem[] = excerpts;
    const questions: ChoiceQuestion[] = [];
    const usedPrompts: Set<string> = new Set<string>();

    const pushQuestion = (prompt: string, options: string[], answerText: string, explanation: string): void => {
      if (usedPrompts.has(prompt) || questions.length >= ChapterQuizBuilder.MAX_QUESTIONS || options.length < 2) {
        return;
      }
      const shuffled: string[] = shuffleStrings(options);
      let answerIndex: number = 0;
      for (let i = 0; i < shuffled.length; i++) {
        if (shuffled[i] === answerText) {
          answerIndex = i;
        }
      }
      usedPrompts.add(prompt);
      const question: ChoiceQuestion = {
        prompt: prompt,
        options: shuffled,
        answerIndex: answerIndex,
        explanation: explanation
      };
      questions.push(question);
    };

    // 1) 页码题：每一条带页码书摘都能出（总页数 >= 4 时凑得出 4 个不同页码）
    if (totalPages >= 4) {
      for (let i = 0; i < pool.length && questions.length < ChapterQuizBuilder.MAX_QUESTIONS; i++) {
        const item: ExcerptItem = pool[i];
        const snippet: string = cleanSnippet(item.content, 0, 26);
        const correct: string = item.pageNo.toString();
        const options: string[] = [correct];
        const offsets: number[] = [1, -1, 2, -2, 3, -3, 5, -5];
        for (let j = 0; j < offsets.length && options.length < 4; j++) {
          const candidate: number = item.pageNo + offsets[j];
          if (candidate < 1 || candidate > totalPages) {
            continue;
          }
          const text: string = candidate.toString();
          if (options.indexOf(text) < 0) {
            options.push(text);
          }
        }
        pushQuestion('书摘「' + snippet + '」记在第几页？', options, correct,
          '这条书摘在第 ' + correct + ' 页。');
      }
    }

    // 2) 章节题：章节数 >= 4 才有 4 个不同选项
    if (chapters.length >= 4) {
      for (let i = 0; i < pool.length && questions.length < ChapterQuizBuilder.MAX_QUESTIONS; i++) {
        const item: ExcerptItem = pool[i];
        let chapter: ChapterSpec | null = null;
        for (let c = 0; c < chapters.length; c++) {
          if (item.pageNo >= chapters[c].startPage && item.pageNo <= chapters[c].endPage) {
            chapter = chapters[c];
            break;
          }
        }
        if (chapter === null) {
          continue;
        }
        const correct: string = chapter.label;
        const options: string[] = [correct];
        for (let c = 0; c < chapters.length && options.length < 4; c++) {
          if (chapters[c].index === chapter.index) {
            continue;
          }
          if (options.indexOf(chapters[c].label) < 0) {
            options.push(chapters[c].label);
          }
        }
        const snippet: string = cleanSnippet(item.content, 0, 24);
        pushQuestion('书摘「' + snippet + '」属于哪一章？', options, correct,
          '第 ' + item.pageNo.toString() + ' 页落在「' + correct + '」。');
      }
    }

    // 3) 章节归属题：给一章，问哪条书摘出自它（书摘够 4 条时）
    if (allExcerpts.length >= 4 && chapters.length >= 1) {
      for (let c = 0; c < chapters.length && questions.length < ChapterQuizBuilder.MAX_QUESTIONS; c++) {
        const chapter: ChapterSpec = chapters[c];
        const inside: ExcerptItem[] = [];
        const outside: ExcerptItem[] = [];
        for (let i = 0; i < allExcerpts.length; i++) {
          const item: ExcerptItem = allExcerpts[i];
          if (item.pageNo <= 0) {
            continue;
          }
          if (item.pageNo >= chapter.startPage && item.pageNo <= chapter.endPage) {
            inside.push(item);
          } else {
            outside.push(item);
          }
        }
        if (inside.length === 0 || outside.length < 3) {
          continue;
        }
        const correctItem: ExcerptItem = inside[0];
        const correct: string = cleanSnippet(correctItem.content, 0, 22);
        const options: string[] = [correct];
        for (let i = 0; i < outside.length && options.length < 4; i++) {
          const text: string = cleanSnippet(outside[i].content, 0, 22);
          if (options.indexOf(text) < 0) {
            options.push(text);
          }
        }
        if (options.length < 4) {
          continue;
        }
        pushQuestion('下面哪条书摘记在「第 ' + chapter.startPage.toString() + '–' + chapter.endPage.toString()
          + ' 页」？', options, correct,
          '只有这一条落在这一页段，其余来自其它页。');
      }
    }

    // 4) 片段补足：同一条书摘换片段/换干扰项，补到 5 题（仍全是真实书摘）
    if (totalPages >= 4) {
      const windows: number[] = [8, 16, 24, 32];
      for (let w = 0; w < windows.length && questions.length < ChapterQuizBuilder.MAX_QUESTIONS; w++) {
        for (let i = 0; i < pool.length && questions.length < ChapterQuizBuilder.MAX_QUESTIONS; i++) {
          const item: ExcerptItem = pool[i];
          const snippet: string = cleanSnippet(item.content, windows[w], 26);
          const firstSnippet: string = cleanSnippet(item.content, 0, 26);
          if (snippet.length === 0 || snippet === firstSnippet) {
            continue;
          }
          const correct: string = item.pageNo.toString();
          const options: string[] = [correct];
          const offsets: number[] = [4, -4, 6, -6, 7, -7, 8, -8];
          for (let j = 0; j < offsets.length && options.length < 4; j++) {
            const candidate: number = item.pageNo + offsets[j];
            if (candidate < 1 || candidate > totalPages) {
              continue;
            }
            const text: string = candidate.toString();
            if (options.indexOf(text) < 0) {
              options.push(text);
            }
          }
          pushQuestion('书摘「' + snippet + '」记在第几页？', options, correct,
            '这条书摘仍在第 ' + correct + ' 页。');
        }
      }
    }

    return questions;
  }
}
