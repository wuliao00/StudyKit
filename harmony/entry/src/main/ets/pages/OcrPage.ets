import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { QuestionStore, QUESTION_SUBJECTS } from '../model/QuestionStore';
import { OcrImport, QuestionDraft, OcrOptions, OcrResult } from '../model/OcrImport';
import { OcrCorrectionStore } from '../model/OcrCorrectionStore';
import { PpOcrEngine } from '../model/PpOcrEngine';

@Entry
@Component
struct OcrPage {
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State imageUri: string = '';
  @State statusMessage: string = '选图预览；默认使用端侧 PP-OCR（离线）识别，引擎不可用时回退 HMS/手动粘贴。';
  @State engineTag: string = 'PP-OCR · 加载中';
  @State engineReady: boolean = false;
  @State engineNote: string = '';
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
  @State cropTop: number = 0.0;
  @State cropBottom: number = 0.0;
  @State cropLeft: number = 0.0;
  @State cropRight: number = 0.0;
  @State scaleMode: boolean = true;
  @State linePass: boolean = true;
  @State enhance: boolean = true;
  @State filteredCount: number = 0;
  @State showAllLines: boolean = false;
  @State correctionCount: number = 0;
  @State ocrBaseLines: string[] = [];
  @State ocrRawLines: string[] = [];
  @State suspiciousCount: number = 0;
  @State cropPreset: number = 0;

  private store: QuestionStore = QuestionStore.get();
  private corrections: OcrCorrectionStore = OcrCorrectionStore.get();
  // 生命周期/请求令牌：页面销毁或重选图片后令牌自增，旧识别结果一律丢弃，不再写 @State。
  private destroyed: boolean = false;
  private runToken: number = 0;

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.initPpOcr(context);
    this.store.init(context).then(() => {
      this.corrections.init(context).then(() => {
        if (!this.destroyed) this.correctionCount = this.corrections.count();
      }).catch(() => {});
      if (!this.destroyed) this.loading = false;
    }).catch(() => {
      if (!this.destroyed) this.loading = false;
    });
  }

  // 异步初始化端侧 PP-OCR：不阻塞首屏；失败时记录原因供回退提示。
  private initPpOcr(context: common.UIAbilityContext): void {
    const engine: PpOcrEngine = PpOcrEngine.get();
    engine.init(context).then((ok: boolean) => {
      if (this.destroyed) return;
      this.engineReady = ok;
      this.engineTag = ok ? 'PP-OCR · 本机推理' : 'PP-OCR 不可用 · 回退 HMS';
      this.engineNote = ok ? '' : engine.initError();
      if (!ok && this.engineNote.length > 0) {
        this.statusMessage = 'PP-OCR 初始化失败：' + this.engineNote + '；将回退 HMS/手动粘贴，不影响导入。';
      }
    }).catch((e: Error) => {
      if (this.destroyed) return;
      this.engineReady = false;
      this.engineTag = 'PP-OCR 不可用 · 回退 HMS';
      this.engineNote = JSON.stringify(e);
    });
  }

  // 页面销毁（返回/路由切换）时取消进行中的识别；后续 await 返回后一律不再 setState。
  aboutToDisappear(): void {
    this.destroyed = true;
    this.runToken++;
  }

  private isCancelled(token: number): boolean {
    return this.destroyed || token !== this.runToken;
  }

  private percent(value: number): string {
    return Math.round(value * 100).toString() + '%';
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private async chooseImage(): Promise<void> {
    // 重选图片 = 取消进行中的识别：旧令牌立即失效，旧结果返回后不会写回 UI。
    const token: number = ++this.runToken;
    this.busy = true;
    this.fallbackMode = false;
    this.statusMessage = '已打开图片选择器…';
    let uri: string = '';
    try {
      uri = await OcrImport.pickSingleImage();
    } catch (e) {
      if (this.isCancelled(token)) return;
      this.busy = false;
      this.fallbackMode = true;
      this.statusMessage = '图片选择失败（可能取消了选择），仍可手动粘贴文本导入。';
      return;
    }
    if (this.isCancelled(token)) return;
    this.busy = false;
    if (uri.length === 0) {
      this.statusMessage = '没有选择图片，可重试或直接手动粘贴文本。';
      return;
    }
    this.imageUri = uri;
    this.lines = [];
    this.ocrBaseLines = [];
    this.checked = [];
    this.ocrRawLines = [];
    this.filteredCount = 0;
    this.suspiciousCount = 0;
    this.cropPreset = 0;
    this.rawText = '';
    this.statusMessage = '图片已选择：先裁掉工具栏/页边，再点「裁剪并识别」；默认会放大 2 倍。';
  }

  private presetCrop(mode: number): void {
    this.cropPreset = mode;
    if (mode === 0) {
      this.cropTop = 0;
      this.cropBottom = 0;
      this.cropLeft = 0;
      this.cropRight = 0;
    } else if (mode === 1) {
      this.cropTop = 0.08;
      this.cropBottom = 0.08;
      this.cropLeft = 0.02;
      this.cropRight = 0.02;
    } else {
      this.cropTop = 0.12;
      this.cropBottom = 0.10;
      this.cropLeft = 0.06;
      this.cropRight = 0.06;
    }
    // 预设点击后立即刷新百分比文本（直接绑定 @State）
    this.statusMessage = '选区已更新：上 ' + this.percent(this.cropTop) + ' · 下 '
      + this.percent(this.cropBottom) + ' · 左 ' + this.percent(this.cropLeft)
      + ' · 右 ' + this.percent(this.cropRight) + '，点「裁剪并识别」查看效果。';
  }

  private async recognizeImage(): Promise<void> {
    if (this.imageUri.length === 0) {
      this.toast('先选择一张题目图片');
      return;
    }
    if (this.busy) {
      return;
    }
    const token: number = ++this.runToken;
    this.busy = true;
    this.fallbackMode = false;
    this.statusMessage = '正在裁剪、放大并识别…';
    const options: OcrOptions = {
      cropTop: this.cropTop,
      cropBottom: this.cropBottom,
      cropLeft: this.cropLeft,
      cropRight: this.cropRight,
      scale: this.scaleMode ? 2 : 1,
      linePass: this.linePass,
      enhance: this.enhance
    };
    let result: OcrResult = { ok: false, message: '', lines: [], rawLines: [], filtered: 0, suspicious: 0, engine: '' };
    try {
      result = await OcrImport.recognizeFromImage(this.imageUri, options, () => this.isCancelled(token));
    } catch (e) {
      if (this.isCancelled(token)) return;
      this.busy = false;
      this.fallbackMode = true;
      this.statusMessage = '识别失败，可重试或手动粘贴文本。';
      return;
    }
    // 页面已销毁或用户已重选图片：丢弃本次结果，绝不更新 @State
    if (this.isCancelled(token)) return;
    this.busy = false;
    if (result.ok) {
      this.ocrBaseLines = result.lines;
      this.lines = this.corrections.applyLines(result.lines);
      this.ocrRawLines = result.rawLines;
      this.filteredCount = result.filtered;
      this.suspiciousCount = result.suspicious;
      const flags: boolean[] = [];
      for (let i = 0; i < this.lines.length; i++) flags.push(true);
      this.checked = flags;
      this.rawText = this.corrections.applyText(result.lines.join('\n'));
      this.statusMessage = result.message + (this.corrections.count() > 0
        ? '；已套用 ' + this.corrections.count().toString() + ' 条本机纠错记忆' : '');
      this.mode = 0;
    } else {
      this.fallbackMode = true;
      this.suspiciousCount = 0;
      this.statusMessage = result.message;
    }
  }

  private displayLines(): string[] {
    return this.showAllLines ? this.ocrRawLines : this.lines;
  }

  private async learnCorrections(): Promise<void> {
    if (this.ocrBaseLines.length === 0 || this.rawText.trim().length === 0) {
      return;
    }
    const learned: number = await this.corrections.learn(this.ocrBaseLines, this.rawText);
    if (this.destroyed) return;
    if (learned > 0) {
      this.correctionCount = this.corrections.count();
      this.toast('已学习 ' + learned.toString() + ' 条 OCR 纠错，下次自动应用');
    }
  }

  private reRecognize(): void {
    this.recognizeImage();
  }

  private rebuildRawText(): void {
    const selected: string[] = [];
    const active: string[] = this.displayLines();
    for (let i = 0; i < active.length; i++) {
      if (i < this.checked.length && this.checked[i]) {
        selected.push(active[i]);
      }
    }
    this.rawText = this.corrections.applyText(selected.join('\n'));
  }

  private toggleLine(index: number): void {
    const next: boolean[] = [];
    const active: string[] = this.displayLines();
    for (let i = 0; i < active.length; i++) {
      next.push(i === index ? !(i < this.checked.length ? this.checked[i] : true) : (i < this.checked.length ? this.checked[i] : true));
    }
    this.checked = next;
    this.rebuildRawText();
  }

  private setAllChecked(value: boolean): void {
    const next: boolean[] = [];
    const active: string[] = this.displayLines();
    for (let i = 0; i < active.length; i++) next.push(value);
    this.checked = next;
    this.rebuildRawText();
  }

  private toQuestionForm(): void {
    this.learnCorrections();
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
    this.learnCorrections();
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
        if (this.destroyed) return;
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
        if (this.destroyed) return;
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
      Button('←').width(48).height(48).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .accessibilityText('返回')
        .onClick(() => { router.back(); })
      Column() {
        Text('拍照 / 粘贴导入').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('选图预览 + 粘贴文本 · 勾选行 · 导入题库/错题本').fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  private toggleShowAll(): void {
    this.showAllLines = !this.showAllLines;
    const active: string[] = this.displayLines();
    const flags: boolean[] = [];
    for (let i = 0; i < active.length; i++) flags.push(true);
    this.checked = flags;
    this.rebuildRawText();
  }

  @Builder
  ocrCard() {
    Column() {
      Row() {
        Text('拍照 / 相册识别').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Text(this.engineTag).fontSize(10).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
          .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(8).margin({ left: 6 })
        Blank()
        if (this.busy) {
          LoadingProgress().width(20).height(20).color(Theme.accent)
        }
      }.width('100%').alignItems(VerticalAlign.Center)
      Text(this.statusMessage).fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 }).width('100%')
      if (!this.engineReady && this.engineNote.length > 0) {
        Text('PP-OCR 不可用原因：' + this.engineNote + '（已自动回退 HMS/手动粘贴）')
          .fontSize(11).fontColor(Theme.warningInk).margin({ top: 4 }).width('100%')
          .accessibilityText('PP-OCR 不可用原因')
      }

      if (this.imageUri.length > 0) {
        Image(this.imageUri).width('100%').height(190).objectFit(ImageFit.Contain)
          .borderRadius(12).backgroundColor(Theme.cardWarm).margin({ top: 10 })

        Row() {
          Text('识别选区').fontSize(12).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Blank()
          Button('整张').height(32).fontSize(11)
            .backgroundColor(this.cropPreset === 0 ? Theme.accentInk : Theme.cardWarm)
            .fontColor(this.cropPreset === 0 ? Color.White : Theme.secondaryText)
            .onClick(() => { this.presetCrop(0); })
          Button('去工具栏').height(32).fontSize(11).margin({ left: 6 })
            .backgroundColor(this.cropPreset === 1 ? Theme.accentInk : Theme.accentSoft)
            .fontColor(this.cropPreset === 1 ? Color.White : Theme.accentInk)
            .onClick(() => { this.presetCrop(1); })
          Button('紧一点').height(32).fontSize(11).margin({ left: 6 })
            .backgroundColor(this.cropPreset === 2 ? Theme.accentInk : Theme.cardWarm)
            .fontColor(this.cropPreset === 2 ? Color.White : Theme.secondaryText)
            .onClick(() => { this.presetCrop(2); })
        }.width('100%').margin({ top: 10 }).alignItems(VerticalAlign.Center)

        // 选区百分比直接绑定 @State：点预设或拖动滑杆后立即刷新
        Text('当前选区：上 ' + this.percent(this.cropTop) + ' · 下 ' + this.percent(this.cropBottom)
          + ' · 左 ' + this.percent(this.cropLeft) + ' · 右 ' + this.percent(this.cropRight))
          .fontSize(11).fontColor(Theme.accentInk).width('100%').margin({ top: 6 })
          .accessibilityText('当前选区百分比')

        Row() {
          Text('上边距').fontSize(11).fontColor(Theme.secondaryText).width(54)
          Slider({ value: this.cropTop * 100, min: 0, max: 35, step: 1, style: SliderStyle.OutSet })
            .layoutWeight(1).selectedColor(Theme.accent).blockColor(Theme.accentInk)
            .onChange((v: number) => { this.cropTop = v / 100; this.cropPreset = -1; })
          Text(this.percent(this.cropTop)).fontSize(11).fontColor(Theme.primaryText).width(44)
            .textAlign(TextAlign.End)
        }.width('100%').height(30).alignItems(VerticalAlign.Center)

        Row() {
          Text('下边距').fontSize(11).fontColor(Theme.secondaryText).width(54)
          Slider({ value: this.cropBottom * 100, min: 0, max: 35, step: 1, style: SliderStyle.OutSet })
            .layoutWeight(1).selectedColor(Theme.accent).blockColor(Theme.accentInk)
            .onChange((v: number) => { this.cropBottom = v / 100; this.cropPreset = -1; })
          Text(this.percent(this.cropBottom)).fontSize(11).fontColor(Theme.primaryText).width(44)
            .textAlign(TextAlign.End)
        }.width('100%').height(30).alignItems(VerticalAlign.Center)

        Row() {
          Text('左边距').fontSize(11).fontColor(Theme.secondaryText).width(54)
          Slider({ value: this.cropLeft * 100, min: 0, max: 35, step: 1, style: SliderStyle.OutSet })
            .layoutWeight(1).selectedColor(Theme.accent).blockColor(Theme.accentInk)
            .onChange((v: number) => { this.cropLeft = v / 100; this.cropPreset = -1; })
          Text(this.percent(this.cropLeft)).fontSize(11).fontColor(Theme.primaryText).width(44)
            .textAlign(TextAlign.End)
        }.width('100%').height(30).alignItems(VerticalAlign.Center)

        Row() {
          Text('右边距').fontSize(11).fontColor(Theme.secondaryText).width(54)
          Slider({ value: this.cropRight * 100, min: 0, max: 35, step: 1, style: SliderStyle.OutSet })
            .layoutWeight(1).selectedColor(Theme.accent).blockColor(Theme.accentInk)
            .onChange((v: number) => { this.cropRight = v / 100; this.cropPreset = -1; })
          Text(this.percent(this.cropRight)).fontSize(11).fontColor(Theme.primaryText).width(44)
            .textAlign(TextAlign.End)
        }.width('100%').height(30).alignItems(VerticalAlign.Center)

        Row() {
          Text('识别前放大 2 倍').fontSize(12).fontColor(Theme.primaryText)
          Blank()
          Toggle({ type: ToggleType.Switch, isOn: this.scaleMode })
            .selectedColor(Theme.accent)
            .onChange((isOn: boolean) => { this.scaleMode = isOn; })
        }.width('100%').margin({ top: 4 }).alignItems(VerticalAlign.Center)

        Row() {
          Column() {
            Text('逐行二次识别').fontSize(12).fontColor(Theme.primaryText)
            Text('对每个文本行再放大识别一次，公式/小字更稳，耗时约多 2–4 秒').fontSize(10).fontColor(Theme.secondaryText)
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.linePass })
            .selectedColor(Theme.accent)
            .onChange((isOn: boolean) => { this.linePass = isOn; })
        }.width('100%').margin({ top: 4 }).alignItems(VerticalAlign.Center)

        Row() {
          Text('增强对比度（扫描件）').fontSize(12).fontColor(Theme.primaryText)
          Blank()
          Toggle({ type: ToggleType.Switch, isOn: this.enhance })
            .selectedColor(Theme.accent)
            .onChange((isOn: boolean) => { this.enhance = isOn; })
        }.width('100%').margin({ top: 4 }).alignItems(VerticalAlign.Center)

        Button(this.busy ? '识别中…' : '裁剪并识别').width('100%').height(48).margin({ top: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .enabled(!this.busy)
          .accessibilityText('裁剪并识别')
          .onClick(() => { this.recognizeImage(); })
      }

      Row() {
        Button(this.imageUri.length > 0 ? '重新选择图片' : '选择图片').layoutWeight(1).height(48)
          .backgroundColor(Theme.accentSoft).fontColor(Theme.accentInk)
          .accessibilityText(this.imageUri.length > 0 ? '重新选择图片' : '选择图片')
          .onClick(() => { this.chooseImage(); })
        Button('PP-OCR 自检').height(48).width(112).margin({ left: 8 })
          .backgroundColor(Theme.goldSoft).fontColor(Theme.goldInk)
          .accessibilityText('PP-OCR 自检')
          .onClick(() => { router.pushUrl({ url: 'pages/PpOcrSelfTestPage' }); })
        Button('清空').height(48).width(74).margin({ left: 8 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => {
            this.runToken++;
            this.busy = false;
            this.imageUri = '';
            this.lines = [];
            this.ocrRawLines = [];
            this.checked = [];
            this.rawText = '';
            this.filteredCount = 0;
            this.suspiciousCount = 0;
            this.statusMessage = '已清空图片，仍可手动粘贴文本。';
          })
      }.width('100%').margin({ top: 10 })

      if (this.filteredCount > 0 || this.correctionCount > 0) {
        Text('已过滤 ' + this.filteredCount.toString() + ' 行工具栏/噪声；本机纠错记忆 '
          + this.correctionCount.toString() + ' 条。')
          .fontSize(11).fontColor(Theme.successInk).width('100%').margin({ top: 8 })
      }
      if (this.fallbackMode) {
        Text('OCR 未返回结果时：下方可直接粘贴/手打题目文字，仍可整理成题目或错题。')
          .fontSize(11).fontColor(Theme.warningInk).width('100%').margin({ top: 8 })
      }
      if (this.suspiciousCount > 0) {
        Text('可疑行 ' + this.suspiciousCount.toString() + ' 行：识别可能不准，可逐行修改')
          .fontSize(11).fontColor(Theme.warningInk).width('100%').margin({ top: 8 })
          .accessibilityText('可疑行提示')
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
        if (this.lines.length > 0 || this.ocrRawLines.length > 0) {
          Text('全选').fontSize(11).fontColor(Theme.accentInk).margin({ right: 10 })
            .onClick(() => { this.setAllChecked(true); })
          Text('全不选').fontSize(11).fontColor(Theme.secondaryText)
            .onClick(() => { this.setAllChecked(false); })
        }
      }.width('100%').alignItems(VerticalAlign.Center)

      if (this.lines.length > 0 || this.ocrRawLines.length > 0) {
        Row() {
          Text('只保留疑似题目行').fontSize(11).fontColor(Theme.primaryText)
          Blank()
          Text(this.showAllLines ? '显示全部 ' + this.ocrRawLines.length.toString() + ' 行' : '已过滤 ' + this.filteredCount.toString() + ' 行')
            .fontSize(11).fontColor(Theme.secondaryText)
          Toggle({ type: ToggleType.Switch, isOn: this.showAllLines })
            .selectedColor(Theme.gold).margin({ left: 8 })
            .onChange((isOn: boolean) => { if (isOn !== this.showAllLines) this.toggleShowAll(); })
        }.width('100%').margin({ top: 6 }).alignItems(VerticalAlign.Center)

        Column() {
          ForEach(this.displayLines(), (line: string, index: number) => {
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
        .width('100%').height(this.lines.length > 0 || this.ocrRawLines.length > 0 ? 110 : 160).margin({ top: 10 })
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
      Text('整理/导入时会把你改过的文本记成本机纠错，下次同一行自动替换；只保存在设备文件里。')
        .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 8 })
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
