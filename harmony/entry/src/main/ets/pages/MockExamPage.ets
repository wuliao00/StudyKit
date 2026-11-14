import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { QuestionStore, QUESTION_ALL } from '../model/QuestionStore';
import {
  ExamLogic,
  EXAM_SECONDS_PER_QUESTION,
  MOCK_COUNT_OPTIONS,
  MockExamConfig,
  MockExamQuestionSnapshot,
  MockExamRecord,
  MockExamSession,
  MockExamStore
} from '../model/MockExamStore';

// 模考页：组卷（10/20/30 题、按学科筛选 · 每题 60 秒）→ 逐题作答/标记 → 时间到或手动交卷。
// 交卷前不显示任何对错（延迟反馈，与 Android v2.7 起的 MockExamState 同口径）；
// 交卷后写 MockExamStore 记录 + 错题本，跳成绩页（ExamResultPage）。
@Entry
@Component
struct MockExamPage {
  @State loading: boolean = true;
  @State mode: number = 0; // 0 = 组卷设置，1 = 考试中
  @State subjects: string[] = [];
  @State selectedSubject: string = QUESTION_ALL;
  @State selectedCount: number = 10;
  @State total: number = 0;
  @State index: number = 0;
  @State answers: number[] = [];
  @State marks: boolean[] = [];
  @State remainingSec: number = 0;
  @State paused: boolean = false;
  @State submitting: boolean = false;
  @State errorText: string = '';
  @State recent: MockExamRecord[] = [];

  private store: MockExamStore = MockExamStore.get();
  private questionStore: QuestionStore = QuestionStore.get();
  private timerId: number = -1;

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.questionStore.init(context).then(() => {
      this.store.init(context).then(() => {
        this.subjects = this.questionStore.getSubjects();
        this.recent = this.store.records().slice(0, 3);
        this.loading = false;
      }).catch(() => {
        this.loading = false;
        this.toast('本地模考记录读取失败');
      });
    }).catch(() => {
      this.loading = false;
      this.toast('本地题库读取失败，可返回后重试');
    });
  }

  aboutToDisappear(): void {
    this.stopTimer();
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  // ---------- 计时 ----------

  private startTimer(): void {
    this.stopTimer();
    this.timerId = setInterval(() => {
      this.onTick();
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  private onTick(): void {
    const session: MockExamSession | null = this.store.currentSession();
    if (session === null || session.submitted) {
      this.stopTimer();
      return;
    }
    this.paused = session.paused;
    const remain: number = this.store.tick();
    this.remainingSec = remain;
    if (remain <= 0 && !this.submitting) {
      this.stopTimer();
      this.doSubmit(true);
    }
  }

  // ---------- 组卷 ----------

  private availableCount(): number {
    return this.questionStore.questionsBySubject(this.selectedSubject).length;
  }

  private maxSeconds(): number {
    return this.selectedCount * EXAM_SECONDS_PER_QUESTION;
  }

  private startExam(): void {
    const available: number = this.availableCount();
    if (available === 0) {
      this.toast('「' + this.selectedSubject + '」下还没有题目，先去题库练习页导入或切到「全部」');
      return;
    }
    this.stopTimer();
    const config: MockExamConfig = {
      count: this.selectedCount,
      subject: this.selectedSubject,
      secondsPerQuestion: EXAM_SECONDS_PER_QUESTION
    };
    const session: MockExamSession | null = this.store.start(config);
    if (session === null) {
      this.toast('组卷失败：题库为空');
      return;
    }
    this.total = session.questions.length;
    this.index = 0;
    this.answers = session.selected.slice(0);
    this.marks = session.marks.slice(0);
    this.remainingSec = session.remainingSec;
    this.paused = false;
    this.submitting = false;
    this.errorText = '';
    this.mode = 1;
    this.startTimer();
    if (this.total < this.selectedCount) {
      this.toast('题库只有 ' + this.total + ' 道可用，本次按 ' + this.total + ' 题组卷');
    }
  }

  // ---------- 作答 / 标记 / 导航（交卷前不算对错） ----------

  private emptyQuestion(): MockExamQuestionSnapshot {
    const empty: MockExamQuestionSnapshot = {
      id: '', subject: '', stem: '', options: [], answer: 0, explanation: '', source: ''
    };
    return empty;
  }

  private currentQuestion(): MockExamQuestionSnapshot {
    const session: MockExamSession | null = this.store.currentSession();
    if (session === null || this.index < 0 || this.index >= session.snapshots.length) {
      return this.emptyQuestion();
    }
    return session.snapshots[this.index];
  }

  private choose(option: number): void {
    if (this.paused || this.submitting) {
      return;
    }
    this.store.answer(this.index, option);
    const session: MockExamSession | null = this.store.currentSession();
    if (session !== null) {
      this.answers = session.selected.slice(0);
    }
  }

  private toggleMark(): void {
    if (this.paused || this.submitting) {
      return;
    }
    this.store.toggleMark(this.index);
    const session: MockExamSession | null = this.store.currentSession();
    if (session !== null) {
      this.marks = session.marks.slice(0);
    }
  }

  private goPrev(): void {
    if (this.index > 0) {
      this.index = this.index - 1;
    }
  }

  private goNext(): void {
    if (this.index + 1 < this.total) {
      this.index = this.index + 1;
    }
  }

  private togglePause(): void {
    const session: MockExamSession | null = this.store.currentSession();
    if (session === null) {
      return;
    }
    if (session.paused) {
      this.store.resume();
    } else {
      this.store.pause();
    }
    this.paused = session.paused;
  }

  private answeredCount(): number {
    let count: number = 0;
    for (let i = 0; i < this.answers.length; i++) {
      if (this.answers[i] >= 0) {
        count++;
      }
    }
    return count;
  }

  private unansweredCount(): number {
    return this.total - this.answeredCount();
  }

  private markedCount(): number {
    let count: number = 0;
    for (let i = 0; i < this.marks.length; i++) {
      if (this.marks[i]) {
        count++;
      }
    }
    return count;
  }

  private selectedOption(at: number): number {
    if (at < 0 || at >= this.answers.length) {
      return -1;
    }
    return this.answers[at];
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
    if (index === 3) {
      return 'D';
    }
    return '?';
  }

  // ---------- 交卷 ----------

  private requestSubmit(): void {
    if (this.submitting) {
      return;
    }
    const unanswered: number = this.unansweredCount();
    let message: string = '已答 ' + this.answeredCount().toString() + ' / ' + this.total.toString() + ' 题。';
    if (unanswered > 0) {
      message = message + '还有 ' + unanswered.toString()
        + ' 题未作答：未作答按错算进「未作答」栏，但不进正确率分母。';
    }
    message = message + '交卷后不能再改答案。';
    AlertDialog.show({
      title: '确认交卷',
      message: message,
      primaryButton: { value: '继续答题', action: () => {} },
      secondaryButton: {
        value: '交卷', fontColor: Theme.accentInk,
        action: () => { this.doSubmit(false); }
      }
    });
  }

  private doSubmit(auto: boolean): void {
    if (this.submitting) {
      return;
    }
    this.submitting = true;
    this.stopTimer();
    this.store.submit(auto).then((record: MockExamRecord | null) => {
      this.submitting = false;
      if (record === null) {
        this.errorText = '交卷失败：没有进行中的试卷，请返回重开一局';
        return;
      }
      this.toast('交卷完成：' + record.correct.toString() + ' / ' + record.total.toString()
        + '，错题已写入错题本');
      router.replaceUrl({ url: 'pages/ExamResultPage' });
    }).catch(() => {
      this.submitting = false;
      this.errorText = '交卷失败，请点「交卷」重试';
      this.toast('交卷失败，请重试');
    });
  }

  private requestBack(): void {
    if (this.mode !== 1 || this.total === 0) {
      router.back();
      return;
    }
    AlertDialog.show({
      title: '放弃本卷？',
      message: '离开后本卷的作答与计时不会保存；已交卷的错题不受影响。',
      primaryButton: { value: '继续答题', action: () => {} },
      secondaryButton: {
        value: '放弃并离开', fontColor: Theme.dangerInk,
        action: () => {
          this.stopTimer();
          router.back();
        }
      }
    });
  }

  // ---------- UI ----------

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { this.requestBack(); })
      Column() {
        Text(this.mode === 1 ? '模考进行中' : '模考').fontSize(26).fontWeight(FontWeight.Bold)
          .fontColor(Theme.primaryText)
        Text(this.mode === 1
          ? '延迟反馈：交卷前不显示对错 · 时间到自动交卷'
          : '按学科组卷 · 每题 60 秒 · 交卷后逐题给解析')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      if (this.mode === 1) {
        Button(this.submitting ? '交卷中…' : '交卷').height(34).fontSize(12)
          .backgroundColor(this.submitting ? Theme.cardWarm : Theme.dangerSoft)
          .fontColor(this.submitting ? Theme.secondaryText : Theme.dangerInk)
          .enabled(!this.submitting)
          .onClick(() => { this.requestSubmit(); })
      }
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  countChips() {
    Row() {
      ForEach(MOCK_COUNT_OPTIONS, (count: number) => {
        Text(count.toString() + ' 题').fontSize(12)
          .fontColor(this.selectedCount === count ? Color.White : Theme.secondaryText)
          .backgroundColor(this.selectedCount === count ? Theme.accentInk : Theme.cardWarm)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 }).borderRadius(14).margin({ right: 8 })
          .onClick(() => { this.selectedCount = count; })
      }, (count: number) => count.toString())
    }.width('100%')
  }

  @Builder
  subjectChips() {
    Scroll() {
      Row() {
        Text('全部').fontSize(12)
          .fontColor(this.selectedSubject === QUESTION_ALL ? Color.White : Theme.secondaryText)
          .backgroundColor(this.selectedSubject === QUESTION_ALL ? Theme.accentInk : Theme.cardWarm)
          .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
          .onClick(() => { this.selectedSubject = QUESTION_ALL; })
        ForEach(this.subjects, (subject: string) => {
          Text(subject).fontSize(12)
            .fontColor(this.selectedSubject === subject ? Color.White : Theme.secondaryText)
            .backgroundColor(this.selectedSubject === subject ? Theme.accentInk : Theme.cardWarm)
            .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
            .onClick(() => { this.selectedSubject = subject; })
        }, (subject: string) => subject)
      }
    }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
  }

  @Builder
  setupView() {
    Column() {
      Column() {
        Text('限时模拟 · 延迟反馈').fontSize(18).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Text('从当前题库抽题组一卷；作答期间不提示对错，交卷后一次性判分并逐题给解析。')
          .fontSize(12).fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
        Row() {
          Text('可用题目').fontSize(11).fontColor(Theme.secondaryText)
          Blank()
          Text(this.availableCount().toString() + ' 道').fontSize(13).fontWeight(FontWeight.Medium)
            .fontColor(this.availableCount() > 0 ? Theme.accentInk : Theme.dangerInk)
        }.width('100%').margin({ top: 12 })
      }
      .width('100%').alignItems(HorizontalAlign.Start).padding(18)
      .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

      Column() {
        Text('题数').fontSize(13).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        this.countChips()
        Text('限时 ' + ExamLogic.formatDuration(this.maxSeconds()) + '（每题 '
          + EXAM_SECONDS_PER_QUESTION.toString() + ' 秒）').fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 10 })
      }
      .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
      .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

      Column() {
        Text('学科').fontSize(13).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Column() {
          this.subjectChips()
        }.width('100%').margin({ top: 10 })
      }
      .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
      .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

      Button('开始模考').width('100%').height(50).margin({ top: 14 })
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => { this.startExam(); })

      if (this.recent.length > 0) {
        Column() {
          Text('最近模考').fontSize(13).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
            .width('100%')
          ForEach(this.recent, (record: MockExamRecord) => {
            Row() {
              Text(record.subjectLabel).fontSize(12).fontColor(Theme.accentInk)
                .backgroundColor(Theme.accentSoft).padding({ left: 8, right: 8, top: 3, bottom: 3 })
                .borderRadius(8)
              Text(record.correct.toString() + ' / ' + record.total.toString()).fontSize(13)
                .fontColor(Theme.primaryText).margin({ left: 10 })
              Blank()
              Text('正确率 ' + record.accuracy.toString() + '%').fontSize(11)
                .fontColor(Theme.secondaryText)
              Text(' · ' + ExamLogic.formatDuration(record.durationSec)).fontSize(11)
                .fontColor(Theme.secondaryText)
            }.width('100%').padding({ top: 9, bottom: 9 })
            .border({ width: { top: 1 }, color: Theme.divider })
          }, (record: MockExamRecord) => record.id)
        }
        .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
        .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
      }

      Text('模考记录保存在应用本地（filesDir/studykit_mock_exams.json），不上传；'
        + '答错的题会自动写入错题本。').fontSize(11).fontColor(Theme.secondaryText)
        .margin({ top: 16, bottom: 28 })
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  statusCard() {
    Column() {
      Row() {
        Text('剩余 ' + ExamLogic.formatDuration(this.remainingSec)).fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.remainingSec <= 60 ? Theme.dangerInk : Theme.primaryText)
        Blank()
        Text('已答 ' + this.answeredCount().toString() + ' / ' + this.total.toString())
          .fontSize(12).fontColor(Theme.secondaryText)
      }.width('100%').alignItems(VerticalAlign.Center)
      Row() {
        Text('第 ' + (this.index + 1).toString() + ' / ' + this.total.toString() + ' 题')
          .fontSize(12).fontColor(Theme.accentInk)
        if (this.markedCount() > 0) {
          Text(' · 已标记 ' + this.markedCount().toString() + ' 题').fontSize(11)
            .fontColor(Theme.goldInk)
        }
        Blank()
        Button(this.paused ? '继续计时' : '暂停').height(32).fontSize(12)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
          .onClick(() => { this.togglePause(); })
      }.width('100%').alignItems(VerticalAlign.Center).margin({ top: 8 })
      if (this.errorText.length > 0) {
        Text(this.errorText).fontSize(11).fontColor(Theme.dangerInk).width('100%')
          .margin({ top: 8 })
      }
    }
    .width('100%').padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  answerSheet() {
    Scroll() {
      Row() {
        ForEach(this.answers, (value: number, at: number) => {
          Text((at + 1).toString()).fontSize(11).width(28).height(28).textAlign(TextAlign.Center)
            .fontColor(at === this.index ? Color.White
              : (value >= 0 ? Theme.accentInk : Theme.secondaryText))
            .backgroundColor(at === this.index ? Theme.accentInk
              : (value >= 0 ? Theme.accentSoft : Theme.cardWarm))
            .borderRadius(8).margin({ right: 6 })
            .border({ width: this.marks[at] ? 2 : 0, color: Theme.gold })
            .onClick(() => { this.index = at; })
        }, (value: number, at: number) => at.toString() + '-' + value.toString()
          + '-' + this.marks[at].toString())
      }
    }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)
    .width('100%').margin({ top: 10 })
  }

  @Builder
  questionCard() {
    Column() {
      Row() {
        Text(this.currentQuestion().subject).fontSize(10).fontColor(Theme.goldInk)
          .backgroundColor(Theme.goldSoft).padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .borderRadius(8)
        if (this.marks[this.index]) {
          Text('已标记').fontSize(10).fontColor(Theme.goldInk)
            .backgroundColor(Theme.goldSoft).padding({ left: 8, right: 8, top: 3, bottom: 3 })
            .borderRadius(8).margin({ left: 6 })
        }
        Blank()
        Button(this.marks[this.index] ? '取消标记' : '标记').height(28).fontSize(11)
          .backgroundColor(this.marks[this.index] ? Theme.goldSoft : Theme.cardWarm)
          .fontColor(this.marks[this.index] ? Theme.goldInk : Theme.secondaryText)
          .onClick(() => { this.toggleMark(); })
      }.width('100%').alignItems(VerticalAlign.Center)
      Text(this.currentQuestion().stem).fontSize(19).fontWeight(FontWeight.Medium)
        .fontColor(Theme.primaryText).width('100%').margin({ top: 14 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(18).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  optionRow(option: string, at: number) {
    Row() {
      Text(this.optionLetter(at)).fontSize(13).fontWeight(FontWeight.Bold)
        .fontColor(this.selectedOption(this.index) === at ? Color.White : Theme.secondaryText)
        .width(24).height(24).textAlign(TextAlign.Center)
        .backgroundColor(this.selectedOption(this.index) === at ? Theme.accentInk : Theme.bg)
        .borderRadius(12)
      Text(option).fontSize(15)
        .fontColor(this.selectedOption(this.index) === at ? Theme.accentInk : Theme.primaryText)
        .layoutWeight(1).margin({ left: 12 })
    }
    .width('100%').padding({ left: 14, right: 14, top: 15, bottom: 15 })
    .backgroundColor(this.selectedOption(this.index) === at ? Theme.accentSoft : Theme.card)
    .borderRadius(14)
    .border({ width: this.selectedOption(this.index) === at ? 2 : 1, color: Theme.divider })
    .margin({ top: 10 })
    .onClick(() => { this.choose(at); })
  }

  @Builder
  pauseCard() {
    Column() {
      Text('已暂停').fontSize(22).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text('计时已停下，剩余 ' + ExamLogic.formatDuration(this.remainingSec)).fontSize(12)
        .fontColor(Theme.secondaryText).margin({ top: 8 })
      Text('暂停期间不扣时；继续后从剩下的时间接着走。').fontSize(11)
        .fontColor(Theme.secondaryText).margin({ top: 4 })
      Button('继续答题').width('100%').height(48).margin({ top: 16 })
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => { this.togglePause(); })
      Button('交卷').width('100%').height(46).margin({ top: 10 })
        .backgroundColor(Theme.dangerSoft).fontColor(Theme.dangerInk)
        .onClick(() => { this.requestSubmit(); })
    }
    .width('100%').alignItems(HorizontalAlign.Center).padding(22).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  examView() {
    Column() {
      this.statusCard()
      this.answerSheet()
      if (this.paused) {
        this.pauseCard()
      } else if (this.submitting) {
        Column() {
          LoadingProgress().width(40).height(40).color(Theme.accent)
          Text('正在判分并写入错题本…').fontSize(12).fontColor(Theme.secondaryText)
            .margin({ top: 10 })
        }.width('100%').height(200).justifyContent(FlexAlign.Center)
        .margin({ top: 12 }).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
      } else {
        this.questionCard()
        ForEach(this.currentQuestion().options, (option: string, at: number) => {
          this.optionRow(option, at)
        }, (option: string, at: number) => this.currentQuestion().id + '-' + at.toString()
          + '-' + option)
        Row() {
          Button('上一题').layoutWeight(1).height(44)
            .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
            .enabled(this.index > 0)
            .onClick(() => { this.goPrev(); })
          Button(this.index + 1 < this.total ? '下一题' : '检查并交卷').layoutWeight(1).height(44)
            .margin({ left: 10 })
            .backgroundColor(Theme.accentInk).fontColor(Color.White)
            .onClick(() => {
              if (this.index + 1 < this.total) {
                this.goNext();
              } else {
                this.requestSubmit();
              }
            })
        }.width('100%').margin({ top: 14 })
      }
      Text('交卷前不显示对错；答错的题会在交卷后自动写入错题本。').fontSize(11)
        .fontColor(Theme.secondaryText).margin({ top: 14, bottom: 28 })
    }.width('100%')
  }

  build() {
    Column() {
      if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Theme.accent)
          Text('正在读取本地题库与模考记录').fontSize(12).fontColor(Theme.secondaryText)
            .margin({ top: 8 })
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.header()
            if (this.mode === 1) {
              this.examView()
            } else {
              this.setupView()
            }
          }.width('100%').constraintSize({ maxWidth: 760 })
        }.width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%')
    .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    .backgroundColor(Theme.bg)
  }
}
