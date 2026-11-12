import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import { Theme } from '../common/Theme';
import { WORD_BANK, WordItem, lookupWord } from '../model/Words';
import { StudyStore } from '../model/Store';
import { Srs, SrsCard } from '../model/Srs';
import { QuizBuilder, QuizQuestion, QuizSession } from '../model/Quiz';
import { SignInCell, SignInDay, SignInMonth, SignInStats, SignInSummary } from '../model/SignIn';

const TAB_HOME: number = 0;
const TAB_SIGN: number = 1;
const TAB_WORDS: number = 2;
const TAB_PRACTICE: number = 3;
const TAB_PROFILE: number = 4;

const REVIEW_TARGET: number = 5;
const QUIZ_TARGET: number = 3;
const TASK_TOTAL: number = 3;

const PRACTICE_MENU: number = 0;
const PRACTICE_QUIZ: number = 1;
const PRACTICE_RESULT: number = 2;

@Entry
@Component
struct Index {
  @State currentTab: number = TAB_HOME;
  @State loading: boolean = true;
  @State viewVersion: number = 0;
  @State today: string = SignInStats.today();

  @State summary: SignInSummary = SignInStats.emptySummary();
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

  private store: StudyStore = StudyStore.get();
  private tabsController: TabsController = new TabsController();

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.store.init(context).then(() => {
      this.reload();
    }).catch(() => {
      this.loading = false;
      this.toast('本地数据读取失败，已从空白数据开始');
      this.reload();
    });
  }

  async reload(): Promise<void> {
    this.today = SignInStats.today();
    const cards: SrsCard[] = this.store.cards;
    this.summary = SignInStats.summary(this.store.signIns, this.today);
    this.dueCount = Srs.dueCount(cards, this.today);
    this.masteredCount = Srs.masteredCount(cards);
    this.learningCount = cards.length;
    const reviewTotal: number = Srs.totalCorrect(cards) + Srs.totalWrong(cards);
    const attempts: number = reviewTotal + this.store.quizTotalCount();
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
    if (tab !== TAB_PRACTICE && this.practiceMode === PRACTICE_QUIZ) {
      this.practiceMode = PRACTICE_MENU;
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
      return '今天已复习 ' + this.todayReviewed.toString() + ' 个词';
    }
    return '约 2 分钟 · 已复习 ' + shown.toString() + ' / ' + REVIEW_TARGET.toString();
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

  // ---------- 学习 tab ----------

  @Builder
  homePage() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Column() {
              Text('学习').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text(this.dateLabel()).fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
            }.alignItems(HorizontalAlign.Start)
            Blank()
            Text(this.streak > 0 ? '连续 ' + this.streak.toString() + ' 天' : '今天开始')
              .fontSize(12).fontColor(Theme.goldInk)
              .backgroundColor(Theme.goldSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
              .borderRadius(14)
          }.width('100%').padding({ top: 6, bottom: 14 })

          this.startCard()

          Row() {
            this.metric('今日待复习', this.dueCount.toString() + ' 词')
            this.metricDivider()
            this.metric('已掌握', this.masteredCount.toString() + ' 词')
            this.metricDivider()
            this.metric('正确率', this.accuracyText())
          }
          .width('100%').padding({ top: 14, bottom: 14 }).margin({ top: 12 })
          .id('home-metrics-' + this.viewVersion.toString() + '-' + this.dueCount.toString() + '-' + this.masteredCount.toString() + '-' + this.accuracy.toString())
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          this.signQuickCard()

          if (this.wrongNames.length > 0) {
            Row() {
              Text('错').fontSize(15).fontColor(Theme.dangerInk).width(38).height(38)
                .textAlign(TextAlign.Center).backgroundColor(Theme.dangerSoft).borderRadius(19)
              Column() {
                Text('错题本').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
                Text(this.wrongNames.length.toString() + ' 个词待重练 · 小测里答错会自动收进来').fontSize(12)
                  .fontColor(Theme.secondaryText).margin({ top: 3 })
              }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
              Text('去练习 ›').fontSize(12).fontColor(Theme.accentInk)
            }
            .width('100%').padding(14).margin({ top: 12 })
            .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
            .onClick(() => { this.goTab(TAB_PRACTICE); })
          }

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

      Text('做完一件是一件，不用按顺序。')
        .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(18).borderRadius(Theme.cardRadius)
    .linearGradient({ angle: 135, colors: [[Theme.accentSoft, 0.0], [Theme.card, 0.78]] })
    .id('start-card-' + this.viewVersion.toString() + '-' + this.tasksDone().toString() + '-' + this.todayReviewed.toString() + '-' + this.todayQuiz.toString())
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
    .id('task-row-' + step.toString() + '-' + (done ? '1' : '0') + '-' + this.viewVersion.toString())
    .onClick(() => { this.goTab(tab); })
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
            Text('累计 ' + this.summary.totalDays.toString() + ' 天').fontSize(12).fontColor(Theme.goldInk)
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
    const cards: SrsCard[] = this.store.cards;
    const due: SrsCard[] = Srs.dueCards(cards, this.today);
    for (let i = 0; i < due.length; i++) {
      if (due[i].word !== this.lastReviewedWord) {
        const item: WordItem | null = lookupWord(due[i].word);
        if (item !== null) {
          return item;
        }
      }
    }
    for (let i = 0; i < WORD_BANK.length; i++) {
      const word: string = WORD_BANK[i].word;
      if (word === this.lastReviewedWord) {
        continue;
      }
      if (Srs.findCard(cards, word) === null) {
        return WORD_BANK[i];
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
      return { word: '', phonetic: '', meaning: '', example: '' };
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

  private currentBoxLabel(): string {
    const card: SrsCard | null = this.currentReviewCard();
    if (card === null) {
      return '新词 · 第 1 次见面';
    }
    return '第 ' + card.box.toString() + ' 盒 · 下次复习：' + Srs.friendlyDue(card, this.today);
  }

  private currentWordAccuracy(): string {
    const card: SrsCard | null = this.currentReviewCard();
    if (card === null || (card.correct + card.wrong) === 0) {
      return '还没答过';
    }
    return '答对 ' + card.correct.toString() + ' 次 · 答错 ' + card.wrong.toString() + ' 次';
  }

  async reviewCurrent(remembered: boolean): Promise<void> {
    const item: WordItem | null = this.currentReviewWord();
    if (item === null) {
      return;
    }
    const card = await this.store.reviewWord(item.word, remembered, this.today);
    this.lastReviewedWord = item.word;
    this.wordRevealed = false;
    if (remembered) {
      this.toast('已记住 · ' + Srs.friendlyDue(card, this.today) + '再复习');
    } else {
      this.toast('再学一次 · 这个词稍后会再出现');
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
              Text('本地词库 ' + WORD_BANK.length.toString() + ' 词 · 待复习 ' + this.dueCount.toString()
                + ' · 已掌握 ' + this.masteredCount.toString())
                .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
            }.alignItems(HorizontalAlign.Start)
            Blank()
            Text('已学 ' + this.learningCount.toString() + ' 词').fontSize(12).fontColor(Theme.secondaryText)
          }.width('100%').padding({ top: 6, bottom: 12 })

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
          .width('100%').padding(14).backgroundColor(Theme.card).borderRadius(14).margin({ bottom: 12 })

          if (this.hasReviewWord()) {
            this.reviewCard()

            Row() {
              Button('再学一次').layoutWeight(1).height(52)
                .backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
                .onClick(() => { this.reviewCurrent(false); })
              Button('记住了').layoutWeight(1).height(52).margin({ left: 12 })
                .backgroundColor(Theme.accentInk).fontColor(Color.White)
                .onClick(() => { this.reviewCurrent(true); })
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
              Text('今天的复习完成了').fontSize(22).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text('待复习的词都清空了，新词会在明天继续出现。').fontSize(13)
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
            Text('Leitner 5 盒：记住升一盒，下次间隔 1 / 2 / 4 / 7 天；再学一次退回第 1 盒，今天晚些时候再见。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 6 })
            Text('“今日待复习”只统计到期日小于等于今天的词，不做假数据。')
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
        Text(this.currentBoxLabel()).fontSize(11).fontColor(Theme.goldInk)
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
    const priority: string[] = [];
    if (fromWrong) {
      for (let i = 0; i < this.store.wrongs.length && priority.length < QuizBuilder.GROUP_SIZE; i++) {
        priority.push(this.store.wrongs[i].word);
      }
    } else {
      const due: string[] = Srs.dueWordNames(this.store.cards, this.today);
      for (let i = 0; i < due.length && priority.length < QuizBuilder.GROUP_SIZE; i++) {
        priority.push(due[i]);
      }
    }
    const questions: QuizQuestion[] = QuizBuilder.build(QuizBuilder.GROUP_SIZE, priority);
    if (questions.length === 0) {
      this.toast('词库暂时没有可出题的词');
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
              Text(this.quizFromWrong ? '错题重练' : '今日小测').fontSize(16).fontWeight(FontWeight.Medium)
                .fontColor(Theme.primaryText)
              Blank()
              Text(this.todayQuiz.toString() + ' / ' + QUIZ_TARGET.toString() + ' 题').fontSize(12)
                .fontColor(this.todayQuiz >= QUIZ_TARGET ? Theme.successInk : Theme.accentInk)
            }.width('100%')
            Text('从 ' + WORD_BANK.length.toString() + ' 词库里随机出 5 题；答错的词自动进错题本，可以重练。')
              .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 6 })
            Button(this.todayQuiz >= QUIZ_TARGET ? '再练一组 · 5 题' : '开始一组小测 · 5 题')
              .width('100%').height(50).margin({ top: 12 })
              .backgroundColor(Theme.accentInk).fontColor(Color.White)
              .onClick(() => { this.startQuiz(false); })
          }
          .width('100%').padding(16).margin({ top: 14 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Row() {
              Text('错题本').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              Blank()
              Text(this.wrongNames.length.toString() + ' 个').fontSize(12).fontColor(Theme.secondaryText)
            }.width('100%')
            if (this.wrongNames.length === 0) {
              Text('还没有错题。答错或拿不准的词会自动收集到这里。').fontSize(12)
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

          Text('小测只记录对错和错题，不排名、不罚分。').fontSize(11)
            .fontColor(Theme.secondaryText).margin({ top: 18, bottom: 30 })
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
              Text(this.quizFromWrong ? '错题重练' : '词库随机').fontSize(11).fontColor(Theme.goldInk)
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

  // ---------- 我的 tab ----------

  private doReset(): void {
    this.store.resetAll().then(() => {
      this.wordRevealed = false;
      this.lastReviewedWord = '';
      this.practiceMode = PRACTICE_MENU;
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
  profilePage() {
    Scroll() {
      Row() {
        Column() {
          Text('我的').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            .width('100%').padding({ top: 6 })
          Text('学习报告与本地数据').fontSize(12).fontColor(Theme.secondaryText)
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
            this.reportRow('本地词库', WORD_BANK.length.toString() + ' 词')
            this.reportRow('已开始学习', this.learningCount.toString() + ' 词')
            this.reportRow('今日待复习', this.dueCount.toString() + ' 词')
            this.reportRow('今日已复习 / 目标', this.todayReviewed.toString() + ' / ' + REVIEW_TARGET.toString() + ' 词')
            this.reportRow('今日已答小测', this.todayQuiz.toString() + ' 题')
            this.reportRow('错题本', this.wrongNames.length.toString() + ' 个')
          }
          .width('100%').padding(18).margin({ top: 12 })
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

          Column() {
            Text('为什么这样安排').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
              .width('100%')
            Text('· 每天只推三件小事：签到、复习 5 词、小测 3 题，先做起来再懂。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 10 })
            Text('· 复习用 Leitner 5 盒排期，今天该复习哪些词按到期日真实计算。').fontSize(12)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
            Text('· 小测 5 题一组，答完立刻看答案与例句，错题自动收集可重练。').fontSize(12)
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

  @Builder
  reportMetric(label: string, value: string) {
    Column() {
      Text(value).fontSize(18).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(label).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
    }.layoutWeight(1)
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
    if (this.loading) {
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
        this.reload();
      })
      .width('100%').height('100%').backgroundColor(Theme.bg)
      .id('tabs-' + this.viewVersion.toString())
    }
  }
}
