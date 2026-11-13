import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { QuestionStore, QUESTION_SUBJECTS } from '../model/QuestionStore';
import { OcrImport, QuestionDraft } from '../model/OcrImport';

@Entry
@Component
struct OcrPage {
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State imageUri: string = '';
  @State statusMessage: string = '选图预览；若设备提供 HMS Core OCR 会自动识别，不可用时把题目文字粘贴/输入到下方文本框导入。';
  @State fallbackMode: boolean = false;
  @State lines: string[] = [];
  @State checked: boolean[] = [];
  @State rawText: string = '';
  @State mode: number = 0;
  @State subject: string = '数学';
  @State stem: string = '';
  @State optA: string = '';
  @State optB: string = '';
  @State optC: string = '';
  @State optD: string = '';
  @State answerIndex: number = 0;
  @State explanation: string = '';
  @State mistakeContent: string = '';
  @State mistakeAnswer: string = '';
  @State mistakeNote: string = '';
  @State mistakeSubject: string = '数学';

  private store: QuestionStore = QuestionStore.get();

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.store.init(context).then(() => {
      this.loading = false;
    }).catch(() => {
      this.loading = false;
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private async chooseImage(): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.fallbackMode = false;
    this.statusMessage = '已打开图片选择器…';
    let uri: string = '';
    try {
      uri = await OcrImport.pickSingleImage();
    } catch (e) {
      this.busy = false;
      this.fallbackMode = true;
      this.statusMessage = '图片选择失败（可能取消了选择），仍可手动粘贴文本导入。';
      return;
    }
    if (uri.length === 0) {
      this.busy = false;
      this.statusMessage = '没有选择图片，可重试或直接手动粘贴文本。';
      return;
    }
    this.imageUri = uri;
    this.statusMessage = '正在读取图片…';
    const result = await OcrImport.recognizeFromImage(uri);
    this.busy = false;
    if (result.ok) {
      this.fallbackMode = false;
      this.lines = result.lines;
      const flags: boolean[] = [];
      for (let i = 0; i < result.lines.length; i++) {
        flags.push(true);
      }
      this.checked = flags;
      this.rawText = result.lines.join('\n');
      this.statusMessage = result.message + '（可勾选行或直接编辑下方文本）';
    } else {
      this.fallbackMode = true;
      this.statusMessage = result.message;
    }
  }

  private rebuildRawText(): void {
    const selected: string[] = [];
    for (let i = 0; i < this.lines.length; i++) {
      if (i < this.checked.length && this.checked[i]) {
        selected.push(this.lines[i]);
      }
    }
    this.rawText = selected.join('\n');
  }

  private toggleLine(index: number): void {
    const next: boolean[] = [];
    for (let i = 0; i < this.checked.length; i++) {
      next.push(i === index ? !this.checked[i] : this.checked[i]);
    }
    this.checked = next;
    this.rebuildRawText();
  }

  private setAllChecked(value: boolean): void {
    const next: boolean[] = [];
    for (let i = 0; i < this.lines.length; i++) {
      next.push(value);
    }
    this.checked = next;
    this.rebuildRawText();
  }

  private toQuestionForm(): void {
    if (this.rawText.trim().length === 0) {
      this.toast('先粘贴或识别一段题目文本');
      return;
    }
    const draft: QuestionDraft = OcrImport.draftFromText(this.rawText);
    this.stem = draft.stem;
    this.optA = draft.options.length > 0 ? draft.options[0] : '';
    this.optB = draft.options.length > 1 ? draft.options[1] : '';
    this.optC = draft.options.length > 2 ? draft.options[2] : '';
    this.optD = draft.options.length > 3 ? draft.options[3] : '';
    this.answerIndex = draft.answerIndex;
    this.explanation = draft.explanation;
    this.mode = 1;
    this.toast('已整理成题目草稿，确认后保存');
  }

  private toMistakeForm(): void {
    if (this.rawText.trim().length === 0) {
      this.toast('先粘贴或识别一段错题文本');
      return;
    }
    this.mistakeContent = this.rawText.trim();
    this.mode = 2;
  }

  private filledOptions(): string[] {
    const result: string[] = [];
    if (this.optA.trim().length > 0) {
      result.push(this.optA.trim());
    }
    if (this.optB.trim().length > 0) {
      result.push(this.optB.trim());
    }
    if (this.optC.trim().length > 0) {
      result.push(this.optC.trim());
    }
    if (this.optD.trim().length > 0) {
      result.push(this.optD.trim());
    }
    return result;
  }

  private saveQuestion(): void {
    if (this.stem.trim().length === 0) {
      this.toast('题干不能为空');
      return;
    }
    const options: string[] = this.filledOptions();
    if (options.length < 2) {
      this.toast('至少填写两个选项');
      return;
    }
    const letters: string[] = ['A', 'B', 'C', 'D'];
    const answerLetter: string = letters[this.answerIndex >= 0 && this.answerIndex < 4 ? this.answerIndex : 0];
    const fields: string[] = [this.optA, this.optB, this.optC, this.optD];
    const answerText: string = fields[this.answerIndex >= 0 && this.answerIndex < 4 ? this.answerIndex : 0].trim();
    let newAnswer: number = 0;
    for (let i = 0; i < options.length; i++) {
      if (options[i] === answerText) {
        newAnswer = i;
      }
    }
    const source: string = this.imageUri.length > 0 ? '拍照/OCR 导入' : '手动导入';
    this.store.addQuestion(this.subject, this.stem.trim(), options, newAnswer,
      this.explanation.trim(), source).then(() => {
        this.toast('已保存到题库（答案 ' + answerLetter + '）');
        this.mode = 0;
        this.stem = '';
        this.optA = '';
        this.optB = '';
        this.optC = '';
        this.optD = '';
        this.explanation = '';
        this.answerIndex = 0;
      });
  }

  private saveMistake(): void {
    if (this.mistakeContent.trim().length === 0) {
      this.toast('错题内容不能为空');
      return;
    }
    this.store.addManualMistake(this.mistakeSubject, this.mistakeContent.trim(),
      this.mistakeAnswer.trim(), this.mistakeNote.trim()).then(() => {
        this.toast('已加入错题本');
        this.mode = 0;
        this.mistakeContent = '';
        this.mistakeAnswer = '';
        this.mistakeNote = '';
      });
  }

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text('拍照 / 粘贴导入').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('选图预览 + 粘贴文本 · 勾选行 · 导入题库/错题本').fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  ocrCard() {
    Column() {
      Row() {
        Text('图片选图 / 预览').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Text('HMS OCR 运行时尝试').fontSize(10).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
          .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(8).margin({ left: 6 })
        Blank()
        if (this.busy) {
          LoadingProgress().width(20).height(20).color(Theme.accent)
        }
      }.width('100%').alignItems(VerticalAlign.Center)
      Text(this.statusMessage).fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 }).width('100%')

      if (this.imageUri.length > 0) {
        Image(this.imageUri).width('100%').height(180).objectFit(ImageFit.Contain)
          .borderRadius(12).backgroundColor(Theme.cardWarm).margin({ top: 10 })
      }

      Row() {
        Button(this.busy ? '处理中…' : '选择图片').layoutWeight(1).height(44)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .enabled(!this.busy)
          .onClick(() => { this.chooseImage(); })
        Button('清空图片').height(44).width(88).margin({ left: 10 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => {
            this.imageUri = '';
            this.statusMessage = '已清空图片，仍可手动粘贴文本。';
          })
      }.width('100%').margin({ top: 10 })

      if (this.fallbackMode) {
        Text('HMS OCR 未集成时：在下方文本框里粘贴或手打题目文字，照样可以整理成题目或错题。')
          .fontSize(11).fontColor(Theme.warningInk).width('100%').margin({ top: 8 })
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  textCard() {
    Column() {
      Row() {
        Text('识别文本 / 手动粘贴').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        if (this.lines.length > 0) {
          Text('全选').fontSize(11).fontColor(Theme.accentInk).margin({ right: 10 })
            .onClick(() => { this.setAllChecked(true); })
          Text('全不选').fontSize(11).fontColor(Theme.secondaryText)
            .onClick(() => { this.setAllChecked(false); })
        }
      }.width('100%').alignItems(VerticalAlign.Center)

      if (this.lines.length > 0) {
        Column() {
          ForEach(this.lines, (line: string, index: number) => {
            Row() {
              Text(index < this.checked.length && this.checked[index] ? '☑' : '☐')
                .fontSize(16).fontColor(Theme.accentInk).width(24)
              Text(line).fontSize(12).fontColor(Theme.primaryText).layoutWeight(1).maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            }.width('100%').padding({ top: 5, bottom: 5 })
            .onClick(() => { this.toggleLine(index); })
          }, (line: string, index: number) => index.toString() + '-' + line)
        }.width('100%').margin({ top: 8 })
      }

      TextArea({ text: this.rawText, placeholder: '在这里粘贴或输入题目文本…' })
        .width('100%').height(this.lines.length > 0 ? 110 : 160).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.rawText = value; })

      Row() {
        Button('整理为题目').layoutWeight(1).height(44)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.toQuestionForm(); })
        Button('导入错题本').layoutWeight(1).height(44).margin({ left: 10 })
          .backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
          .onClick(() => { this.toMistakeForm(); })
      }.width('100%').margin({ top: 10 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  subjectRow(current: string, target: number) {
    Row() {
      ForEach(QUESTION_SUBJECTS, (subject: string) => {
        Text(subject).fontSize(11)
          .fontColor(current === subject ? Color.White : Theme.secondaryText)
          .backgroundColor(current === subject ? Theme.accentInk : Theme.cardWarm)
          .padding({ left: 10, right: 10, top: 5, bottom: 5 }).borderRadius(10).margin({ right: 8, top: 8 })
          .onClick(() => {
            if (target === 1) {
              this.subject = subject;
            } else {
              this.mistakeSubject = subject;
            }
          })
      }, (subject: string) => target.toString() + '-' + subject)
    }.width('100%')
  }

  @Builder
  questionForm() {
    Column() {
      Text('确认题目信息').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      this.subjectRow(this.subject, 1)
      TextArea({ text: this.stem, placeholder: '题干' })
        .width('100%').height(70).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.stem = value; })
      TextInput({ text: this.optA, placeholder: 'A 选项' }).width('100%').height(40).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.optA = value; })
      TextInput({ text: this.optB, placeholder: 'B 选项' }).width('100%').height(40).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.optB = value; })
      TextInput({ text: this.optC, placeholder: 'C 选项（选填）' }).width('100%').height(40).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.optC = value; })
      TextInput({ text: this.optD, placeholder: 'D 选项（选填）' }).width('100%').height(40).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.optD = value; })
      Row() {
        Text('正确答案').fontSize(12).fontColor(Theme.secondaryText)
        ForEach(['A', 'B', 'C', 'D'], (letter: string, index: number) => {
          Text(letter).fontSize(12)
            .fontColor(this.answerIndex === index ? Color.White : Theme.secondaryText)
            .backgroundColor(this.answerIndex === index ? Theme.successInk : Theme.cardWarm)
            .width(32).height(32).textAlign(TextAlign.Center).borderRadius(16).margin({ left: 8 })
            .onClick(() => { this.answerIndex = index; })
        }, (letter: string, index: number) => letter)
      }.width('100%').margin({ top: 10 }).alignItems(VerticalAlign.Center)
      TextArea({ text: this.explanation, placeholder: '解析（选填）' })
        .width('100%').height(60).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.explanation = value; })
      Row() {
        Button('取消').layoutWeight(1).height(42)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.mode = 0; })
        Button('保存到题库').layoutWeight(1).height(42).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveQuestion(); })
      }.width('100%').margin({ top: 10 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  mistakeForm() {
    Column() {
      Text('确认错题信息').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      this.subjectRow(this.mistakeSubject, 2)
      TextArea({ text: this.mistakeContent, placeholder: '错题内容' })
        .width('100%').height(90).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.mistakeContent = value; })
      TextArea({ text: this.mistakeAnswer, placeholder: '正确答案（选填）' })
        .width('100%').height(60).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.mistakeAnswer = value; })
      TextArea({ text: this.mistakeNote, placeholder: '笔记 / 错因（选填）' })
        .width('100%').height(60).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.mistakeNote = value; })
      Row() {
        Button('取消').layoutWeight(1).height(42)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.mode = 0; })
        Button('保存到错题本').layoutWeight(1).height(42).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveMistake(); })
      }.width('100%').margin({ top: 10 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  build() {
    Column() {
      if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Theme.accent)
          Text('正在准备本地题库').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.header()
            this.ocrCard()
            this.textCard()
            if (this.mode === 1) {
              this.questionForm()
            }
            if (this.mode === 2) {
              this.mistakeForm()
            }
            Text('图片只在本机读取用于识别，不上传；识别/粘贴的文本确认后才会写入本地题库。')
              .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 16, bottom: 28 })
          }.width('100%').constraintSize({ maxWidth: 760 })
        }.width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%')
    .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    .backgroundColor(Theme.bg)
  }
}
