import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { QUESTION_SUBJECTS, QuestionStore } from '../model/QuestionStore';
import {
  DraftCheck, ImportDraft, ImportError, IMPORT_SOURCE, ParseReport, QuestionImportParser
} from '../model/QuestionImport';

@Entry
@Component
struct QuestionImportPage {
  @State loading: boolean = true;
  @State saving: boolean = false;
  @State rawText: string = '';
  @State subject: string = '数学';
  @State drafts: ImportDraft[] = [];
  @State errors: ImportError[] = [];
  @State totalLines: number = 0;
  @State blankLines: number = 0;
  @State commentLines: number = 0;
  @State duplicateCount: number = 0;
  @State parsed: boolean = false;
  @State savedMessage: string = '';
  @State showHelp: boolean = false;

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

  private lineSample(): string {
    return '# 每题一行：题干|A|B|C|D|答案|解析（解析可省略）\n'
      + 'HTTP 状态码 404 表示？|未找到|服务器错误|重定向|请求成功|A|404 表示资源未找到。\n'
      + 'TCP 三次握手的第二次报文是？|SYN|SYN+ACK|ACK|FIN|B|第二次是服务端回复 SYN+ACK。\n'
      + 'CSS 中设置文字颜色的属性是？|color|background|font-size|border|A|color 控制文字颜色。';
  }

  private blockSample(): string {
    return '# 分块粘贴：题干/选项/答案/解析，块之间空行分隔\n'
      + '1. 以下哪个是 HTTP 的幂等方法？\n'
      + 'A. GET\n'
      + 'B. POST\n'
      + 'C. PATCH\n'
      + 'D. CONNECT\n'
      + '答案：A\n'
      + '解析：GET 是幂等方法；POST/PATCH 通常不是幂等。\n'
      + '\n'
      + '2. 二分查找的时间复杂度是？\n'
      + 'A. O(1)\n'
      + 'B. O(n)\n'
      + 'C. O(log n)\n'
      + 'D. O(n log n)\n'
      + '答案：C\n'
      + '解析：二分查找每次淘汰一半数据，复杂度 O(log n)。';
  }

  private fillLineSample(): void {
    this.rawText = this.lineSample();
    this.parsed = false;
    this.drafts = [];
    this.errors = [];
    this.savedMessage = '';
  }

  private fillBlockSample(): void {
    this.rawText = this.blockSample();
    this.parsed = false;
    this.drafts = [];
    this.errors = [];
    this.savedMessage = '';
  }

  private clearAll(): void {
    this.rawText = '';
    this.drafts = [];
    this.errors = [];
    this.parsed = false;
    this.savedMessage = '';
    this.totalLines = 0;
    this.blankLines = 0;
    this.commentLines = 0;
    this.duplicateCount = 0;
  }

  private parseText(): void {
    if (this.rawText.trim().length === 0) {
      this.toast('先粘贴题目文本，或点“填入示例”看看格式');
      return;
    }
    const report: ParseReport = QuestionImportParser.parse(this.rawText, this.subject, this.store.getQuestions());
    this.drafts = report.drafts;
    this.errors = report.errors;
    this.totalLines = report.totalLines;
    this.blankLines = report.blankLines;
    this.commentLines = report.commentLines;
    this.duplicateCount = report.duplicateCount;
    this.parsed = true;
    this.savedMessage = '';
    this.toast('识别 ' + report.drafts.length.toString() + ' 题，错误 ' + report.errors.length.toString()
      + ' 行，重复 ' + report.duplicateCount.toString() + ' 题');
  }

  private changeSubject(subject: string): void {
    this.subject = subject;
    if (this.drafts.length === 0) {
      return;
    }
    const next: ImportDraft[] = [];
    for (let i = 0; i < this.drafts.length; i++) {
      const draft: ImportDraft = this.cloneDraft(this.drafts[i]);
      draft.subject = subject;
      draft.status = draft.status === QuestionImportParser.STATUS_DUPLICATE
        ? QuestionImportParser.STATUS_DUPLICATE : QuestionImportParser.STATUS_EDITED;
      next.push(draft);
    }
    this.drafts = next;
    this.savedMessage = '';
  }

  private cloneDraft(source: ImportDraft): ImportDraft {
    return {
      key: source.key,
      subject: source.subject,
      stem: source.stem,
      options: source.options.slice(0),
      answer: source.answer,
      explanation: source.explanation,
      source: source.source,
      status: source.status,
      message: source.message,
      lineLabel: source.lineLabel
    };
  }

  private markEdited(draft: ImportDraft): void {
    draft.status = QuestionImportParser.STATUS_EDITED;
    draft.message = '已手动编辑，保存时重新校验';
  }

  private updateStem(index: number, value: string): void {
    if (index < 0 || index >= this.drafts.length) {
      return;
    }
    const next: ImportDraft[] = [];
    for (let i = 0; i < this.drafts.length; i++) {
      const draft: ImportDraft = this.cloneDraft(this.drafts[i]);
      if (i === index) {
        draft.stem = value;
        this.markEdited(draft);
      }
      next.push(draft);
    }
    this.drafts = next;
  }

  private updateOption(index: number, optIndex: number, value: string): void {
    if (index < 0 || index >= this.drafts.length) {
      return;
    }
    const next: ImportDraft[] = [];
    for (let i = 0; i < this.drafts.length; i++) {
      const draft: ImportDraft = this.cloneDraft(this.drafts[i]);
      if (i === index) {
        const options: string[] = [];
        for (let j = 0; j < 4; j++) {
          if (j === optIndex) {
            options.push(value);
          } else {
            options.push(j < draft.options.length ? draft.options[j] : '');
          }
        }
        draft.options = options;
        this.markEdited(draft);
      }
      next.push(draft);
    }
    this.drafts = next;
  }

  private updateAnswer(index: number, answer: number): void {
    if (index < 0 || index >= this.drafts.length) {
      return;
    }
    const next: ImportDraft[] = [];
    for (let i = 0; i < this.drafts.length; i++) {
      const draft: ImportDraft = this.cloneDraft(this.drafts[i]);
      if (i === index) {
        draft.answer = answer;
        this.markEdited(draft);
      }
      next.push(draft);
    }
    this.drafts = next;
  }

  private updateExplanation(index: number, value: string): void {
    if (index < 0 || index >= this.drafts.length) {
      return;
    }
    const next: ImportDraft[] = [];
    for (let i = 0; i < this.drafts.length; i++) {
      const draft: ImportDraft = this.cloneDraft(this.drafts[i]);
      if (i === index) {
        draft.explanation = value;
        this.markEdited(draft);
      }
      next.push(draft);
    }
    this.drafts = next;
  }

  private removeDraft(index: number): void {
    const next: ImportDraft[] = [];
    for (let i = 0; i < this.drafts.length; i++) {
      if (i !== index) {
        next.push(this.drafts[i]);
      }
    }
    this.drafts = next;
  }

  private optionValue(draft: ImportDraft, optIndex: number): string {
    return optIndex < draft.options.length ? draft.options[optIndex] : '';
  }

  private importableCount(): number {
    return this.drafts.length;
  }

  private async saveAll(): Promise<void> {
    if (this.saving) {
      return;
    }
    if (this.drafts.length === 0) {
      this.toast('没有可导入的题目，请先解析预览');
      return;
    }
    this.saving = true;
    const keys: string[] = QuestionImportParser.existingKeys(this.store.getQuestions());
    let added: number = 0;
    let duplicate: number = 0;
    let invalid: number = 0;
    for (let i = 0; i < this.drafts.length; i++) {
      const draft: ImportDraft = this.drafts[i];
      const check: DraftCheck = QuestionImportParser.checkDraft(draft, keys);
      if (!check.ok) {
        invalid++;
        continue;
      }
      if (check.duplicate) {
        duplicate++;
        continue;
      }
      await this.store.addQuestion(draft.subject, draft.stem.trim(), check.options, draft.answer,
        draft.explanation.trim(), IMPORT_SOURCE);
      keys.push(QuestionImportParser.questionKey(draft.subject, draft.stem));
      added++;
    }
    this.saving = false;
    this.savedMessage = '导入完成：成功 ' + added.toString() + ' 题，跳过重复 ' + duplicate.toString()
      + ' 题，跳过无效 ' + invalid.toString() + ' 题';
    if (added > 0) {
      this.drafts = [];
      this.errors = [];
      this.parsed = false;
      this.rawText = '';
      this.toast('成功导入 ' + added.toString() + ' 题');
    } else {
      this.toast('没有新题被导入，检查重复/无效项');
    }
  }

  private statusLabel(status: string): string {
    if (status === QuestionImportParser.STATUS_DUPLICATE) {
      return '重复';
    }
    if (status === QuestionImportParser.STATUS_EDITED) {
      return '已编辑';
    }
    return '可导入';
  }

  private statusInk(status: string): string {
    if (status === QuestionImportParser.STATUS_DUPLICATE) {
      return Theme.warningInk;
    }
    if (status === QuestionImportParser.STATUS_EDITED) {
      return Theme.accentInk;
    }
    return Theme.successInk;
  }

  private statusBg(status: string): string {
    if (status === QuestionImportParser.STATUS_DUPLICATE) {
      return Theme.warningSoft;
    }
    if (status === QuestionImportParser.STATUS_EDITED) {
      return Theme.accentSoft;
    }
    return Theme.successSoft;
  }

  private letters(): string[] {
    return ['A', 'B', 'C', 'D'];
  }

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text('批量导入题目').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('一行一题或分块粘贴 · 预览去重 · 逐条编辑 · 批量入库').fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      Text('帮助').fontSize(12).fontColor(Theme.accentInk)
        .onClick(() => { this.showHelp = !this.showHelp; })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  subjectRow() {
    Row() {
      Text('学科').fontSize(12).fontColor(Theme.secondaryText)
      ForEach(QUESTION_SUBJECTS, (subject: string) => {
        Text(subject).fontSize(12)
          .fontColor(this.subject === subject ? Color.White : Theme.secondaryText)
          .backgroundColor(this.subject === subject ? Theme.accentInk : Theme.card)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 }).borderRadius(12).margin({ left: 8 })
          .onClick(() => { this.changeSubject(subject); })
      }, (subject: string) => subject)
    }.width('100%').alignItems(VerticalAlign.Center)
  }

  @Builder
  helpCard() {
    Column() {
      Text('支持的两种格式').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      Text('1) 每题一行：题干|A|B|C|D|答案字母|解析').fontSize(12).fontColor(Theme.secondaryText)
        .width('100%').margin({ top: 8 })
      Text('   例：HTTP 404 表示？|未找到|服务器错误|重定向|请求成功|A|404 表示资源未找到。')
        .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })
      Text('2) 分块粘贴：题干 + A./B./C./D. 选项 + 答案：X + 解析：...，块之间空行分隔')
        .fontSize(12).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
      Text('容错：# 或 // 开头的注释行、空行、序号前缀（1. / 2、）、全角竖线、答案数字 1-4 都能识别。')
        .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
      Text('去重按“学科 + 题干”判断：题库里已有的题、或本次粘贴内重复的题，预览里会标为“重复”，默认跳过；编辑题干后可正常导入。')
        .fontSize(11).fontColor(Theme.warningInk).width('100%').margin({ top: 8 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)
  }

  @Builder
  pasteCard() {
    Column() {
      Row() {
        Text('粘贴题目文本').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text('一行一题示例').fontSize(11).fontColor(Theme.accentInk)
          .onClick(() => { this.fillLineSample(); })
        Text('分块示例').fontSize(11).fontColor(Theme.accentInk).margin({ left: 10 })
          .onClick(() => { this.fillBlockSample(); })
        Text('清空').fontSize(11).fontColor(Theme.secondaryText).margin({ left: 10 })
          .onClick(() => { this.clearAll(); })
      }.width('100%').alignItems(VerticalAlign.Center)

      TextArea({ text: this.rawText, placeholder: '在这里粘贴题目…\n一行一题：题干|A|B|C|D|答案|解析\n或直接粘贴带选项和答案的多行题目（块之间空行）' })
        .width('100%').height(200).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.rawText = value; })

      Row() {
        Button('解析并预览').layoutWeight(1).height(46)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.parseText(); })
        Button('填入一行一题示例').height(46).width(150).margin({ left: 10 })
          .backgroundColor(Theme.goldSoft).fontColor(Theme.goldInk)
          .onClick(() => { this.fillLineSample(); })
      }.width('100%').margin({ top: 10 })
      Button('填入分块示例').width('100%').height(40).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
        .onClick(() => { this.fillBlockSample(); })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  summaryCard() {
    Column() {
      Row() {
        Text('解析结果').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text('共 ' + this.totalLines.toString() + ' 行 · 空行 ' + this.blankLines.toString()
          + ' · 注释 ' + this.commentLines.toString()).fontSize(10).fontColor(Theme.secondaryText)
      }.width('100%')
      Row() {
        Column() {
          Text(this.drafts.length.toString()).fontSize(20).fontWeight(FontWeight.Bold)
            .fontColor(Theme.primaryText)
          Text('识别题目').fontSize(10).fontColor(Theme.secondaryText)
        }.layoutWeight(1)
        Column() {
          Text(this.duplicateCount.toString()).fontSize(20).fontWeight(FontWeight.Bold)
            .fontColor(Theme.warningInk)
          Text('重复').fontSize(10).fontColor(Theme.secondaryText)
        }.layoutWeight(1)
        Column() {
          Text(this.errors.length.toString()).fontSize(20).fontWeight(FontWeight.Bold)
            .fontColor(Theme.dangerInk)
          Text('错误行').fontSize(10).fontColor(Theme.secondaryText)
        }.layoutWeight(1)
      }.width('100%').padding({ top: 10, bottom: 4 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(14).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  errorCard() {
    Column() {
      Text('这些行没有识别成功（可修改原文后重新解析）').fontSize(13).fontWeight(FontWeight.Medium)
        .fontColor(Theme.dangerInk).width('100%')
      ForEach(this.errors, (error: ImportError, index: number) => {
        Column() {
          Row() {
            Text(error.lineLabel).fontSize(11).fontColor(Theme.dangerInk)
            Text(error.message).fontSize(11).fontColor(Theme.dangerInk).margin({ left: 8 }).layoutWeight(1)
          }.width('100%')
          if (error.content.length > 0) {
            Text(error.content).fontSize(11).fontColor(Theme.secondaryText).width('100%')
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 2 })
          }
        }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 6, bottom: 6 })
      }, (error: ImportError, index: number) => index.toString() + '-' + error.lineLabel)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(14).margin({ top: 10 })
    .backgroundColor(Theme.dangerSoft).borderRadius(Theme.cardRadius)
  }

  @Builder
  draftCard(draft: ImportDraft, index: number) {
    Column() {
      Row() {
        Text('第 ' + (index + 1).toString() + ' 题').fontSize(13).fontWeight(FontWeight.Medium)
          .fontColor(Theme.primaryText)
        Text(draft.lineLabel).fontSize(10).fontColor(Theme.secondaryText).margin({ left: 8 })
        Blank()
        Text(this.statusLabel(draft.status)).fontSize(10).fontColor(this.statusInk(draft.status))
          .backgroundColor(this.statusBg(draft.status)).padding({ left: 7, right: 7, top: 3, bottom: 3 })
          .borderRadius(8)
        Text('删除').fontSize(11).fontColor(Theme.dangerInk).margin({ left: 10 })
          .onClick(() => { this.removeDraft(index); })
      }.width('100%').alignItems(VerticalAlign.Center)

      if (draft.status === QuestionImportParser.STATUS_DUPLICATE) {
        Text(draft.message).fontSize(10).fontColor(Theme.warningInk).width('100%').margin({ top: 6 })
      }

      TextArea({ text: draft.stem, placeholder: '题干' })
        .width('100%').height(64).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.updateStem(index, value); })

      ForEach(this.letters(), (letter: string, optIndex: number) => {
        Row() {
          Text(letter).fontSize(12).fontColor(Theme.accentInk).width(22)
          TextInput({ text: this.optionValue(draft, optIndex), placeholder: letter + ' 选项' })
            .layoutWeight(1).height(38)
            .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
            .onChange((value: string) => { this.updateOption(index, optIndex, value); })
        }.width('100%').margin({ top: 6 })
      }, (letter: string, optIndex: number) => draft.key + '-' + letter)

      Row() {
        Text('答案').fontSize(12).fontColor(Theme.secondaryText)
        ForEach(this.letters(), (letter: string, answerIndex: number) => {
          Text(letter).fontSize(12)
            .fontColor(draft.answer === answerIndex ? Color.White : Theme.secondaryText)
            .backgroundColor(draft.answer === answerIndex ? Theme.successInk : Theme.cardWarm)
            .width(32).height(32).textAlign(TextAlign.Center).borderRadius(16).margin({ left: 8 })
            .onClick(() => { this.updateAnswer(index, answerIndex); })
        }, (letter: string, answerIndex: number) => draft.key + '-ans-' + letter)
      }.width('100%').margin({ top: 8 }).alignItems(VerticalAlign.Center)

      TextArea({ text: draft.explanation, placeholder: '解析（选填）' })
        .width('100%').height(54).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.updateExplanation(index, value); })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(14).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .border({ width: draft.status === QuestionImportParser.STATUS_DUPLICATE ? 1 : 0, color: Theme.warningInk })
  }

  @Builder
  saveCard() {
    Column() {
      if (this.savedMessage.length > 0) {
        Text(this.savedMessage).fontSize(12).fontColor(Theme.successInk).width('100%').margin({ bottom: 8 })
      }
      Button(this.saving ? '正在导入…' : '导入 ' + this.importableCount().toString() + ' 题到题库')
        .width('100%').height(50)
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .enabled(!this.saving)
        .onClick(() => { this.saveAll(); })
      Text('重复题和无效题会自动跳过；导入后的题目可在“题库练习”里刷到。').fontSize(11)
        .fontColor(Theme.secondaryText).margin({ top: 8 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10, bottom: 28 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
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
            if (this.showHelp) {
              this.helpCard()
            }
            this.subjectRow()
            this.pasteCard()
            if (this.parsed) {
              this.summaryCard()
              if (this.errors.length > 0) {
                this.errorCard()
              }
              if (this.drafts.length > 0) {
                ForEach(this.drafts, (draft: ImportDraft, index: number) => {
                  this.draftCard(draft, index)
                }, (draft: ImportDraft, index: number) => draft.key)
                this.saveCard()
              }
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
