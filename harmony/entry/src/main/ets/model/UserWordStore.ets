import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';
import { WordItem, setUserWords } from './Words';

/**
 * 自定义词库（task-12 D）。
 *
 * 与内置 Words.ets / Store.ets 完全解耦：只负责"用户自己加的单词"这一张表，
 * 独立落盘到 filesDir/studykit_user_words.json，由页面经 UserWordStore.get() 读写。
 * Lead 后续把它并入 SRS / Quiz 数据源时，只需要调用 allWords() 或 toWordItems()。
 *
 * 设计口径：
 * - 身份用 id（时间戳+自增序号），不用 word 当主键 —— 改名不该改变"这一条"；
 * - word 同名单词不允许重复（大小写不敏感），避免 SRS 里同一个词两张卡；
 * - 导入/导出都用同一份纯文本格式：word|phonetic|meaning|example|book（book 可省略）；
 * - 所有写入都先落内存再整份 JSON 覆盖写，与 Store.ets 同一个写法，没有半份文件；
 * - 不联网、不需要账号、不碰系统剪贴板（复制由页面用 pasteboard 完成）。
 */

/** 自定义词库的默认词书标签（页面"自建"一档）。 */
export const USER_BOOK_CUSTOM: string = 'custom';

/** 落盘文件名（与 StudyStore 的 studykit_data.json 分开，互不覆盖）。 */
export const USER_WORD_FILE: string = 'studykit_user_words.json';

const NL: string = String.fromCharCode(10);

/** 一条用户单词。字段与内置 WordItem 对齐，另加 id 与时间戳供 CRUD 使用。 */
export interface UserWord {
  id: string;
  word: string;
  phonetic: string;
  meaning: string;
  example: string;
  book: string;
  createdAt: number;
  updatedAt: number;
}

/** 添加/编辑时页面交进来的草稿（没有 id / 时间戳）。 */
export interface UserWordDraft {
  word: string;
  phonetic: string;
  meaning: string;
  example: string;
  book: string;
}

/** 整份落盘文件的结构；version 留给以后迁移。 */
export interface UserWordsData {
  version: number;
  words: UserWord[];
}

/** add / update 的结果：成功与否 + 一句人话 + 落库后的条目。 */
export interface UserWordResult {
  ok: boolean;
  message: string;
  item: UserWord | null;
}

/** 批量导入的计数。 */
export interface ImportSummary {
  added: number;
  skipped: number;
  failed: number;
  book: string;
}

/** 单行解析的中间结果（valid 表示这一行有没有形成一条可入库的词）。 */
interface ParsedWordLine {
  valid: boolean;
  word: string;
  phonetic: string;
  meaning: string;
  example: string;
  book: string;
}

const DATA_VERSION: number = 1;
const BOOK_SEPARATOR: string = '|';
const FULLWIDTH_SEPARATOR: string = '｜';

export class UserWordStore {
  private static instance: UserWordStore = new UserWordStore();

  private filesDir: string = '';
  private filePath: string = '';
  private data: UserWordsData = UserWordStore.emptyData();
  private seq: number = 0;

  static get(): UserWordStore {
    return UserWordStore.instance;
  }

  static emptyData(): UserWordsData {
    return { version: DATA_VERSION, words: [] };
  }

  /** 由页面在 aboutToAppear 里注入 UIAbilityContext；重复调用是幂等的。 */
  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.filePath.length > 0) {
      return;
    }
    this.filesDir = context.filesDir;
    this.filePath = this.filesDir + '/' + USER_WORD_FILE;
    await this.load();
  }

  private async load(): Promise<void> {
    try {
      if (!fs.accessSync(this.filePath)) {
        this.data = UserWordStore.emptyData();
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: UserWordsData = JSON.parse(text) as UserWordsData;
      this.data = UserWordStore.normalize(raw);
    } catch (e) {
      this.data = UserWordStore.emptyData();
    }
  }

  private static normalize(raw: UserWordsData): UserWordsData {
    const out: UserWordsData = UserWordStore.emptyData();
    if (raw === null || raw === undefined) {
      return out;
    }
    if (raw.words !== undefined && raw.words !== null) {
      const list: UserWord[] = [];
      for (let i = 0; i < raw.words.length; i++) {
        const item: UserWord = raw.words[i];
        if (item === null || item === undefined) {
          continue;
        }
        if (item.word === undefined || item.word === null || item.word.length === 0) {
          continue;
        }
        list.push({
          id: item.id !== undefined && item.id !== null && item.id.length > 0 ? item.id : 'w-' + i.toString(),
          word: item.word,
          phonetic: item.phonetic !== undefined && item.phonetic !== null ? item.phonetic : '',
          meaning: item.meaning !== undefined && item.meaning !== null ? item.meaning : '',
          example: item.example !== undefined && item.example !== null ? item.example : '',
          book: item.book !== undefined && item.book !== null && item.book.length > 0
            ? item.book : USER_BOOK_CUSTOM,
          createdAt: item.createdAt !== undefined && item.createdAt !== null ? item.createdAt : 0,
          updatedAt: item.updatedAt !== undefined && item.updatedAt !== null ? item.updatedAt : 0
        });
      }
      out.words = list;
    }
    return out;
  }

  private async save(): Promise<void> {
    setUserWords(this.toWordItems());
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

  // ---------- 读 ----------

  allWords(): UserWord[] {
    return this.data.words.slice();
  }

  count(): number {
    return this.data.words.length;
  }

  findById(id: string): UserWord | null {
    for (let i = 0; i < this.data.words.length; i++) {
      if (this.data.words[i].id === id) {
        return this.data.words[i];
      }
    }
    return null;
  }

  /** 同名判重（大小写不敏感、忽略首尾空白）；skipId 用于编辑时跳过自己。 */
  wordExists(word: string, skipId: string): boolean {
    const target: string = word.trim().toLowerCase();
    if (target.length === 0) {
      return false;
    }
    for (let i = 0; i < this.data.words.length; i++) {
      const item: UserWord = this.data.words[i];
      if (item.id === skipId) {
        continue;
      }
      if (item.word.trim().toLowerCase() === target) {
        return true;
      }
    }
    return false;
  }

  /** 搜索：单词 / 音标 / 释义 / 例句 / 词书标签，大小写不敏感；空关键词返回全部。 */
  search(keyword: string): UserWord[] {
    const key: string = keyword.trim().toLowerCase();
    if (key.length === 0) {
      return this.data.words.slice();
    }
    const result: UserWord[] = [];
    for (let i = 0; i < this.data.words.length; i++) {
      const item: UserWord = this.data.words[i];
      if (item.word.toLowerCase().indexOf(key) >= 0
        || item.phonetic.toLowerCase().indexOf(key) >= 0
        || item.meaning.toLowerCase().indexOf(key) >= 0
        || item.example.toLowerCase().indexOf(key) >= 0
        || this.bookLabel(item.book).toLowerCase().indexOf(key) >= 0) {
        result.push(item);
      }
    }
    return result;
  }

  /** 词书标签的人话名字；未知 id 原样返回（导入的第五列可能是任意标签）。 */
  bookLabel(book: string): string {
    if (book === USER_BOOK_CUSTOM || book.length === 0) {
      return '自建';
    }
    return book;
  }

  // ---------- 写 ----------

  async add(draft: UserWordDraft): Promise<UserWordResult> {
    const problem: string = this.validate(draft, '');
    if (problem.length > 0) {
      return { ok: false, message: problem, item: null };
    }
    const now: number = Date.now();
    this.seq = this.seq + 1;
    const item: UserWord = {
      id: 'uw-' + now.toString() + '-' + this.seq.toString(),
      word: draft.word.trim(),
      phonetic: draft.phonetic.trim(),
      meaning: draft.meaning.trim(),
      example: draft.example.trim(),
      book: draft.book.length > 0 ? draft.book : USER_BOOK_CUSTOM,
      createdAt: now,
      updatedAt: now
    };
    this.data.words.push(item);
    await this.save();
    return { ok: true, message: '已添加：' + item.word, item: item };
  }

  async update(id: string, draft: UserWordDraft): Promise<UserWordResult> {
    let index: number = -1;
    for (let i = 0; i < this.data.words.length; i++) {
      if (this.data.words[i].id === id) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      return { ok: false, message: '这条单词已经不在了，刷新后再试', item: null };
    }
    const problem: string = this.validate(draft, id);
    if (problem.length > 0) {
      return { ok: false, message: problem, item: null };
    }
    const old: UserWord = this.data.words[index];
    const next: UserWord = {
      id: old.id,
      word: draft.word.trim(),
      phonetic: draft.phonetic.trim(),
      meaning: draft.meaning.trim(),
      example: draft.example.trim(),
      book: draft.book.length > 0 ? draft.book : USER_BOOK_CUSTOM,
      createdAt: old.createdAt,
      updatedAt: Date.now()
    };
    this.data.words[index] = next;
    await this.save();
    return { ok: true, message: '已保存：' + next.word, item: next };
  }

  async remove(id: string): Promise<boolean> {
    const next: UserWord[] = [];
    let removed: boolean = false;
    for (let i = 0; i < this.data.words.length; i++) {
      if (this.data.words[i].id === id) {
        removed = true;
      } else {
        next.push(this.data.words[i]);
      }
    }
    if (!removed) {
      return false;
    }
    this.data.words = next;
    await this.save();
    return true;
  }

  private validate(draft: UserWordDraft, skipId: string): string {
    if (draft.word.trim().length === 0) {
      return '单词不能为空';
    }
    if (draft.meaning.trim().length === 0) {
      return '释义不能为空（背单词时至少要看到中文意思）';
    }
    if (this.wordExists(draft.word, skipId)) {
      return '已经有同名单词了：' + draft.word.trim();
    }
    return '';
  }

  // ---------- 批量导入 / 导出 ----------

  /**
   * 批量粘贴导入。每行一条，格式 word|phonetic|meaning|example|book：
   * - 只有一个分隔符 => word|meaning；
   * - 三个字段 => word|phonetic|meaning；
   * - 四个及以上 => word|phonetic|meaning|example（第五列是词书标签，可省略）；
   * - 空行、以 # 开头的行忽略；word/meaning 缺失计入 failed；重名计入 skipped。
   * 全角竖线 ｜ 也接受。
   */
  async importLines(text: string, defaultBook: string): Promise<ImportSummary> {
    const rawLines: string[] = text.split(NL);
    const summary: ImportSummary = { added: 0, skipped: 0, failed: 0, book: defaultBook };
    const book: string = defaultBook.length > 0 ? defaultBook : USER_BOOK_CUSTOM;
    const pending: UserWord[] = [];
    const seen: string[] = [];
    for (let i = 0; i < rawLines.length; i++) {
      const line: string = rawLines[i].trim();
      if (line.length === 0 || line.charAt(0) === '#') {
        continue;
      }
      const parsed: ParsedWordLine = UserWordStore.parseLine(line, book);
      if (!parsed.valid) {
        summary.failed = summary.failed + 1;
        continue;
      }
      const lower: string = parsed.word.toLowerCase();
      if (this.wordExists(parsed.word, '') || seen.indexOf(lower) >= 0) {
        summary.skipped = summary.skipped + 1;
        continue;
      }
      seen.push(lower);
      const now: number = Date.now();
      this.seq = this.seq + 1;
      pending.push({
        id: 'uw-' + now.toString() + '-' + this.seq.toString() + '-i' + i.toString(),
        word: parsed.word,
        phonetic: parsed.phonetic,
        meaning: parsed.meaning,
        example: parsed.example,
        book: parsed.book,
        createdAt: now,
        updatedAt: now
      });
    }
    for (let i = 0; i < pending.length; i++) {
      this.data.words.push(pending[i]);
    }
    summary.added = pending.length;
    if (summary.added > 0) {
      await this.save();
    }
    return summary;
  }

  private static parseLine(line: string, defaultBook: string): ParsedWordLine {
    const normalized: string = line.split(FULLWIDTH_SEPARATOR).join(BOOK_SEPARATOR);
    const parts: string[] = normalized.split(BOOK_SEPARATOR);
    const empty: ParsedWordLine = {
      valid: false, word: '', phonetic: '', meaning: '', example: '', book: defaultBook
    };
    if (parts.length < 2) {
      return empty;
    }
    const word: string = parts[0].trim();
    let phonetic: string = '';
    let meaning: string = '';
    let example: string = '';
    let book: string = defaultBook;
    if (parts.length === 2) {
      meaning = parts[1].trim();
    } else if (parts.length === 3) {
      phonetic = parts[1].trim();
      meaning = parts[2].trim();
    } else {
      phonetic = parts[1].trim();
      meaning = parts[2].trim();
      example = parts[3].trim();
      const bookPart: string = parts[4] !== undefined ? parts[4].trim() : '';
      if (bookPart.length > 0) {
        book = bookPart;
      }
    }
    if (word.length === 0 || meaning.length === 0) {
      return empty;
    }
    return { valid: true, word: word, phonetic: phonetic, meaning: meaning, example: example, book: book };
  }

  /** 导出全部为纯文本（可直接再粘贴导入；book 是第五列）。 */
  exportText(): string {
    const lines: string[] = [];
    for (let i = 0; i < this.data.words.length; i++) {
      const item: UserWord = this.data.words[i];
      lines.push(item.word + BOOK_SEPARATOR + item.phonetic + BOOK_SEPARATOR + item.meaning
        + BOOK_SEPARATOR + item.example + BOOK_SEPARATOR + item.book);
    }
    return lines.join(NL);
  }

  /** 导出到 filesDir，返回绝对路径；页面负责提示/复制路径。 */
  async exportToFile(): Promise<string> {
    if (this.filesDir.length === 0) {
      throw new Error('本地目录还没准备好');
    }
    const name: string = 'studykit_words_export-' + UserWordStore.timestamp() + '.txt';
    const path: string = this.filesDir + '/' + name;
    const content: string = '# StudyKit 自定义词库导出' + NL
      + '# 每行：word|phonetic|meaning|example|book（可直接在「批量导入」里粘回）' + NL
      + '# 条数：' + this.data.words.length.toString() + NL
      + this.exportText();
    const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(file.fd, content);
    } finally {
      fs.closeSync(file);
    }
    return path;
  }

  /** 给 SRS / Quiz 数据源用的视图：内置 WordItem 结构与自定义词一一对应。 */
  toWordItems(): WordItem[] {
    const out: WordItem[] = [];
    for (let i = 0; i < this.data.words.length; i++) {
      const item: UserWord = this.data.words[i];
      out.push({
        word: item.word,
        phonetic: item.phonetic,
        meaning: item.meaning,
        example: item.example,
        book: item.book
      });
    }
    return out;
  }

  private static timestamp(): string {
    const d = new Date();
    return d.getFullYear().toString()
      + UserWordStore.pad2(d.getMonth() + 1) + UserWordStore.pad2(d.getDate())
      + '-' + UserWordStore.pad2(d.getHours()) + UserWordStore.pad2(d.getMinutes())
      + UserWordStore.pad2(d.getSeconds());
  }

  private static pad2(value: number): string {
    return value < 10 ? '0' + value.toString() : value.toString();
  }
}
