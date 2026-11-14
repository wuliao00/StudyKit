import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';
import { QuestionItem, QuestionStore } from './QuestionStore';

// 模考记录独立落盘：filesDir/studykit_mock_exams.json（与 studykit_data.json、question_data.json 分开，互不覆盖）
const DATA_FILE: string = 'studykit_mock_exams.json';
const MAX_RECORDS: number = 30;

export const MOCK_COUNT_OPTIONS: number[] = [10, 20, 30];
export const EXAM_SECONDS_PER_QUESTION: number = 60;
export const EXAM_QUESTION_MAX: number = 30;

// >>> PURE-LOGIC-BEGIN
// 纯逻辑区：不 import fs / ohos / UI，任何一行都可以被 Node 直接跑。
// 证据脚本 E:/dev/parity-evidence-E/run-logic-test.mjs 会按这两个标记整段抽出并执行，
// 所以这里不许出现 ArkUI 装饰器、@ohos 依赖或页面状态。

export interface SubjectTally {
  subject: string;
  total: number;
  correct: number;
  accuracy: number; // 0..100，整数；total = 0 时为 0
}

export class ExamLogic {
  static pad2(value: number): string {
    return value < 10 ? '0' + value.toString() : value.toString();
  }

  // 正确率：分母是「判过的题」（已作答），不是整卷题数；0 题判过时给 0。
  static accuracyPercent(correct: number, judged: number): number {
    if (judged <= 0) {
      return 0;
    }
    let pct: number = Math.round(correct * 100 / judged);
    if (pct < 0) {
      pct = 0;
    }
    if (pct > 100) {
      pct = 100;
    }
    return pct;
  }

  // 秒数 -> mm:ss；负数按 0 处理。模考上限 30 题 x 60 秒 = 30 分钟，两位数分钟足够。
  static formatDuration(totalSec: number): string {
    let sec: number = Math.floor(totalSec);
    if (sec < 0) {
      sec = 0;
    }
    const minutes: number = Math.floor(sec / 60);
    const seconds: number = sec % 60;
    return ExamLogic.pad2(minutes) + ':' + ExamLogic.pad2(seconds);
  }

  // 从 0..total-1 里挑 count 个不重复下标（Fisher-Yates 洗牌后取前 count 个）。
  // rand 由调用方注入，测试里可以给确定性序列；count 超过 total 时按 total 截断，不抛错。
  static pickIndices(total: number, count: number, rand: (bound: number) => number): number[] {
    const pool: number[] = [];
    for (let i = 0; i < total; i++) {
      pool.push(i);
    }
    for (let i = total - 1; i > 0; i--) {
      const raw: number = rand(i + 1);
      let j: number = raw;
      if (j < 0) {
        j = 0;
      }
      if (j > i) {
        j = i;
      }
      const tmp: number = pool[i];
      pool[i] = pool[j];
      pool[j] = tmp;
    }
    let take: number = count;
    if (take < 0) {
      take = 0;
    }
    if (take > total) {
      take = total;
    }
    const picked: number[] = [];
    for (let i = 0; i < take; i++) {
      picked.push(pool[i]);
    }
    return picked;
  }

  // 已作答（selected >= 0）且命中正确项的题数；未作答不算对。
  static countCorrect(selected: number[], answers: number[]): number {
    const n: number = selected.length < answers.length ? selected.length : answers.length;
    let correct: number = 0;
    for (let i = 0; i < n; i++) {
      if (selected[i] >= 0 && selected[i] === answers[i]) {
        correct++;
      }
    }
    return correct;
  }

  static countAnswered(selected: number[]): number {
    let answered: number = 0;
    for (let i = 0; i < selected.length; i++) {
      if (selected[i] >= 0) {
        answered++;
      }
    }
    return answered;
  }

  // 按学科聚合：只统计这一卷里出现过的学科，保持首次出现的顺序。
  static subjectTallies(subjects: string[], selected: number[], answers: number[]): SubjectTally[] {
    const names: string[] = [];
    const totals: number[] = [];
    const corrects: number[] = [];
    const n: number = subjects.length < selected.length ? subjects.length : selected.length;
    for (let i = 0; i < n; i++) {
      const name: string = subjects[i];
      let at: number = names.indexOf(name);
      if (at < 0) {
        names.push(name);
        totals.push(0);
        corrects.push(0);
        at = names.length - 1;
      }
      totals[at] = totals[at] + 1;
      if (selected[i] >= 0 && i < answers.length && selected[i] === answers[i]) {
        corrects[at] = corrects[at] + 1;
      }
    }
    const tallies: SubjectTally[] = [];
    for (let i = 0; i < names.length; i++) {
      const tally: SubjectTally = {
        subject: names[i],
        total: totals[i],
        correct: corrects[i],
        accuracy: ExamLogic.accuracyPercent(corrects[i], totals[i])
      };
      tallies.push(tally);
    }
    return tallies;
  }

  // 薄弱学科：正确率未满 100% 的学科，按正确率升序（同分按名字），最多 3 个。
  // 全对时返回空数组 —— 结果页据此显示「各科全对，没有明显薄弱学科」。
  static weakSubjects(tallies: SubjectTally[]): SubjectTally[] {
    const weak: SubjectTally[] = [];
    for (let i = 0; i < tallies.length; i++) {
      if (tallies[i].accuracy < 100) {
        weak.push(tallies[i]);
      }
    }
    weak.sort((a: SubjectTally, b: SubjectTally): number => {
      if (a.accuracy !== b.accuracy) {
        return a.accuracy - b.accuracy;
      }
      return a.subject < b.subject ? -1 : (a.subject > b.subject ? 1 : 0);
    });
    const out: SubjectTally[] = [];
    for (let i = 0; i < weak.length && i < 3; i++) {
      out.push(weak[i]);
    }
    return out;
  }
}
// <<< PURE-LOGIC-END

export interface MockExamConfig {
  count: number;              // 请求题数（10/20/30）
  subject: string;            // QUESTION_ALL 或具体学科
  secondsPerQuestion: number; // 每题秒数
}

// 试卷题目快照：交卷后即使题库原题被删/被改，结果页仍然按这份快照逐题还原。
export interface MockExamQuestionSnapshot {
  id: string;
  subject: string;
  stem: string;
  options: string[];
  answer: number;
  explanation: string;
  source: string;
}

export interface MockExamRecord {
  id: string;
  createdAt: number;
  subjectLabel: string;      // 「全部」或学科名
  requestedCount: number;
  total: number;             // 实际组卷题数（题库不足时会小于 requestedCount）
  judged: number;            // 作答过的题数（正确率分母）
  correct: number;
  accuracy: number;          // 0..100
  durationSec: number;       // 实际用时
  autoSubmitted: boolean;    // 时间到自动交卷
  wrongCount: number;        // 已写入错题本的道数
  tallies: SubjectTally[];
  weak: SubjectTally[];
  questions: MockExamQuestionSnapshot[];
  selected: number[];        // 每题所选下标，-1 = 未作答
  marks: boolean[];          // 每题是否被标记
}

export interface MockExamData {
  records: MockExamRecord[];
}

export interface MockExamSession {
  config: MockExamConfig;
  questions: QuestionItem[];           // 内存里的活动卷（判分 / 写错题本用）
  snapshots: MockExamQuestionSnapshot[]; // 交卷落库用的快照
  selected: number[];
  marks: boolean[];
  remainingSec: number;
  elapsedSec: number;
  paused: boolean;
  submitted: boolean;
}

export class MockExamStore {
  private static instance: MockExamStore = new MockExamStore();
  private filePath: string = '';
  private data: MockExamData = { records: [] };
  private inited: boolean = false;
  private session: MockExamSession | null = null;
  private pendingResultId: string = '';

  static get(): MockExamStore {
    return MockExamStore.instance;
  }

  static newId(): string {
    return 'exam-' + Date.now().toString() + '-' + Math.floor(Math.random() * 10000).toString();
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.inited) {
      return;
    }
    this.filePath = context.filesDir + '/' + DATA_FILE;
    this.load();
    this.inited = true;
  }

  private load(): void {
    try {
      if (!fs.accessSync(this.filePath)) {
        this.data = { records: [] };
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: MockExamData = JSON.parse(text) as MockExamData;
      const out: MockExamData = { records: [] };
      if (raw !== null && raw !== undefined && raw.records !== undefined && raw.records !== null) {
        out.records = raw.records;
      }
      this.data = out;
    } catch (e) {
      this.data = { records: [] };
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
      // 本地写失败不阻塞页面使用；记录仍在内存里
    }
  }

  // ---------- 组卷 / 作答（交卷前绝不计算对错：延迟反馈是模考的口径） ----------

  // 返回 null 表示题库里没有可抽的题（页面给空态提示）。
  start(config: MockExamConfig): MockExamSession | null {
    const pool: QuestionItem[] = QuestionStore.get().questionsBySubject(config.subject);
    if (pool.length === 0) {
      return null;
    }
    let secondsPer: number = config.secondsPerQuestion;
    if (secondsPer <= 0) {
      secondsPer = EXAM_SECONDS_PER_QUESTION;
    }
    let requestCount: number = config.count;
    if (requestCount <= 0) {
      requestCount = 10;
    }
    if (requestCount > EXAM_QUESTION_MAX) {
      requestCount = EXAM_QUESTION_MAX;
    }
    const take: number = requestCount < pool.length ? requestCount : pool.length;
    const picked: number[] = ExamLogic.pickIndices(pool.length, take,
      (bound: number): number => Math.floor(Math.random() * bound));
    const questions: QuestionItem[] = [];
    const snapshots: MockExamQuestionSnapshot[] = [];
    const selected: number[] = [];
    const marks: boolean[] = [];
    for (let i = 0; i < picked.length; i++) {
      const q: QuestionItem = pool[picked[i]];
      questions.push(q);
      snapshots.push(MockExamStore.snapshotOf(q));
      selected.push(-1);
      marks.push(false);
    }
    const normalized: MockExamConfig = {
      count: requestCount,
      subject: config.subject,
      secondsPerQuestion: secondsPer
    };
    const session: MockExamSession = {
      config: normalized,
      questions: questions,
      snapshots: snapshots,
      selected: selected,
      marks: marks,
      remainingSec: take * secondsPer,
      elapsedSec: 0,
      paused: false,
      submitted: false
    };
    this.session = session;
    this.pendingResultId = '';
    return session;
  }

  private static snapshotOf(q: QuestionItem): MockExamQuestionSnapshot {
    const snap: MockExamQuestionSnapshot = {
      id: q.id,
      subject: q.subject,
      stem: q.stem,
      options: q.options.slice(0),
      answer: q.answer,
      explanation: q.explanation,
      source: q.source
    };
    return snap;
  }

  currentSession(): MockExamSession | null {
    return this.session;
  }

  // 只记录选择，不判对错（MockExamSession.selected 是唯一写入口）。
  answer(index: number, option: number): void {
    const s: MockExamSession | null = this.session;
    if (s === null || s.submitted) {
      return;
    }
    if (index < 0 || index >= s.selected.length) {
      return;
    }
    if (option < 0 || option >= s.questions[index].options.length) {
      return;
    }
    s.selected[index] = option;
  }

  toggleMark(index: number): void {
    const s: MockExamSession | null = this.session;
    if (s === null || s.submitted) {
      return;
    }
    if (index < 0 || index >= s.marks.length) {
      return;
    }
    s.marks[index] = !s.marks[index];
  }

  pause(): void {
    if (this.session !== null && !this.session.submitted) {
      this.session.paused = true;
    }
  }

  resume(): void {
    if (this.session !== null && !this.session.submitted) {
      this.session.paused = false;
    }
  }

  // 每秒由页面喂一次；暂停或已交卷时不动，返回剩余秒数（0 = 到点）。
  tick(): number {
    const s: MockExamSession | null = this.session;
    if (s === null) {
      return 0;
    }
    if (s.submitted || s.paused) {
      return s.remainingSec;
    }
    if (s.remainingSec > 0) {
      s.remainingSec = s.remainingSec - 1;
      s.elapsedSec = s.elapsedSec + 1;
    }
    return s.remainingSec;
  }

  // ---------- 交卷 / 结果 ----------

  // 一次性判分并冻结；auto=true 表示时间到自动交卷（结果页会标注）。
  // 答错且作答过的题逐条写进 QuestionStore 的错题本（未作答不算错题，交卷后单独列「未作答」）。
  async submit(auto: boolean): Promise<MockExamRecord | null> {
    const s: MockExamSession | null = this.session;
    if (s === null || s.submitted) {
      return null;
    }
    s.submitted = true;
    const answers: number[] = [];
    const subjects: string[] = [];
    for (let i = 0; i < s.questions.length; i++) {
      answers.push(s.questions[i].answer);
      subjects.push(s.questions[i].subject);
    }
    const judged: number = ExamLogic.countAnswered(s.selected);
    const correct: number = ExamLogic.countCorrect(s.selected, answers);
    const tallies: SubjectTally[] = ExamLogic.subjectTallies(subjects, s.selected, answers);
    const weak: SubjectTally[] = ExamLogic.weakSubjects(tallies);
    const snapshots: MockExamQuestionSnapshot[] = [];
    for (let i = 0; i < s.snapshots.length; i++) {
      snapshots.push(s.snapshots[i]);
    }
    const selected: number[] = [];
    const marks: boolean[] = [];
    for (let i = 0; i < s.selected.length; i++) {
      selected.push(s.selected[i]);
      marks.push(s.marks[i]);
    }
    const record: MockExamRecord = {
      id: MockExamStore.newId(),
      createdAt: Date.now(),
      subjectLabel: s.config.subject,
      requestedCount: s.config.count,
      total: s.questions.length,
      judged: judged,
      correct: correct,
      accuracy: ExamLogic.accuracyPercent(correct, judged),
      durationSec: s.elapsedSec,
      autoSubmitted: auto,
      wrongCount: 0,
      tallies: tallies,
      weak: weak,
      questions: snapshots,
      selected: selected,
      marks: marks
    };
    let wrongCount: number = 0;
    const qs: QuestionStore = QuestionStore.get();
    for (let i = 0; i < s.questions.length; i++) {
      if (s.selected[i] >= 0 && s.selected[i] !== answers[i]) {
        await qs.addMistakeFromQuestion(s.questions[i], s.selected[i]);
        wrongCount++;
      }
    }
    record.wrongCount = wrongCount;
    const next: MockExamRecord[] = [record];
    for (let i = 0; i < this.data.records.length && next.length < MAX_RECORDS; i++) {
      next.push(this.data.records[i]);
    }
    this.data = { records: next };
    this.pendingResultId = record.id;
    this.save();
    return record;
  }

  // 结果页优先读这次交卷的记录；读不到（例如进程重启后直接打开结果页）回退到最近一次。
  resultId(): string {
    return this.pendingResultId;
  }

  findRecord(id: string): MockExamRecord | null {
    if (id.length === 0) {
      return null;
    }
    for (let i = 0; i < this.data.records.length; i++) {
      if (this.data.records[i].id === id) {
        return this.data.records[i];
      }
    }
    return null;
  }

  latestRecord(): MockExamRecord | null {
    if (this.data.records.length === 0) {
      return null;
    }
    return this.data.records[0];
  }

  records(): MockExamRecord[] {
    return this.data.records.slice(0);
  }

  recordCount(): number {
    return this.data.records.length;
  }
}
