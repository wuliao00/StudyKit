import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';

const DATA_FILE: string = 'question_data.json';
const EXPORT_PREFIX: string = 'mistakes-export-';

export interface QuestionItem {
  id: string;
  subject: string;
  stem: string;
  options: string[];
  answer: number;
  explanation: string;
  source: string;
  createdAt: number;
}

export interface MistakeItem {
  id: string;
  questionId: string;
  subject: string;
  content: string;
  answer: string;
  note: string;
  reviewAt: number;
  wrongCount: number;
  mastered: boolean;
  createdAt: number;
}

export interface QuestionData {
  questions: QuestionItem[];
  mistakes: MistakeItem[];
}

export const QUESTION_SUBJECTS: string[] = ['数学', '英语', '计算机', '政治'];
export const QUESTION_ALL: string = '全部';

// 独立于主应用 Store 的题库/错题本地存储：自己的 JSON 文件，放在应用 filesDir。
export class QuestionStore {
  private static instance: QuestionStore = new QuestionStore();
  private filePath: string = '';
  private filesDir: string = '';
  private data: QuestionData = { questions: [], mistakes: [] };
  private inited: boolean = false;

  static get(): QuestionStore {
    return QuestionStore.instance;
  }

  static newId(prefix: string): string {
    return prefix + '-' + Date.now().toString() + '-' + Math.floor(Math.random() * 10000).toString();
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.inited) {
      return;
    }
    this.filesDir = context.filesDir;
    this.filePath = context.filesDir + '/' + DATA_FILE;
    this.load();
    if (this.data.questions.length === 0) {
      this.seedBuiltInQuestions();
      this.save();
    }
    this.inited = true;
  }

  private load(): void {
    try {
      if (!fs.accessSync(this.filePath)) {
        this.data = { questions: [], mistakes: [] };
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: QuestionData = JSON.parse(text) as QuestionData;
      const out: QuestionData = { questions: [], mistakes: [] };
      if (raw !== null && raw !== undefined) {
        if (raw.questions !== undefined && raw.questions !== null) {
          out.questions = raw.questions;
        }
        if (raw.mistakes !== undefined && raw.mistakes !== null) {
          out.mistakes = raw.mistakes;
        }
      }
      this.data = out;
    } catch (e) {
      this.data = { questions: [], mistakes: [] };
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
      // 本地写失败不阻塞页面使用
    }
  }

  private seedBuiltInQuestions(): void {
    const now: number = Date.now();
    const seeds: QuestionItem[] = [
      {
        id: QuestionStore.newId('q'),
        subject: '数学',
        stem: '圆的面积公式是？',
        options: ['πr²', '2πr', 'πd', '4πr²'],
        answer: 0,
        explanation: '圆的面积 S = πr²；“2πr / πd” 是周长公式。',
        source: '内置题库',
        createdAt: now
      },
      {
        id: QuestionStore.newId('q'),
        subject: '数学',
        stem: '一元二次方程 x² - 5x + 6 = 0 的解是？',
        options: ['x = 2 或 x = 3', 'x = -2 或 x = -3', 'x = 1 或 x = 6', 'x = 2 或 x = -3'],
        answer: 0,
        explanation: '因式分解得 (x-2)(x-3)=0，所以 x=2 或 x=3。',
        source: '内置题库',
        createdAt: now
      },
      {
        id: QuestionStore.newId('q'),
        subject: '英语',
        stem: 'consistent 的中文释义是？',
        options: ['adj. 一致的；持续的', 'v. 放弃；抛弃', 'n. 优势；有利条件', 'adj. 焦虑的；渴望的'],
        answer: 0,
        explanation: 'consistent = 一致的、持续的；例：Her progress is consistent and steady.',
        source: '内置题库',
        createdAt: now
      },
      {
        id: QuestionStore.newId('q'),
        subject: '英语',
        stem: 'affect 与 effect 的用法，正确的是？',
        options: ['affect 多作动词，effect 多作名词', 'affect 多作名词，effect 多作动词', '两者都只能作名词', '两者都只能作动词'],
        answer: 0,
        explanation: 'affect 是动词“影响”；effect 是名词“效果、影响”。',
        source: '内置题库',
        createdAt: now
      },
      {
        id: QuestionStore.newId('q'),
        subject: '计算机',
        stem: '下列排序算法中，平均时间复杂度为 O(n log n) 的是？',
        options: ['归并排序', '冒泡排序', '选择排序', '插入排序'],
        answer: 0,
        explanation: '归并排序平均/最坏都是 O(n log n)；冒泡、选择、插入平均是 O(n²)。',
        source: '内置题库',
        createdAt: now
      },
      {
        id: QuestionStore.newId('q'),
        subject: '政治',
        stem: '“实践是检验真理的唯一标准”强调的是？',
        options: ['实践对认识的决定性作用', '理论没有价值', '权威的判断最重要', '感觉经验最可靠'],
        answer: 0,
        explanation: '真理要靠实践来检验，强调实践对认识的决定性作用。',
        source: '内置题库',
        createdAt: now
      }
    ];
    this.data.questions = seeds;
  }

  getQuestions(): QuestionItem[] {
    return this.data.questions;
  }

  getMistakes(): MistakeItem[] {
    return this.data.mistakes;
  }

  getSubjects(): string[] {
    const result: string[] = [];
    for (let i = 0; i < QUESTION_SUBJECTS.length; i++) {
      result.push(QUESTION_SUBJECTS[i]);
    }
    for (let i = 0; i < this.data.questions.length; i++) {
      const subject: string = this.data.questions[i].subject;
      if (result.indexOf(subject) < 0) {
        result.push(subject);
      }
    }
    return result;
  }

  questionsBySubject(subject: string): QuestionItem[] {
    if (subject === QUESTION_ALL || subject.length === 0) {
      return this.data.questions;
    }
    const result: QuestionItem[] = [];
    for (let i = 0; i < this.data.questions.length; i++) {
      if (this.data.questions[i].subject === subject) {
        result.push(this.data.questions[i]);
      }
    }
    return result;
  }

  questionsByIds(ids: string[]): QuestionItem[] {
    const result: QuestionItem[] = [];
    for (let i = 0; i < this.data.questions.length; i++) {
      if (ids.indexOf(this.data.questions[i].id) >= 0) {
        result.push(this.data.questions[i]);
      }
    }
    return result;
  }

  getMistakeById(id: string): MistakeItem | null {
    for (let i = 0; i < this.data.mistakes.length; i++) {
      if (this.data.mistakes[i].id === id) {
        return this.data.mistakes[i];
      }
    }
    return null;
  }

  async addQuestion(subject: string, stem: string, options: string[], answer: number,
    explanation: string, source: string): Promise<QuestionItem> {
    const item: QuestionItem = {
      id: QuestionStore.newId('q'),
      subject: subject.length === 0 ? '未分类' : subject,
      stem: stem,
      options: options,
      answer: answer,
      explanation: explanation,
      source: source,
      createdAt: Date.now()
    };
    this.data.questions.push(item);
    this.save();
    return item;
  }

  addQuestionItem(item: QuestionItem): Promise<void> {
    this.data.questions.push(item);
    this.save();
    return Promise.resolve();
  }

  // 答错时自动入库；同一道题已有未掌握错题时累加次数
  async addMistakeFromQuestion(question: QuestionItem, selectedIndex: number): Promise<MistakeItem> {
    let mistake: MistakeItem | null = null;
    for (let i = 0; i < this.data.mistakes.length; i++) {
      if (this.data.mistakes[i].questionId === question.id) {
        mistake = this.data.mistakes[i];
        break;
      }
    }
    const now: number = Date.now();
    const answerText: string = question.options.length > question.answer
      ? question.options[question.answer] : '';
    if (mistake !== null) {
      mistake.wrongCount = mistake.wrongCount + 1;
      mistake.reviewAt = now;
      mistake.mastered = false;
      mistake.note = mistake.note.length > 0 ? mistake.note
        : ('上次选了 ' + (selectedIndex >= 0 && selectedIndex < question.options.length
          ? question.options[selectedIndex] : '') + '；正确答案：' + answerText);
      this.save();
      return mistake;
    }
    const created: MistakeItem = {
      id: QuestionStore.newId('m'),
      questionId: question.id,
      subject: question.subject,
      content: question.stem,
      answer: answerText,
      note: '上次选了 ' + (selectedIndex >= 0 && selectedIndex < question.options.length
        ? question.options[selectedIndex] : '未作答') + '。' + question.explanation,
      reviewAt: now,
      wrongCount: 1,
      mastered: false,
      createdAt: now
    };
    this.data.mistakes.push(created);
    this.save();
    return created;
  }

  async addManualMistake(subject: string, content: string, answer: string, note: string): Promise<MistakeItem> {
    const now: number = Date.now();
    const item: MistakeItem = {
      id: QuestionStore.newId('m'),
      questionId: '',
      subject: subject.length === 0 ? '未分类' : subject,
      content: content,
      answer: answer,
      note: note,
      reviewAt: now,
      wrongCount: 1,
      mastered: false,
      createdAt: now
    };
    this.data.mistakes.push(item);
    this.save();
    return item;
  }

  async markMastered(id: string, mastered: boolean): Promise<void> {
    for (let i = 0; i < this.data.mistakes.length; i++) {
      if (this.data.mistakes[i].id === id) {
        this.data.mistakes[i].mastered = mastered;
        this.data.mistakes[i].reviewAt = Date.now();
      }
    }
    this.save();
  }

  async updateMistakeNote(id: string, note: string): Promise<void> {
    for (let i = 0; i < this.data.mistakes.length; i++) {
      if (this.data.mistakes[i].id === id) {
        this.data.mistakes[i].note = note;
      }
    }
    this.save();
  }

  async reviewMistake(id: string): Promise<void> {
    for (let i = 0; i < this.data.mistakes.length; i++) {
      if (this.data.mistakes[i].id === id) {
        this.data.mistakes[i].reviewAt = Date.now();
        if (this.data.mistakes[i].mastered) {
          this.data.mistakes[i].mastered = false;
        }
      }
    }
    this.save();
  }

  async deleteMistake(id: string): Promise<void> {
    const next: MistakeItem[] = [];
    for (let i = 0; i < this.data.mistakes.length; i++) {
      if (this.data.mistakes[i].id !== id) {
        next.push(this.data.mistakes[i]);
      }
    }
    this.data.mistakes = next;
    this.save();
  }

  mistakesBySubject(subject: string): MistakeItem[] {
    if (subject === QUESTION_ALL || subject.length === 0) {
      return this.data.mistakes;
    }
    const result: MistakeItem[] = [];
    for (let i = 0; i < this.data.mistakes.length; i++) {
      if (this.data.mistakes[i].subject === subject) {
        result.push(this.data.mistakes[i]);
      }
    }
    return result;
  }

  mistakeSubjects(): string[] {
    const result: string[] = [];
    for (let i = 0; i < this.data.mistakes.length; i++) {
      const subject: string = this.data.mistakes[i].subject;
      if (result.indexOf(subject) < 0) {
        result.push(subject);
      }
    }
    return result;
  }

  exportMistakesText(): string {
    const lines: string[] = [];
    lines.push('StudyKit 错题本导出');
    lines.push('导出时间：' + QuestionStore.formatTime(Date.now()));
    lines.push('共 ' + this.data.mistakes.length.toString() + ' 道错题');
    lines.push('');
    for (let i = 0; i < this.data.mistakes.length; i++) {
      const m: MistakeItem = this.data.mistakes[i];
      lines.push('[' + (i + 1).toString() + '] [' + m.subject + '] '
        + (m.mastered ? '已掌握' : '待复习') + ' · 错 ' + m.wrongCount.toString() + ' 次');
      lines.push('题目：' + m.content);
      lines.push('答案：' + m.answer);
      if (m.note.length > 0) {
        lines.push('笔记：' + m.note);
      }
      lines.push('');
    }
    return lines.join('\n');
  }

  saveMistakesExport(): string {
    const text: string = this.exportMistakesText();
    if (this.filesDir.length === 0) {
      return '';
    }
    const path: string = this.filesDir + '/' + EXPORT_PREFIX + QuestionStore.stamp() + '.txt';
    try {
      const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, text);
      } finally {
        fs.closeSync(file);
      }
      return path;
    } catch (e) {
      return '';
    }
  }

  static formatTime(ms: number): string {
    if (ms <= 0) {
      return '—';
    }
    const d = new Date(ms);
    return d.getFullYear().toString() + '-' + QuestionStore.pad2(d.getMonth() + 1) + '-'
      + QuestionStore.pad2(d.getDate()) + ' ' + QuestionStore.pad2(d.getHours()) + ':'
      + QuestionStore.pad2(d.getMinutes());
  }

  static pad2(value: number): string {
    return value < 10 ? '0' + value.toString() : value.toString();
  }

  static stamp(): string {
    const d = new Date();
    return d.getFullYear().toString() + QuestionStore.pad2(d.getMonth() + 1) + QuestionStore.pad2(d.getDate())
      + '-' + QuestionStore.pad2(d.getHours()) + QuestionStore.pad2(d.getMinutes())
      + QuestionStore.pad2(d.getSeconds());
  }
}
