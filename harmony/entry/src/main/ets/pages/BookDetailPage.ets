import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { BookItem, ExcerptItem, BookStore } from '../model/BookStore';
import {
  BookStudyStore,
  ChapterQuizBuilder,
  ChapterSpec,
  ChapterTestRecord,
  ChoiceQuestion,
  ExcerptReview,
  bookTodayIso
} from '../model/BookStudyStore';

const CHAPTER_SIZE_MIN: number = 10;
const CHAPTER_SIZE_MAX: number = 200;
const CHAPTER_SIZE_STEP: number = 10;

/**
 * 书籍详情（G 模块）：只读 BookStore 里的书与书摘（不写 book_data.json 的书字段），
 * 复用 BookStore 公开的书摘增删接口；复习状态/自测记录写自己的 filesDir/book_study.json。
 *
 * 页面能力：阅读进度展示、书摘增删（带复习档位）、1/2/4/7 天到期复习入口、
 * 按页段章节生成 5 题选择题的章节自测。
 */
@Entry
@Component
struct BookDetailPage {
  @State loading: boolean = true;
  @State version: number = 0;
  @State books: BookItem[] = [];
  @State bookId: string = '';

  @State bookTitle: string = '';
  @State bookAuthor: string = '';
  @State bookStatus: string = '';
  @State bookTotal: number = 0;
  @State bookCurrent: number = 0;
  @State bookPercent: number = 0;

  @State excerpts: ExcerptItem[] = [];
  @State addContent: string = '';
  @State addPage: string = '';

  @State dueCount: number = 0;
  @State upcomingLine: string = '';
  @State reviewTotal: number = 0;

  @State chapterSize: number = 20;
  @State chapterIndex: number = -1;
  @State chapters: ChapterSpec[] = [];
  @State quizStarted: boolean = false;
  @State quizDone: boolean = false;
  @State questions: ChoiceQuestion[] = [];
  @State qIndex: number = 0;
  @State qSelected: number = -1;
  @State qCorrect: number = 0;
  @State quizMessage: string = '';
  @State lastTestLine: string = '还没有做过章节自测。';

  private store: BookStore = BookStore.get();
  private study: BookStudyStore = BookStudyStore.get();

  async aboutToAppear(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    try {
      await this.store.init(ctx);
      await this.study.init(ctx);
    } catch (e) {
      this.toast('本地读书数据读取失败，页面可能为空');
    }
    this.parseParams();
    this.refreshAll();
    this.loading = false;
  }

  private parseParams(): void {
    try {
      const raw: Object = router.getParams();
      if (raw === null || raw === undefined) {
        return;
      }
      const params: Record<string, string> = raw as Record<string, string>;
      const byBookId: string = params['bookId'];
      const byId: string = params['id'];
      if (byBookId !== undefined && byBookId !== null && byBookId.length > 0) {
        this.bookId = byBookId;
      } else if (byId !== undefined && byId !== null && byId.length > 0) {
        this.bookId = byId;
      }
    } catch (e) {
      this.bookId = '';
    }
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private today(): string {
    return bookTodayIso();
  }

  private refreshAll(): void {
    const today: string = this.today();
    this.books = this.store.getBooks();
    this.version = this.version + 1;
    if (this.bookId.length === 0) {
      return;
    }
    const book: BookItem | null = this.store.getBook(this.bookId);
    if (book === null) {
      this.bookId = '';
      return;
    }
    this.bookTitle = book.title;
    this.bookAuthor = book.author;
    this.bookStatus = BookStore.statusLabel(book.status);
    this.bookTotal = book.totalPages;
    this.bookCurrent = book.currentPage;
    this.bookPercent = BookStore.progressPercent(book);
    this.excerpts = this.store.excerptsOf(this.bookId);
    this.refreshChapterOptions(book);
    this.pruneReviews();
    this.dueCount = this.study.dueCount(this.bookId, today);
    this.reviewTotal = this.study.reviewCount(this.bookId);
    this.upcomingLine = this.study.upcomingLine(this.bookId, today, 7);
    this.lastTestLine = this.buildLastTestLine();
  }

  private refreshChapterOptions(book: BookItem): void {
    this.chapters = BookStudyStore.chaptersOf(book, this.chapterSize);
    if (this.chapterIndex > this.chapters.length) {
      this.chapterIndex = -1;
    }
  }

  private pruneReviews(): void {
    const existing: Set<string> = new Set<string>();
    for (let i = 0; i < this.books.length; i++) {
      const list: ExcerptItem[] = this.store.excerptsOf(this.books[i].id);
      for (let j = 0; j < list.length; j++) {
        existing.add(list[j].id);
      }
    }
    this.study.pruneMissing(existing);
  }

  private buildLastTestLine(): string {
    const record: ChapterTestRecord | null = this.study.lastTest(this.bookId);
    if (record === null) {
      return '还没有做过章节自测。';
    }
    const scope: string = record.chapterIndex > 0 ? '第 ' + record.chapterIndex.toString() + ' 章' : '全书';
    return '上次自测：' + record.correct.toString() + '/' + record.total.toString() + ' 题正确 · '
      + scope + ' · ' + BookStore.formatDate(record.at);
  }

  private selectBook(id: string): void {
    this.bookId = id;
    this.quizStarted = false;
    this.quizDone = false;
    this.questions = [];
    this.quizMessage = '';
    this.refreshAll();
  }

  private digitsOnly(value: string): string {
    let out: string = '';
    for (let i = 0; i < value.length; i++) {
      const ch: string = value.charAt(i);
      if (ch >= '0' && ch <= '9') {
        out = out + ch;
      }
    }
    return out;
  }

  private intValue(text: string): number {
    if (text.length === 0) {
      return 0;
    }
    return Number(text);
  }

  private progressLine(): string {
    return '第 ' + this.bookCurrent.toString() + ' / ' + this.bookTotal.toString() + ' 页 · '
      + this.bookPercent.toString() + '%';
  }

  private reviewBadge(item: ExcerptItem): string {
    const review: ExcerptReview | null = this.study.reviewOf(item.id);
    if (review === null) {
      return '待排期';
    }
    return BookStudyStore.stageText(review.stage) + ' · ' + BookStudyStore.dueText(review.dueAt, this.today());
  }

  private pagedExcerptCount(): number {
    let count: number = 0;
    for (let i = 0; i < this.excerpts.length; i++) {
      if (this.excerpts[i].pageNo > 0) {
        count++;
      }
    }
    return count;
  }

  private saveExcerpt(): void {
    const content: string = this.addContent.trim();
    if (content.length === 0) {
      this.toast('先写一句想留下的书摘');
      return;
    }
    if (this.bookId.length === 0) {
      return;
    }
    const page: number = this.intValue(this.addPage);
    this.store.addExcerpt(this.bookId, content, page).then((item: ExcerptItem | null) => {
      if (item === null) {
        this.toast('保存失败：确认这本书还在书架上');
        return;
      }
      this.study.ensureReview(item);
      this.addContent = '';
      this.addPage = '';
      this.refreshAll();
      this.toast('书摘已保存，今天到期');
    }).catch(() => {
      this.toast('保存书摘失败，请重试');
    });
  }

  private confirmDeleteExcerpt(item: ExcerptItem): void {
    AlertDialog.show({
      title: '删除书摘',
      message: '删除这条书摘？它的复习进度也会一起清掉，书本身不受影响。',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '删除',
        fontColor: Theme.dangerInk,
        action: () => {
          this.deleteExcerpt(item.id);
        }
      }
    });
  }

  private deleteExcerpt(id: string): void {
    this.store.deleteExcerpt(id).then((removed: boolean) => {
      this.study.removeReview(id);
      if (this.quizStarted) {
        this.resetQuiz();
      }
      this.refreshAll();
      this.toast(removed ? '书摘已删除' : '书摘已经不存在');
    }).catch(() => {
      this.toast('删除书摘失败，请重试');
    });
  }

  private openReview(): void {
    if (this.bookId.length === 0) {
      return;
    }
    router.pushUrl({
      url: 'pages/ExcerptReviewPage',
      params: { bookId: this.bookId }
    }).catch(() => {
      this.toast('打开复习页失败，请重试');
    });
  }

  private changeChapterSize(delta: number): void {
    const next: number = this.chapterSize + delta;
    if (next < CHAPTER_SIZE_MIN || next > CHAPTER_SIZE_MAX) {
      return;
    }
    this.chapterSize = next;
    const book: BookItem | null = this.bookId.length > 0 ? this.store.getBook(this.bookId) : null;
    if (book !== null) {
      this.chapters = BookStudyStore.chaptersOf(book, this.chapterSize);
    }
    this.chapterIndex = -1;
    this.resetQuiz();
  }

  private resetQuiz(): void {
    this.quizStarted = false;
    this.quizDone = false;
    this.questions = [];
    this.qIndex = 0;
    this.qSelected = -1;
    this.qCorrect = 0;
    this.quizMessage = '';
  }

  private generateQuiz(): void {
    if (this.bookId.length === 0) {
      return;
    }
    const book: BookItem | null = this.store.getBook(this.bookId);
    if (book === null) {
      return;
    }
    const questions: ChoiceQuestion[] = ChapterQuizBuilder.build(
      book, this.store.excerptsOf(this.bookId), this.chapterSize, this.chapterIndex);
    if (questions.length === 0) {
      this.quizStarted = false;
      this.questions = [];
      this.quizMessage = '这一范围还没有带页码的书摘，出不了题：先在下方记 1 条带页码的书摘（总页数也要 >= 4），再回来。';
      return;
    }
    this.questions = questions;
    this.quizStarted = true;
    this.quizDone = false;
    this.qIndex = 0;
    this.qSelected = -1;
    this.qCorrect = 0;
    this.quizMessage = '共 ' + questions.length.toString() + ' 题'
      + (questions.length < 5 ? '（带页码书摘还不够 5 题的量，先按现有题练）' : '');
  }

  private currentQuestionSafe(): ChoiceQuestion {
    if (!this.quizStarted || this.qIndex < 0 || this.qIndex >= this.questions.length) {
      const empty: ChoiceQuestion = { prompt: '', options: [], answerIndex: 0, explanation: '' };
      return empty;
    }
    return this.questions[this.qIndex];
  }

  private hasCurrentQuestion(): boolean {
    return this.quizStarted && this.qIndex >= 0 && this.qIndex < this.questions.length;
  }

  private currentPrompt(): string {
    return this.currentQuestionSafe().prompt;
  }

  private currentOptions(): string[] {
    return this.currentQuestionSafe().options;
  }

  private currentExplanation(): string {
    return this.currentQuestionSafe().explanation;
  }

  private currentAnswerIndex(): number {
    if (!this.hasCurrentQuestion()) {
      return -1;
    }
    return this.questions[this.qIndex].answerIndex;
  }

  private currentOptionColor(index: number): string {
    if (this.qSelected < 0) {
      return Theme.card;
    }
    if (index === this.currentAnswerIndex()) {
      return Theme.successSoft;
    }
    if (index === this.qSelected) {
      return Theme.dangerSoft;
    }
    return Theme.card;
  }

  private currentOptionInk(index: number): string {
    if (this.qSelected < 0) {
      return Theme.primaryText;
    }
    if (index === this.currentAnswerIndex()) {
      return Theme.successInk;
    }
    if (index === this.qSelected) {
      return Theme.dangerInk;
    }
    return Theme.secondaryText;
  }

  private isLastQuestion(): boolean {
    return this.qIndex + 1 >= this.questions.length;
  }

  private chooseOption(index: number): void {
    if (!this.hasCurrentQuestion() || this.qSelected >= 0) {
      return;
    }
    this.qSelected = index;
    if (index === this.currentAnswerIndex()) {
      this.qCorrect = this.qCorrect + 1;
      this.toast('答对了');
    }
  }

  private optionLetter(index: number): string {
    const letters: string[] = ['A', 'B', 'C', 'D', 'E', 'F'];
    return index < letters.length ? letters[index] : '?';
  }

  private nextQuestion(): void {
    if (this.qSelected < 0) {
      return;
    }
    if (this.qIndex + 1 >= this.questions.length) {
      this.finishQuiz();
      return;
    }
    this.qIndex = this.qIndex + 1;
    this.qSelected = -1;
  }

  private finishQuiz(): void {
    this.quizDone = true;
    this.study.recordTest(this.bookId, this.chapterIndex, this.chapterSize, this.questions.length, this.qCorrect);
    this.lastTestLine = this.buildLastTestLine();
    this.version = this.version + 1;
    this.toast('自测完成：' + this.qCorrect.toString() + '/' + this.questions.length.toString());
  }

  // ---------- 页面 ----------

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text(this.bookId.length > 0 ? '书籍详情' : '选择一本书').fontSize(26)
          .fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text(this.bookId.length > 0
          ? this.bookTitle + ' · ' + this.bookStatus
          : '从书架挑一本，看进度、记书摘、做章节自测')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      if (this.bookId.length > 0) {
        Button('换书').height(34).fontSize(12)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => {
            this.bookId = '';
            this.resetQuiz();
            this.refreshAll();
          })
      }
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  bookPicker() {
    Column() {
      if (this.books.length === 0) {
        Column() {
          Text('书架还是空的').fontSize(18).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Text('书籍详情是书架里某本书的深入页：先去「读书笔记」加一本正在读的书，再回来记书摘、做自测。')
            .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 }).lineHeight(19)
          Button('去读书笔记页添加').width('100%').height(44).margin({ top: 14 })
            .backgroundColor(Theme.accentInk).fontColor(Color.White)
            .onClick(() => {
              router.pushUrl({ url: 'pages/BooksPage' }).catch(() => {
                this.toast('打开书架失败，请从主页面进入读书笔记');
              });
            })
        }.width('100%').alignItems(HorizontalAlign.Start).padding(20)
        .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
      } else {
        ForEach(this.books, (book: BookItem) => {
          Row() {
            Column() {
              Text(book.title).fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text((book.author.length > 0 ? book.author : '作者未填') + ' · 第 '
                + book.currentPage.toString() + '/' + book.totalPages.toString() + ' 页 · 摘录 '
                + this.store.excerptCount(book.id).toString() + ' 条')
                .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 4 })
            }.alignItems(HorizontalAlign.Start).layoutWeight(1)
            Text('进入 ›').fontSize(13).fontColor(Theme.accentInk)
          }
          .width('100%').padding(16).margin({ top: 10 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          .border({ width: 1, color: Theme.divider })
          .onClick(() => { this.selectBook(book.id); })
        }, (book: BookItem) => book.id + '-' + this.version.toString())
      }
    }.width('100%')
  }

  @Builder
  bookSummary() {
    Column() {
      Row() {
        Text(this.bookTitle).fontSize(22).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
          .maxLines(2).layoutWeight(1)
        Text(this.bookStatus).fontSize(10)
          .fontColor(this.bookStatus === '已读完' ? Theme.successInk : Theme.accentInk)
          .backgroundColor(this.bookStatus === '已读完' ? Theme.successSoft : Theme.accentSoft)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8)
      }.width('100%').alignItems(VerticalAlign.Top)
      Text((this.bookAuthor.length > 0 ? this.bookAuthor : '作者未填') + ' · ' + this.progressLine())
        .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 6 })
      Progress({ value: this.bookPercent, total: 100, type: ProgressType.Linear })
        .color(this.bookStatus === '已读完' ? Theme.successInk : Theme.accent)
        .backgroundColor(Theme.divider).width('100%').height(6).margin({ top: 10 })
      Text('阅读进度与书摘都来自本机 book_data.json；这一页只读书籍信息，'
        + '更新页码/改状态请回「读书笔记」页。').fontSize(10).fontColor(Theme.secondaryText)
        .margin({ top: 8 }).lineHeight(16)
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 10 })
  }

  @Builder
  reviewCard() {
    Column() {
      Row() {
        Text('书摘复习').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text('今天到期 ' + this.dueCount.toString() + ' 张').fontSize(11)
          .fontColor(this.dueCount > 0 ? Theme.goldInk : Theme.secondaryText)
          .backgroundColor(this.dueCount > 0 ? Theme.goldSoft : Theme.cardWarm)
          .padding({ left: 8, right: 8, top: 4, bottom: 4 }).borderRadius(9)
      }.width('100%')
      Text('复习节奏：新书摘当天入队；记住后按 1 / 2 / 4 / 7 天往后排一档，没记住回到第一档（1 天后再看）。'
        + '到期日 <= 今天的就进今天的队列。').fontSize(11).fontColor(Theme.secondaryText)
        .margin({ top: 5 }).lineHeight(17)
      Text(this.upcomingLine).fontSize(10).fontColor(Theme.secondaryText).margin({ top: 6 }).lineHeight(16)
      Row() {
        Button(this.dueCount > 0 ? '开始复习（' + this.dueCount.toString() + ' 张到期）' : '今天没有到期书摘')
          .layoutWeight(1).height(44).fontSize(14)
          .backgroundColor(this.dueCount > 0 ? Theme.accentInk : Theme.cardWarm)
          .fontColor(this.dueCount > 0 ? Color.White : Theme.secondaryText)
          .enabled(this.dueCount > 0)
          .onClick(() => { this.openReview(); })
      }.width('100%').margin({ top: 12 })
      Text('已排期书摘 ' + this.reviewTotal.toString() + ' 张（含今天及以后）')
        .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 8 })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 10 })
  }

  @Builder
  excerptCard() {
    Column() {
      Row() {
        Text('书摘 / 读书笔记').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text(this.excerpts.length.toString() + ' 条 · 带页码 ' + this.pagedExcerptCount().toString())
          .fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%')

      ForEach(this.excerpts, (item: ExcerptItem) => {
        Column() {
          Text(item.content).fontSize(13).fontColor(Theme.primaryText).width('100%').lineHeight(19)
          Row() {
            Text(item.pageNo > 0 ? '第 ' + item.pageNo.toString() + ' 页' : '未记页码')
              .fontSize(10).fontColor(Theme.secondaryText)
            Text(' · ' + BookStore.formatDate(item.createdAt)).fontSize(10)
              .fontColor(Theme.secondaryText)
            Text(' · ' + this.reviewBadge(item)).fontSize(10).fontColor(Theme.goldInk)
            Blank()
            Text('删除').fontSize(11).fontColor(Theme.dangerInk)
              .padding({ left: 8, right: 4, top: 4, bottom: 4 })
              .onClick(() => { this.confirmDeleteExcerpt(item); })
          }.width('100%').margin({ top: 6 }).alignItems(VerticalAlign.Center)
        }
        .width('100%').padding(12).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).borderRadius(10)
      }, (item: ExcerptItem) => item.id + '-' + this.version.toString())

      if (this.excerpts.length === 0) {
        Text('还没有书摘。记下打动你的句子，之后按 1/2/4/7 天回看；带页码的书摘才能出章节自测题。')
          .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 }).lineHeight(17)
      }

      TextArea({ text: this.addContent, placeholder: '摘录打动你的句子…（必填）' })
        .width('100%').height(86).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.addContent = value; })
      Row() {
        TextInput({ text: this.addPage, placeholder: '页码（选填，自测需要）' })
          .layoutWeight(1).height(40)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
          .onChange((value: string) => { this.addPage = this.digitsOnly(value); })
        Button('保存书摘').height(40).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveExcerpt(); })
      }.width('100%').margin({ top: 8 })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 10 })
  }

  @Builder
  chapterChips() {
    Scroll() {
      Row() {
        Text('全书')
          .fontSize(12)
          .fontColor(this.chapterIndex === -1 ? Color.White : Theme.secondaryText)
          .backgroundColor(this.chapterIndex === -1 ? Theme.accentInk : Theme.cardWarm)
          .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(13).margin({ right: 8 })
          .onClick(() => {
            this.chapterIndex = -1;
            this.resetQuiz();
          })
        ForEach(this.chapters, (chapter: ChapterSpec) => {
          Text('第 ' + chapter.index.toString() + ' 章')
            .fontSize(12)
            .fontColor(this.chapterIndex === chapter.index ? Color.White : Theme.secondaryText)
            .backgroundColor(this.chapterIndex === chapter.index ? Theme.accentInk : Theme.cardWarm)
            .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(13).margin({ right: 8 })
            .onClick(() => {
              this.chapterIndex = chapter.index;
              this.resetQuiz();
            })
        }, (chapter: ChapterSpec) => chapter.index.toString() + '-' + this.chapterSize.toString())
      }
    }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
  }

  @Builder
  quizCard() {
    Column() {
      Row() {
        Text('章节自测').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text('5 题选择题 · 从书摘生成').fontSize(10).fontColor(Theme.secondaryText)
      }.width('100%')
      Text('按页段切章（每章页数可调），从这一范围的书摘生成选择题；题面与选项都来自你记下的真实书摘。')
        .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 5 }).lineHeight(17)

      Row() {
        Text('每章页数').fontSize(11).fontColor(Theme.secondaryText)
        Blank()
        Button('−').width(34).height(32).fontSize(18)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
          .onClick(() => { this.changeChapterSize(-CHAPTER_SIZE_STEP); })
        Text(this.chapterSize.toString() + ' 页').fontSize(12).fontWeight(FontWeight.Medium)
          .fontColor(Theme.primaryText).width(58).textAlign(TextAlign.Center)
        Button('+').width(34).height(32).fontSize(18)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
          .onClick(() => { this.changeChapterSize(CHAPTER_SIZE_STEP); })
      }.width('100%').margin({ top: 10 }).alignItems(VerticalAlign.Center)

      this.chapterChips()

      Text(this.lastTestLine).fontSize(10).fontColor(Theme.secondaryText).margin({ top: 8 })

      if (this.quizMessage.length > 0) {
        Text(this.quizMessage).fontSize(11).fontColor(Theme.warningInk)
          .backgroundColor(Theme.warningSoft).padding(10).borderRadius(10)
          .width('100%').margin({ top: 8 }).lineHeight(17)
      }

      if (!this.quizStarted) {
        Button('生成 5 题自测').width('100%').height(44).fontSize(14).margin({ top: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.generateQuiz(); })
      } else if (this.hasCurrentQuestion()) {
        Column() {
          Row() {
            Text('第 ' + (this.qIndex + 1).toString() + ' / ' + this.questions.length.toString() + ' 题')
              .fontSize(11).fontColor(Theme.secondaryText)
            Blank()
            Text(this.chapterIndex > 0 ? '第 ' + this.chapterIndex.toString() + ' 章' : '全书')
              .fontSize(10).fontColor(Theme.secondaryText)
          }.width('100%')
          Text(this.currentPrompt()).fontSize(15).fontWeight(FontWeight.Medium)
            .fontColor(Theme.primaryText).width('100%').margin({ top: 8 }).lineHeight(22)
          ForEach(this.currentOptions(), (option: string, index: number) => {
            Row() {
              Text(this.optionLetter(index)).fontSize(12).fontWeight(FontWeight.Medium)
                .fontColor(this.currentOptionInk(index))
                .width(24)
              Text(option).fontSize(13).fontColor(this.currentOptionInk(index))
                .layoutWeight(1).lineHeight(19)
            }
            .width('100%').padding(12).margin({ top: 8 })
            .backgroundColor(this.currentOptionColor(index))
            .borderRadius(10)
            .border({ width: 1, color: this.qSelected < 0 ? Theme.divider : Theme.card })
            .onClick(() => { this.chooseOption(index); })
          }, (option: string, index: number) => index.toString() + '-' + option)

          if (this.qSelected >= 0) {
            Text(this.currentExplanation()).fontSize(11).fontColor(Theme.secondaryText)
              .width('100%').margin({ top: 8 }).lineHeight(17)
            Button(this.isLastQuestion() ? '看结果' : '下一题')
              .width('100%').height(42).fontSize(14).margin({ top: 10 })
              .backgroundColor(Theme.accentInk).fontColor(Color.White)
              .onClick(() => { this.nextQuestion(); })
          }
        }.width('100%').margin({ top: 10 })
      }

      if (this.quizDone) {
        Column() {
          Text('这一轮自测：' + this.qCorrect.toString() + ' / ' + this.questions.length.toString() + ' 题正确')
            .fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Text(this.qCorrect * 2 >= this.questions.length
            ? '过半了：这一页段记得还行，明天再复习一轮书摘。'
            : '错得多的题回看对应书摘；把没记住的书摘标出来，复习时多停两秒。')
            .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 6 }).lineHeight(17)
          Row() {
            Button('再来一轮').layoutWeight(1).height(40).fontSize(13)
              .backgroundColor(Theme.accentInk).fontColor(Color.White)
              .onClick(() => { this.generateQuiz(); })
            Button('收起').layoutWeight(1).height(40).fontSize(13).margin({ left: 10 })
              .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
              .onClick(() => { this.resetQuiz(); })
          }.width('100%').margin({ top: 10 })
        }.width('100%').padding(12).margin({ top: 10 })
        .backgroundColor(this.qCorrect * 2 >= this.questions.length ? Theme.successSoft : Theme.warningSoft)
        .borderRadius(12)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 10 })
  }

  build() {
    Scroll() {
      Column() {
        this.header()
        if (this.loading) {
          Column() {
            LoadingProgress().width(40).height(40).color(Theme.accent)
            Text('正在读取书架与复习记录').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
          }.width('100%').height(240).justifyContent(FlexAlign.Center)
        } else if (this.bookId.length === 0) {
          this.bookPicker()
        } else {
          this.bookSummary()
          this.reviewCard()
          this.excerptCard()
          this.quizCard()
          Text('数据说明：书籍与书摘在应用本地 book_data.json（书籍信息只读），'
            + '复习档位与自测记录在本页自己的 book_study.json；都不上传、不需要账号。')
            .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 14, bottom: 28 }).lineHeight(16)
        }
      }
      .width('100%').constraintSize({ maxWidth: 760 })
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .width('100%').height('100%').backgroundColor(Theme.bg).scrollBar(BarState.Off)
  }
}
