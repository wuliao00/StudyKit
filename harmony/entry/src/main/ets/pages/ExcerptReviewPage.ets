import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { BookItem, ExcerptItem, BookStore } from '../model/BookStore';
import {
  BookStudyStore,
  ExcerptReview,
  bookTodayIso
} from '../model/BookStudyStore';

interface ReviewCard {
  review: ExcerptReview;
  excerpt: ExcerptItem;
  bookTitle: string;
}

const MASK_PREFIX: number = 8;

/**
 * 书摘到期复习页（G 模块）。
 *
 * 队列 = BookStudyStore 里 dueAt <= 今天的书摘（可按书过滤），按到期日排序。
 * 复习方式：先看开头几个字与出处，回想原句，再揭晓全文自评「记住了 / 没记住」。
 * 记住 → 往后一档（1/2/4/7 天），没记住 → 回到第一档（1 天后再看）。
 * 只写自己的 filesDir/book_study.json，不碰 book_data.json 的内容。
 */
@Entry
@Component
struct ExcerptReviewPage {
  @State loading: boolean = true;
  @State bookId: string = '';
  @State bookTitle: string = '';
  @State cards: ReviewCard[] = [];
  @State index: number = 0;
  @State revealed: boolean = false;
  @State done: boolean = false;
  @State rememberedCount: number = 0;
  @State forgotCount: number = 0;
  @State summaryLine: string = '';
  @State upcomingLine: string = '';

  private store: BookStore = BookStore.get();
  private study: BookStudyStore = BookStudyStore.get();

  async aboutToAppear(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    try {
      await this.store.init(ctx);
      await this.study.init(ctx);
    } catch (e) {
      this.toast('读取本地读书数据失败，复习队列可能为空');
    }
    this.parseParams();
    this.reloadQueue();
    this.loading = false;
  }

  private parseParams(): void {
    try {
      const raw: Object = router.getParams();
      if (raw === null || raw === undefined) {
        return;
      }
      const params: Record<string, string> = raw as Record<string, string>;
      const byBookId: string = params['bookId'];
      if (byBookId !== undefined && byBookId !== null && byBookId.length > 0) {
        this.bookId = byBookId;
        const book: BookItem | null = this.store.getBook(this.bookId);
        this.bookTitle = book === null ? '' : book.title;
      }
    } catch (e) {
      this.bookId = '';
    }
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private today(): string {
    return bookTodayIso();
  }

  private reloadQueue(): void {
    const today: string = this.today();
    this.pruneReviews();
    const due: ExcerptReview[] = this.study.dueReviews(this.bookId, today);
    const cards: ReviewCard[] = [];
    for (let i = 0; i < due.length; i++) {
      const excerpt: ExcerptItem | null = this.store.getExcerpt(due[i].excerptId);
      if (excerpt === null) {
        continue;
      }
      const book: BookItem | null = this.store.getBook(excerpt.bookId);
      cards.push({
        review: due[i],
        excerpt: excerpt,
        bookTitle: book === null ? '未知书籍' : book.title
      });
    }
    this.cards = cards;
    this.index = 0;
    this.revealed = false;
    this.rememberedCount = 0;
    this.forgotCount = 0;
    this.done = cards.length === 0;
    this.summaryLine = '';
    this.upcomingLine = this.study.upcomingLine(this.bookId, today, 7);
    if (this.bookId.length > 0) {
      const book: BookItem | null = this.store.getBook(this.bookId);
      this.bookTitle = book === null ? '' : book.title;
    }
  }

  private pruneReviews(): void {
    const existing: Set<string> = new Set<string>();
    const books: BookItem[] = this.store.getBooks();
    for (let i = 0; i < books.length; i++) {
      const list: ExcerptItem[] = this.store.excerptsOf(books[i].id);
      for (let j = 0; j < list.length; j++) {
        existing.add(list[j].id);
      }
    }
    this.study.pruneMissing(existing);
  }

  private currentCard(): ReviewCard | null {
    if (this.index < 0 || this.index >= this.cards.length) {
      return null;
    }
    return this.cards[this.index];
  }

  private currentTitle(): string {
    const card: ReviewCard | null = this.currentCard();
    return card === null ? '' : card.bookTitle;
  }

  private currentPageLabel(): string {
    const card: ReviewCard | null = this.currentCard();
    if (card === null) {
      return '';
    }
    return card.excerpt.pageNo > 0 ? '第 ' + card.excerpt.pageNo.toString() + ' 页' : '未记页码';
  }

  private currentStageLabel(): string {
    const card: ReviewCard | null = this.currentCard();
    if (card === null) {
      return '';
    }
    return BookStudyStore.stageText(card.review.stage);
  }

  private currentMask(): string {
    const card: ReviewCard | null = this.currentCard();
    if (card === null) {
      return '';
    }
    const content: string = card.excerpt.content;
    if (content.length <= MASK_PREFIX) {
      return content + '…';
    }
    return content.substring(0, MASK_PREFIX) + '…';
  }

  private currentFull(): string {
    const card: ReviewCard | null = this.currentCard();
    return card === null ? '' : card.excerpt.content;
  }

  private currentRememberHint(): string {
    const card: ReviewCard | null = this.currentCard();
    if (card === null) {
      return '';
    }
    const nextStage: number = BookStudyStore.nextStage(true, card.review.stage);
    return BookStudyStore.intervalForStage(nextStage).toString() + ' 天后再看';
  }

  private progressPercent(): number {
    if (this.cards.length === 0) {
      return 0;
    }
    return Math.floor(this.index * 100 / this.cards.length);
  }

  private reveal(): void {
    this.revealed = true;
  }

  private mark(remembered: boolean): void {
    const card: ReviewCard | null = this.currentCard();
    if (card === null || !this.revealed) {
      return;
    }
    this.study.markReviewed(card.excerpt.id, remembered, this.today());
    if (remembered) {
      this.rememberedCount = this.rememberedCount + 1;
    } else {
      this.forgotCount = this.forgotCount + 1;
    }
    if (this.index + 1 >= this.cards.length) {
      this.finishRound();
      return;
    }
    this.index = this.index + 1;
    this.revealed = false;
  }

  private finishRound(): void {
    this.done = true;
    this.revealed = false;
    this.summaryLine = '这一轮复习完：记住 ' + this.rememberedCount.toString() + ' 张 · 需要再看 '
      + this.forgotCount.toString() + ' 张。';
    this.upcomingLine = this.study.upcomingLine(this.bookId, this.today(), 7);
  }

  private restartQueue(): void {
    this.reloadQueue();
  }

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text('书摘复习').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text(this.bookId.length > 0 && this.bookTitle.length > 0
          ? '《' + this.bookTitle + '》的到期书摘'
          : '所有书籍的到期书摘 · 1/2/4/7 天节奏')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      Text(this.done ? '已结束' : '第 ' + (this.index + 1).toString() + ' / ' + this.cards.length.toString() + ' 张')
        .fontSize(11).fontColor(Theme.secondaryText)
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  emptyState() {
    Column() {
      Text('今天没有到期的书摘').fontSize(19).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('书摘按 1 / 2 / 4 / 7 天的节奏排期：新记的书摘当天到期；记住后往后排一档，'
        + '没记住回到第一档。到期日 <= 今天的会出现在这里。').fontSize(12)
        .fontColor(Theme.secondaryText).margin({ top: 8 }).lineHeight(19)
      Text(this.upcomingLine).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 10 }).lineHeight(17)
      Row() {
        Button('回上一页').layoutWeight(1).height(44).fontSize(14)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { router.back(); })
        Button('刷新队列').layoutWeight(1).height(44).fontSize(14).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.restartQueue(); })
      }.width('100%').margin({ top: 14 })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(20)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  summaryCard() {
    Column() {
      Text('这一轮复习完了').fontSize(20).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(this.summaryLine).fontSize(13).fontColor(Theme.primaryText).margin({ top: 8 }).lineHeight(20)
      Row() {
        Column() {
          Text(this.rememberedCount.toString()).fontSize(26).fontWeight(FontWeight.Bold)
            .fontColor(Theme.successInk)
          Text('记住了').fontSize(10).fontColor(Theme.secondaryText)
        }.layoutWeight(1).alignItems(HorizontalAlign.Center)
        Column() {
          Text(this.forgotCount.toString()).fontSize(26).fontWeight(FontWeight.Bold)
            .fontColor(Theme.warningInk)
          Text('需要再看').fontSize(10).fontColor(Theme.secondaryText)
        }.layoutWeight(1).alignItems(HorizontalAlign.Center)
      }.width('100%').margin({ top: 14 }).padding(12)
      .backgroundColor(Theme.cardWarm).borderRadius(12)
      Text(this.upcomingLine).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 10 }).lineHeight(17)
      Row() {
        Button('回上一页').layoutWeight(1).height(44).fontSize(14)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { router.back(); })
        Button('再看有没有到期').layoutWeight(1).height(44).fontSize(14).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.restartQueue(); })
      }.width('100%').margin({ top: 14 })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(20)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  reviewCardView() {
    Column() {
      Row() {
        Text(this.currentTitle()).fontSize(13).fontWeight(FontWeight.Medium).fontColor(Theme.accentInk)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
        Text(this.currentPageLabel()).fontSize(11).fontColor(Theme.secondaryText)
        Text(' · ' + this.currentStageLabel()).fontSize(11).fontColor(Theme.goldInk).margin({ left: 4 })
      }.width('100%').alignItems(VerticalAlign.Center)

      Text(this.currentMask()).fontSize(18).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%').margin({ top: 14 }).lineHeight(28)

      if (!this.revealed) {
        Text('先在心里补全这句话，再揭晓全文。').fontSize(11).fontColor(Theme.secondaryText)
          .margin({ top: 10 })
        Button('揭晓全文').width('100%').height(46).fontSize(15).margin({ top: 14 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.reveal(); })
      } else {
        Text(this.currentFull()).fontSize(15).fontColor(Theme.primaryText).width('100%')
          .margin({ top: 12 }).lineHeight(23).padding(12)
          .backgroundColor(Theme.cardWarm).borderRadius(10)
        Text('刚才那句对上了吗？').fontSize(11).fontColor(Theme.secondaryText).margin({ top: 12 })
        Row() {
          Button('记住了').layoutWeight(1).height(48).fontSize(15)
            .backgroundColor(Theme.successSoft).fontColor(Theme.successInk)
            .onClick(() => { this.mark(true); })
          Button('没记住').layoutWeight(1).height(48).fontSize(15).margin({ left: 10 })
            .backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
            .onClick(() => { this.mark(false); })
        }.width('100%').margin({ top: 8 })
        Row() {
          Text('下次 ' + this.currentRememberHint()).fontSize(10).fontColor(Theme.secondaryText)
            .layoutWeight(1).textAlign(TextAlign.Center)
          Text('回到第一档 · 1 天后再看').fontSize(10).fontColor(Theme.secondaryText)
            .layoutWeight(1).textAlign(TextAlign.Center)
        }.width('100%').margin({ top: 4 })
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(20)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 10 })
  }

  @Builder
  scheduleLegend() {
    Column() {
      Text('复习节奏').fontSize(13).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('记住了：第 1 档 → 1 天后；第 2 档 → 2 天后；第 3 档 → 4 天后；第 4 档 → 7 天后。'
        + '没记住：回到第 1 档（1 天后再看）。新书摘记下的当天先复习一次。').fontSize(11).fontColor(Theme.secondaryText)
        .margin({ top: 5 }).lineHeight(17)
      Text('复习记录只写本机 filesDir/book_study.json；书摘原文仍在 book_data.json，不上传。')
        .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 5 }).lineHeight(16)
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)
  }

  build() {
    Scroll() {
      Column() {
        this.header()
        if (this.loading) {
          Column() {
            LoadingProgress().width(40).height(40).color(Theme.accent)
            Text('正在读取到期书摘').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
          }.width('100%').height(240).justifyContent(FlexAlign.Center)
        } else {
          if (!this.done && this.cards.length > 0) {
            Progress({ value: this.progressPercent(), total: 100, type: ProgressType.Linear })
              .color(Theme.accent).backgroundColor(Theme.divider)
              .width('100%').height(6).margin({ top: 2, bottom: 4 })
            this.reviewCardView()
            Text('已复习 ' + (this.index).toString() + ' / ' + this.cards.length.toString() + ' 张')
              .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 8 })
          }
          if (this.done && this.cards.length === 0) {
            this.emptyState()
          } else if (this.done) {
            this.summaryCard()
          }
          this.scheduleLegend()
          Text('数据只保存在这台设备：书摘在 book_data.json，复习档位在 book_study.json。')
            .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 12, bottom: 28 }).lineHeight(16)
        }
      }
      .width('100%').constraintSize({ maxWidth: 760 })
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .width('100%').height('100%').backgroundColor(Theme.bg).scrollBar(BarState.Off)
  }
}
