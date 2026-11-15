import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { PpOcrEngine, PpOcrEvalSummary, PpOcrEvalItem } from '../model/PpOcrEngine';

const TAG: string = 'PPOCR_SELFTEST';
const DOMAIN: number = 0x3201;

// 落盘 JSON 的类型（ArkTS 禁止无类型对象字面量）。
interface PpOcrEvalPayload {
  engine: string;
  engineLabel: string;
  ortVersion: string;
  dictSize: number;
  classes: number;
  initMs: number;
  totalMs: number;
  avgCer: number;
  imageCount: number;
  emptyCount: number;
  startedAt: string;
  offline: boolean;
  criteriaF01: boolean;
  criteriaF02: number;
  criteriaP01: boolean;
  engineSource: string;
  items: PpOcrEvalItem[];
}

@Entry
@Component
struct PpOcrSelfTestPage {
  @State busy: boolean = false;
  @State status: string = '进入自检页，准备初始化 PP-OCR 引擎…';
  @State engineLine: string = 'PP-OCR';
  @State items: PpOcrEvalItem[] = [];
  @State avgCerText: string = '—';
  @State progressText: string = '';
  @State totalMs: number = 0;
  @State initMs: number = 0;
  @State ortVersion: string = '';
  @State dictSize: number = 0;
  @State classes: number = 0;
  @State jsonPath: string = '';
  @State errorText: string = '';
  @State doneCount: number = 0;
  @State totalCount: number = 10;
  @State finished: boolean = false;
  private started: boolean = false;
  private destroyed: boolean = false;

  aboutToAppear(): void {
    this.start();
  }

  aboutToDisappear(): void {
    this.destroyed = true;
  }

  private percent(value: number): string {
    return (value * 100).toFixed(2) + '%';
  }

  private async start(): Promise<void> {
    if (this.busy || this.started) return;
    this.started = true;
    this.busy = true;
    this.finished = false;
    this.errorText = '';
    this.status = '正在加载 det/rec 模型并初始化 PP-OCR（离线）…';
    this.engineLine = 'PP-OCR · 加载中';
    const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    const engine: PpOcrEngine = PpOcrEngine.get();
    try {
      const summary: PpOcrEvalSummary = await engine.runSelfTest(ctx, (done: number, total: number) => {
        this.doneCount = done;
        this.totalCount = total;
        if (!this.destroyed) this.status = '正在识别固定评测图 ' + done.toString() + '/' + total.toString() + '…';
      });
      if (this.destroyed) return;
      this.items = summary.items;
      this.avgCerText = this.percent(summary.avgCer);
      this.totalMs = summary.totalMs;
      this.initMs = summary.initMs;
      this.ortVersion = summary.ortVersion;
      this.dictSize = summary.dictSize;
      this.classes = summary.classes;
      this.errorText = summary.error;
      this.engineLine = engine.engineLabel();
      this.status = summary.ok
        ? ('自检完成：' + summary.imageCount.toString() + ' 张固定评测图，空文本 ' + summary.emptyCount.toString() + ' 张')
        : ('自检部分失败：' + (summary.error.length > 0 ? summary.error : '见下方明细'));
      this.writeJson(ctx, summary, engine.engineLabel());
    } catch (e) {
      if (!this.destroyed) {
        this.errorText = JSON.stringify(e);
        this.status = '自检异常：' + JSON.stringify(e);
      }
    } finally {
      this.busy = false;
      this.finished = true;
    }
  }

  private retry(): void {
    this.started = false;
    this.items = [];
    this.avgCerText = '—';
    this.start();
  }

  private writeJson(ctx: common.UIAbilityContext, summary: PpOcrEvalSummary, engineLabel: string): void {
    const path: string = ctx.filesDir + '/ppocr_eval.json';
    this.jsonPath = path;
    const payload: PpOcrEvalPayload = {
      engine: 'PP-OCR',
      engineLabel: engineLabel,
      ortVersion: summary.ortVersion,
      dictSize: summary.dictSize,
      classes: summary.classes,
      initMs: summary.initMs,
      totalMs: summary.totalMs,
      avgCer: summary.avgCer,
      imageCount: summary.imageCount,
      emptyCount: summary.emptyCount,
      startedAt: summary.startedAt,
      offline: true,
      criteriaF01: summary.items.length > 0 && summary.emptyCount === 0,
      criteriaF02: summary.avgCer,
      criteriaP01: summary.totalMs <= 100000,
      engineSource: 'libppocr.so + libonnxruntime.so.1 (native ORT)',
      items: summary.items
    };
    let text: string = '';
    try {
      text = JSON.stringify(payload);
    } catch (e) {
      text = '{"error":"json stringify failed"}';
    }
    try {
      const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.READ_WRITE);
      fs.writeSync(file.fd, text);
      fs.closeSync(file);
      hilog.info(DOMAIN, TAG, 'PPOCR_EVAL_WRITE_OK path=%{public}s bytes=%{public}d', path, text.length);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'PPOCR_EVAL_WRITE_FAIL %{public}s', JSON.stringify(e));
    }
    // hilog 通道同步输出，便于 DSH 在沙箱文件不可读时独立取证。
    hilog.info(DOMAIN, TAG, 'PPOCR_EVAL_JSON_BEGIN');
    const chunk: number = 700;
    for (let i = 0; i < text.length; i += chunk) {
      hilog.info(DOMAIN, TAG, 'J:%{public}s', text.substring(i, Math.min(text.length, i + chunk)));
    }
    hilog.info(DOMAIN, TAG, 'PPOCR_EVAL_JSON_END');
    for (let i = 0; i < summary.items.length; i++) {
      const item: PpOcrEvalItem = summary.items[i];
      hilog.info(DOMAIN, TAG, 'ITEM id=%{public}s cer=%{public}s lines=%{public}s detMs=%{public}s recMs=%{public}s totalMs=%{public}s ok=%{public}s',
        item.id, item.cer.toFixed(4), item.lineCount.toString(), item.detMs.toString(),
        item.recMs.toString(), item.totalMs.toString(), item.ok ? '1' : '0');
    }
  }

  @Builder
  header() {
    Row() {
      Button('←').width(48).height(48).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .accessibilityText('返回')
        .onClick(() => { router.back(); })
      Column() {
        Text('PP-OCR 离线自检').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('固定 10 张评测图 · det+rec 端侧推理 · 不联网').fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  engineCard() {
    Column() {
      Row() {
        Text('引擎状态').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Text(this.engineLine).fontSize(10).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
          .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(8).margin({ left: 6 })
        Blank()
        if (this.busy) {
          LoadingProgress().width(22).height(22).color(Theme.accent)
        }
      }.width('100%').alignItems(VerticalAlign.Center)
      Text(this.status).fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 }).width('100%')
      if (this.busy) {
        Progress({ value: this.doneCount, total: this.totalCount, type: ProgressType.Linear })
          .width('100%').height(6).margin({ top: 8 }).color(Theme.accent)
        Text('已完成 ' + this.doneCount.toString() + ' / ' + this.totalCount.toString() + ' 张')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 4 }).width('100%')
      }
      if (this.finished) {
        Row() {
          Column() {
            Text('平均 CER').fontSize(11).fontColor(Theme.secondaryText)
            Text(this.avgCerText).fontSize(30).fontWeight(FontWeight.Bold)
              .fontColor(this.avgCerText !== '—' && parseFloat(this.avgCerText) <= 5 ? Theme.successInk : Theme.dangerInk)
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Column() {
            Text('初始化').fontSize(11).fontColor(Theme.secondaryText)
            Text(this.initMs.toString() + ' ms').fontSize(15).fontColor(Theme.primaryText)
            Text('10 张总耗时').fontSize(11).fontColor(Theme.secondaryText).margin({ top: 4 })
            Text(this.totalMs.toString() + ' ms').fontSize(15).fontColor(Theme.primaryText)
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        }.width('100%').margin({ top: 10 }).alignItems(VerticalAlign.Top)
        Text('ORT ' + this.ortVersion + ' · 字典 ' + this.dictSize.toString()
          + ' 字符 · 分类 ' + this.classes.toString()).fontSize(10)
          .fontColor(Theme.secondaryText).margin({ top: 8 }).width('100%')
        if (this.jsonPath.length > 0) {
          Text('JSON: ' + this.jsonPath).fontSize(10).fontColor(Theme.accentInk).margin({ top: 4 }).width('100%')
        }
        if (this.errorText.length > 0) {
          Text(this.errorText).fontSize(11).fontColor(Theme.dangerInk).margin({ top: 6 }).width('100%')
        }
      }
      Row() {
        Button(this.busy ? '识别中…' : '开始识别').layoutWeight(1).height(48)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .enabled(!this.busy)
          .accessibilityText('开始识别')
          .onClick(() => { this.start(); })
        Button('重试').width(96).height(48).margin({ left: 10 })
          .backgroundColor(Theme.accentSoft).fontColor(Theme.accentInk)
          .enabled(!this.busy)
          .accessibilityText('重试')
          .onClick(() => { this.retry(); })
      }.width('100%').margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  itemRow(item: PpOcrEvalItem) {
    Column() {
      Row() {
        Text(item.id).fontSize(12).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText).layoutWeight(1)
        Text(item.cer <= 0.05 ? 'CER ' + (item.cer * 100).toFixed(2) + '%' : 'CER ' + (item.cer * 100).toFixed(2) + '%')
          .fontSize(12).fontColor(item.cer <= 0.05 ? Theme.successInk : Theme.dangerInk)
        Text(item.category).fontSize(10).fontColor(Theme.secondaryText)
          .backgroundColor(Theme.cardWarm).padding({ left: 6, right: 6, top: 1, bottom: 1 })
          .borderRadius(6).margin({ left: 8 })
      }.width('100%').alignItems(VerticalAlign.Center)
      Text(item.text.length > 0 ? item.text : '（空文本）').fontSize(11).fontColor(Theme.secondaryText)
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').margin({ top: 4 })
      Text(item.lineCount.toString() + ' 行 · det ' + item.detMs.toString() + 'ms · rec '
        + item.recMs.toString() + 'ms · total ' + item.totalMs.toString() + 'ms')
        .fontSize(10).fontColor(Theme.secondaryText).width('100%').margin({ top: 2 })
      if (item.error.length > 0) {
        Text(item.error).fontSize(10).fontColor(Theme.dangerInk).width('100%').margin({ top: 2 })
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding({ top: 8, bottom: 8 })
    .border({ width: { bottom: 1 }, color: Theme.divider })
  }

  @Builder
  listCard() {
    Column() {
      Row() {
        Text('逐图结果').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text(this.items.length.toString() + ' 张').fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%').alignItems(VerticalAlign.Center)
      if (this.items.length === 0) {
        Text('尚未产生结果：自动自检会在页面打开后运行，也可点「开始识别」重跑。')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 8 }).width('100%')
      }
      ForEach(this.items, (item: PpOcrEvalItem) => {
        this.itemRow(item)
      }, (item: PpOcrEvalItem) => item.id)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  build() {
    Column() {
      Scroll() {
        Column() {
          this.header()
          this.engineCard()
          this.listCard()
          Text('评测图与模型来自应用 rawfile，全部在本机推理；本页不访问网络。')
            .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 16, bottom: 28 })
        }.width('100%').constraintSize({ maxWidth: 860 })
      }.width('100%').layoutWeight(1)
    }
    .width('100%').height('100%')
    .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    .backgroundColor(Theme.bg)
  }
}
