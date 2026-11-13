import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { Theme } from '../common/Theme';
import {
  BOOK_ALL, WORD_BANK, WORD_BOOKS, WordBook, WordItem, allWords, setUserWords, bookName, booksOf, bookWordCount, lookupWord, wordsOfBook
} from '../model/Words';
import { StudyStore } from '../model/Store';
import { Srs, SrsCard } from '../model/Srs';
import { QuizBuilder, QuizQuestion, QuizSession } from '../model/Quiz';
import { DEMO_QUESTIONS, DEMO_SECONDS, DemoQuestion } from '../model/Demo';
import { SignInCell, SignInDay, SignInMonth, SignInStats, SignInSummary } from '../model/SignIn';
import { UserWordStore } from '../model/UserWordStore';

const TAB_HOME: number = 0;
const TAB_SIGN: number = 1;
const TAB_WORDS: number = 2;
const TAB_PRACTICE: number = 3;
const TAB_PROFILE: number = 4;

const REVIEW_TARGET: number = 5;
const QUIZ_TARGET: number = 3;
const TASK_TOTAL: number = 3;
const MEMORY_CURVE_TARGET: number = 100;

const PRACTICE_MENU: number = 0;
const PRACTICE_QUIZ: number = 1;
const PRACTICE_RESULT: number = 2;
const PRACTICE_DEMO_QUIZ: number = 3;
const PRACTICE_DEMO_RESULT: number = 4;

const POSTER_NONE: number = 0;
const POSTER_REPORT: number = 1;
const POSTER_SIGN: number = 2;
const POSTER_WIDTH: number = 720;
const POSTER_HEIGHT: number = 1040;

@Entry
@Component
struct Index {
  @State currentTab: number = TAB_HOME;
  @State loading: boolean = true;
  @State viewVersion: number = 0;
  @State today: string = SignInStats.today();

  @State summary: SignInSummary = SignInStats.emptySummary();
  @State selectedBook: string = 'cet4';
  @State dueCount: number = 0;
  @State masteredCount: number = 0;
  @State learningCount: number = 0;
  @State accuracy: number = -1;
  @State streak: number = 0;
  @State totalDays: number = 0;
  @State todayReviewed: number = 0;
  @State todayQuiz: number = 0;
  @State wrongNames: string[] = [];
  @State hasUndo: boolean = false;
  @State reviewTotal: number = 0;
  @State demoImported: boolean = false;

  @State wordRevealed: boolean = false;
  @State lastReviewedWord: string = '';

  @State monthOffset: number = 0;

  @State practiceMode: number = PRACTICE_MENU;
  @State quizQuestions: QuizQuestion[] = [];
  @State quizIndex: number = 0;
  @State quizSelected: number = -1;
  @State quizCorrect: number = 0;
  @State quizWrongWords: string[] = [];
  @State quizFromWrong: boolean = false;
  @State resultTotal: number = 0;
  @State resultCorrect: number = 0;

  @State demoIndex: number = 0;
  @State demoSelected: number = -1;
  @State demoCorrect: number = 0;
  @State demoSeconds: number = DEMO_SECONDS;
  @State demoUsed: number = 0;
  @State demoTimedOut: boolean = false;

  @State posterKind: number = POSTER_NONE;
  @State posterData: string = '';
  @State posterPath: string = '';
  @State posterError: string = '';

  private store: StudyStore = StudyStore.get();
  private tabsController: TabsController = new TabsController();
  private demoInterval: number = -1;
  private filesDir: string = '';

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.filesDir = context.filesDir;
    this.store.init(context).then(() => {
      UserWordStore.get().init(context).then(() => {
        setUserWords(UserWordStore.get().toWordItems());
        this.reload();
      }).catch(() => { this.reload(); });
    }).catch(() => {
      this.loading = false;
      this.toast('本地数据读取失败，已从空白数据开始');
      this.reload();
    });
  }

  async reload(): Promise<void> {
    this.today = SignInStats.today();
    const cards: SrsCard[] = this.store.cards;
    this.selectedBook = this.store.bookId;
    this.demoImported = this.store.demoImported;
    this.reviewTotal = this.store.reviewTotalCount();
    this.summary = SignInStats.summary(this.store.signIns, this.today);
    this.dueCount = this.scopedDueCount();
    this.masteredCount = Srs.masteredCount(cards);
    this.learningCount = cards.length;
    const reviewTotalAttempts: number = Srs.totalCorrect(cards) + Srs.totalWrong(cards);
    const attempts: number = reviewTotalAttempts + this.store.quizTotalCount();
    const hits: number = Srs.totalCorrect(cards) + this.store.quizCorrectCount();
    this.accuracy = attempts <= 0 ? -1 : Math.round(hits * 100 / attempts);
    this.streak = this.store.studyStreak(this.today);
    this.totalDays = this.store.studyTotalDays();
    this.todayReviewed = this.store.todayReviewCount(this.today);
    this.todayQuiz = this.store.todayQuizCount(this.today);
    this.wrongNames = this.store.wrongWordNames();
    this.hasUndo = this.store.hasUndoReview();
    this.loading = false;
    this.viewVersion = this.viewVersion + 1;
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private goTab(tab: number): void {
    if (tab !== TAB_PRACTICE) {
      this.stopDemoTimer();
      if (this.practiceMode === PRACTICE_QUIZ) {
        this.practiceMode = PRACTICE_MENU;
      }
    }
    this.currentTab = tab;
    this.tabsController.changeIndex(tab);
    this.reload();
  }

  private dateLabel(): string {
    const now = new Date();
    const week: string[] = ['日', '一', '二', '三', '四', '五', '六'];
    return (now.getMonth() + 1).toString() + ' 月 ' + now.getDate().toString() + ' 日 · 周' + week[now.getDay()];
  }

  private productSubtitle(): string {
    return 'StudyKit · 错题本 · 背单词 · 打卡';
  }

  // ---------- 词书 ----------

  private bookPool(): WordItem[] {
    return wordsOfBook(this.selectedBook);
  }

  private currentBookName(): string {
    return bookName(this.selectedBook);
  }

  private poolHasWord(word: string): boolean {
    if (this.selectedBook === BOOK_ALL) {
      return lookupWord(word) !== null;
    }
    const item: WordItem | null = lookupWord(word);
    return item !== null && item.book === this.selectedBook;
  }

  private scopedDueCount(): number {
    let count: number = 0;
    const due: SrsCard[] = Srs.dueCards(this.store.cards, this.today);
    for (let i = 0; i < due.length; i++) {
      if (this.poolHasWord(due[i].word)) {
        count++;
      }
    }
    return count;
  }

  private bookStats(bookId: string): string {
    const total: number = bookWordCount(bookId);
    let learned: number = 0;
    let mastered: number = 0;
    const pool: WordItem[] = wordsOfBook(bookId);
    for (let i = 0; i < pool.length; i++) {
      const card: SrsCard | null = Srs.findCard(this.store.cards, pool[i].word);
      if (card !== null) {
        learned++;
        if (card.box >= Srs.MAX_BOX) {
          mastered++;
        }
      }
    }
    return '已学 ' + learned.toString() + '/' + total.toString() + ' · 掌握 ' + mastered.toString();
  }

  async selectBook(book: string): Promise<void> {
    this.lastReviewedWord = '';
    this.wordRevealed = false;
    this.selectedBook = book;
    await this.store.setBook(book);
    await this.reload();
    this.toast('已切换到 ' + bookName(book) + '（' + bookWordCount(book).toString() + ' 词，离线可用）');
  }

  // ---------- 每日任务 ----------

  private taskSignDone(): boolean {
    return this.summary.signedToday;
  }

  private taskReviewDone(): boolean {
    return this.todayReviewed >= REVIEW_TARGET;
  }

  private taskQuizDone(): boolean {
    return this.todayQuiz >= QUIZ_TARGET;
  }

  private tasksDone(): number {
    let done: number = 0;
    if (this.taskSignDone()) {
      done++;
    }
    if (this.taskReviewDone()) {
      done++;
    }
    if (this.taskQuizDone()) {
      done++;
    }
    return done;
  }

  private allDone(): boolean {
    return this.tasksDone() >= TASK_TOTAL;
  }

  private signCaption(): string {
    if (this.summary.signedToday) {
      return this.summary.currentStreak.toString() + ' 天连续签到，明天保持';
    }
    return '1 秒 · 签完就算今天开始学习';
  }

  private reviewCaption(): string {
    const shown: number = this.todayReviewed > REVIEW_TARGET ? REVIEW_TARGET : this.todayReviewed;
    if (this.todayReviewed >= REVIEW_TARGET) {
      return this.currentBookName() + ' · 今天已复习 ' + this.todayReviewed.toString() + ' 个词';
    }
    return this.currentBookName() + ' · 已复习 ' + shown.toString() + ' / ' + REVIEW_TARGET.toString();
  }

  private quizCaption(): string {
    const shown: number = this.todayQuiz > QUIZ_TARGET ? QUIZ_TARGET : this.todayQuiz;
    if (this.todayQuiz >= QUIZ_TARGET) {
      return '今天已答 ' + this.todayQuiz.toString() + ' 题';
    }
    return '约 1 分钟 · 已答 ' + shown.toString() + ' / ' + QUIZ_TARGET;
  }

  private accuracyText(): string {
    return this.accuracy < 0 ? '—' : this.accuracy.toString() + '%';
  }

  private memoryRemaining(): number {
    const left: number = MEMORY_CURVE_TARGET - this.reviewTotal;
    return left > 0 ? left : 0;
  }

  private memoryPercent(): number {
    if (this.reviewTotal >= MEMORY_CURVE_TARGET) {
      return 100;
    }
    return Math.floor(this.reviewTotal * 100 / MEMORY_CURVE_TARGET);
  }

  // ---------- 学习 tab ----------

  @Builder
  homePage() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Column() {
              Text('学习').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text(this.productSubtitle()).fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
              Text(this.dateLabel()).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
            }.alignItems(HorizontalAlign.Start)
            Blank()
            Text(this.streak > 0 ? '连续 ' + this.streak.toString() + ' 天' : '今天开始')
              .fontSize(12).fontColor(Theme.goldInk)
              .backgroundColor(Theme.goldSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
              .borderRadius(14)
          }.width('100%').padding({ top: 6, bottom: 12 })

          this.startCard()
          this.bookSwitcher(false)

          Row() {
            this.metric('今日待复习', this.dueCount.toString() + ' 词')
            this.metricDivider()
            this.metric('已掌握', this.masteredCount.toString() + ' 词')
            this.metricDivider()
            this.metric('正确率', this.accuracyText())
          }
          .width('100%').padding({ top: 14, bottom: 14 }).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          this.signQuickCard()

          Row() {
            Text('60').fontSize(15).fontColor(Theme.goldInk).width(38).height(38)
              .textAlign(TextAlign.Center).backgroundColor(Theme.goldSoft).borderRadius(19)
            Column() {
              Row() {
                Text('示例错题 · 60 秒体验').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
                Text('示例').fontSize(10).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
                  .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(8).margin({ left: 6 })
              }.alignItems(VerticalAlign.Center)
              Text('数学公式 / 英语 / 形近词 3 道示例，不计入学习统计').fontSize(11)
                .fontColor(Theme.secondaryText).margin({ top: 3 })
            }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
            Text('去体验 ›').fontSize(12).fontColor(Theme.accentInk)
          }
          .width('100%').padding(14).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          .onClick(() => { this.goTab(TAB_PRACTICE); })

          if (this.wrongNames.length > 0) {
            Row() {
              Text('错').fontSize(15).fontColor(Theme.dangerInk).width(38).height(38)
                .textAlign(TextAlign.Center).backgroundColor(Theme.dangerSoft).borderRadius(19)
              Column() {
                Text('错题本').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
                Text(this.wrongNames.length.toString() + ' 个词待重练 · 小测里答错会自动收进来').fontSize(11)
                  .fontColor(Theme.secondaryText).margin({ top: 3 })
              }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
              Text('去练习 ›').fontSize(12).fontColor(Theme.accentInk)
            }
            .width('100%').padding(14).margin({ top: 12 })
            .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
            .onClick(() => { this.goTab(TAB_PRACTICE); })
          }

          Column() {
            Row() {
              Text('个人记忆曲线').fontSize(13).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              Blank()
              Text(this.memoryPercent().toString() + '%').fontSize(12).fontColor(Theme.goldInk)
            }.width('100%')
            Progress({ value: this.memoryPercent(), total: 100, type: ProgressType.Linear })
              .color(Theme.gold).backgroundColor(Theme.goldSoft).width('100%').height(6).margin({ top: 8 })
            Text(this.memoryRemaining() > 0
              ? '再复习 ' + this.memoryRemaining().toString() + ' 次解锁个人记忆曲线'
              : '个人记忆曲线已解锁：累计复习 ' + this.reviewTotal.toString() + ' 次')
              .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 6 })
          }
          .width('100%').padding(14).margin({ top: 12 })
          .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)

          Text('所有数据只保存在这台设备 · 不需要账号，也不会上传')
            .fontSize(11).fontColor(Theme.secondaryText).width('100%')
            .textAlign(TextAlign.Center).margin({ top: 22, bottom: 30 })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('home-' + this.viewVersion.toString())
  }

  @Builder
  startCard() {
    Column() {
      Row() {
        Column() {
          Text('今天从哪开始').fontSize(13).fontColor(Theme.secondaryText)
          Text(this.allDone() ? '今日已起飞' : '三件小事')
            .fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText).margin({ top: 3 })
        }.alignItems(HorizontalAlign.Start)
        Blank()
        Column() {
          Text(this.tasksDone().toString() + ' / ' + TASK_TOTAL.toString())
            .fontSize(32).fontWeight(FontWeight.Bold)
            .fontColor(this.allDone() ? Theme.goldInk : Theme.accentInk)
          Text(this.allDone() ? '全部完成' : '完成进度').fontSize(11).fontColor(Theme.secondaryText)
        }.alignItems(HorizontalAlign.End)
      }.width('100%').alignItems(VerticalAlign.Center)

      if (this.allDone()) {
        Column() {
          Text('3 / 3 完成 · 今天该做的都做完了').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.goldInk)
          Text('复习间隔会自动拉长，明天回来接着做就行。').fontSize(12).fontColor(Theme.goldInk).margin({ top: 4 })
        }
        .width('100%').alignItems(HorizontalAlign.Start)
        .padding(12).margin({ top: 12 })
        .backgroundColor(Theme.goldSoft).borderRadius(14)
      }

      this.taskRow(1, '签到打卡', this.signCaption(), this.taskSignDone(), TAB_SIGN)
      this.taskRow(2, '复习 ' + REVIEW_TARGET.toString() + ' 个词', this.reviewCaption(), this.taskReviewDone(), TAB_WORDS)
      this.taskRow(3, '做 ' + QUIZ_TARGET.toString() + ' 道小测', this.quizCaption(), this.taskQuizDone(), TAB_PRACTICE)

      Text('做完一件是一件，不用按顺序。').fontSize(11).fontColor(Theme.secondaryText).margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(18).borderRadius(Theme.cardRadius)
    .linearGradient({ angle: 135, colors: [[Theme.accentSoft, 0.0], [Theme.card, 0.78]] })
  }

  @Builder
  taskRow(step: number, title: string, caption: string, done: boolean, tab: number) {
    Row() {
      Text(done ? '✓' : step.toString())
        .fontSize(13).fontWeight(FontWeight.Bold)
        .fontColor(done ? Theme.successInk : Theme.accentInk)
        .width(28).height(28).textAlign(TextAlign.Center)
        .backgroundColor(done ? Theme.successSoft : Theme.accentSoft).borderRadius(14)
      Column() {
        Row() {
          Text(title).fontSize(15).fontWeight(FontWeight.Medium)
            .fontColor(done ? Theme.secondaryText : Theme.primaryText)
          if (done) {
            Text('已完成').fontSize(10).fontColor(Theme.successInk)
              .backgroundColor(Theme.successSoft).padding({ left: 6, right: 6, top: 2, bottom: 2 })
              .borderRadius(8).margin({ left: 6 })
          }
        }.alignItems(VerticalAlign.Center)
        Text(caption).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
      if (!done) {
        Text('去完成').fontSize(12).fontColor(Theme.accentInk)
        Text('›').fontSize(20).fontColor(Theme.secondaryText).margin({ left: 2 })
      }
    }
    .width('100%').padding({ top: 12, bottom: 12 })
    .border({ width: { top: 1 }, color: Theme.divider })
    .onClick(() => { this.goTab(tab); })
  }

  @Builder
  bookSwitcher(compact: boolean) {
    Column() {
      Row() {
        Text('词书').fontSize(12).fontColor(Theme.secondaryText)
        Text(this.selectedBook === BOOK_ALL ? '全部词书' : this.currentBookName()).fontSize(12)
          .fontColor(Theme.accentInk).margin({ left: 6 })
        Blank()
        Text('离线 · 4 本内置').fontSize(10).fontColor(Theme.secondaryText)
      }.width('100%').margin({ bottom: 8 })
      Flex({ wrap: FlexWrap.Wrap }) {
        this.bookChip(BOOK_ALL, '全部')
        ForEach(booksOf(), (book: WordBook) => {
          this.bookChip(book.id, book.name)
        }, (book: WordBook) => book.id)
      }.width('100%')
      if (!compact) {
        Text(this.selectedBook === BOOK_ALL
          ? '当前显示全部 ' + allWords().length.toString() + ' 词'
          : this.bookStats(this.selectedBook)).fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 8 })
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(14).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  bookChip(bookId: string, label: string) {
    Text(label + ' ' + bookWordCount(bookId).toString())
      .fontSize(12)
      .fontColor(this.selectedBook === bookId ? Color.White : Theme.secondaryText)
      .backgroundColor(this.selectedBook === bookId ? Theme.accentInk : Theme.cardWarm)
      .padding({ left: 10, right: 10, top: 6, bottom: 6 })
      .borderRadius(12).margin({ right: 8, bottom: 6 })
      .onClick(() => { this.selectBook(bookId); })
  }

  @Builder
  metric(label: string, value: string) {
    Column() {
      Text(value).fontSize(20).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(label).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
    }.layoutWeight(1)
  }

  @Builder
  metricDivider() {
    Divider().vertical(true).height(30).color(Theme.divider)
  }

  @Builder
  signQuickCard() {
    Row() {
      Text(this.summary.signedToday ? '✓' : '○')
        .fontSize(22).fontColor(this.summary.signedToday ? Theme.goldInk : Theme.accentInk)
        .width(46).height(46).textAlign(TextAlign.Center)
        .backgroundColor(this.summary.signedToday ? Theme.goldSoft : Theme.accentSoft).borderRadius(23)
      Column() {
        Text(this.summary.signedToday ? '今天已签到' : '还没签到').fontSize(16).fontWeight(FontWeight.Medium)
          .fontColor(Theme.primaryText)
        Text(this.summary.signedToday ? '连续 ' + this.summary.currentStreak.toString() + ' 天 · 本月 '
          + this.currentMonthSigned().toString() + ' 天' : '每天来一下，积少成多')
          .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      Button(this.summary.signedToday ? '查看日历' : '签到')
        .height(36).fontSize(13)
        .backgroundColor(this.summary.signedToday ? Theme.successSoft : Theme.accentInk)
        .fontColor(this.summary.signedToday ? Theme.successInk : Color.White)
        .onClick(() => {
          if (this.summary.signedToday) {
            this.goTab(TAB_SIGN);
          } else {
            this.signInToday();
          }
        })
    }
    .width('100%').padding(14).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .onClick(() => { this.goTab(TAB_SIGN); })
  }

  private currentMonthSigned(): number {
    const now = new Date();
    const month: string = now.getFullYear().toString() + '-' + (now.getMonth() + 1 < 10 ? '0' : '')
      + (now.getMonth() + 1).toString();
    return SignInStats.monthSigned(month, this.summary.signedDays);
  }

  // ---------- 签到 tab ----------

  async signInToday(): Promise<void> {
    const added = await this.store.signIn(this.today);
    if (added) {
      this.toast('签到成功，今天也算学习日');
    } else {
      this.toast('今天已经签到过了');
    }
    await this.reload();
  }

  async makeUp(date: string): Promise<void> {
    const added = await this.store.makeUp(date, this.today);
    if (added) {
      this.toast('补签成功');
    } else {
      this.toast('本月补签次数已用完，或日期不在可补范围');
    }
    await this.reload();
  }

  async undoMakeUp(date: string): Promise<void> {
    const removed = await this.store.undoMakeUp(date, this.today);
    if (removed) {
      this.toast('已撤销补签');
    }
    await this.reload();
  }

  private currentMonthGrid(): SignInMonth {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    const signed = new Set<string>();
    const makeup = new Set<string>();
    for (let i = 0; i < this.summary.signedDays.length; i++) {
      signed.add(this.summary.signedDays[i]);
    }
    for (let i = 0; i < this.summary.makeupDays.length; i++) {
      makeup.add(this.summary.makeupDays[i]);
    }
    return SignInStats.monthGrid(target.getFullYear(), target.getMonth() + 1, signed, makeup, this.today);
  }

  private monthLabel(): string {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    return target.getFullYear().toString() + ' 年 ' + (target.getMonth() + 1).toString() + ' 月';
  }

  private monthSignedCount(): number {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    const month = target.getFullYear().toString() + '-' + (target.getMonth() + 1 < 10 ? '0' : '')
      + (target.getMonth() + 1).toString();
    return SignInStats.monthSigned(month, this.summary.signedDays);
  }

  private monthDays(): number {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    if (this.monthOffset === 0) {
      return now.getDate();
    }
    return new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate();
  }

  private monthPercent(): number {
    const days = this.monthDays();
    return days <= 0 ? 0 : Math.floor(this.monthSignedCount() * 100 / days);
  }

  private weekDotColor(day: SignInDay): string {
    if (day.makeup) {
      return Theme.gold;
    }
    if (day.signed) {
      return Theme.accent;
    }
    if (day.date === this.today) {
      return Theme.accentSoft;
    }
    return Theme.divider;
  }

  @Builder
  signInPage() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Column() {
              Text('签到').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text('连续、补签、里程碑都存在本机').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
            }.alignItems(HorizontalAlign.Start)
            Blank()
            Button('签到海报').height(32).fontSize(12)
              .backgroundColor(Theme.goldSoft).fontColor(Theme.goldInk)
              .onClick(() => { this.generatePoster(POSTER_SIGN); })
          }.width('100%').padding({ top: 6, bottom: 12 })

          Row() {
            this.statBox('连续', this.summary.currentStreak.toString() + ' 天')
            this.metricDivider()
            this.statBox('最长', this.summary.longestStreak.toString() + ' 天')
            this.metricDivider()
            this.statBox('本月', this.monthSignedCount().toString() + '/' + this.monthDays().toString())
          }.width('100%').padding(12).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Row() {
            Text('里程碑').fontSize(11).fontColor(Theme.secondaryText)
            ForEach(SignInStats.MILESTONES, (milestone: number) => {
              Text(milestone.toString() + ' 天').fontSize(11)
                .fontColor(this.summary.longestStreak >= milestone ? Theme.goldInk : Theme.secondaryText)
                .backgroundColor(this.summary.longestStreak >= milestone ? Theme.goldSoft : Theme.divider)
                .padding({ left: 8, right: 8, top: 4, bottom: 4 }).borderRadius(10).margin({ left: 5 })
            }, (milestone: number) => milestone.toString())
          }.width('100%').alignItems(VerticalAlign.Center).margin({ top: 12 })

          Row() {
            Text('‹').fontSize(28).fontColor(this.monthOffset < 0 ? Theme.accentInk : Theme.divider)
              .width(44).height(44).textAlign(TextAlign.Center)
              .onClick(() => { if (this.monthOffset < 0) { this.monthOffset++; } })
            Text(this.monthLabel()).fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              .layoutWeight(1).textAlign(TextAlign.Center)
            Text('›').fontSize(28).fontColor(this.monthOffset < 0 ? Theme.accentInk : Theme.divider)
              .width(44).height(44).textAlign(TextAlign.Center)
              .onClick(() => { if (this.monthOffset < 0) { this.monthOffset--; } })
          }.width('100%').margin({ top: 12 })

          Text('本月签到 ' + this.monthSignedCount().toString() + ' / ' + this.monthDays().toString() + ' 天（'
            + this.monthPercent().toString() + '%）')
            .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 4 })

          Row() {
            ForEach(['一', '二', '三', '四', '五', '六', '日'], (label: string) => {
              Text(label).fontSize(11).fontColor(Theme.secondaryText).layoutWeight(1).textAlign(TextAlign.Center)
            }, (label: string) => label)
          }.width('100%').margin({ top: 10, bottom: 6 })

          Grid() {
            ForEach(this.currentMonthGrid().cells, (cell: SignInCell, index: number) => {
              GridItem() {
                this.dayCell(cell)
              }
            }, (cell: SignInCell, index: number) => index.toString() + '-' + this.viewVersion.toString())
          }
          .columnsTemplate('1fr 1fr 1fr 1fr 1fr 1fr 1fr')
          .rowsGap(6).columnsGap(6).height(300).width('100%')

          Text('本月补签 ' + SignInStats.makeupUsed(this.currentMonthKey(), this.summary.makeupDays).toString() + ' / 3 次')
            .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
          Text('补签只允许最近 3 天；每个月最多 3 次，点已补签的格子可以撤销。')
            .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 4 })
          Text('不会自动上传任何签到数据；海报在本地生成。')
            .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 4, bottom: 30 })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('signin-' + this.viewVersion.toString())
  }

  private currentMonthKey(): string {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    const month = target.getMonth() + 1;
    return target.getFullYear().toString() + '-' + (month < 10 ? '0' : '') + month.toString();
  }

  @Builder
  statBox(label: string, value: string) {
    Column() {
      Text(value).fontSize(20).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(label).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
    }.layoutWeight(1)
  }

  @Builder
  dayCell(cell: SignInCell) {
    if (cell.blank) {
      Text('').height(38)
    } else {
      Column() {
        Text(cell.day.toString()).fontSize(13)
          .fontColor(cell.makeup ? Theme.goldInk : (cell.signed ? Theme.accentInk :
            (cell.today ? Theme.accentInk : Theme.primaryText)))
        if (cell.signed) {
          Circle().width(4).height(4).fill(cell.makeup ? Theme.gold : Theme.accent).margin({ top: 3 })
        }
      }
      .width('100%').height(38)
      .justifyContent(FlexAlign.Center)
      .backgroundColor(cell.makeup ? Theme.goldSoft : (cell.signed ? Theme.accentSoft : Color.Transparent))
      .borderRadius(19)
      .border({ width: cell.today ? 1.5 : (this.isMakeUpEligible(cell) ? 1 : 0), color: Theme.accent })
      .onClick(() => { this.onDayTap(cell); })
    }
  }

  private isMakeUpEligible(cell: SignInCell): boolean {
    if (cell.blank || cell.signed || !cell.date) {
      return false;
    }
    if (this.summary.makeupDays.indexOf(cell.date) >= 0) {
      return false;
    }
    return SignInStats.canMakeUp(cell.date, this.today, this.summary.makeupDays);
  }

  private onDayTap(cell: SignInCell): void {
    if (cell.blank || !cell.date) {
      return;
    }
    if (cell.makeup && SignInStats.canUndo(cell.date, this.today, this.summary.makeupDays)) {
      AlertDialog.show({
        title: '撤销补签',
        message: '撤销 ' + cell.date + ' 的补签？连续签到和本月统计会相应减少。',
        primaryButton: { value: '取消', action: () => {} },
        secondaryButton: {
          value: '撤销', fontColor: Theme.warningInk,
          action: () => { this.undoMakeUp(cell.date); }
        }
      });
      return;
    }
    if (this.isMakeUpEligible(cell)) {
      AlertDialog.show({
        title: '补签',
        message: '补签 ' + cell.date + '？补签会计入连续签到和本月统计，并在日历里标出来。',
        primaryButton: { value: '取消', action: () => {} },
        secondaryButton: {
          value: '补签', fontColor: Theme.accentInk,
          action: () => { this.makeUp(cell.date); }
        }
      });
    }
  }

  // ---------- 单词 tab ----------

  private currentReviewWord(): WordItem | null {
    const due: SrsCard[] = Srs.dueCards(this.store.cards, this.today);
    for (let i = 0; i < due.length; i++) {
      if (due[i].word !== this.lastReviewedWord && this.poolHasWord(due[i].word)) {
        const item: WordItem | null = lookupWord(due[i].word);
        if (item !== null) {
          return item;
        }
      }
    }
    const pool: WordItem[] = this.bookPool();
    for (let i = 0; i < pool.length; i++) {
      const word: string = pool[i].word;
      if (word === this.lastReviewedWord) {
        continue;
      }
      if (Srs.findCard(this.store.cards, word) === null) {
        return pool[i];
      }
    }
    return null;
  }

  private hasReviewWord(): boolean {
    return this.currentReviewWord() !== null;
  }

  private reviewWordDisplay(): WordItem {
    const item: WordItem | null = this.currentReviewWord();
    if (item === null) {
      return { word: '', phonetic: '', meaning: '', example: '', book: '' };
    }
    return item;
  }

  private currentReviewCard(): SrsCard | null {
    const item: WordItem | null = this.currentReviewWord();
    if (item === null) {
      return null;
    }
    return Srs.findCard(this.store.cards, item.word);
  }

  private currentBookTag(): string {
    const item: WordItem | null = this.currentReviewWord();
    if (item === null) {
      return '';
    }
    return bookName(item.book);
  }

  private currentBoxLabel(): string {
    const card: SrsCard | null = this.currentReviewCard();
    if (card === null) {
      return '新词 · 第 1 次见面';
    }
    return '第 ' + card.box.toString() + ' 盒 · 下次复习：' + Srs.friendlyDue(card, this.today);
  }

  private currentWordAccuracy(): string {
    const card: SrsCard | null = this.currentReviewCard();
    if (card === null) {
      return '还没复习过';
    }
    const attempts: number = card.correct + card.wrong;
    if (attempts === 0) {
      return '还没复习过';
    }
    return '已复习 ' + attempts.toString() + ' 次 · 答对 ' + card.correct.toString() + ' 次';
  }

  async reviewCurrent(rating: number): Promise<void> {
    const item: WordItem | null = this.currentReviewWord();
    if (item === null) {
      return;
    }
    const card = await this.store.reviewWordRating(item.word, rating, this.today);
    this.lastReviewedWord = item.word;
    this.wordRevealed = false;
    if (rating >= 2) {
      this.toast('已记录 · ' + Srs.friendlyDue(card, this.today) + '再复习');
    } else {
      this.toast('忘了 · 这个词稍后会再出现');
    }
    await this.reload();
  }

  async undoReview(): Promise<void> {
    const ok = await this.store.undoLastReview(this.today);
    if (ok) {
      this.lastReviewedWord = '';
      this.wordRevealed = false;
      this.toast('已撤销上一次复习，排期和统计都退回去了');
    } else {
      this.toast('没有可以撤销的复习');
    }
    await this.reload();
  }

  @Builder
  wordsPage() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Column() {
              Text('单词').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text(this.currentBookName() + ' · 共 ' + this.bookPool().length.toString() + ' 词 · 待复习 '
                + this.dueCount.toString() + ' · 已掌握 ' + this.masteredCount.toString())
                .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
            }.alignItems(HorizontalAlign.Start)
            Blank()
            Text('已学 ' + this.learningCount.toString() + ' 词').fontSize(12).fontColor(Theme.secondaryText)
          }.width('100%').padding({ top: 6, bottom: 12 })

          this.bookSwitcher(true)

          Column() {
            Row() {
              Text('今日复习').fontSize(12).fontColor(Theme.secondaryText)
              Blank()
              Text(this.todayReviewed.toString() + ' / ' + REVIEW_TARGET.toString() + ' 词')
                .fontSize(12).fontColor(this.todayReviewed >= REVIEW_TARGET ? Theme.successInk : Theme.accentInk)
            }.width('100%')
            Progress({ value: this.todayReviewed > REVIEW_TARGET ? REVIEW_TARGET : this.todayReviewed,
              total: REVIEW_TARGET, type: ProgressType.Linear })
              .color(Theme.accent).backgroundColor(Theme.accentSoft).width('100%').height(6).margin({ top: 8 })
          }
          .width('100%').padding(14).backgroundColor(Theme.card).borderRadius(14).margin({ top: 12, bottom: 12 })

          if (this.hasReviewWord()) {
            this.reviewCard()

            Row() {
              Button('忘记').layoutWeight(1).height(52)
                .backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
                .onClick(() => { this.reviewCurrent(1); })
              Button('模糊').layoutWeight(1).height(52).margin({ left: 8 })
                .backgroundColor(Theme.goldSoft).fontColor(Theme.goldInk)
                .onClick(() => { this.reviewCurrent(2); })
              Button('记住了').layoutWeight(1).height(52).margin({ left: 8 })
                .backgroundColor(Theme.accentInk).fontColor(Color.White)
                .onClick(() => { this.reviewCurrent(3); })
            }.width('100%').margin({ top: 14 })

            Button(this.hasUndo ? '撤销上一步复习' : '暂无可撤销的复习')
              .width('100%').height(42).margin({ top: 10 })
              .backgroundColor(Color.Transparent).fontColor(this.hasUndo ? Theme.secondaryText : Theme.divider)
              .enabled(this.hasUndo)
              .onClick(() => { this.undoReview(); })

            Text('先点卡片看释义，再选记住/再学。忘了不罚分，只把词放回第 1 盒。')
              .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 8 })
          } else {
            Column() {
              Text('这本词书今天的复习完成了').fontSize(22).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text('待复习的词都清空了，新词会在明天继续出现。可以切换其他词书继续。').fontSize(13)
                .fontColor(Theme.secondaryText).margin({ top: 8 })
              if (this.hasUndo) {
                Button('撤销上一步复习').height(42).margin({ top: 16 })
                  .backgroundColor(Theme.accentSoft).fontColor(Theme.accentInk)
                  .onClick(() => { this.undoReview(); })
              }
            }
            .width('100%').padding({ top: 40, bottom: 40 }).alignItems(HorizontalAlign.Center)
            .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          }

          Column() {
            Text('复习是怎么安排的').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
            Text('FSRS：忘记 / 模糊 / 记住了三档评分会更新稳定度与难度，按目标 90% 召回率安排下一次复习；忘记会降低稳定度，但不会扣分。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 6 })
            Text('“今日待复习”只统计当前词书里到期日小于等于今天的词；复习次数是真实计数，不用任何算法校准冒充。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 4 })
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding(14).margin({ top: 16, bottom: 30 })
          .backgroundColor(Theme.cardWarm).borderRadius(14)
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('words-' + this.viewVersion.toString())
  }

  @Builder
  reviewCard() {
    Column() {
      Row() {
        Text(this.currentBookTag() + ' · ' + this.currentBoxLabel()).fontSize(11).fontColor(Theme.goldInk)
          .backgroundColor(Theme.goldSoft).padding({ left: 8, right: 8, top: 4, bottom: 4 }).borderRadius(10)
        Blank()
        Text(this.currentWordAccuracy()).fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%')

      Text(this.reviewWordDisplay().word).fontSize(40).fontWeight(FontWeight.Bold)
        .fontColor(Theme.primaryText).margin({ top: 26 })
      Text(this.reviewWordDisplay().phonetic).fontSize(15).fontColor(Theme.secondaryText).margin({ top: 8 })

      if (this.wordRevealed) {
        Divider().color(Theme.divider).width('100%').margin({ top: 18, bottom: 14 })
        Text(this.reviewWordDisplay().meaning).fontSize(18).fontColor(Theme.accentInk)
        Row() {
          Text('例句').fontSize(10).fontColor(Theme.goldInk)
            .backgroundColor(Theme.goldSoft).padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(6)
          Text(this.reviewWordDisplay().example).fontSize(13).fontColor(Theme.secondaryText)
            .layoutWeight(1).margin({ left: 8 })
        }.alignItems(VerticalAlign.Top).margin({ top: 14 })
      } else {
        Text('点击卡片，看释义和例句').fontSize(13).fontColor(Theme.secondaryText).margin({ top: 26 })
      }
    }
    .width('100%').height(320)
    .justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Start)
    .padding(20).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .onClick(() => { this.wordRevealed = !this.wordRevealed; })
  }

  // ---------- 练习 tab ----------

  private startQuiz(fromWrong: boolean): void {
    const pool: WordItem[] = fromWrong ? allWords() : this.bookPool();
    const priority: string[] = [];
    if (fromWrong) {
      for (let i = 0; i < this.store.wrongs.length && priority.length < QuizBuilder.GROUP_SIZE; i++) {
        priority.push(this.store.wrongs[i].word);
      }
    } else {
      const due: SrsCard[] = Srs.dueCards(this.store.cards, this.today);
      for (let i = 0; i < due.length && priority.length < QuizBuilder.GROUP_SIZE; i++) {
        if (this.poolHasWord(due[i].word)) {
          priority.push(due[i].word);
        }
      }
    }
    const questions: QuizQuestion[] = QuizBuilder.build(QuizBuilder.GROUP_SIZE, priority, pool);
    if (questions.length === 0) {
      this.toast('这本词书暂时没有可出题的词');
      return;
    }
    this.quizQuestions = questions;
    this.quizIndex = 0;
    this.quizSelected = -1;
    this.quizCorrect = 0;
    this.quizWrongWords = [];
    this.quizFromWrong = fromWrong;
    this.practiceMode = PRACTICE_QUIZ;
  }

  async importDemoSamples(): Promise<void> {
    await this.store.importDemoSamples();
    await this.reload();
    this.toast('已导入 3 道示例错题，点开始复习即可');
  }

  async resetDemoSamples(): Promise<void> {
    await this.store.resetDemoSamples();
    await this.reload();
    this.toast('示例错题已重置');
  }

  private startDemo(): void {
    this.stopDemoTimer();
    this.demoIndex = 0;
    this.demoSelected = -1;
    this.demoCorrect = 0;
    this.demoSeconds = DEMO_SECONDS;
    this.demoUsed = 0;
    this.demoTimedOut = false;
    this.practiceMode = PRACTICE_DEMO_QUIZ;
    this.demoInterval = setInterval(() => {
      if (this.practiceMode !== PRACTICE_DEMO_QUIZ) {
        this.stopDemoTimer();
        return;
      }
      this.demoSeconds = this.demoSeconds - 1;
      if (this.demoSeconds <= 0) {
        this.demoSeconds = 0;
        this.demoTimedOut = true;
        this.finishDemo();
      }
    }, 1000);
  }

  private stopDemoTimer(): void {
    if (this.demoInterval >= 0) {
      clearInterval(this.demoInterval);
      this.demoInterval = -1;
    }
  }

  private finishDemo(): void {
    this.stopDemoTimer();
    this.demoUsed = DEMO_SECONDS - this.demoSeconds;
    this.practiceMode = PRACTICE_DEMO_RESULT;
  }

  private quitDemo(): void {
    this.stopDemoTimer();
    this.practiceMode = PRACTICE_MENU;
  }

  private currentDemo(): DemoQuestion {
    return DEMO_QUESTIONS[this.demoIndex];
  }

  private chooseDemoOption(index: number): void {
    if (this.demoSelected >= 0) {
      return;
    }
    const question: DemoQuestion = this.currentDemo();
    this.demoSelected = index;
    if (index === question.answerIndex) {
      this.demoCorrect = this.demoCorrect + 1;
    }
  }

  private nextDemoQuestion(): void {
    if (this.demoIndex + 1 < DEMO_QUESTIONS.length) {
      this.demoIndex = this.demoIndex + 1;
      this.demoSelected = -1;
    } else {
      this.finishDemo();
    }
  }

  private demoOptionBg(index: number): string {
    if (this.demoSelected < 0) {
      return Theme.card;
    }
    if (index === this.currentDemo().answerIndex) {
      return Theme.successSoft;
    }
    if (index === this.demoSelected) {
      return Theme.dangerSoft;
    }
    return Theme.card;
  }

  private demoOptionBorder(index: number): string {
    if (this.demoSelected < 0) {
      return Theme.divider;
    }
    if (index === this.currentDemo().answerIndex) {
      return Theme.successInk;
    }
    if (index === this.demoSelected) {
      return Theme.dangerInk;
    }
    return Theme.divider;
  }

  private demoOptionInk(index: number): string {
    if (this.demoSelected < 0) {
      return Theme.primaryText;
    }
    if (index === this.currentDemo().answerIndex) {
      return Theme.successInk;
    }
    if (index === this.demoSelected) {
      return Theme.dangerInk;
    }
    return Theme.secondaryText;
  }

  private currentQuestion(): QuizQuestion {
    if (this.quizQuestions.length === 0) {
      return { word: '', phonetic: '', meaning: '', example: '', options: [], answerIndex: 0, fromWrong: false };
    }
    return this.quizQuestions[this.quizIndex];
  }

  private currentOptions(): string[] {
    return this.currentQuestion().options;
  }

  private optionLetter(index: number): string {
    if (index === 0) {
      return 'A';
    }
    if (index === 1) {
      return 'B';
    }
    if (index === 2) {
      return 'C';
    }
    return 'D';
  }

  private optionBg(index: number): string {
    if (this.quizSelected < 0) {
      return Theme.card;
    }
    if (index === this.currentQuestion().answerIndex) {
      return Theme.successSoft;
    }
    if (index === this.quizSelected) {
      return Theme.dangerSoft;
    }
    return Theme.card;
  }

  private optionBorder(index: number): string {
    if (this.quizSelected < 0) {
      return Theme.divider;
    }
    if (index === this.currentQuestion().answerIndex) {
      return Theme.successInk;
    }
    if (index === this.quizSelected) {
      return Theme.dangerInk;
    }
    return Theme.divider;
  }

  private optionInk(index: number): string {
    if (this.quizSelected < 0) {
      return Theme.primaryText;
    }
    if (index === this.currentQuestion().answerIndex) {
      return Theme.successInk;
    }
    if (index === this.quizSelected) {
      return Theme.dangerInk;
    }
    return Theme.secondaryText;
  }

  async chooseOption(index: number): Promise<void> {
    if (this.quizSelected >= 0) {
      return;
    }
    const question: QuizQuestion = this.currentQuestion();
    const correct: boolean = index === question.answerIndex;
    this.quizSelected = index;
    if (correct) {
      this.quizCorrect = this.quizCorrect + 1;
    } else {
      this.quizWrongWords.push(question.word);
      this.quizWrongWords = this.quizWrongWords.slice(0, this.quizWrongWords.length);
    }
    await this.store.recordQuizAnswer(question.word, correct, this.today);
    await this.reload();
  }

  async nextQuestion(): Promise<void> {
    if (this.quizIndex + 1 < this.quizQuestions.length) {
      this.quizIndex = this.quizIndex + 1;
      this.quizSelected = -1;
      return;
    }
    await this.store.finishQuiz(this.quizQuestions.length, this.quizCorrect, this.today);
    this.resultTotal = this.quizQuestions.length;
    this.resultCorrect = this.quizCorrect;
    this.practiceMode = PRACTICE_RESULT;
    await this.reload();
  }

  private quitQuiz(): void {
    this.practiceMode = PRACTICE_MENU;
    this.quizFromWrong = false;
    this.quizQuestions = [];
    this.quizSelected = -1;
    this.quizIndex = 0;
    this.quizWrongWords = [];
  }

  private wrongMeaning(word: string): string {
    const item: WordItem | null = lookupWord(word);
    return item === null ? '' : item.meaning;
  }

  private sessionLabel(session: QuizSession): string {
    const day: string = session.date.length >= 10 ? session.date.substring(5) : session.date;
    return day + ' · ' + session.total.toString() + ' 题 · 答对 ' + session.correct.toString();
  }

  @Builder
  practicePage() {
    if (this.practiceMode === PRACTICE_QUIZ) {
      this.quizView()
    } else if (this.practiceMode === PRACTICE_RESULT) {
      this.resultView()
    } else if (this.practiceMode === PRACTICE_DEMO_QUIZ) {
      this.demoQuizView()
    } else if (this.practiceMode === PRACTICE_DEMO_RESULT) {
      this.demoResultView()
    } else {
      this.practiceMenu()
    }
  }

  @Builder
  practiceMenu() {
    Scroll() {
      Row() {
        Column() {
          Text('练习').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
          Text('4 选 1 · 5 题一组 · 答完立刻看答案和例句').fontSize(12)
            .fontColor(Theme.secondaryText).margin({ top: 3 })

          Column() {
            Row() {
              Text('示例错题 · 60 秒体验').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              Text('示例').fontSize(10).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
                .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(8).margin({ left: 6 })
              Blank()
              Text('不计入统计').fontSize(10).fontColor(Theme.secondaryText)
            }.width('100%').alignItems(VerticalAlign.Center)
            Text('3 道预置示例：数学公式 / 英语 / 形近词。60 秒内可以复习完一次，用来看懂错题怎么练；不是扫描识别结果。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 6 })
            if (!this.demoImported) {
              Button('导入 3 道示例错题').width('100%').height(46).margin({ top: 12 })
                .backgroundColor(Theme.goldSoft).fontColor(Theme.goldInk)
                .onClick(() => { this.importDemoSamples(); })
            } else {
              ForEach(DEMO_QUESTIONS, (question: DemoQuestion, index: number) => {
                Row() {
                  Text(question.kind).fontSize(10).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
                    .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(6).width(64)
                  Text(question.prompt).fontSize(12).fontColor(Theme.primaryText)
                    .layoutWeight(1).margin({ left: 8 }).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                }.width('100%').padding({ top: 5, bottom: 5 })
              }, (question: DemoQuestion, index: number) => question.id)
              Row() {
                Button('开始 60 秒示例复习').layoutWeight(1).height(46)
                  .backgroundColor(Theme.goldInk).fontColor(Color.White)
                  .onClick(() => { this.startDemo(); })
                Button('重置').height(46).width(72).margin({ left: 10 })
                  .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
                  .onClick(() => { this.resetDemoSamples(); })
              }.width('100%').margin({ top: 10 })
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 14 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Row() {
              Text('今日小测').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              Blank()
              Text(this.todayQuiz.toString() + ' / ' + QUIZ_TARGET.toString() + ' 题').fontSize(12)
                .fontColor(this.todayQuiz >= QUIZ_TARGET ? Theme.successInk : Theme.accentInk)
            }.width('100%')
            Text('从「' + this.currentBookName() + '」的 ' + this.bookPool().length.toString()
              + ' 个词里随机出 5 题；答错的词自动进错题本，可以重练。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 6 })
            Button(this.todayQuiz >= QUIZ_TARGET ? '再练一组 · 5 题' : '开始一组小测 · 5 题')
              .width('100%').height(50).margin({ top: 12 })
              .backgroundColor(Theme.accentInk).fontColor(Color.White)
              .onClick(() => { this.startQuiz(false); })
          }
          .width('100%').padding(16).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Row() {
              Text('错题本').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              Blank()
              Text(this.wrongNames.length.toString() + ' 个').fontSize(12).fontColor(Theme.secondaryText)
            }.width('100%')
            if (this.wrongNames.length === 0) {
              Text('还没有真实错题。答错或拿不准的词会自动收集到这里。').fontSize(12)
                .fontColor(Theme.secondaryText).margin({ top: 6 })
            } else {
              ForEach(this.wrongNames.slice(0, 4), (word: string) => {
                Row() {
                  Text(word).fontSize(14).fontColor(Theme.primaryText).width(120)
                  Text(this.wrongMeaning(word)).fontSize(12).fontColor(Theme.secondaryText).layoutWeight(1)
                }.width('100%').padding({ top: 6, bottom: 6 })
              }, (word: string) => word)
              if (this.wrongNames.length > 4) {
                Text('还有 ' + (this.wrongNames.length - 4).toString() + ' 个，重练时都会出现').fontSize(11)
                  .fontColor(Theme.secondaryText).margin({ top: 2 })
              }
              Button('错题重练 · 5 题').width('100%').height(46).margin({ top: 10 })
                .backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
                .onClick(() => { this.startQuiz(true); })
            }
          }
          .width('100%').padding(16).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          if (this.store.recentSessions(3).length > 0) {
            Column() {
              Text('最近小测').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              ForEach(this.store.recentSessions(3), (session: QuizSession, index: number) => {
                Row() {
                  Text(this.sessionLabel(session)).fontSize(12).fontColor(Theme.secondaryText)
                }.width('100%').padding({ top: 5, bottom: 5 })
              }, (session: QuizSession, index: number) => session.at.toString() + '-' + index.toString())
            }
            .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
            .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          }

          Text('小测只记录对错和错题，不排名、不罚分；示例错题单独演示，不写进统计。')
            .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 18, bottom: 30 })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('practice-' + this.viewVersion.toString())
  }

  @Builder
  quizView() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Text('小测').fontSize(30).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            Text('第 ' + (this.quizIndex + 1).toString() + ' / ' + this.quizQuestions.length.toString() + ' 题')
              .fontSize(13).fontColor(Theme.secondaryText).margin({ left: 10 }).margin({ top: 8 })
            Blank()
            Button('退出').height(32).fontSize(12)
              .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
              .onClick(() => { this.quitQuiz(); })
          }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 6, bottom: 12 })

          Column() {
            Row() {
              Text(this.quizFromWrong ? '错题重练' : '词库随机 · ' + this.currentBookName()).fontSize(11)
                .fontColor(Theme.goldInk)
                .backgroundColor(Theme.goldSoft).padding({ left: 8, right: 8, top: 4, bottom: 4 }).borderRadius(10)
              Blank()
              Text('本组答对 ' + this.quizCorrect.toString() + ' 题').fontSize(11).fontColor(Theme.secondaryText)
            }.width('100%')

            Text(this.currentQuestion().word).fontSize(38).fontWeight(FontWeight.Bold)
              .fontColor(Theme.primaryText).margin({ top: 22 })
            Text(this.currentQuestion().phonetic).fontSize(14).fontColor(Theme.secondaryText).margin({ top: 6 })
            Text('选出正确的中文释义').fontSize(13).fontColor(Theme.secondaryText).margin({ top: 16 })
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(18)
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          ForEach(this.currentOptions(), (option: string, index: number) => {
            this.optionRow(option, index)
          }, (option: string, index: number) => this.quizIndex.toString() + '-' + index.toString() + '-' + option)

          if (this.quizSelected >= 0) {
            Column() {
              Text(this.quizSelected === this.currentQuestion().answerIndex ? '✓ 答对了'
                : '✗ 正确答案：' + this.currentQuestion().meaning)
                .fontSize(15).fontWeight(FontWeight.Medium)
                .fontColor(this.quizSelected === this.currentQuestion().answerIndex
                  ? Theme.successInk : Theme.dangerInk)
              Row() {
                Text('例句').fontSize(10).fontColor(Theme.goldInk)
                  .backgroundColor(Theme.goldSoft).padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(6)
                Text(this.currentQuestion().example).fontSize(13).fontColor(Theme.secondaryText)
                  .layoutWeight(1).margin({ left: 8 })
              }.alignItems(VerticalAlign.Top).margin({ top: 10 })
              Button(this.quizIndex + 1 < this.quizQuestions.length ? '下一题' : '查看本组结果')
                .width('100%').height(48).margin({ top: 14 })
                .backgroundColor(Theme.accentInk).fontColor(Color.White)
                .onClick(() => { this.nextQuestion(); })
            }
            .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
            .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          }

          Text('答错不扣分，答完会立刻显示正确答案和例句。').fontSize(11)
            .fontColor(Theme.secondaryText).margin({ top: 16, bottom: 30 })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('quiz-' + this.viewVersion.toString())
  }

  @Builder
  optionRow(option: string, index: number) {
    Row() {
      Text(this.optionLetter(index)).fontSize(13).fontWeight(FontWeight.Bold)
        .fontColor(this.optionInk(index)).width(24).height(24).textAlign(TextAlign.Center)
        .backgroundColor(this.quizSelected >= 0 ? Theme.card : Theme.bg).borderRadius(12)
      Text(option).fontSize(15).fontColor(this.optionInk(index)).layoutWeight(1).margin({ left: 12 })
    }
    .width('100%').padding({ left: 14, right: 14, top: 15, bottom: 15 })
    .backgroundColor(this.optionBg(index)).borderRadius(14)
    .border({ width: 1, color: this.optionBorder(index) })
    .margin({ top: 10 })
    .onClick(() => { this.chooseOption(index); })
  }

  @Builder
  resultView() {
    Scroll() {
      Row() {
        Column() {
          Text('本组完成').fontSize(30).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            .width('100%').textAlign(TextAlign.Center).margin({ top: 20 })

          Column() {
            Text(this.resultCorrect.toString() + ' / ' + this.resultTotal.toString())
              .fontSize(52).fontWeight(FontWeight.Bold)
              .fontColor(this.resultCorrect * 2 >= this.resultTotal ? Theme.successInk : Theme.warningInk)
            Text(this.resultCorrect === this.resultTotal ? '全对，这一组很稳'
              : '答错 ' + (this.resultTotal - this.resultCorrect).toString() + ' 题，已放进错题本')
              .fontSize(13).fontColor(Theme.secondaryText).margin({ top: 6 })
          }
          .width('100%').padding({ top: 26, bottom: 26 }).alignItems(HorizontalAlign.Center)
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 16 })

          if (this.quizWrongWords.length > 0) {
            Column() {
              Text('这一组需要再看一眼').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              ForEach(this.quizWrongWords, (word: string) => {
                Row() {
                  Text(word).fontSize(14).fontColor(Theme.dangerInk).width(120)
                  Text(this.wrongMeaning(word)).fontSize(12).fontColor(Theme.secondaryText).layoutWeight(1)
                }.width('100%').padding({ top: 6, bottom: 6 })
              }, (word: string, index: number) => word + '-' + index.toString())
            }
            .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
            .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          }

          Button('再来一组 · 5 题').width('100%').height(50).margin({ top: 16 })
            .backgroundColor(Theme.accentInk).fontColor(Color.White)
            .onClick(() => { this.startQuiz(false); })
          if (this.wrongNames.length > 0) {
            Button('错题重练 · 5 题').width('100%').height(46).margin({ top: 10 })
              .backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
              .onClick(() => { this.startQuiz(true); })
          }
          Button('返回练习').width('100%').height(46).margin({ top: 10 })
            .backgroundColor(Color.Transparent).fontColor(Theme.secondaryText)
            .onClick(() => {
              this.practiceMode = PRACTICE_MENU;
              this.quizFromWrong = false;
            })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding, bottom: 30 })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('result-' + this.viewVersion.toString())
  }

  @Builder
  demoQuizView() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Text('示例复习').fontSize(30).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            Text('第 ' + (this.demoIndex + 1).toString() + ' / ' + DEMO_QUESTIONS.length.toString() + ' 题')
              .fontSize(13).fontColor(Theme.secondaryText).margin({ left: 10 }).margin({ top: 8 })
            Blank()
            Text('剩余 ' + this.demoSeconds.toString() + ' 秒').fontSize(13).fontWeight(FontWeight.Medium)
              .fontColor(this.demoSeconds <= 15 ? Theme.dangerInk : Theme.goldInk)
            Button('退出').height(30).fontSize(11).margin({ left: 8 })
              .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
              .onClick(() => { this.quitDemo(); })
          }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 6, bottom: 12 })

          Column() {
            Row() {
              Text('示例').fontSize(11).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
                .padding({ left: 8, right: 8, top: 4, bottom: 4 }).borderRadius(10)
              Text(this.currentDemo().kind).fontSize(11).fontColor(Theme.secondaryText).margin({ left: 8 })
              Blank()
              Text('不计入学习统计').fontSize(10).fontColor(Theme.secondaryText)
            }.width('100%')
            Text(this.currentDemo().prompt).fontSize(24).fontWeight(FontWeight.Medium)
              .fontColor(Theme.primaryText).margin({ top: 18 })
            Text('示例内容：数学公式 / 英语 / 形近词，用来演示错题复习流程。').fontSize(11)
              .fontColor(Theme.secondaryText).margin({ top: 10 })
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(18)
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          ForEach(this.currentDemo().options, (option: string, index: number) => {
            this.demoOptionRow(option, index)
          }, (option: string, index: number) => this.demoIndex.toString() + '-' + index.toString() + '-' + option)

          if (this.demoSelected >= 0) {
            Column() {
              Text(this.demoSelected === this.currentDemo().answerIndex ? '✓ 答对了'
                : '✗ 正确答案：' + this.currentDemo().options[this.currentDemo().answerIndex])
                .fontSize(15).fontWeight(FontWeight.Medium)
                .fontColor(this.demoSelected === this.currentDemo().answerIndex
                  ? Theme.successInk : Theme.dangerInk)
              Text(this.currentDemo().explanation).fontSize(13).fontColor(Theme.secondaryText)
                .margin({ top: 10 })
              Button(this.demoIndex + 1 < DEMO_QUESTIONS.length ? '下一题' : '查看示例结果')
                .width('100%').height(48).margin({ top: 14 })
                .backgroundColor(Theme.goldInk).fontColor(Color.White)
                .onClick(() => { this.nextDemoQuestion(); })
            }
            .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
            .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          }

          Text('这是示例，不是扫描识别结果，也不会写进你的正确率/错题本。').fontSize(11)
            .fontColor(Theme.secondaryText).margin({ top: 16, bottom: 30 })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('demo-' + this.viewVersion.toString())
  }

  @Builder
  demoOptionRow(option: string, index: number) {
    Row() {
      Text(this.optionLetter(index)).fontSize(13).fontWeight(FontWeight.Bold)
        .fontColor(this.demoOptionInk(index)).width(24).height(24).textAlign(TextAlign.Center)
        .backgroundColor(this.demoSelected >= 0 ? Theme.card : Theme.bg).borderRadius(12)
      Text(option).fontSize(15).fontColor(this.demoOptionInk(index)).layoutWeight(1).margin({ left: 12 })
    }
    .width('100%').padding({ left: 14, right: 14, top: 15, bottom: 15 })
    .backgroundColor(this.demoOptionBg(index)).borderRadius(14)
    .border({ width: 1, color: this.demoOptionBorder(index) })
    .margin({ top: 10 })
    .onClick(() => { this.chooseDemoOption(index); })
  }

  @Builder
  demoResultView() {
    Scroll() {
      Row() {
        Column() {
          Text(this.demoTimedOut ? '时间到' : '示例复习完成').fontSize(30).fontWeight(FontWeight.Bold)
            .fontColor(Theme.primaryText).width('100%').textAlign(TextAlign.Center).margin({ top: 20 })

          Column() {
            Text(this.demoCorrect.toString() + ' / ' + DEMO_QUESTIONS.length.toString())
              .fontSize(52).fontWeight(FontWeight.Bold).fontColor(Theme.goldInk)
            Text('用时 ' + this.demoUsed.toString() + ' 秒 · 示例不计入学习统计').fontSize(13)
              .fontColor(Theme.secondaryText).margin({ top: 6 })
            Text(this.demoTimedOut ? '60 秒到了，没答完的题下次再来。' : '60 秒内完成了 3 道示例错题复习。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 10 })
          }
          .width('100%').padding({ top: 26, bottom: 26 }).alignItems(HorizontalAlign.Center)
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 16 })

          Column() {
            Text('示例说明了什么').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
            Text('· 错题可以按“示例 → 逐题反馈 → 结果”的路径快速复习；').fontSize(12)
              .fontColor(Theme.secondaryText).margin({ top: 8 })
            Text('· 真实的错题来自你做小测时答错的词，会自动进错题本；').fontSize(12)
              .fontColor(Theme.secondaryText).margin({ top: 6 })
            Text('· 这不是扫描识别，也没有假装有相机识别能力。').fontSize(12)
              .fontColor(Theme.secondaryText).margin({ top: 6 })
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
          .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)

          Button('再试一次 · 60 秒').width('100%').height(50).margin({ top: 16 })
            .backgroundColor(Theme.goldInk).fontColor(Color.White)
            .onClick(() => { this.startDemo(); })
          Button('进入正式小测 · 5 题').width('100%').height(46).margin({ top: 10 })
            .backgroundColor(Theme.accentSoft).fontColor(Theme.accentInk)
            .onClick(() => { this.startQuiz(false); })
          Button('返回练习').width('100%').height(46).margin({ top: 10 })
            .backgroundColor(Color.Transparent).fontColor(Theme.secondaryText)
            .onClick(() => { this.quitDemo(); })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding, bottom: 30 })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('demo-result-' + this.viewVersion.toString())
  }

  // ---------- 我的 tab ----------

  private doReset(): void {
    this.store.resetAll().then(() => {
      this.wordRevealed = false;
      this.lastReviewedWord = '';
      this.practiceMode = PRACTICE_MENU;
      this.stopDemoTimer();
      this.toast('学习数据已重置');
      this.reload();
    });
  }

  private confirmReset(): void {
    AlertDialog.show({
      title: '重置全部学习数据',
      message: '签到记录、复习排期、小测成绩和错题本都会被清空，词库本身不受影响。',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '继续', fontColor: Theme.warningInk,
        action: () => { this.confirmResetSecond(); }
      }
    });
  }

  private confirmResetSecond(): void {
    AlertDialog.show({
      title: '再次确认',
      message: '这一步不可撤销：确定清空这台设备上的全部学习数据吗？',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '确认重置', fontColor: Theme.dangerInk,
        action: () => { this.doReset(); }
      }
    });
  }

  @Builder
  reportRow(label: string, value: string) {
    Row() {
      Text(label).fontSize(13).fontColor(Theme.secondaryText)
      Blank()
      Text(value).fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
    }.width('100%').padding({ top: 9, bottom: 9 })
  }

  @Builder
  reportMetric(label: string, value: string) {
    Column() {
      Text(value).fontSize(18).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(label).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
    }.layoutWeight(1)
  }

  @Builder
  featureNavRow(title: string, caption: string, url: string) {
    Row() {
      Column() {
        Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Text(caption).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text('›').fontSize(22).fontColor(Theme.secondaryText)
    }
    .width('100%').padding({ top: 10, bottom: 10 })
    .border({ width: { top: 1 }, color: Theme.divider })
    .onClick(() => { router.pushUrl({ url: url }); })
  }

  @Builder
  profilePage() {
    Scroll() {
      Row() {
        Column() {
          Text('我的').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            .width('100%').padding({ top: 6 })
          Text(this.productSubtitle()).fontSize(12).fontColor(Theme.secondaryText)
            .width('100%').margin({ top: 3, bottom: 14 })

          Column() {
            Row() {
              Text('学').fontSize(20).fontColor(Theme.accentInk).width(48).height(48)
                .textAlign(TextAlign.Center).backgroundColor(Theme.accentSoft).borderRadius(14)
              Column() {
                Text('StudyKit').fontSize(20).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
                Text('HarmonyOS 原生 · v1.0.0').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 4 })
              }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
            }.width('100%')
            Text('所有数据保存在本机，不需要账号，也不会上传；每一步都能撤销。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 12 })
          }
          .width('100%').padding(18).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Text('完整功能').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              .width('100%')
            Text('Android 五大模块的鸿蒙原生页面，全部本地保存。')
              .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 5, bottom: 6 })
            this.featureNavRow('学习计划', '模板 · 编辑 · 预览 · 本地保存', 'pages/PlanPage')
            this.featureNavRow('习惯打卡', '每日 / 补卡 · 连续天数 · 月历', 'pages/HabitsPage')
            this.featureNavRow('读书笔记', '书架 · 阅读进度 · 摘录笔记', 'pages/BooksPage')
            this.featureNavRow('题库练习', '按学科刷题 · 错题自动入库', 'pages/QuestionPracticePage')
            this.featureNavRow('错题本', '筛选 · 重做 · 掌握 · 导出', 'pages/MistakesPage')
            this.featureNavRow('拍照 / 相册 OCR', '识别题目文字 · 校对导入 · 手动兜底', 'pages/OcrPage')
            this.featureNavRow('自定义单词库', '增删改 · 批量粘贴 · 导出', 'pages/WordManagePage')
            this.featureNavRow('学习统计', '记忆报告 · 分享海报', 'pages/StatsPage')
            this.featureNavRow('设置与备份', '每日目标 · 提醒 · zip 导出/恢复', 'pages/SettingsPage')
          }
          .width('100%').padding(18).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Row() {
              Text('个人记忆曲线').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              Blank()
              Text(this.memoryPercent().toString() + ' %').fontSize(14).fontWeight(FontWeight.Medium)
                .fontColor(Theme.goldInk)
            }.width('100%')
            Progress({ value: this.memoryPercent(), total: 100, type: ProgressType.Linear })
              .color(Theme.gold).backgroundColor(Theme.goldSoft).width('100%').height(8).margin({ top: 10 })
            Text(this.memoryRemaining() > 0
              ? '再复习 ' + this.memoryRemaining().toString() + ' 次解锁个人记忆曲线'
              : '已解锁：累计复习 ' + this.reviewTotal.toString() + ' 次')
              .fontSize(13).fontColor(Theme.primaryText).margin({ top: 10 })
            Text('进度按真实复习次数计算（每复习 1 个词记 1 次，满 ' + MEMORY_CURVE_TARGET.toString()
              + ' 次解锁）；只按真实次数计数，不做算法校准。')
              .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 6 })
          }
          .width('100%').padding(18).margin({ top: 12 })
          .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)

          Column() {
            Text('学习报告').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              .width('100%')
            Row() {
              this.reportMetric('已掌握', this.masteredCount.toString() + ' 词')
              this.reportMetric('待复习', this.dueCount.toString() + ' 词')
              this.reportMetric('正确率', this.accuracyText())
            }.width('100%').padding({ top: 14, bottom: 8 })
            Row() {
              this.reportMetric('连续学习', this.streak.toString() + ' 天')
              this.reportMetric('累计学习', this.totalDays.toString() + ' 天')
              this.reportMetric('签到累计', this.summary.totalDays.toString() + ' 天')
            }.width('100%').padding({ bottom: 8 })
            Divider().color(Theme.divider).margin({ top: 4, bottom: 4 })
            this.reportRow('本地词库', allWords().length.toString() + ' 词 / ' + WORD_BOOKS.length.toString() + ' 本词书')
            this.reportRow('已开始学习', this.learningCount.toString() + ' 词')
            this.reportRow('累计复习次数', this.reviewTotal.toString() + ' 次')
            this.reportRow('今日已复习 / 目标', this.todayReviewed.toString() + ' / ' + REVIEW_TARGET.toString() + ' 词')
            this.reportRow('今日已答小测', this.todayQuiz.toString() + ' 题')
            this.reportRow('错题本', this.wrongNames.length.toString() + ' 个')
            Divider().color(Theme.divider).margin({ top: 4, bottom: 8 })
            ForEach(booksOf(), (book: WordBook) => {
              Row() {
                Text(book.name).fontSize(13).fontColor(Theme.primaryText).width(56)
                Text(this.bookStats(book.id)).fontSize(12).fontColor(Theme.secondaryText).layoutWeight(1)
              }.width('100%').padding({ top: 5, bottom: 5 })
            }, (book: WordBook) => book.id)
          }
          .width('100%').padding(18).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Text('分享海报').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              .width('100%')
            Text('用 Canvas 在本地生成 PNG 海报（带 StudyKit 水印），保存到应用 filesDir，不上传、不需要账号。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
            Row() {
              Button('学习报告海报').layoutWeight(1).height(46)
                .backgroundColor(Theme.accentInk).fontColor(Color.White)
                .onClick(() => { this.generatePoster(POSTER_REPORT); })
              Button('签到海报').layoutWeight(1).height(46).margin({ left: 10 })
                .backgroundColor(Theme.goldSoft).fontColor(Theme.goldInk)
                .onClick(() => { this.generatePoster(POSTER_SIGN); })
            }.width('100%').margin({ top: 12 })
          }
          .width('100%').padding(18).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Text('为什么这样安排').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              .width('100%')
            Text('· 每天只推三件小事：签到、复习 5 词、小测 3 题，先做起来再懂。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 10 })
            Text('· 66 个词分成 CET4 / CET6 / 考研 / 基础 四本离线词书，点一下就能切换。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
            Text('· 复习用 Leitner 5 盒排期；今日待复习按到期日真实计算。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
            Text('· 3 道示例错题（数学公式 / 英语 / 形近词）用于 60 秒看懂错题复习，不计入统计。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
            Text('· 签到可以补最近 3 天、每月 3 次；补签和上一步复习都能撤销。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
            Text('· 数据只在这台设备上，没有账号，也不联网上传。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
          }
          .width('100%').padding(18).margin({ top: 12 })
          .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)

          Column() {
            Row() {
              Column() {
                Text('重置全部学习数据').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
                Text('清空签到、复习排期、小测成绩与错题').fontSize(11).fontColor(Theme.secondaryText)
                  .margin({ top: 3 })
              }.alignItems(HorizontalAlign.Start).layoutWeight(1)
              Button('重置').height(34).fontSize(13)
                .backgroundColor(Theme.dangerSoft).fontColor(Theme.dangerInk)
                .onClick(() => { this.confirmReset(); })
            }.width('100%')
          }
          .width('100%').padding(16).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Text('v1.0.0 · 本地数据，不上传账号').fontSize(11).fontColor(Theme.secondaryText)
            .width('100%').textAlign(TextAlign.Center).margin({ top: 22, bottom: 30 })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('profile-' + this.viewVersion.toString())
  }

  // ---------- 海报（Canvas -> PNG） ----------

  private pad2(value: number): string {
    return value < 10 ? '0' + value.toString() : value.toString();
  }

  private timestamp(): string {
    const d = new Date();
    return d.getFullYear().toString() + this.pad2(d.getMonth() + 1) + this.pad2(d.getDate())
      + '-' + this.pad2(d.getHours()) + this.pad2(d.getMinutes()) + this.pad2(d.getSeconds());
  }

  private generatePoster(kind: number): void {
    try {
      const canvas = new OffscreenCanvas(POSTER_WIDTH, POSTER_HEIGHT);
      const ctx = canvas.getContext('2d', new RenderingContextSettings(true));
      this.drawPoster(ctx, kind);
      const dataUrl: string = ctx.toDataURL('image/png');
      this.posterKind = kind;
      this.posterData = dataUrl;
      this.posterPath = '';
      this.posterError = '';
      this.savePoster(dataUrl, kind);
    } catch (e) {
      this.posterKind = kind;
      this.posterData = '';
      this.posterPath = '';
      this.posterError = '海报生成失败，请重试（' + JSON.stringify(e) + '）';
    }
  }

  private savePoster(dataUrl: string, kind: number): void {
    try {
      const comma: number = dataUrl.indexOf(',');
      if (comma < 0) {
        this.posterError = '没有取到 PNG 图片数据';
        return;
      }
      const base64: string = dataUrl.substring(comma + 1);
      const helper = new util.Base64Helper();
      const bytes: Uint8Array = helper.decodeSync(base64);
      const name: string = (kind === POSTER_REPORT ? 'StudyKit-report-' : 'StudyKit-signin-')
        + this.timestamp() + '.png';
      const path: string = this.filesDir + '/' + name;
      const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, bytes.buffer as ArrayBuffer);
      } finally {
        fs.closeSync(file);
      }
      this.posterPath = path;
      this.toast('PNG 已保存：' + name);
    } catch (e) {
      this.posterError = 'PNG 保存失败，请重试';
    }
  }

  private drawPoster(ctx: OffscreenCanvasRenderingContext2D, kind: number): void {
    ctx.fillStyle = '#F7F2E8';
    ctx.fillRect(0, 0, POSTER_WIDTH, POSTER_HEIGHT);
    ctx.fillStyle = '#0A6F5C';
    ctx.fillRect(0, 0, POSTER_WIDTH, 170);
    ctx.textAlign = 'left';
    ctx.fillStyle = '#FBF0D2';
    ctx.font = 'bold 46px sans-serif';
    ctx.fillText('StudyKit', 48, 82);
    ctx.fillStyle = '#FFFFFF';
    ctx.font = '24px sans-serif';
    ctx.fillText(kind === POSTER_REPORT ? '学习报告 · 分享海报' : '签到打卡 · 分享海报', 48, 126);
    ctx.textAlign = 'right';
    ctx.fillStyle = '#E1F3ED';
    ctx.font = '18px sans-serif';
    ctx.fillText(this.today, 672, 82);
    ctx.textAlign = 'left';
    if (kind === POSTER_REPORT) {
      this.drawReportPoster(ctx);
    } else {
      this.drawSignPoster(ctx);
    }
    ctx.fillStyle = '#7C776B';
    ctx.font = '16px sans-serif';
    ctx.fillText('本地生成 · 数据只保存在这台设备 · StudyKit 水印', 48, 1002);
    ctx.textAlign = 'left';
  }

  private drawMetricCard(ctx: OffscreenCanvasRenderingContext2D, x: number, y: number, w: number,
    h: number, value: string, label: string): void {
    ctx.fillStyle = '#FFFDF8';
    ctx.fillRect(x, y, w, h);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 34px sans-serif';
    ctx.fillText(value, x + w / 2, y + 62);
    ctx.fillStyle = '#7C776B';
    ctx.font = '18px sans-serif';
    ctx.fillText(label, x + w / 2, y + 104);
    ctx.textAlign = 'left';
  }

  private drawReportPoster(ctx: OffscreenCanvasRenderingContext2D): void {
    this.drawMetricCard(ctx, 48, 205, 192, 140, this.masteredCount.toString() + ' 词', '已掌握');
    this.drawMetricCard(ctx, 264, 205, 192, 140, this.dueCount.toString() + ' 词', '待复习');
    this.drawMetricCard(ctx, 480, 205, 192, 140, this.accuracyText(), '正确率');
    this.drawMetricCard(ctx, 48, 362, 192, 140, this.streak.toString() + ' 天', '连续学习');
    this.drawMetricCard(ctx, 264, 362, 192, 140, this.totalDays.toString() + ' 天', '累计学习');
    this.drawMetricCard(ctx, 480, 362, 192, 140, this.reviewTotal.toString() + ' 次', '累计复习');

    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 22px sans-serif';
    ctx.fillText('个人记忆曲线进度（真实复习次数）', 48, 560);
    ctx.fillStyle = '#E9E1D1';
    ctx.fillRect(48, 582, 624, 24);
    ctx.fillStyle = '#B8860B';
    ctx.fillRect(48, 582, 624 * this.memoryPercent() / 100, 24);
    ctx.fillStyle = '#7C776B';
    ctx.font = '18px sans-serif';
    ctx.fillText(this.memoryRemaining() > 0
      ? '再复习 ' + this.memoryRemaining().toString() + ' 次解锁个人记忆曲线'
      : '已解锁：累计复习 ' + this.reviewTotal.toString() + ' 次', 48, 636);

    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 22px sans-serif';
    ctx.fillText('四本词书进度', 48, 692);
    const books: WordBook[] = booksOf();
    let y: number = 726;
    for (let i = 0; i < books.length; i++) {
      const pool: WordItem[] = wordsOfBook(books[i].id);
      let learned: number = 0;
      let mastered: number = 0;
      for (let j = 0; j < pool.length; j++) {
        const card: SrsCard | null = Srs.findCard(this.store.cards, pool[j].word);
        if (card !== null) {
          learned++;
          if (card.box >= Srs.MAX_BOX) {
            mastered++;
          }
        }
      }
      ctx.fillStyle = '#20241F';
      ctx.font = '18px sans-serif';
      ctx.fillText(books[i].name + '  已学 ' + learned.toString() + '/' + pool.length.toString()
        + ' · 掌握 ' + mastered.toString(), 48, y);
      ctx.fillStyle = '#E9E1D1';
      ctx.fillRect(48, y + 10, 624, 14);
      ctx.fillStyle = '#0F9C82';
      ctx.fillRect(48, y + 10, pool.length === 0 ? 0 : 624 * learned / pool.length, 14);
      y += 62;
    }
  }

  private drawSignPoster(ctx: OffscreenCanvasRenderingContext2D): void {
    ctx.textAlign = 'center';
    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 56px sans-serif';
    ctx.fillText('连续签到 ' + this.summary.currentStreak.toString() + ' 天', 360, 300);
    ctx.fillStyle = '#7C776B';
    ctx.font = '22px sans-serif';
    ctx.fillText('累计 ' + this.summary.totalDays.toString() + ' 天 · 最长连续 '
      + this.summary.longestStreak.toString() + ' 天 · 本月 ' + this.currentMonthSigned().toString() + ' 天', 360, 352);
    ctx.textAlign = 'left';

    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 22px sans-serif';
    ctx.fillText('最近 14 天', 48, 430);
    const last14: SignInDay[] = this.summary.last14;
    for (let i = 0; i < last14.length; i++) {
      const x: number = 48 + i * 44;
      ctx.fillStyle = last14[i].signed ? (last14[i].makeup ? '#B8860B' : '#0F9C82') : '#E9E1D1';
      ctx.fillRect(x, 452, 34, 34);
    }

    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 22px sans-serif';
    ctx.fillText('里程碑', 48, 560);
    let mx: number = 48;
    for (let i = 0; i < SignInStats.MILESTONES.length; i++) {
      const reached: boolean = this.summary.longestStreak >= SignInStats.MILESTONES[i];
      ctx.fillStyle = reached ? '#FBF0D2' : '#E9E1D1';
      ctx.fillRect(mx, 584, 104, 44);
      ctx.fillStyle = reached ? '#8A6500' : '#7C776B';
      ctx.font = '18px sans-serif';
      ctx.fillText(SignInStats.MILESTONES[i].toString() + ' 天', mx + 18, 614);
      mx += 118;
    }

    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 22px sans-serif';
    ctx.fillText('个人记忆曲线', 48, 716);
    ctx.fillStyle = '#E9E1D1';
    ctx.fillRect(48, 738, 624, 24);
    ctx.fillStyle = '#B8860B';
    ctx.fillRect(48, 738, 624 * this.memoryPercent() / 100, 24);
    ctx.fillStyle = '#7C776B';
    ctx.font = '18px sans-serif';
    ctx.fillText('累计复习 ' + this.reviewTotal.toString() + ' 次 · 再复习 '
      + this.memoryRemaining().toString() + ' 次解锁个人记忆曲线', 48, 794);
  }

  @Builder
  posterView() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Column() {
              Text('分享海报').fontSize(30).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text(this.posterKind === POSTER_REPORT ? '学习报告 · PNG' : '签到打卡 · PNG').fontSize(12)
                .fontColor(Theme.secondaryText).margin({ top: 3 })
            }.alignItems(HorizontalAlign.Start)
            Blank()
            Button('关闭').height(34).fontSize(13)
              .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
              .onClick(() => { this.posterKind = POSTER_NONE; })
          }.width('100%').padding({ top: 6, bottom: 12 })

          if (this.posterData.length > 0) {
            Image(this.posterData).width('100%').height(460).objectFit(ImageFit.Contain)
              .borderRadius(16).backgroundColor(Theme.card)
          } else {
            Column() {
              Text('海报生成中').fontSize(13).fontColor(Theme.secondaryText)
            }.width('100%').height(200).justifyContent(FlexAlign.Center)
          }

          if (this.posterPath.length > 0) {
            Column() {
              Text('PNG 已保存到应用本地').fontSize(13).fontColor(Theme.successInk)
              Text(this.posterPath).fontSize(11).fontColor(Theme.secondaryText)
                .margin({ top: 4 }).wordBreak(WordBreak.BREAK_ALL)
            }.width('100%').alignItems(HorizontalAlign.Start).padding(14).margin({ top: 12 })
            .backgroundColor(Theme.successSoft).borderRadius(12)
          }
          if (this.posterError.length > 0) {
            Text(this.posterError).fontSize(12).fontColor(Theme.dangerInk).margin({ top: 12 })
          }

          Row() {
            Button('重新生成').layoutWeight(1).height(46)
              .backgroundColor(Theme.accentInk).fontColor(Color.White)
              .onClick(() => { this.generatePoster(this.posterKind); })
            Button('返回').layoutWeight(1).height(46).margin({ left: 10 })
              .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
              .onClick(() => { this.posterKind = POSTER_NONE; })
          }.width('100%').margin({ top: 16 })

          Text('海报由 ArkUI Canvas 在本地绘制；文字和数据都来自本机，不会上传。').fontSize(11)
            .fontColor(Theme.secondaryText).margin({ top: 14, bottom: 30 })
        }
        .width('100%').constraintSize({ maxWidth: 760 })
      }
      .width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('poster-' + this.viewVersion.toString())
  }

  // ---------- 框架 ----------

  @Builder
  tabBarItem(label: string, index: number) {
    Column() {
      Text(label).fontSize(13)
        .fontColor(this.currentTab === index ? Theme.accentInk : Theme.secondaryText)
        .fontWeight(this.currentTab === index ? FontWeight.Medium : FontWeight.Normal)
      Row().width(16).height(3).borderRadius(2).margin({ top: 4 })
        .backgroundColor(this.currentTab === index ? Theme.accent : Color.Transparent)
    }
    .width('100%').height(56).justifyContent(FlexAlign.Center)
  }

  build() {
    if (this.posterKind !== POSTER_NONE) {
      this.posterView()
    } else if (this.loading) {
      Column() {
        LoadingProgress().width(44).height(44).color(Theme.accent)
        Text('正在读取本地数据').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 10 })
      }
      .width('100%').height('100%').justifyContent(FlexAlign.Center)
      .backgroundColor(Theme.bg)
    } else {
      Tabs({ barPosition: BarPosition.End, index: this.currentTab, controller: this.tabsController }) {
        TabContent() { this.homePage() }.tabBar(this.tabBarItem('学习', TAB_HOME))
        TabContent() { this.signInPage() }.tabBar(this.tabBarItem('签到', TAB_SIGN))
        TabContent() { this.wordsPage() }.tabBar(this.tabBarItem('单词', TAB_WORDS))
        TabContent() { this.practicePage() }.tabBar(this.tabBarItem('练习', TAB_PRACTICE))
        TabContent() { this.profilePage() }.tabBar(this.tabBarItem('我的', TAB_PROFILE))
      }
      .barMode(BarMode.Fixed)
      .barBackgroundColor(Theme.card)
      .barHeight(58)
      .onChange((index: number) => {
        this.currentTab = index;
        if (index !== TAB_PRACTICE) {
          this.stopDemoTimer();
          if (this.practiceMode === PRACTICE_QUIZ || this.practiceMode === PRACTICE_DEMO_QUIZ) {
            if (this.practiceMode === PRACTICE_DEMO_QUIZ) {
              this.practiceMode = PRACTICE_MENU;
            }
          }
        }
        this.reload();
      })
      .width('100%').height('100%').backgroundColor(Theme.bg)
      .id('tabs-' + this.viewVersion.toString())
    }
  }
}
