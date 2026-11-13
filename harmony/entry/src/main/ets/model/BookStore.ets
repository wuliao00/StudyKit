import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';

const DATA_FILE: string = 'book_data.json';

/** 一本书（对应 Android books 表的核心字段；Harmony 侧只存本地阅读闭环要用的部分）。 */
export interface BookItem {
  id: string;
  title: string;
  author: string;
  totalPages: number;
  currentPage: number;
  /** 'reading' 在读 / 'finished' 已读完，与 Android Book.STATUS_* 同值。 */
  status: string;
  startedAt: number;
  /** 读完时刻；0 表示还没读完（Android 用可空列，这里用 0 哨兵，JSON 更省事）。 */
  finishedAt: number;
}

/** 一条书摘 / 读书笔记（对应 Android excerpts 表）。pageNo=0 表示没记页码。 */
export interface ExcerptItem {
  id: string;
  bookId: string;
  content: string;
  pageNo: number;
  createdAt: number;
}

export interface BookData {
  books: BookItem[];
  excerpts: ExcerptItem[];
}

/**
 * 书籍 / 书摘的本地存储（独立 JSON 文件 book_data.json，不碰主 Store.ets）。
 *
 * 口径与 Android 版一致：
 * - 进度百分比 = 当前页 / 总页数（总页数 <= 0 时记 0，防除零），四舍五入取整；
 * - 标记「已读完」会把当前页推到总页数并记 finishedAt；改回「在读」清掉 finishedAt，
 *   当前页保留（不硬退回去，读者可能只是手滑点了）；
 * - 删除一本书会连带删除它的全部书摘（与 Android 清库同样的「不留悬空行」口径）。
 */
export class BookStore {
  static readonly STATUS_READING: string = 'reading';
  static readonly STATUS_FINISHED: string = 'finished';

  private static instance: BookStore = new BookStore();
  private filePath: string = '';
  private data: BookData = { books: [], excerpts: [] };
  private inited: boolean = false;

  static get(): BookStore {
    return BookStore.instance;
  }

  static newId(prefix: string): string {
    return prefix + '-' + Date.now().toString() + '-' + Math.floor(Math.random() * 10000).toString();
  }

  static statusLabel(status: string): string {
    return status === BookStore.STATUS_FINISHED ? '已读完' : '在读';
  }

  /** 阅读进度百分比：0..100 的整数；总页数非法时返回 0（不抛、不显示 NaN）。 */
  static progressPercent(book: BookItem): number {
    if (book.totalPages <= 0) {
      return 0;
    }
    const value: number = Math.round(book.currentPage * 100 / book.totalPages);
    if (value < 0) {
      return 0;
    }
    if (value > 100) {
      return 100;
    }
    return value;
  }

  static formatDate(ms: number): string {
    if (ms <= 0) {
      return '—';
    }
    const d: Date = new Date(ms);
    const m: string = d.getMonth() + 1 < 10 ? '0' + (d.getMonth() + 1).toString() : (d.getMonth() + 1).toString();
    const day: string = d.getDate() < 10 ? '0' + d.getDate().toString() : d.getDate().toString();
    return d.getFullYear().toString() + '-' + m + '-' + day;
  }

  // ---------- 生命周期与落盘 ----------

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
        this.data = { books: [], excerpts: [] };
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: BookData = JSON.parse(text) as BookData;
      const out: BookData = { books: [], excerpts: [] };
      if (raw !== null && raw !== undefined) {
        if (raw.books !== undefined && raw.books !== null) {
          out.books = raw.books;
        }
        if (raw.excerpts !== undefined && raw.excerpts !== null) {
          out.excerpts = raw.excerpts;
        }
      }
      this.data = out;
    } catch (e) {
      this.data = { books: [], excerpts: [] };
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

  // ---------- 书籍 CRUD ----------

  /** 全部书：最近开始读的在前。 */
  getBooks(): BookItem[] {
    const result: BookItem[] = this.data.books.slice(0);
    result.sort((a: BookItem, b: BookItem) => b.startedAt - a.startedAt);
    return result;
  }

  getBook(id: string): BookItem | null {
    for (let i = 0; i < this.data.books.length; i++) {
      if (this.data.books[i].id === id) {
        return this.data.books[i];
      }
    }
    return null;
  }

  readingCount(): number {
    let count: number = 0;
    for (let i = 0; i < this.data.books.length; i++) {
      if (this.data.books[i].status !== BookStore.STATUS_FINISHED) {
        count++;
      }
    }
    return count;
  }

  finishedCount(): number {
    return this.data.books.length - this.readingCount();
  }

  async addBook(title: string, author: string, totalPages: number): Promise<BookItem> {
    const now: number = Date.now();
    const item: BookItem = {
      id: BookStore.newId('b'),
      title: title.trim().length > 0 ? title.trim() : '未命名书籍',
      author: author.trim(),
      totalPages: totalPages > 0 ? totalPages : 1,
      currentPage: 0,
      status: BookStore.STATUS_READING,
      startedAt: now,
      finishedAt: 0
    };
    this.data.books.push(item);
    this.save();
    return item;
  }

  /**
   * 编辑书：书名 / 作者 / 总页数 / 当前页 / 状态一起写。
   *
   * 状态与页码互相约束（与 Android markFinished / updateProgress 的口径一致）：
   * - 已读完 → 当前页强制推到总页数、记 finishedAt；
   * - 在读 → 清 finishedAt，当前页收在 0..总页数。
   * 总页数改小后当前页会被夹回来，不会出现「第 300 页 / 共 97 页」。
   */
  async updateBook(id: string, title: string, author: string, totalPages: number,
    currentPage: number, status: string): Promise<boolean> {
    const book: BookItem | null = this.getBook(id);
    if (book === null) {
      return false;
    }
    if (title.trim().length > 0) {
      book.title = title.trim();
    }
    book.author = author.trim();
    book.totalPages = totalPages > 0 ? totalPages : book.totalPages;
    if (status === BookStore.STATUS_FINISHED) {
      book.status = BookStore.STATUS_FINISHED;
      book.currentPage = book.totalPages;
      if (book.finishedAt <= 0) {
        book.finishedAt = Date.now();
      }
    } else {
      book.status = BookStore.STATUS_READING;
      book.finishedAt = 0;
      let page: number = currentPage;
      if (page < 0) {
        page = 0;
      }
      if (page > book.totalPages) {
        page = book.totalPages;
      }
      book.currentPage = page;
    }
    this.save();
    return true;
  }

  /** 只更新进度：到达总页数时自动标记读完（与 Android stepProgress 同一行为）。 */
  async updateProgress(id: string, page: number): Promise<boolean> {
    const book: BookItem | null = this.getBook(id);
    if (book === null) {
      return false;
    }
    let pageValue: number = page;
    if (pageValue < 0) {
      pageValue = 0;
    }
    if (pageValue > book.totalPages) {
      pageValue = book.totalPages;
    }
    book.currentPage = pageValue;
    if (pageValue >= book.totalPages && book.totalPages > 0) {
      book.status = BookStore.STATUS_FINISHED;
      if (book.finishedAt <= 0) {
        book.finishedAt = Date.now();
      }
    } else {
      book.status = BookStore.STATUS_READING;
      book.finishedAt = 0;
    }
    this.save();
    return true;
  }

  async setStatus(id: string, status: string): Promise<boolean> {
    const book: BookItem | null = this.getBook(id);
    if (book === null) {
      return false;
    }
    if (status === BookStore.STATUS_FINISHED) {
      book.status = BookStore.STATUS_FINISHED;
      book.currentPage = book.totalPages;
      if (book.finishedAt <= 0) {
        book.finishedAt = Date.now();
      }
    } else {
      book.status = BookStore.STATUS_READING;
      book.finishedAt = 0;
    }
    this.save();
    return true;
  }

  /** 删除书 + 它的全部书摘。 */
  async deleteBook(id: string): Promise<boolean> {
    const nextBooks: BookItem[] = [];
    let found: boolean = false;
    for (let i = 0; i < this.data.books.length; i++) {
      if (this.data.books[i].id === id) {
        found = true;
      } else {
        nextBooks.push(this.data.books[i]);
      }
    }
    if (!found) {
      return false;
    }
    const nextExcerpts: ExcerptItem[] = [];
    for (let i = 0; i < this.data.excerpts.length; i++) {
      if (this.data.excerpts[i].bookId !== id) {
        nextExcerpts.push(this.data.excerpts[i]);
      }
    }
    this.data.books = nextBooks;
    this.data.excerpts = nextExcerpts;
    this.save();
    return true;
  }

  // ---------- 书摘 / 读书笔记 ----------

  /** 某本书的全部书摘：新的在前。 */
  excerptsOf(bookId: string): ExcerptItem[] {
    const result: ExcerptItem[] = [];
    for (let i = 0; i < this.data.excerpts.length; i++) {
      if (this.data.excerpts[i].bookId === bookId) {
        result.push(this.data.excerpts[i]);
      }
    }
    result.sort((a: ExcerptItem, b: ExcerptItem) => b.createdAt - a.createdAt);
    return result;
  }

  excerptCount(bookId: string): number {
    let count: number = 0;
    for (let i = 0; i < this.data.excerpts.length; i++) {
      if (this.data.excerpts[i].bookId === bookId) {
        count++;
      }
    }
    return count;
  }

  totalExcerptCount(): number {
    return this.data.excerpts.length;
  }

  getExcerpt(id: string): ExcerptItem | null {
    for (let i = 0; i < this.data.excerpts.length; i++) {
      if (this.data.excerpts[i].id === id) {
        return this.data.excerpts[i];
      }
    }
    return null;
  }

  /** 新建书摘；内容为空白时返回 null（页面据此提示）。pageNo<=0 视为没记页码。 */
  async addExcerpt(bookId: string, content: string, pageNo: number): Promise<ExcerptItem | null> {
    if (this.getBook(bookId) === null) {
      return null;
    }
    if (content.trim().length === 0) {
      return null;
    }
    const item: ExcerptItem = {
      id: BookStore.newId('e'),
      bookId: bookId,
      content: content.trim(),
      pageNo: pageNo > 0 ? pageNo : 0,
      createdAt: Date.now()
    };
    this.data.excerpts.push(item);
    this.save();
    return item;
  }

  async updateExcerpt(id: string, content: string, pageNo: number): Promise<boolean> {
    const item: ExcerptItem | null = this.getExcerpt(id);
    if (item === null || content.trim().length === 0) {
      return false;
    }
    item.content = content.trim();
    item.pageNo = pageNo > 0 ? pageNo : 0;
    this.save();
    return true;
  }

  async deleteExcerpt(id: string): Promise<boolean> {
    const next: ExcerptItem[] = [];
    let found: boolean = false;
    for (let i = 0; i < this.data.excerpts.length; i++) {
      if (this.data.excerpts[i].id === id) {
        found = true;
      } else {
        next.push(this.data.excerpts[i]);
      }
    }
    if (!found) {
      return false;
    }
    this.data.excerpts = next;
    this.save();
    return true;
  }
}
