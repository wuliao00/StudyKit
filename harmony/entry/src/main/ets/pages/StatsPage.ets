import router from '@ohos.router';
import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import promptAction from '@ohos.promptAction';
import { Theme } from '../common/Theme';
import { StudyStore } from '../model/Store';
import { Srs, SrsCard } from '../model/Srs';
import { SignInStats, SignInSummary } from '../model/SignIn';
import { QuizSession, WrongWord } from '../model/Quiz';
import { SettingsStore } from '../model/BackupStore';

// 学习统计页：全部读数来自 StudyStore/Srs 的真实数据；记忆曲线是按 Leitner 盒子间隔
// 粗算的 retention（不做算法校准，不冒充 FSRS），海报用 ArkUI Canvas 在本机生成 PNG。

const POSTER_NONE: number = 0;
const POSTER_REPORT: number = 1;
const POSTER_WIDTH: number = 720;
const POSTER_HEIGHT: number = 1040;
// R(t) = (1 + FACTOR * t / S)^DECAY，FSRS-5 的化简口径：R(S) = 0.9
const RETENTION_FACTOR: number = 0.2345679;
const RETENTION_DECAY: number = -0.5;

@Entry
@Component
struct StatsPage {
  @State loading: boolean = true;
  @State today: string = SignInStats.today();
  @State mastered: number = 0;
  @State due: number = 0;
  @State learning: number = 0;
  @State accuracy: number = -1;
  @State quizAccuracy: number = -1;
  @State streak: number = 0;
  @State totalDays: number = 0;
  @State reviewTotal: number = 0;
  @State todayReviewed: number = 0;
  @State todayQuiz: number = 0;
  @State signStreak: number = 0;
  @State dailyGoal: number = 30;
  @State wrongCount: number = 0;
  @State reportText: string = '';
  @State hasCurve: boolean = false;
  @State posterMode: number = POSTER_NONE;
  @State posterData: string = '';
  @State posterPath: string = '';
  @State posterError: string = '';

  private store: StudyStore = StudyStore.get();
  private prefs: SettingsStore = SettingsStore.get();
  private filesDir: string = '';
  private wrongTop: WrongWord[] = [];
  private recentSessions: QuizSession[] = [];
  private retentionPts: number[] = [];
  private summary: SignInSummary = SignInStats.emptySummary();
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private curveCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);

  async aboutToAppear(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    this.filesDir = ctx.filesDir;
    try {
      await this.store.init(ctx);
      await this.prefs.init(ctx);
    } catch (e) {
      promptAction.showToast({ message: '读取本机数据失败，统计可能为空' });
    }
    this.reload();
    this.loading = false;
  }

  private toast(text: string): void {
    promptAction.showToast({ message: text });
  }

  private reload(): void {
    this.today = SignInStats.today();
    const cards: SrsCard[] = this.store.cards;
    this.summary = SignInStats.summary(this.store.signIns, this.today);
    this.mastered = Srs.masteredCount(cards);
    this.due = Srs.dueCount(cards, this.today);
    this.learning = cards.length;
    this.accuracy = Srs.accuracy(cards);
    this.quizAccuracy = this.store.quizTotalCount() <= 0
      ? -1
      : Math.round(this.store.quizCorrectCount() * 100 / this.store.quizTotalCount());
    this.streak = this.store.studyStreak(this.today);
    this.totalDays = this.store.studyTotalDays();
    this.reviewTotal = this.store.reviewTotalCount();
    this.todayReviewed = this.store.todayReviewCount(this.today);
    this.todayQuiz = this.store.todayQuizCount(this.today);
    this.signStreak = this.summary.currentStreak;
    this.dailyGoal = this.prefs.dailyGoal;
    this.wrongCount = this.store.wrongs.length;
    this.wrongTop = this.store.wrongs.slice(0, 3);
    this.recentSessions = this.store.recentSessions(3);
    this.retentionPts = this.computeRetention(cards);
    this.hasCurve = this.retentionPts.length > 0;
    this.reportText = this.buildReport();
  }

  private parseDay(iso: string): number {
    const parts: string[] = iso.split('-');
    return new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2])).getTime();
  }

  private elapsedDays(lastReviewed: string, todayIso: string): number {
    const ms: number = this.parseDay(todayIso) - this.parseDay(lastReviewed);
    const days: number = Math.round(ms / 86400000);
    return days < 0 ? 0 : days;
  }

  // 返回未来 8 天（今天 + 之后 7 天）的平均 retention；没有可算的卡时返回空数组
  private computeRetention(cards: SrsCard[]): number[] {
    const result: number[] = [];
    let subjects: number = 0;
    for (let i = 0; i < cards.length; i++) {
      if (cards[i].box >= 2) {
        subjects++;
      }
    }
    if (subjects <= 0) {
      return result;
    }
    for (let offset = 0; offset < 8; offset++) {
      let sum: number = 0;
      let n: number = 0;
      for (let i = 0; i < cards.length; i++) {
        const card: SrsCard = cards[i];
        if (card.box < 2) {
          continue;
        }
        const stability: number = Srs.intervalForBox(card.box);
        const t: number = this.elapsedDays(card.lastReviewed, this.today) + offset;
        const r: number = Math.pow(1 + RETENTION_FACTOR * t / stability, RETENTION_DECAY);
        sum += r;
        n++;
      }
      result.push(n <= 0 ? 0 : sum / n);
    }
    return result;
  }

  private pct(value: number): string {
    return value < 0 ? '—' : Math.round(value * 100).toString() + '%';
  }

  private accuracyText(): string {
    return this.accuracy < 0 ? '—' : this.accuracy.toString() + '%';
  }

  private quizAccuracyText(): string {
    return this.quizAccuracy < 0 ? '—' : this.quizAccuracy.toString() + '%';
  }

  private todayRetention(): string {
    if (this.retentionPts.length === 0) {
      return '—';
    }
    return this.pct(this.retentionPts[0]);
  }

  private goalPercent(): number {
    if (this.dailyGoal <= 0) {
      return 0;
    }
    const percent: number = Math.floor(this.todayReviewed * 100 / this.dailyGoal);
    return percent > 100 ? 100 : percent;
  }

  private goalLine(): string {
    const left: number = this.dailyGoal - this.todayReviewed;
    if (left <= 0) {
      return '今天的目标已经达成：复习 ' + this.todayReviewed.toString() + ' 词（目标 ' + this.dailyGoal.toString() + '）。';
    }
    return '今天已复习 ' + this.todayReviewed.toString() + ' / ' + this.dailyGoal.toString() + ' 词，还差 '
      + left.toString() + ' 词。';
  }

  private wrongTopText(): string {
    if (this.wrongTop.length === 0) {
      return '错题本是空的。答错的小测题会自动进来。';
    }
    let text: string = '';
    for (let i = 0; i < this.wrongTop.length; i++) {
      if (i > 0) {
        text += ' · ';
      }
      text += this.wrongTop[i].word + '（错 ' + this.wrongTop[i].wrongCount.toString() + ' 次）';
    }
    return text;
  }

  private recentText(): string {
    if (this.recentSessions.length === 0) {
      return '还没有完成过小测。';
    }
    let text: string = '';
    for (let i = 0; i < this.recentSessions.length; i++) {
      if (i > 0) {
        text += ' · ';
      }
      text += this.recentSessions[i].correct.toString() + '/' + this.recentSessions[i].total.toString();
    }
    return text;
  }

  private buildReport(): string {
    if (this.learning <= 0 && this.reviewTotal <= 0) {
      return '还没有学习数据：先去背几个词或复习几张卡，这里会长出你真实的记录。';
    }
    let text: string = '你在这台设备上学了 ' + this.totalDays.toString() + ' 天';
    if (this.streak > 0) {
      text += '（连续 ' + this.streak.toString() + ' 天）';
    }
    text += '，累计复习 ' + this.reviewTotal.toString() + ' 次。';
    text += '已掌握 ' + this.mastered.toString() + ' 个词，还有 ' + this.due.toString() + ' 个词今天到期';
    if (this.wrongCount > 0) {
      text += '，错题本 ' + this.wrongCount.toString() + ' 题';
    }
    text += '。';
    if (this.accuracy >= 0) {
      text += '复习正确率 ' + this.accuracy.toString() + '%。';
    }
    if (this.quizAccuracy >= 0) {
      text += '小测正确率 ' + this.quizAccuracy.toString() + '%。';
    }
    text += '以上只按真实记录统计，不做算法校准。';
    return text;
  }

  private drawCurve(): void {
    const ctx: CanvasRenderingContext2D = this.curveCtx;
    const w: number = ctx.width > 0 ? ctx.width : 320;
    const h: number = ctx.height > 0 ? ctx.height : 180;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = Theme.card;
    ctx.fillRect(0, 0, w, h);
    const left: number = 34;
    const right: number = 12;
    const top: number = 16;
    const bottom: number = 26;
    const chartW: number = w - left - right;
    const chartH: number = h - top - bottom;
    ctx.strokeStyle = Theme.divider;
    ctx.lineWidth = 1;
    for (let row = 0; row <= 2; row++) {
      const y: number = top + chartH * row / 2;
      ctx.beginPath();
      ctx.moveTo(left, y);
      ctx.lineTo(left + chartW, y);
      ctx.stroke();
    }
    ctx.fillStyle = Theme.secondaryText;
    ctx.font = '9px sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText('100%', left - 4, top + 4);
    ctx.fillText('50%', left - 4, top + chartH / 2 + 3);
    ctx.fillText('0%', left - 4, top + chartH + 3);
    ctx.textAlign = 'center';
    for (let d = 0; d < this.retentionPts.length; d++) {
      const x: number = left + chartW * d / 7;
      if (d % 2 === 0) {
        ctx.fillText(d === 0 ? '今天' : '+' + d.toString() + '天', x, h - 8);
      }
    }
    if (this.retentionPts.length > 0) {
      ctx.strokeStyle = Theme.accent;
      ctx.lineWidth = 2;
      ctx.beginPath();
      for (let d = 0; d < this.retentionPts.length; d++) {
        const x: number = left + chartW * d / 7;
        const y: number = top + chartH * (1 - this.retentionPts[d]);
        if (d === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
      }
      ctx.stroke();
      ctx.fillStyle = Theme.accentInk;
      for (let d = 0; d < this.retentionPts.length; d++) {
        const x: number = left + chartW * d / 7;
        const y: number = top + chartH * (1 - this.retentionPts[d]);
        ctx.beginPath();
        ctx.arc(x, y, 2.5, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.fillStyle = Theme.primaryText;
    ctx.textAlign = 'left';
    ctx.font = 'bold 11px sans-serif';
    ctx.fillText('平均记忆保持率（按盒子间隔粗算）', left, h - 1);
  }

  // ---------- 海报（Canvas -> PNG，照搬 Index 已验证的 OffscreenCanvas 路线） ----------

  private pad2(value: number): string {
    return value < 10 ? '0' + value.toString() : value.toString();
  }

  private timestamp(): string {
    const d = new Date();
    return d.getFullYear().toString() + this.pad2(d.getMonth() + 1) + this.pad2(d.getDate())
      + '-' + this.pad2(d.getHours()) + this.pad2(d.getMinutes()) + this.pad2(d.getSeconds());
  }

  private generatePoster(): void {
    try {
      const canvas = new OffscreenCanvas(POSTER_WIDTH, POSTER_HEIGHT);
      const ctx = canvas.getContext('2d', new RenderingContextSettings(true));
      this.drawPoster(ctx);
      const dataUrl: string = ctx.toDataURL('image/png');
      this.posterData = dataUrl;
      this.posterPath = '';
      this.posterError = '';
      this.posterMode = POSTER_REPORT;
      this.savePoster(dataUrl);
    } catch (e) {
      this.posterMode = POSTER_REPORT;
      this.posterData = '';
      this.posterError = '海报生成失败，请重试（' + JSON.stringify(e) + '）';
    }
  }

  private savePoster(dataUrl: string): void {
    try {
      const comma: number = dataUrl.indexOf(',');
      if (comma < 0) {
        this.posterError = '没有取到 PNG 图片数据';
        return;
      }
      const helper = new util.Base64Helper();
      const bytes: Uint8Array = helper.decodeSync(dataUrl.substring(comma + 1));
      const name: string = 'StudyKit-stats-' + this.timestamp() + '.png';
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

  private drawPoster(ctx: OffscreenCanvasRenderingContext2D): void {
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
    ctx.fillText('学习统计 · 记忆报告', 48, 126);
    ctx.textAlign = 'right';
    ctx.fillStyle = '#E1F3ED';
    ctx.font = '18px sans-serif';
    ctx.fillText(this.today, 672, 82);
    ctx.textAlign = 'left';

    this.drawMetricCard(ctx, 48, 205, 192, 140, this.mastered.toString() + ' 词', '已掌握');
    this.drawMetricCard(ctx, 264, 205, 192, 140, this.due.toString() + ' 词', '今日待复习');
    this.drawMetricCard(ctx, 480, 205, 192, 140, this.accuracyText(), '复习正确率');
    this.drawMetricCard(ctx, 48, 362, 192, 140, this.streak.toString() + ' 天', '连续学习');
    this.drawMetricCard(ctx, 264, 362, 192, 140, this.totalDays.toString() + ' 天', '累计学习');
    this.drawMetricCard(ctx, 480, 362, 192, 140, this.reviewTotal.toString() + ' 次', '累计复习');

    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 22px sans-serif';
    ctx.fillText('今日目标进度', 48, 560);
    ctx.fillStyle = '#E9E1D1';
    ctx.fillRect(48, 582, 624, 26);
    ctx.fillStyle = '#0F9C82';
    ctx.fillRect(48, 582, 624 * this.goalPercent() / 100, 26);
    ctx.fillStyle = '#7C776B';
    ctx.font = '18px sans-serif';
    ctx.fillText(this.goalLine(), 48, 640);

    ctx.fillStyle = '#20241F';
    ctx.font = 'bold 22px sans-serif';
    ctx.fillText('未来 7 天记忆保持（粗算）', 48, 700);
    if (this.retentionPts.length > 0) {
      const left: number = 48;
      const baseY: number = 880;
      const chartH: number = 140;
      const stepX: number = 624 / 7;
      ctx.strokeStyle = '#0F9C82';
      ctx.lineWidth = 4;
      ctx.beginPath();
      for (let d = 0; d < this.retentionPts.length; d++) {
        const x: number = left + stepX * d;
        const y: number = baseY - chartH * this.retentionPts[d];
        if (d === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
      }
      ctx.stroke();
      ctx.fillStyle = '#7C776B';
      ctx.font = '16px sans-serif';
      for (let d = 0; d < this.retentionPts.length; d++) {
        const x: number = left + stepX * d;
        ctx.fillText(d === 0 ? '今天' : '+' + d.toString(), x - 12, baseY + 28);
      }
    } else {
      ctx.fillStyle = '#7C776B';
      ctx.font = '18px sans-serif';
      ctx.fillText('还没有可画的复习记录：评过几次分，曲线就长出来。', 48, 780);
    }

    ctx.fillStyle = '#7C776B';
    ctx.font = '16px sans-serif';
    ctx.fillText('本地生成 · 数据只保存在这台设备 · StudyKit 水印', 48, 1002);
    ctx.textAlign = 'left';
  }

  @Builder
  header() {
    Column() {
      Row() {
        Text('‹ 返回').fontSize(15).fontColor(Theme.accentInk)
          .onClick(() => {
            router.back();
          })
        Blank()
        Text('真实数据 · 本机计算').fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%').padding({ top: 4, bottom: 10 })
      Text('学习统计').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text('已掌握、待复习、正确率、连续与累计，全部来自本机记录。').fontSize(12)
        .fontColor(Theme.secondaryText).margin({ top: 4 })
    }.alignItems(HorizontalAlign.Start).width('100%').padding({ top: 6, bottom: 14 })
  }

  @Builder
  metricGrid() {
    Column() {
      Row() {
        this.metricCell('已掌握', this.mastered.toString() + ' 词')
        this.metricCell('今日待复习', this.due.toString() + ' 词')
        this.metricCell('复习正确率', this.accuracyText())
      }.width('100%')
      Row() {
        this.metricCell('连续学习', this.streak.toString() + ' 天')
        this.metricCell('累计学习', this.totalDays.toString() + ' 天')
        this.metricCell('累计复习', this.reviewTotal.toString() + ' 次')
      }.width('100%').margin({ top: 10 })
    }.width('100%').padding(14).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  metricCell(label: string, value: string) {
    Column() {
      Text(value).fontSize(19).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(label).fontSize(10).fontColor(Theme.secondaryText).margin({ top: 3 })
    }.layoutWeight(1).alignItems(HorizontalAlign.Center)
  }

  @Builder
  goalCard() {
    Column() {
      Row() {
        Text('今日目标进度').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text(this.goalPercent().toString() + '%').fontSize(15).fontWeight(FontWeight.Bold)
          .fontColor(Theme.accentInk)
      }.width('100%')
      Row() {
        Row().width(this.goalPercent().toString() + '%').height(10).borderRadius(5).backgroundColor(Theme.accent)
      }.width('100%').height(10).borderRadius(5).backgroundColor(Theme.divider).margin({ top: 10 })
      Text(this.goalLine()).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 8 })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 14 })
  }

  @Builder
  reportCard() {
    Column() {
      Text('学习报告').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text(this.reportText).fontSize(12).lineHeight(20).fontColor(Theme.primaryText).margin({ top: 8 })
      Text('小测：' + this.recentText() + '　今日已答 ' + this.todayQuiz.toString() + ' 题。')
        .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 8 })
      Text('错题：' + this.wrongTopText()).fontSize(11).fontColor(Theme.secondaryText)
        .margin({ top: 6 }).lineHeight(17)
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 14 })
  }

  @Builder
  curveCard() {
    Column() {
      Text('记忆曲线').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('按每个盒子「到期间隔」粗算未来 7 天的平均保持率；只做趋势参考，不做算法校准，'
        + '也不冒充 FSRS 模型读数。').fontSize(11).fontColor(Theme.secondaryText)
        .margin({ top: 5 }).lineHeight(17)
      Column() {
        if (this.hasCurve) {
          Canvas(this.curveCtx)
            .width('100%')
            .height(190)
            .onReady(() => {
              this.drawCurve();
            })
          Text('今天是 ' + this.todayRetention() + '；未来 7 天按同一批卡粗算。')
            .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 4 })
        } else {
          Text('还画不出曲线：先复习几张盒子 2 以上的卡，这里就会出现你的保持率。')
            .fontSize(11).fontColor(Theme.secondaryText).padding(16)
        }
      }.width('100%').margin({ top: 10 })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 14 })
  }

  @Builder
  shareCard() {
    Column() {
      Text('分享海报').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('用本机 Canvas 画一张 720×1040 的 PNG，保存到应用 filesDir；'
        + '不上传、不需要相册权限，想分享时自己从文件管理里取。').fontSize(11)
        .fontColor(Theme.secondaryText).margin({ top: 5 }).lineHeight(17)
      Button('生成学习海报').width('100%').height(46).fontSize(14).margin({ top: 12 })
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => {
          this.generatePoster();
        })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 14 })
  }

  @Builder
  posterView() {
    Scroll() {
      Row() {
        Column() {
          Row() {
            Column() {
              Text('学习海报').fontSize(28).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
              Text('本地生成 · PNG').fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
            }.alignItems(HorizontalAlign.Start)
            Blank()
            Button('关闭').height(34).fontSize(13)
              .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
              .onClick(() => {
                this.posterMode = POSTER_NONE;
              })
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
              .onClick(() => {
                this.generatePoster();
              })
            Button('返回').layoutWeight(1).height(46).margin({ left: 10 })
              .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
              .onClick(() => {
                this.posterMode = POSTER_NONE;
              })
          }.width('100%').margin({ top: 16 })
          Text('海报由 ArkUI Canvas 在本机绘制；文字和数据都来自这台设备。').fontSize(11)
            .fontColor(Theme.secondaryText).margin({ top: 14, bottom: 30 })
        }.width('100%').constraintSize({ maxWidth: 760 })
      }.width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }.width('100%').height('100%').backgroundColor(Theme.bg).scrollBar(BarState.Off)
  }

  build() {
    if (this.posterMode !== POSTER_NONE) {
      this.posterView()
    } else {
      Scroll() {
        Row() {
          Column() {
            this.header()
            if (this.loading) {
              Column() {
                LoadingProgress().width(40).height(40).color(Theme.accent)
                Text('正在汇总本机记录').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
              }.width('100%').height(240).justifyContent(FlexAlign.Center)
            } else {
              this.metricGrid()
              this.goalCard()
              this.reportCard()
              this.curveCard()
              this.shareCard()
              Text('口径：统计只读 StudyStore/Srs 的真实记录；记忆曲线按盒子间隔粗算，'
                + '与首页「累计复习解锁」不是同一套算法。').fontSize(10).fontColor(Theme.secondaryText)
                .margin({ top: 12, bottom: 30 }).lineHeight(16)
            }
          }.width('100%').constraintSize({ maxWidth: 760 })
        }.width('100%').justifyContent(FlexAlign.Center)
        .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
      }
      .width('100%').height('100%').backgroundColor(Theme.bg).scrollBar(BarState.Off)
    }
  }
}
