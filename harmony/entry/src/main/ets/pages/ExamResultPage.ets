import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { QuestionStore } from '../model/QuestionStore';
import {
  ExamLogic,
  MockExamQuestionSnapshot,
  MockExamRecord,
  MockExamStore,
  SubjectTally
} from '../model/MockExamStore';

// 模考成绩页：交卷后第一次摊开对错（延迟反馈的另一半）。
// 正确率的分母是「判过的题」（已作答），未作答单独一栏，不计入正确率分母 —— 与 Android v3.0 口径一致。
// 逐题给判词 + 解析；「薄弱学科」按学科正确率升序取未满 100% 的前 3 科。
@Entry
@Component
struct ExamResultPage {
  @State loading: boolean = true;
  @State hasRecord: boolean = false;
  @State subjectLabel: string = '';
  @State createdAt: number = 0;
  @State total: number = 0;
  @State judged: number = 0;
  @State correct: number = 0;
  @State accuracy: number = 0;
  @State durationSec: number = 0;
  @State autoSubmitted: boolean = false;
  @State wrongCount: number = 0;
  @State requestedCount: number = 0;
  @State weak: SubjectTally[] = [];
  @State tallies: SubjectTally[] = [];
  @State questions: MockExamQuestionSnapshot[] = [];
  @State selected: number[] = [];
  @State marks: boolean[] = [];
  @State history: MockExamRecord[] = [];

  private store: MockExamStore = MockExamStore.get();

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.store.init(context).then(() => {
      this.history = this.store.records().slice(0, 5);
      const id: string = this.store.resultId();
      let record: MockExamRecord | null = this.store.findRecord(id);
      if (record === null) {
        record = this.store.latestRecord();
      }
      if (record === null) {
        this.hasRecord = false;
      } else {
        this.showRecord(record);
      }
      this.loading = false;
    }).catch(() => {
      this.loading = false;
      this.hasRecord = false;
      this.toast('本地模考记录读取失败');
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private showRecord(record: MockExamRecord): void {
    this.hasRecord = true;
    this.subjectLabel = record.subjectLabel;
    this.createdAt = record.createdAt;
    this.total = record.total;
    this.judged = record.judged;
    this.correct = record.correct;
    this.accuracy = record.accuracy;
    this.durationSec = record.durationSec;
    this.autoSubmitted = record.autoSubmitted;
    this.wrongCount = record.wrongCount;
    this.requestedCount = record.requestedCount;
    this.weak = record.weak.slice(0);
    this.tallies = record.tallies.slice(0);
    this.questions = record.questions.slice(0);
    this.selected = record.selected.slice(0);
    this.marks = record.marks.slice(0);
  }

  private openHistory(record: MockExamRecord): void {
    this.showRecord(record);
  }

  private unansweredCount(): number {
    return this.total - this.judged;
  }

  private accuracyText(): string {
    if (this.judged <= 0) {
      return '0%';
    }
    return this.accuracy.toString() + '%';
  }

  private dateText(): string {
    if (this.createdAt <= 0) {
      return '—';
    }
    return QuestionStore.formatTime(this.createdAt);
  }

  private isWeak(subject: string): boolean {
    for (let i = 0; i < this.weak.length; i++) {
      if (this.weak[i].subject === subject) {
        return true;
      }
    }
    return false;
  }

  private selectedOf(at: number): number {
    if (at < 0 || at >= this.selected.length) {
      return -1;
    }
    return this.selected[at];
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

  private optionBg(at: number, optionIndex: number): string {
    const q: MockExamQuestionSnapshot = this.questions[at];
    if (optionIndex === q.answer) {
      return Theme.successSoft;
    }
    if (this.selectedOf(at) === optionIndex) {
      return Theme.dangerSoft;
    }
    return Theme.card;
  }

  private optionInk(at: number, optionIndex: number): string {
    const q: MockExamQuestionSnapshot = this.questions[at];
    if (optionIndex === q.answer) {
      return Theme.successInk;
    }
    if (this.selectedOf(at) === optionIndex) {
      return Theme.dangerInk;
    }
    return Theme.secondaryText;
  }

  private yourAnswerText(at: number): string {
    const q: MockExamQuestionSnapshot = this.questions[at];
    const picked: number = this.selectedOf(at);
    if (picked < 0) {
      return '未作答';
    }
    if (picked >= q.options.length) {
      return '—';
    }
    return q.options[picked];
  }

  private correctAnswerText(at: number): string {
    const q: MockExamQuestionSnapshot = this.questions[at];
    if (q.answer < 0 || q.answer >= q.options.length) {
      return '—';
    }
    return q.options[q.answer];
  }

  private verdictText(at: number): string {
    const picked: number = this.selectedOf(at);
    if (picked < 0) {
      return '未作答';
    }
    if (picked === this.questions[at].answer) {
      return '✓ 答对';
    }
    return '✗ 答错';
  }

  private verdictInk(at: number): string {
    const picked: number = this.selectedOf(at);
    if (picked < 0) {
      return Theme.secondaryText;
    }
    if (picked === this.questions[at].answer) {
      return Theme.successInk;
    }
    return Theme.dangerInk;
  }

  // ---------- UI ----------

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text('模考成绩').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text(this.hasRecord
          ? (this.autoSubmitted ? '时间到，已自动交卷' : '手动交卷')
            + ' · 判分与解析只在交卷后可见'
          : '还没有可看的成绩')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 12 })
  }

  @Builder
  summaryCard() {
    Column() {
      Row() {
        Text('得分').fontSize(12).fontColor(Theme.secondaryText)
        Blank()
        Text(this.dateText()).fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%')
      Text(this.correct.toString() + ' / ' + this.total.toString()).fontSize(52)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.accuracy >= 60 ? Theme.successInk : Theme.warningInk)
        .margin({ top: 6 })
      Text('正确率 ' + this.accuracyText() + '（以判过的 ' + this.judged.toString() + ' 题为分母）')
        .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 2 })
      if (this.unansweredCount() > 0) {
        Text('有 ' + this.unansweredCount().toString() + ' 题未作答：未作答不进正确率分母，单列在下面。')
          .fontSize(11).fontColor(Theme.warningInk).width('100%').margin({ top: 10 })
      }
      Row() {
        Column() {
          Text(ExamLogic.formatDuration(this.durationSec)).fontSize(17)
            .fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Text('用时').fontSize(10).fontColor(Theme.secondaryText).margin({ top: 2 })
        }.layoutWeight(1)
        Column() {
          Text(this.autoSubmitted ? '自动' : '手动').fontSize(17)
            .fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Text('交卷方式').fontSize(10).fontColor(Theme.secondaryText).margin({ top: 2 })
        }.layoutWeight(1)
        Column() {
          Text(this.wrongCount.toString()).fontSize(17)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.wrongCount > 0 ? Theme.dangerInk : Theme.successInk)
          Text('错题入库').fontSize(10).fontColor(Theme.secondaryText).margin({ top: 2 })
        }.layoutWeight(1)
      }.width('100%').margin({ top: 14 })
      Row() {
        Text('试卷：' + this.subjectLabel + ' · ' + this.total.toString() + ' 题')
          .fontSize(11).fontColor(Theme.secondaryText)
        if (this.total < this.requestedCount) {
          Text('（题库不足，原计划 ' + this.requestedCount.toString() + ' 题）').fontSize(10)
            .fontColor(Theme.secondaryText)
        }
      }.width('100%').margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(18)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  weakCard() {
    Column() {
      Text('薄弱学科').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      if (this.tallies.length === 0) {
        Text('这一卷没有可统计的学科。').fontSize(11).fontColor(Theme.secondaryText)
          .width('100%').margin({ top: 6 })
      } else {
        if (this.weak.length === 0) {
          Text('各科全对，没有明显薄弱学科。').fontSize(12).fontColor(Theme.successInk)
            .width('100%').margin({ top: 6 })
        }
        ForEach(this.tallies, (tally: SubjectTally) => {
          Row() {
            Text(tally.subject).fontSize(12)
              .fontColor(this.isWeak(tally.subject) ? Theme.dangerInk : Theme.primaryText)
            if (this.isWeak(tally.subject)) {
              Text('薄弱').fontSize(9).fontColor(Theme.dangerInk)
                .backgroundColor(Theme.dangerSoft).padding({ left: 6, right: 6, top: 2, bottom: 2 })
                .borderRadius(6).margin({ left: 6 })
            }
            Blank()
            Text(tally.correct.toString() + ' / ' + tally.total.toString()).fontSize(11)
              .fontColor(Theme.secondaryText)
            Text(' · ' + tally.accuracy.toString() + '%').fontSize(12)
              .fontColor(this.isWeak(tally.subject) ? Theme.dangerInk : Theme.successInk)
          }.width('100%').padding({ top: 8, bottom: 8 })
          .border({ width: { top: 1 }, color: Theme.divider })
        }, (tally: SubjectTally) => tally.subject + '-' + tally.accuracy.toString())
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  analysisCard(at: number) {
    Column() {
      Row() {
        Text('第 ' + (at + 1).toString() + ' 题').fontSize(11).fontColor(Theme.secondaryText)
        Text(this.questions[at].subject).fontSize(10).fontColor(Theme.goldInk)
          .backgroundColor(Theme.goldSoft).padding({ left: 7, right: 7, top: 2, bottom: 2 })
          .borderRadius(7).margin({ left: 8 })
        if (this.marks[at]) {
          Text('已标记').fontSize(10).fontColor(Theme.goldInk).margin({ left: 8 })
        }
        Blank()
        Text(this.verdictText(at)).fontSize(12).fontWeight(FontWeight.Medium)
          .fontColor(this.verdictInk(at))
      }.width('100%').alignItems(VerticalAlign.Center)
      Text(this.questions[at].stem).fontSize(15).fontColor(Theme.primaryText)
        .width('100%').margin({ top: 10 })
      ForEach(this.questions[at].options, (option: string, optionIndex: number) => {
        Row() {
          Text(this.optionLetter(optionIndex)).fontSize(11).fontWeight(FontWeight.Bold)
            .fontColor(this.optionInk(at, optionIndex)).width(20).height(20)
            .textAlign(TextAlign.Center).backgroundColor(Theme.bg).borderRadius(10)
          Text(option).fontSize(13).fontColor(this.optionInk(at, optionIndex))
            .layoutWeight(1).margin({ left: 10 })
        }
        .width('100%').padding({ left: 10, right: 10, top: 9, bottom: 9 })
        .backgroundColor(this.optionBg(at, optionIndex)).borderRadius(10).margin({ top: 6 })
      }, (option: string, optionIndex: number) => this.questions[at].id + '-' + optionIndex.toString()
        + '-' + option)
      Row() {
        Text('你的答案：' + this.yourAnswerText(at)).fontSize(11)
          .fontColor(this.selectedOf(at) >= 0 && this.selectedOf(at) !== this.questions[at].answer
            ? Theme.dangerInk : Theme.secondaryText)
        Blank()
        Text('正确答案：' + this.correctAnswerText(at)).fontSize(11).fontColor(Theme.successInk)
      }.width('100%').margin({ top: 8 })
      if (this.questions[at].explanation.length > 0) {
        Text('解析：' + this.questions[at].explanation).fontSize(12)
          .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  historyCard() {
    Column() {
      Text('最近模考').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      ForEach(this.history, (record: MockExamRecord) => {
        Row() {
          Text(QuestionStore.formatTime(record.createdAt)).fontSize(10)
            .fontColor(Theme.secondaryText).width(96)
          Text(record.subjectLabel).fontSize(11).fontColor(Theme.primaryText)
          Blank()
          Text(record.correct.toString() + ' / ' + record.total.toString()).fontSize(11)
            .fontColor(Theme.primaryText)
          Text(' · ' + record.accuracy.toString() + '%').fontSize(10)
            .fontColor(Theme.secondaryText)
          Text(' · ' + ExamLogic.formatDuration(record.durationSec)).fontSize(10)
            .fontColor(Theme.secondaryText)
        }.width('100%').padding({ top: 9, bottom: 9 })
        .border({ width: { top: 1 }, color: Theme.divider })
        .onClick(() => { this.openHistory(record); })
      }, (record: MockExamRecord) => record.id)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  emptyView() {
    Column() {
      Text('还没有模考记录').fontSize(18).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('去「模考」页组一卷，交卷后这里会显示分数、用时、正确率与逐题解析。')
        .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
      Button('返回').width('100%').height(44).margin({ top: 18 })
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => { router.back(); })
    }
    .width('100%').alignItems(HorizontalAlign.Center).padding(24)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  build() {
    Column() {
      if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Theme.accent)
          Text('正在读取本地模考记录').fontSize(12).fontColor(Theme.secondaryText)
            .margin({ top: 8 })
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.header()
            if (!this.hasRecord) {
              this.emptyView()
            } else {
              this.summaryCard()
              this.weakCard()
              Text('逐题解析').fontSize(14).fontWeight(FontWeight.Medium)
                .fontColor(Theme.primaryText).width('100%').margin({ top: 16 })
              ForEach(this.questions, (question: MockExamQuestionSnapshot, at: number) => {
                this.analysisCard(at)
              }, (question: MockExamQuestionSnapshot, at: number) => question.id + '-' + at.toString()
                + '-' + this.selectedOf(at).toString())
              if (this.history.length > 0) {
                this.historyCard()
              }
              Text('成绩与逐题解析保存在应用本地（filesDir/studykit_mock_exams.json），不上传；'
                + '本轮答错的题已在交卷时写入错题本。').fontSize(11).fontColor(Theme.secondaryText)
                .margin({ top: 16, bottom: 28 })
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
