import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';
import { SignInRecord, SignInStats } from './SignIn';
import { Srs, SrsCard, ReviewLog } from './Srs';
import { QuizAnswer, QuizSession, WrongWord } from './Quiz';

const DATA_FILE: string = 'studykit_data.json';
const MAX_REVIEWS: number = 400;
const MAX_QUIZ_ANSWERS: number = 400;
const MAX_SESSIONS: number = 200;
const MAX_WRONGS: number = 100;

export interface LastReview {
  word: string;
  prevCard: SrsCard | null;
  date: string;
}

export interface AppData {
  signIns: SignInRecord[];
  cards: SrsCard[];
  reviews: ReviewLog[];
  quizAnswers: QuizAnswer[];
  quizSessions: QuizSession[];
  wrongs: WrongWord[];
  studyDays: string[];
}

export class StudyStore {
  private static instance: StudyStore = new StudyStore();
  private filePath: string = '';
  private data: AppData = StudyStore.emptyData();
  private lastReview: LastReview | null = null;

  static get(): StudyStore {
    return StudyStore.instance;
  }

  static emptyData(): AppData {
    return {
      signIns: [],
      cards: [],
      reviews: [],
      quizAnswers: [],
      quizSessions: [],
      wrongs: [],
      studyDays: []
    };
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.filePath.length === 0) {
      this.filePath = context.filesDir + '/' + DATA_FILE;
      await this.load();
    }
  }

  private async load(): Promise<void> {
    try {
      if (!fs.accessSync(this.filePath)) {
        this.data = StudyStore.emptyData();
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: AppData = JSON.parse(text) as AppData;
      this.data = StudyStore.normalize(raw);
    } catch (e) {
      this.data = StudyStore.emptyData();
    }
  }

  private static normalize(raw: AppData): AppData {
    const out: AppData = StudyStore.emptyData();
    if (raw === null || raw === undefined) {
      return out;
    }
    if (raw.signIns !== undefined && raw.signIns !== null) {
      out.signIns = raw.signIns;
    }
    if (raw.cards !== undefined && raw.cards !== null) {
      out.cards = raw.cards;
    }
    if (raw.reviews !== undefined && raw.reviews !== null) {
      out.reviews = raw.reviews;
    }
    if (raw.quizAnswers !== undefined && raw.quizAnswers !== null) {
      out.quizAnswers = raw.quizAnswers;
    }
    if (raw.quizSessions !== undefined && raw.quizSessions !== null) {
      out.quizSessions = raw.quizSessions;
    }
    if (raw.wrongs !== undefined && raw.wrongs !== null) {
      out.wrongs = raw.wrongs;
    }
    if (raw.studyDays !== undefined && raw.studyDays !== null) {
      out.studyDays = raw.studyDays;
    }
    return out;
  }

  private async save(): Promise<void> {
    if (this.filePath.length === 0) {
      return;
    }
    const file = fs.openSync(this.filePath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(file.fd, JSON.stringify(this.data));
    } finally {
      fs.closeSync(file);
    }
  }

  getData(): AppData {
    return this.data;
  }

  get signIns(): SignInRecord[] {
    return this.data.signIns;
  }

  get cards(): SrsCard[] {
    return this.data.cards;
  }

  get wrongs(): WrongWord[] {
    return this.data.wrongs;
  }

  get studyDays(): string[] {
    return this.data.studyDays;
  }

  get lastReviewInfo(): LastReview | null {
    return this.lastReview;
  }

  // ---------- 签到 ----------

  async signIn(todayIso: string): Promise<boolean> {
    for (let i = 0; i < this.data.signIns.length; i++) {
      if (this.data.signIns[i].day === todayIso) {
        return false;
      }
    }
    this.data.signIns.push({ day: todayIso, makeup: false });
    this.touchStudyDay(todayIso);
    await this.save();
    return true;
  }

  async makeUp(date: string, todayIso: string): Promise<boolean> {
    for (let i = 0; i < this.data.signIns.length; i++) {
      if (this.data.signIns[i].day === date) {
        return false;
      }
    }
    const summary = SignInStats.summary(this.data.signIns, todayIso);
    if (!SignInStats.canMakeUp(date, todayIso, summary.makeupDays)) {
      return false;
    }
    this.data.signIns.push({ day: date, makeup: true });
    await this.save();
    return true;
  }

  async undoMakeUp(date: string, todayIso: string): Promise<boolean> {
    const summary = SignInStats.summary(this.data.signIns, todayIso);
    if (!SignInStats.canUndo(date, todayIso, summary.makeupDays)) {
      return false;
    }
    const next: SignInRecord[] = [];
    for (let i = 0; i < this.data.signIns.length; i++) {
      const record: SignInRecord = this.data.signIns[i];
      if (!(record.day === date && record.makeup)) {
        next.push(record);
      }
    }
    this.data.signIns = next;
    await this.save();
    return true;
  }

  // ---------- SRS 复习 ----------

  async reviewWord(word: string, remembered: boolean, todayIso: string): Promise<SrsCard> {
    const current: SrsCard | null = Srs.findCard(this.data.cards, word);
    const prevCard: SrsCard | null = current === null ? null : StudyStore.copyCard(current);
    const baseBox: number = current === null ? 1 : current.box;
    const box: number = Srs.nextBox(baseBox, remembered);
    const next: SrsCard = {
      word: word,
      box: box,
      dueDate: Srs.nextDueDate(box, todayIso),
      lastReviewed: todayIso,
      correct: (current === null ? 0 : current.correct) + (remembered ? 1 : 0),
      wrong: (current === null ? 0 : current.wrong) + (remembered ? 0 : 1)
    };
    this.upsertCard(next);
    this.data.reviews.push({ date: todayIso, word: word, remembered: remembered, at: Date.now() });
    if (this.data.reviews.length > MAX_REVIEWS) {
      this.data.reviews = this.data.reviews.slice(this.data.reviews.length - MAX_REVIEWS);
    }
    this.lastReview = { word: word, prevCard: prevCard, date: todayIso };
    this.touchStudyDay(todayIso);
    await this.save();
    return next;
  }

  hasUndoReview(): boolean {
    return this.lastReview !== null;
  }

  async undoLastReview(todayIso: string): Promise<boolean> {
    const last: LastReview | null = this.lastReview;
    if (last === null) {
      return false;
    }
    if (last.prevCard === null) {
      const next: SrsCard[] = [];
      for (let i = 0; i < this.data.cards.length; i++) {
        if (this.data.cards[i].word !== last.word) {
          next.push(this.data.cards[i]);
        }
      }
      this.data.cards = next;
    } else {
      this.upsertCard(last.prevCard);
    }
    for (let i = this.data.reviews.length - 1; i >= 0; i--) {
      if (this.data.reviews[i].date === last.date && this.data.reviews[i].word === last.word) {
        this.data.reviews.splice(i, 1);
        break;
      }
    }
    this.lastReview = null;
    this.pruneStudyDay(last.date);
    await this.save();
    return true;
  }

  private upsertCard(card: SrsCard): void {
    for (let i = 0; i < this.data.cards.length; i++) {
      if (this.data.cards[i].word === card.word) {
        this.data.cards[i] = card;
        return;
      }
    }
    this.data.cards.push(card);
  }

  private static copyCard(card: SrsCard): SrsCard {
    return {
      word: card.word,
      box: card.box,
      dueDate: card.dueDate,
      lastReviewed: card.lastReviewed,
      correct: card.correct,
      wrong: card.wrong
    };
  }

  todayReviewCount(todayIso: string): number {
    let count: number = 0;
    for (let i = 0; i < this.data.reviews.length; i++) {
      if (this.data.reviews[i].date === todayIso) {
        count++;
      }
    }
    return count;
  }

  // ---------- 小测 ----------

  async recordQuizAnswer(word: string, correct: boolean, todayIso: string): Promise<void> {
    this.data.quizAnswers.push({ date: todayIso, word: word, correct: correct, at: Date.now() });
    if (this.data.quizAnswers.length > MAX_QUIZ_ANSWERS) {
      this.data.quizAnswers = this.data.quizAnswers.slice(this.data.quizAnswers.length - MAX_QUIZ_ANSWERS);
    }
    if (correct) {
      this.removeWrong(word);
    } else {
      this.addWrong(word, todayIso);
    }
    this.touchStudyDay(todayIso);
    await this.save();
  }

  async finishQuiz(total: number, correct: number, todayIso: string): Promise<void> {
    this.data.quizSessions.push({ date: todayIso, total: total, correct: correct, at: Date.now() });
    if (this.data.quizSessions.length > MAX_SESSIONS) {
      this.data.quizSessions = this.data.quizSessions.slice(this.data.quizSessions.length - MAX_SESSIONS);
    }
    await this.save();
  }

  private addWrong(word: string, todayIso: string): void {
    for (let i = 0; i < this.data.wrongs.length; i++) {
      if (this.data.wrongs[i].word === word) {
        this.data.wrongs[i].wrongCount = this.data.wrongs[i].wrongCount + 1;
        this.data.wrongs[i].date = todayIso;
        return;
      }
    }
    this.data.wrongs.push({ word: word, date: todayIso, wrongCount: 1 });
    if (this.data.wrongs.length > MAX_WRONGS) {
      this.data.wrongs = this.data.wrongs.slice(this.data.wrongs.length - MAX_WRONGS);
    }
  }

  private removeWrong(word: string): void {
    const next: WrongWord[] = [];
    for (let i = 0; i < this.data.wrongs.length; i++) {
      if (this.data.wrongs[i].word !== word) {
        next.push(this.data.wrongs[i]);
      }
    }
    this.data.wrongs = next;
  }

  wrongWordNames(): string[] {
    const names: string[] = [];
    for (let i = 0; i < this.data.wrongs.length; i++) {
      names.push(this.data.wrongs[i].word);
    }
    return names;
  }

  todayQuizCount(todayIso: string): number {
    let count: number = 0;
    for (let i = 0; i < this.data.quizAnswers.length; i++) {
      if (this.data.quizAnswers[i].date === todayIso) {
        count++;
      }
    }
    return count;
  }

  recentSessions(count: number): QuizSession[] {
    const all: QuizSession[] = this.data.quizSessions;
    const start: number = all.length > count ? all.length - count : 0;
    const result: QuizSession[] = [];
    for (let i = all.length - 1; i >= start; i--) {
      result.push(all[i]);
    }
    return result;
  }

  quizCorrectCount(): number {
    let count: number = 0;
    for (let i = 0; i < this.data.quizAnswers.length; i++) {
      if (this.data.quizAnswers[i].correct) {
        count++;
      }
    }
    return count;
  }

  quizTotalCount(): number {
    return this.data.quizAnswers.length;
  }

  // ---------- 学习天数 ----------

  private touchStudyDay(todayIso: string): void {
    if (this.data.studyDays.indexOf(todayIso) < 0) {
      this.data.studyDays.push(todayIso);
    }
  }

  private pruneStudyDay(date: string): void {
    for (let i = 0; i < this.data.reviews.length; i++) {
      if (this.data.reviews[i].date === date) {
        return;
      }
    }
    for (let i = 0; i < this.data.quizAnswers.length; i++) {
      if (this.data.quizAnswers[i].date === date) {
        return;
      }
    }
    for (let i = 0; i < this.data.signIns.length; i++) {
      if (this.data.signIns[i].day === date) {
        return;
      }
    }
    const next: string[] = [];
    for (let i = 0; i < this.data.studyDays.length; i++) {
      if (this.data.studyDays[i] !== date) {
        next.push(this.data.studyDays[i]);
      }
    }
    this.data.studyDays = next;
  }

  studyTotalDays(): number {
    return this.data.studyDays.length;
  }

  studyStreak(todayIso: string): number {
    const days: Set<string> = new Set<string>();
    for (let i = 0; i < this.data.studyDays.length; i++) {
      days.add(this.data.studyDays[i]);
    }
    let cursor: string = days.has(todayIso) ? todayIso : SignInStats.addDays(todayIso, -1);
    if (!days.has(cursor)) {
      return 0;
    }
    let count: number = 0;
    while (days.has(cursor)) {
      count++;
      cursor = SignInStats.addDays(cursor, -1);
    }
    return count;
  }

  // ---------- 重置 ----------

  async resetAll(): Promise<void> {
    this.data = StudyStore.emptyData();
    this.lastReview = null;
    await this.save();
  }
}
