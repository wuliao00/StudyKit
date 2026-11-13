import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { QuestionItem, QuestionStore, QUESTION_ALL } from '../model/QuestionStore';

@Entry
@Component
struct QuestionPracticePage {
  @State loading: boolean = true;
  @State subjects: string[] = [];
  @State selectedSubject: string = QUESTION_ALL;
  @State questions: QuestionItem[] = [];
  @State index: number = 0;
  @State selected: number = -1;
  @State correctCount: number = 0;
  @State answeredCount: number = 0;
  @State mode: number = 0;
  @State wrongIds: string[] = [];
  @State sessionTotal: number = 0;
  @State sessionCorrect: number = 0;
  @State lastMistakeTip: string = '';

  private store: QuestionStore = QuestionStore.get();

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.store.init(context).then(() => {
      this.subjects = this.store.getSubjects();
      this.startSession(this.selectedSubject);
      this.loading = false;
    }).catch(() => {
      this.loading = false;
      this.lastMistakeTip = '本地题库读取失败，可返回后重试';
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private startSession(subject: string): void {
    this.selectedSubject = subject;
    this.questions = this.store.questionsBySubject(subject);
    this.index = 0;
    this.selected = -1;
    this.correctCount = 0;
    this.answeredCount = 0;
    this.wrongIds = [];
    this.mode = 0;
    this.lastMistakeTip = '';
  }

  private currentQuestion(): QuestionItem {
    if (this.questions.length === 0) {
      return { id: '', subject: '', stem: '', options: [], answer: 0, explanation: '', source: '', createdAt: 0 };
    }
    return this.questions[this.index];
  }

  private choose(index: number): void {
    if (this.selected >= 0) {
      return;
    }
    const question: QuestionItem = this.currentQuestion();
    this.selected = index;
    this.answeredCount = this.answeredCount + 1;
    if (index === question.answer) {
      this.correctCount = this.correctCount + 1;
    } else {
      this.wrongIds.push(question.id);
      this.wrongIds = this.wrongIds.slice(0, this.wrongIds.length);
      this.lastMistakeTip = '已自动加入错题本';
      this.store.addMistakeFromQuestion(question, index).then(() => {
        this.lastMistakeTip = '已自动加入错题本（可到错题本查看）';
      });
    }
  }

  private nextQuestion(): void {
    if (this.index + 1 < this.questions.length) {
      this.index = this.index + 1;
      this.selected = -1;
      return;
    }
    this.mode = 1;
    this.sessionTotal = this.questions.length;
    this.sessionCorrect = this.correctCount;
  }

  private retryWrongOnly(): void {
    const list: QuestionItem[] = this.store.questionsByIds(this.wrongIds);
    if (list.length === 0) {
      this.toast('本组没有错题');
      return;
    }
    this.questions = list;
    this.index = 0;
    this.selected = -1;
    this.correctCount = 0;
    this.answeredCount = 0;
    this.wrongIds = [];
    this.mode = 0;
    this.lastMistakeTip = '';
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
    if (this.selected < 0) {
      return Theme.card;
    }
    if (index === this.currentQuestion().answer) {
      return Theme.successSoft;
    }
    if (index === this.selected) {
      return Theme.dangerSoft;
    }
    return Theme.card;
  }

  private optionBorder(index: number): string {
    if (this.selected < 0) {
      return Theme.divider;
    }
    if (index === this.currentQuestion().answer) {
      return Theme.successInk;
    }
    if (index === this.selected) {
      return Theme.dangerInk;
    }
    return Theme.divider;
  }

  private optionInk(index: number): string {
    if (this.selected < 0) {
      return Theme.primaryText;
    }
    if (index === this.currentQuestion().answer) {
      return Theme.successInk;
    }
    if (index === this.selected) {
      return Theme.dangerInk;
    }
    return Theme.secondaryText;
  }

  private accuracyText(): string {
    if (this.sessionTotal <= 0) {
      return '—';
    }
    return Math.round(this.sessionCorrect * 100 / this.sessionTotal).toString() + '%';
  }

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text('题库练习').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('按学科筛选 · 逐题作答 · 即时判分 · 错题自动入库').fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 12 })
  }

  @Builder
  subjectChips() {
    Scroll() {
      Row() {
        Text('全部').fontSize(12)
          .fontColor(this.selectedSubject === QUESTION_ALL ? Color.White : Theme.secondaryText)
          .backgroundColor(this.selectedSubject === QUESTION_ALL ? Theme.accentInk : Theme.card)
          .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
          .onClick(() => { this.startSession(QUESTION_ALL); })
        ForEach(this.subjects, (subject: string) => {
          Text(subject).fontSize(12)
            .fontColor(this.selectedSubject === subject ? Color.White : Theme.secondaryText)
            .backgroundColor(this.selectedSubject === subject ? Theme.accentInk : Theme.card)
            .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
            .onClick(() => { this.startSession(subject); })
        }, (subject: string) => subject)
      }
    }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
  }

  @Builder
  practiceView() {
    Column() {
      Row() {
        Text(this.selectedSubject + ' · 共 ' + this.questions.length.toString() + ' 题')
          .fontSize(12).fontColor(Theme.secondaryText)
        Blank()
        Text('第 ' + (this.index + 1).toString() + ' / ' + this.questions.length.toString() + ' 题')
          .fontSize(12).fontColor(Theme.accentInk)
      }.width('100%').padding(12).backgroundColor(Theme.card).borderRadius(12)

      Column() {
        Row() {
          Text(this.currentQuestion().subject).fontSize(10).fontColor(Theme.goldInk)
            .backgroundColor(Theme.goldSoft).padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8)
          Blank()
          Text(this.currentQuestion().source).fontSize(10).fontColor(Theme.secondaryText)
        }.width('100%')
        Text(this.currentQuestion().stem).fontSize(20).fontWeight(FontWeight.Medium)
          .fontColor(Theme.primaryText).margin({ top: 16 }).width('100%')
      }
      .width('100%').alignItems(HorizontalAlign.Start).padding(18).margin({ top: 12 })
      .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

      ForEach(this.currentQuestion().options, (option: string, index: number) => {
        this.optionRow(option, index)
      }, (option: string, index: number) => this.currentQuestion().id + '-' + index.toString() + '-' + option)

      if (this.selected >= 0) {
        Column() {
          Text(this.selected === this.currentQuestion().answer ? '✓ 答对了'
            : '✗ 正确答案：' + (this.currentQuestion().options.length > this.currentQuestion().answer
              ? this.currentQuestion().options[this.currentQuestion().answer] : ''))
            .fontSize(15).fontWeight(FontWeight.Medium)
            .fontColor(this.selected === this.currentQuestion().answer ? Theme.successInk : Theme.dangerInk)
          if (this.currentQuestion().explanation.length > 0) {
            Text('解析：' + this.currentQuestion().explanation).fontSize(13)
              .fontColor(Theme.secondaryText).margin({ top: 8 })
          }
          if (this.selected !== this.currentQuestion().answer) {
            Text(this.lastMistakeTip).fontSize(11).fontColor(Theme.warningInk).margin({ top: 8 })
          }
          Button(this.index + 1 < this.questions.length ? '下一题' : '查看本组结果')
            .width('100%').height(48).margin({ top: 14 })
            .backgroundColor(Theme.accentInk).fontColor(Color.White)
            .onClick(() => { this.nextQuestion(); })
        }
        .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
        .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
      }

      Text('答错的题会自动进入错题本；本题组 ' + this.questions.length.toString() + ' 题。').fontSize(11)
        .fontColor(Theme.secondaryText).margin({ top: 14, bottom: 28 })
    }.width('100%')
  }

  @Builder
  optionRow(option: string, index: number) {
    Row() {
      Text(this.optionLetter(index)).fontSize(13).fontWeight(FontWeight.Bold)
        .fontColor(this.optionInk(index)).width(24).height(24).textAlign(TextAlign.Center)
        .backgroundColor(this.selected >= 0 ? Theme.card : Theme.bg).borderRadius(12)
      Text(option).fontSize(15).fontColor(this.optionInk(index)).layoutWeight(1).margin({ left: 12 })
    }
    .width('100%').padding({ left: 14, right: 14, top: 15, bottom: 15 })
    .backgroundColor(this.optionBg(index)).borderRadius(14)
    .border({ width: 1, color: this.optionBorder(index) })
    .margin({ top: 10 })
    .onClick(() => { this.choose(index); })
  }

  @Builder
  resultView() {
    Column() {
      Column() {
        Text('本组完成').fontSize(22).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text(this.sessionCorrect.toString() + ' / ' + this.sessionTotal.toString())
          .fontSize(52).fontWeight(FontWeight.Bold)
          .fontColor(this.sessionCorrect * 2 >= this.sessionTotal ? Theme.successInk : Theme.warningInk)
          .margin({ top: 8 })
        Text('正确率 ' + this.accuracyText() + ' · 错题已入库').fontSize(12)
          .fontColor(Theme.secondaryText).margin({ top: 4 })
        if (this.wrongIds.length > 0) {
          Text('本组有 ' + this.wrongIds.length.toString() + ' 道错题，可以只重做错题。').fontSize(12)
            .fontColor(Theme.warningInk).margin({ top: 10 })
        }
      }
      .width('100%').alignItems(HorizontalAlign.Center).padding(22)
      .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

      if (this.wrongIds.length > 0) {
        Button('只重做错题').width('100%').height(50).margin({ top: 14 })
          .backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
          .onClick(() => { this.retryWrongOnly(); })
      }
      Button('再练一组').width('100%').height(50).margin({ top: 10 })
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => { this.startSession(this.selectedSubject); })
      Button('返回').width('100%').height(46).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
        .onClick(() => { router.back(); })
    }.width('100%').margin({ top: 16 })
  }

  build() {
    Column() {
      if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Theme.accent)
          Text('正在读取本地题库').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.header()
            this.subjectChips()
            if (this.questions.length === 0) {
              Column() {
                Text('当前学科还没有题目').fontSize(18).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
                Text('可以先到“拍照/粘贴导入”页添加题目，再回来练习。').fontSize(12)
                  .fontColor(Theme.secondaryText).margin({ top: 8 })
                Button('返回').height(42).margin({ top: 16 })
                  .backgroundColor(Theme.accentInk).fontColor(Color.White)
                  .onClick(() => { router.back(); })
              }.width('100%').padding(30).margin({ top: 20 })
              .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
            } else if (this.mode === 1) {
              this.resultView()
            } else {
              this.practiceView()
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
