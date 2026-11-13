import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import { BookItem, ExcerptItem, BookStore } from '../model/BookStore';

/**
 * 书架 / 读书笔记独立页（HarmonyOS 完全移植 B 模块）。
 *
 * 数据存在自己的 filesDir/book_data.json（BookStore），不碰 Index/Store.ets。
 * 页面与主应用同语言：@Entry @Component、router.back()、暖纸色 Theme、760 居中。
 *
 * 功能：书籍新增/编辑（书名、作者、总页数、当前页、状态）、阅读进度百分比、
 * 标记读完 / 改回在读、书摘（读书笔记）增删、删除书二次确认、空状态给下一步。
 */
@Entry
@Component
struct BooksPage {
  @State loading: boolean = true;
  @State books: BookItem[] = [];
  @State filter: number = 0;
  @State version: number = 0;

  @State showForm: boolean = false;
  @State editingId: string = '';
  @State formTitle: string = '';
  @State formAuthor: string = '';
  @State formTotal: string = '';
  @State formCurrent: string = '';
  @State formFinished: boolean = false;

  @State progressBookId: string = '';
  @State progressDraft: string = '';

  @State excerptBookId: string = '';
  @State excerptContent: string = '';
  @State excerptPage: string = '';

  private store: BookStore = BookStore.get();

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.store.init(context).then(() => {
      this.reload();
      this.loading = false;
    }).catch(() => {
      this.loading = false;
      this.toast('本地读书数据读取失败');
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private reload(): void {
    this.books = this.store.getBooks();
    this.version = this.version + 1;
  }

  private readingCount(): number {
    return this.store.readingCount();
  }

  private finishedCount(): number {
    return this.store.finishedCount();
  }

  private excerptTotal(): number {
    return this.store.totalExcerptCount();
  }

  private filteredBooks(): BookItem[] {
    const result: BookItem[] = [];
    for (let i = 0; i < this.books.length; i++) {
      const book: BookItem = this.books[i];
      if (this.filter === 1 && book.status === BookStore.STATUS_FINISHED) {
        continue;
      }
      if (this.filter === 2 && book.status !== BookStore.STATUS_FINISHED) {
        continue;
      }
      result.push(book);
    }
    return result;
  }

  private digitsOnly(value: string): string {
    let out: string = '';
    for (let i = 0; i < value.length; i++) {
      const ch: string = value.charAt(i);
      if (ch >= '0' && ch <= '9') {
        out = out + ch;
      }
    }
    return out;
  }

  /** 文本 → 非负整数（空串按 0 处理；调用前已用 digitsOnly 收过口）。 */
  private intValue(text: string): number {
    if (text.length === 0) {
      return 0;
    }
    return Number(text);
  }

  private isFinished(book: BookItem): boolean {
    return book.status === BookStore.STATUS_FINISHED;
  }

  private progressPercent(book: BookItem): number {
    return BookStore.progressPercent(book);
  }

  private progressLine(book: BookItem): string {
    return '第 ' + book.currentPage.toString() + ' / ' + book.totalPages.toString() + ' 页 · '
      + this.progressPercent(book).toString() + '%';
  }

  private bookMetaLine(book: BookItem): string {
    const author: string = book.author.length > 0 ? book.author : '作者未填';
    return author + ' · ' + this.progressLine(book);
  }

  // ---------- 书表单 ----------

  private openCreate(): void {
    this.editingId = '';
    this.formTitle = '';
    this.formAuthor = '';
    this.formTotal = '';
    this.formCurrent = '';
    this.formFinished = false;
    this.showForm = true;
    this.progressBookId = '';
  }

  private openEdit(book: BookItem): void {
    this.editingId = book.id;
    this.formTitle = book.title;
    this.formAuthor = book.author;
    this.formTotal = book.totalPages.toString();
    this.formCurrent = book.currentPage.toString();
    this.formFinished = this.isFinished(book);
    this.showForm = true;
    this.progressBookId = '';
  }

  private saveForm(): void {
    const title: string = this.formTitle.trim();
    if (title.length === 0) {
      this.toast('书名不能空着');
      return;
    }
    const totalPages: number = this.intValue(this.formTotal);
    if (totalPages <= 0 || totalPages > 100000) {
      this.toast('总页数填 1–100000 的整数');
      return;
    }
    const current: number = this.intValue(this.formCurrent);
    const status: string = this.formFinished ? BookStore.STATUS_FINISHED : BookStore.STATUS_READING;
    if (this.editingId.length > 0) {
      this.store.updateBook(this.editingId, title, this.formAuthor, totalPages, current, status)
        .then((ok: boolean) => {
          if (ok) {
            this.showForm = false;
            this.reload();
            this.toast('书籍已更新');
          } else {
            this.toast('没找到要改的书');
          }
        });
    } else {
      this.store.addBook(title, this.formAuthor, totalPages).then(() => {
        this.showForm = false;
        this.reload();
        this.toast('已放上书架');
      });
    }
  }

  private confirmDeleteBook(book: BookItem): void {
    AlertDialog.show({
      title: '删除这本书？',
      message: '「' + book.title + '」和它的 ' + this.store.excerptCount(book.id).toString()
        + ' 条书摘会一起删除，删除后不可恢复。',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '删除', fontColor: Theme.dangerInk,
        action: () => {
          this.store.deleteBook(book.id).then(() => {
            if (this.excerptBookId === book.id) {
              this.excerptBookId = '';
            }
            if (this.progressBookId === book.id) {
              this.progressBookId = '';
            }
            this.reload();
            this.toast('已删除');
          });
        }
      }
    });
  }

  // ---------- 阅读进度 ----------

  private openProgress(book: BookItem): void {
    this.progressBookId = book.id;
    this.progressDraft = book.currentPage.toString();
  }

  private saveProgress(book: BookItem): void {
    const page: number = this.intValue(this.progressDraft);
    if (page < 0) {
      this.toast('当前页填 0 或正整数');
      return;
    }
    if (page > book.totalPages) {
      this.toast('当前页不能超过总页数 ' + book.totalPages.toString());
      return;
    }
    this.store.updateProgress(book.id, page).then((ok: boolean) => {
      if (!ok) {
        this.toast('进度没写进去，请重试');
        return;
      }
      this.progressBookId = '';
      this.reload();
      this.toast(page >= book.totalPages ? '读完了，已标记为已读完' : '进度已更新');
    });
  }

  private toggleFinished(book: BookItem): void {
    const nextStatus: string = this.isFinished(book) ? BookStore.STATUS_READING
      : BookStore.STATUS_FINISHED;
    this.store.setStatus(book.id, nextStatus).then((ok: boolean) => {
      if (ok) {
        this.reload();
        this.toast(nextStatus === BookStore.STATUS_FINISHED ? '已标记为读完' : '已改回在读');
      }
    });
  }

  // ---------- 书摘 ----------

  private toggleExcerpts(book: BookItem): void {
    if (this.excerptBookId === book.id) {
      this.excerptBookId = '';
      return;
    }
    this.excerptBookId = book.id;
    this.excerptContent = '';
    this.excerptPage = '';
  }

  private saveExcerpt(book: BookItem): void {
    const content: string = this.excerptContent.trim();
    if (content.length === 0) {
      this.toast('先写一句想记下的原文');
      return;
    }
    const page: number = this.intValue(this.excerptPage);
    this.store.addExcerpt(book.id, content, page).then((item: ExcerptItem | null) => {
      if (item === null) {
        this.toast('书摘没写进去，请重试');
        return;
      }
      this.excerptContent = '';
      this.excerptPage = '';
      this.reload();
      this.toast('已记下这条书摘');
    });
  }

  private confirmDeleteExcerpt(item: ExcerptItem): void {
    AlertDialog.show({
      title: '删除这条书摘？',
      message: '删除后不可恢复。',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '删除', fontColor: Theme.dangerInk,
        action: () => {
          this.store.deleteExcerpt(item.id).then(() => {
            this.reload();
            this.toast('已删除');
          });
        }
      }
    });
  }

  private startDemoBook(): void {
    this.store.addBook('小王子', '圣-埃克苏佩里', 97).then((book: BookItem) => {
      this.store.addExcerpt(book.id, '真正重要的东西，用眼睛是看不见的。', 21).then(() => {
        this.reload();
        this.toast('已放上示例书和一条书摘，可以接着读');
      });
    });
  }

  // ---------- 页面 ----------

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text('读书笔记').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text(this.books.length.toString() + ' 本 · 在读 ' + this.readingCount().toString()
          + ' 本 · 读完 ' + this.finishedCount().toString() + ' 本 · 摘录 '
          + this.excerptTotal().toString() + ' 条')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      Button(this.showForm ? '收起' : '添加书籍').height(36).fontSize(13)
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => {
          if (this.showForm) {
            this.showForm = false;
          } else {
            this.openCreate();
          }
        })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  filterChips() {
    Row() {
      Text('全部 ' + this.books.length.toString()).fontSize(12)
        .fontColor(this.filter === 0 ? Color.White : Theme.secondaryText)
        .backgroundColor(this.filter === 0 ? Theme.accentInk : Theme.card)
        .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
        .onClick(() => { this.filter = 0; })
      Text('在读 ' + this.readingCount().toString()).fontSize(12)
        .fontColor(this.filter === 1 ? Color.White : Theme.secondaryText)
        .backgroundColor(this.filter === 1 ? Theme.goldInk : Theme.card)
        .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
        .onClick(() => { this.filter = 1; })
      Text('已读完 ' + this.finishedCount().toString()).fontSize(12)
        .fontColor(this.filter === 2 ? Color.White : Theme.secondaryText)
        .backgroundColor(this.filter === 2 ? Theme.successInk : Theme.card)
        .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14)
        .onClick(() => { this.filter = 2; })
    }.width('100%').margin({ top: 4, bottom: 4 })
  }

  @Builder
  formPanel() {
    Column() {
      Text(this.editingId.length > 0 ? '编辑书籍' : '添加书籍')
        .fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText).width('100%')
      Text('页码只是用来算进度；读了多少记多少，不用一次填满。')
        .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })

      TextInput({ text: this.formTitle, placeholder: '书名（必填，如 小王子）' })
        .width('100%').height(42).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.formTitle = value; })
      TextInput({ text: this.formAuthor, placeholder: '作者（选填）' })
        .width('100%').height(42).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.formAuthor = value; })

      Row() {
        Column() {
          Text('总页数').fontSize(11).fontColor(Theme.secondaryText)
          TextInput({ text: this.formTotal, placeholder: '如 97' })
            .width('100%').height(40).margin({ top: 4 })
            .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
            .onChange((value: string) => { this.formTotal = this.digitsOnly(value); })
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Column() {
          Text('当前页').fontSize(11).fontColor(Theme.secondaryText).margin({ left: 12 })
          TextInput({ text: this.formCurrent, placeholder: '如 0' })
            .width('100%').height(40).margin({ top: 4, left: 12 })
            .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
            .onChange((value: string) => { this.formCurrent = this.digitsOnly(value); })
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      }.width('100%').margin({ top: 8 })

      if (this.editingId.length > 0) {
        Row() {
          Text('状态').fontSize(11).fontColor(Theme.secondaryText)
          Blank()
          Text('在读').fontSize(11)
            .fontColor(!this.formFinished ? Color.White : Theme.secondaryText)
            .backgroundColor(!this.formFinished ? Theme.accentInk : Theme.cardWarm)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 }).borderRadius(10)
            .onClick(() => { this.formFinished = false; })
          Text('已读完').fontSize(11)
            .fontColor(this.formFinished ? Color.White : Theme.secondaryText)
            .backgroundColor(this.formFinished ? Theme.successInk : Theme.cardWarm)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 }).borderRadius(10).margin({ left: 8 })
            .onClick(() => { this.formFinished = true; })
        }.width('100%').alignItems(VerticalAlign.Center).margin({ top: 10 })
      }

      Row() {
        Button('取消').layoutWeight(1).height(42)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.showForm = false; })
        Button(this.editingId.length > 0 ? '保存修改' : '保存到书架').layoutWeight(1).height(42)
          .margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveForm(); })
      }.width('100%').margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  progressPanel(book: BookItem) {
    Column() {
      Text('更新阅读进度').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      Text('总页数 ' + book.totalPages.toString() + ' 页，记下今天读到第几页。')
        .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })
      Row() {
        TextInput({ text: this.progressDraft, placeholder: '当前页' })
          .layoutWeight(1).height(40)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
          .onChange((value: string) => { this.progressDraft = this.digitsOnly(value); })
        Button('保存进度').height(40).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveProgress(book); })
        Button('取消').height(40).margin({ left: 8 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.progressBookId = ''; })
      }.width('100%').margin({ top: 10 })
    }
    .width('100%').padding(12).margin({ top: 10 })
    .backgroundColor(Theme.cardWarm).borderRadius(12)
  }

  @Builder
  excerptPanel(book: BookItem) {
    Column() {
      Row() {
        Text('书摘 / 读书笔记').fontSize(14).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text(this.store.excerptCount(book.id).toString() + ' 条').fontSize(11)
          .fontColor(Theme.secondaryText)
      }.width('100%')

      ForEach(this.store.excerptsOf(book.id), (item: ExcerptItem) => {
        Column() {
          Text(item.content).fontSize(13).fontColor(Theme.primaryText).width('100%')
          Row() {
            Text(item.pageNo > 0 ? '第 ' + item.pageNo.toString() + ' 页' : '未记页码')
              .fontSize(10).fontColor(Theme.secondaryText)
            Text(' · ' + BookStore.formatDate(item.createdAt)).fontSize(10)
              .fontColor(Theme.secondaryText)
            Blank()
            Text('删除').fontSize(11).fontColor(Theme.dangerInk)
              .padding({ left: 8, right: 4, top: 4, bottom: 4 })
              .onClick(() => { this.confirmDeleteExcerpt(item); })
          }.width('100%').margin({ top: 6 }).alignItems(VerticalAlign.Center)
        }
        .width('100%').padding(10).margin({ top: 8 })
        .backgroundColor(Theme.card).borderRadius(10)
      }, (item: ExcerptItem) => item.id + '-' + this.version.toString())

      if (this.store.excerptCount(book.id) === 0) {
        Text('这本书还没有摘录。读到喜欢的句子就记下来，之后能回看。')
          .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
      }

      TextArea({ text: this.excerptContent, placeholder: '摘录打动你的句子…（必填）' })
        .width('100%').height(80).margin({ top: 10 })
        .backgroundColor(Theme.card).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.excerptContent = value; })
      Row() {
        TextInput({ text: this.excerptPage, placeholder: '页码（选填）' })
          .layoutWeight(1).height(40)
          .backgroundColor(Theme.card).fontColor(Theme.primaryText)
          .onChange((value: string) => { this.excerptPage = this.digitsOnly(value); })
        Button('保存书摘').height(40).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveExcerpt(book); })
      }.width('100%').margin({ top: 8 })
    }
    .width('100%').padding(12).margin({ top: 10 })
    .backgroundColor(Theme.cardWarm).borderRadius(12)
  }

  @Builder
  bookCard(book: BookItem) {
    Column() {
      Row() {
        Text(book.title).fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
        Text(this.isFinished(book) ? '已读完' : '在读').fontSize(10)
          .fontColor(this.isFinished(book) ? Theme.successInk : Theme.accentInk)
          .backgroundColor(this.isFinished(book) ? Theme.successSoft : Theme.accentSoft)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8).margin({ left: 8 })
      }.width('100%').alignItems(VerticalAlign.Center)

      Text(this.bookMetaLine(book)).fontSize(11).fontColor(Theme.secondaryText)
        .width('100%').margin({ top: 5 })

      Progress({ value: this.progressPercent(book), total: 100, type: ProgressType.Linear })
        .color(this.isFinished(book) ? Theme.successInk : Theme.accent)
        .backgroundColor(Theme.divider)
        .width('100%').height(6).margin({ top: 8 })

      if (this.isFinished(book)) {
        Text('读完于 ' + BookStore.formatDate(book.finishedAt)).fontSize(10)
          .fontColor(Theme.successInk).width('100%').margin({ top: 5 })
      }

      Flex({ wrap: FlexWrap.Wrap }) {
        Text('更新进度').fontSize(11).fontColor(Theme.accentInk)
          .backgroundColor(Theme.accentSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(10).margin({ right: 8, top: 8 })
          .onClick(() => { this.openProgress(book); })
        Text('书摘 ' + this.store.excerptCount(book.id).toString()).fontSize(11)
          .fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
          .padding({ left: 10, right: 10, top: 6, bottom: 6 }).borderRadius(10)
          .margin({ right: 8, top: 8 })
          .onClick(() => { this.toggleExcerpts(book); })
        Text(this.isFinished(book) ? '改回在读' : '标记读完').fontSize(11)
          .fontColor(Theme.successInk).backgroundColor(Theme.successSoft)
          .padding({ left: 10, right: 10, top: 6, bottom: 6 }).borderRadius(10)
          .margin({ right: 8, top: 8 })
          .onClick(() => { this.toggleFinished(book); })
        Text('编辑').fontSize(11).fontColor(Theme.secondaryText)
          .backgroundColor(Theme.cardWarm).padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(10).margin({ right: 8, top: 8 })
          .onClick(() => { this.openEdit(book); })
        Text('删除').fontSize(11).fontColor(Theme.dangerInk)
          .backgroundColor(Theme.dangerSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(10).margin({ top: 8 })
          .onClick(() => { this.confirmDeleteBook(book); })
      }.width('100%')

      if (this.progressBookId === book.id) {
        this.progressPanel(book)
      }
      if (this.excerptBookId === book.id) {
        this.excerptPanel(book)
      }
    }
    .width('100%').padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .border({ width: 1, color: Theme.divider })
  }

  @Builder
  emptyState() {
    Column() {
      Text('书架还是空的').fontSize(18).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('先记下正在读的一本，之后每读一点就更新页码；喜欢的句子可以存成书摘，随时回看。')
        .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 }).width('100%')
      Row() {
        Button('添加第一本书').layoutWeight(1).height(42)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.openCreate(); })
        Button('用示例开始').layoutWeight(1).height(42).margin({ left: 10 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
          .onClick(() => { this.startDemoBook(); })
      }.width('100%').margin({ top: 14 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(20).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  build() {
    Column() {
      if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Theme.accent)
          Text('正在读取本地书架').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.header()
            this.filterChips()

            if (this.showForm) {
              this.formPanel()
            }

            if (this.filteredBooks().length === 0) {
              this.emptyState()
            } else {
              ForEach(this.filteredBooks(), (book: BookItem) => {
                this.bookCard(book)
              }, (book: BookItem) => book.id + '-' + book.status + '-' + book.currentPage.toString()
                + '-' + this.progressBookId + '-' + this.excerptBookId + '-' + this.version.toString())
            }

            Text('书籍、进度与书摘只保存在应用本地文件 book_data.json，不上传；'
              + '删除书籍会连同它的书摘一起删除。').fontSize(11).fontColor(Theme.secondaryText)
              .margin({ top: 16, bottom: 28 })
          }.width('100%').constraintSize({ maxWidth: 760 })
        }.width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%')
    .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    .backgroundColor(Theme.bg)
  }
}
