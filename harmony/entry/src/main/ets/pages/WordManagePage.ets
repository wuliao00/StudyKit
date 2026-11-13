import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import pasteboard from '@ohos.pasteboard';
import { Theme } from '../common/Theme';
import { WORD_BOOKS, WordBook, booksOf } from '../model/Words';
import {
  ImportSummary, USER_BOOK_CUSTOM, UserWord, UserWordDraft, UserWordResult, UserWordStore
} from '../model/UserWordStore';

/**
 * 自定义词库管理页（task-12 D）。
 *
 * 只做一件事：让用户把自己遇到的词收进来，并能搜、改、删、批量粘、导出去。
 * 入口不在这里改（Index.ets / main_pages.json 归 Lead 合并），本页自带 @Entry + back。
 *
 * 暖纸感与 Index.ets 同一套 Theme；数据只落 filesDir 的 studykit_user_words.json，
 * 不联网、不需要账号。空态/错误都给人话，不出现"功能未完善"。
 */

const MIN_IMPORT_HINT: string = '每行一条，格式：word|phonetic|meaning|example';
const MAX_EXPORT_PREVIEW: number = 3;

@Entry
@Component
struct WordManagePage {
  @State words: UserWord[] = [];
  @State keyword: string = '';
  @State loading: boolean = true;
  @State viewVersion: number = 0;
  @State message: string = '';
  @State messageIsError: boolean = false;

  @State editorOpen: boolean = false;
  @State editingId: string = '';
  @State draftWord: string = '';
  @State draftPhonetic: string = '';
  @State draftMeaning: string = '';
  @State draftExample: string = '';
  @State draftBook: string = USER_BOOK_CUSTOM;

  @State importOpen: boolean = false;
  @State importText: string = '';
  @State importResult: string = '';

  @State exportPath: string = '';
  @State pendingDeleteId: string = '';

  private store: UserWordStore = UserWordStore.get();
  private filesDir: string = '';

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.filesDir = context.filesDir;
    this.store.init(context).then(() => {
      this.reload();
    }).catch(() => {
      this.loading = false;
      this.showMessage('本地词库读取失败，已从空白词库开始', true);
      this.reload();
    });
  }

  // ---------- 数据 ----------

  private reload(): void {
    this.words = this.store.search(this.keyword);
    this.loading = false;
    this.viewVersion = this.viewVersion + 1;
  }

  private totalCount(): number {
    return this.store.count();
  }

  private toast(text: string): void {
    promptAction.showToast({ message: text });
  }

  private showMessage(text: string, isError: boolean): void {
    this.message = text;
    this.messageIsError = isError;
  }

  private bookLabel(bookId: string): string {
    if (bookId === USER_BOOK_CUSTOM || bookId.length === 0) {
      return '自建';
    }
    for (let i = 0; i < WORD_BOOKS.length; i++) {
      if (WORD_BOOKS[i].id === bookId) {
        return WORD_BOOKS[i].name;
      }
    }
    return bookId;
  }

  private goBack(): void {
    router.back();
  }

  // ---------- 编辑器 ----------

  private openAdd(): void {
    this.editorOpen = true;
    this.importOpen = false;
    this.pendingDeleteId = '';
    this.editingId = '';
    this.draftWord = '';
    this.draftPhonetic = '';
    this.draftMeaning = '';
    this.draftExample = '';
    this.draftBook = USER_BOOK_CUSTOM;
    this.showMessage('', false);
  }

  private openEdit(item: UserWord): void {
    this.editorOpen = true;
    this.importOpen = false;
    this.pendingDeleteId = '';
    this.editingId = item.id;
    this.draftWord = item.word;
    this.draftPhonetic = item.phonetic;
    this.draftMeaning = item.meaning;
    this.draftExample = item.example;
    this.draftBook = item.book;
    this.showMessage('', false);
  }

  private closeEditor(): void {
    this.editorOpen = false;
    this.editingId = '';
  }

  private currentDraft(): UserWordDraft {
    return {
      word: this.draftWord,
      phonetic: this.draftPhonetic,
      meaning: this.draftMeaning,
      example: this.draftExample,
      book: this.draftBook
    };
  }

  private saveDraft(): void {
    if (this.editingId.length > 0) {
      this.store.update(this.editingId, this.currentDraft()).then((result: UserWordResult) => {
        if (!result.ok) {
          this.showMessage(result.message, true);
          return;
        }
        this.closeEditor();
        this.reload();
        this.showMessage(result.message, false);
        this.toast(result.message);
      }).catch(() => {
        this.showMessage('保存失败：本地文件写入异常，请重试', true);
      });
      return;
    }
    this.store.add(this.currentDraft()).then((result: UserWordResult) => {
      if (!result.ok) {
        this.showMessage(result.message, true);
        return;
      }
      this.closeEditor();
      this.reload();
      this.showMessage(result.message, false);
      this.toast(result.message);
    }).catch(() => {
      this.showMessage('添加失败：本地文件写入异常，请重试', true);
    });
  }

  // ---------- 删除 ----------

  private askDelete(id: string): void {
    this.pendingDeleteId = id;
    this.editorOpen = false;
    this.importOpen = false;
  }

  private cancelDelete(): void {
    this.pendingDeleteId = '';
  }

  private confirmDelete(): void {
    const id: string = this.pendingDeleteId;
    if (id.length === 0) {
      return;
    }
    this.pendingDeleteId = '';
    this.store.remove(id).then((removed: boolean) => {
      if (removed) {
        this.reload();
        this.showMessage('已删除', false);
        this.toast('已删除');
      } else {
        this.showMessage('这条单词已经不在了，刷新后再试', true);
      }
    }).catch(() => {
      this.showMessage('删除失败：本地文件写入异常，请重试', true);
    });
  }

  // ---------- 批量导入 ----------

  private openImport(): void {
    this.importOpen = true;
    this.editorOpen = false;
    this.pendingDeleteId = '';
    this.importResult = '';
    this.showMessage('', false);
  }

  private closeImport(): void {
    this.importOpen = false;
    this.importText = '';
    this.importResult = '';
  }

  private runImport(): void {
    if (this.importText.trim().length === 0) {
      this.showMessage('先粘贴内容：每行一条，格式 word|phonetic|meaning|example', true);
      return;
    }
    this.store.importLines(this.importText, USER_BOOK_CUSTOM).then((summary: ImportSummary) => {
      this.importResult = '新增 ' + summary.added.toString() + ' 条 · 跳过重名 ' + summary.skipped.toString()
        + ' 条 · 格式不符 ' + summary.failed.toString() + ' 条（默认归入「自建」）';
      this.reload();
      if (summary.added > 0) {
        this.importText = '';
      }
      this.showMessage('批量导入完成', false);
      this.toast('导入完成：新增 ' + summary.added.toString() + ' 条');
    }).catch(() => {
      this.showMessage('导入失败：本地文件写入异常，请重试', true);
    });
  }

  // ---------- 导出 / 复制 ----------

  private exportToFile(): void {
    if (this.totalCount() === 0) {
      this.showMessage('还没有单词可导出，先添加或导入几条', true);
      return;
    }
    this.store.exportToFile().then((path: string) => {
      this.exportPath = path;
      this.showMessage('已导出到 filesDir：' + path, false);
      this.toast('已导出到 filesDir');
    }).catch(() => {
      this.showMessage('导出失败：本地文件写入异常，请重试', true);
    });
  }

  private copyAll(): void {
    if (this.totalCount() === 0) {
      this.showMessage('还没有单词可复制', true);
      return;
    }
    const text: string = this.store.exportText();
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      this.exportPath = '';
      this.showMessage('已复制全部 ' + this.totalCount().toString() + ' 条到剪贴板', false);
      this.toast('已复制到剪贴板');
    }).catch(() => {
      this.showMessage('复制失败，请改用「导出到文件」', true);
    });
  }

  private previewCount(): number {
    const total: number = this.words.length;
    return total > MAX_EXPORT_PREVIEW ? MAX_EXPORT_PREVIEW : total;
  }

  // ---------- 组件 ----------

  @Builder
  headerBar() {
    Row() {
      Text('‹')
        .fontSize(30).fontColor(Theme.accentInk).width(40).height(40).textAlign(TextAlign.Center)
        .onClick(() => { this.goBack(); })
      Column() {
        Text('自定义词库').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('本地 JSON · filesDir · 不上传、不需要账号').fontSize(11).fontColor(Theme.secondaryText)
          .margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
      Text(this.totalCount().toString() + ' 词')
        .fontSize(12).fontColor(Theme.accentInk)
        .backgroundColor(Theme.accentSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
        .borderRadius(12)
    }
    .width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  searchRow() {
    Row() {
      Text('⌕').fontSize(18).fontColor(Theme.secondaryText).width(24).textAlign(TextAlign.Center)
      TextInput({ text: this.keyword, placeholder: '搜索单词 / 音标 / 释义 / 例句 / 词书' })
        .layoutWeight(1).height(40).fontSize(14).backgroundColor(Color.Transparent)
        .onChange((value: string) => {
          this.keyword = value;
          this.reload();
        })
      if (this.keyword.length > 0) {
        Text('×').fontSize(18).fontColor(Theme.secondaryText).width(28).textAlign(TextAlign.Center)
          .onClick(() => {
            this.keyword = '';
            this.reload();
          })
      }
    }
    .width('100%').height(46).padding({ left: 10, right: 6 })
    .backgroundColor(Theme.card).borderRadius(14)
    .border({ width: 1, color: Theme.divider })
  }

  @Builder
  actionRow() {
    Flex({ wrap: FlexWrap.Wrap }) {
      Button('＋ 添加单词').height(38).fontSize(13)
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => { this.openAdd(); })
      Button('批量粘贴导入').height(38).fontSize(13).margin({ left: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
        .onClick(() => { this.openImport(); })
      Button('导出到文件').height(38).fontSize(13).margin({ left: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
        .onClick(() => { this.exportToFile(); })
      Button('复制全部').height(38).fontSize(13).margin({ left: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
        .onClick(() => { this.copyAll(); })
    }.width('100%').margin({ top: 12 })
  }

  @Builder
  messageBar() {
    Row() {
      Text(this.messageIsError ? '!' : '·')
        .fontSize(13).fontWeight(FontWeight.Bold)
        .fontColor(this.messageIsError ? Theme.dangerInk : Theme.accentInk)
        .width(22).textAlign(TextAlign.Center)
      Text(this.message)
        .fontSize(12)
        .fontColor(this.messageIsError ? Theme.dangerInk : Theme.primaryText)
        .layoutWeight(1)
    }
    .width('100%').padding({ left: 8, right: 10, top: 9, bottom: 9 }).margin({ top: 10 })
    .backgroundColor(this.messageIsError ? Theme.dangerSoft : Theme.accentSoft)
    .borderRadius(12)
  }

  @Builder
  bookChip(bookId: string, label: string) {
    Text(label)
      .fontSize(12)
      .fontColor(this.draftBook === bookId ? Color.White : Theme.secondaryText)
      .backgroundColor(this.draftBook === bookId ? Theme.accentInk : Theme.cardWarm)
      .padding({ left: 10, right: 10, top: 6, bottom: 6 })
      .borderRadius(12).margin({ right: 8, bottom: 6 })
      .onClick(() => { this.draftBook = bookId; })
  }

  @Builder
  editorCard() {
    Column() {
      Row() {
        Text(this.editingId.length > 0 ? '编辑单词' : '添加单词')
          .fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text('取消').fontSize(13).fontColor(Theme.secondaryText)
          .onClick(() => { this.closeEditor(); })
      }.width('100%').alignItems(VerticalAlign.Center)

      TextInput({ text: this.draftWord, placeholder: '单词（必填，如 abandon）' })
        .width('100%').height(44).fontSize(14).margin({ top: 12 })
        .backgroundColor(Theme.cardWarm).borderRadius(12)
        .onChange((value: string) => { this.draftWord = value; })
      TextInput({ text: this.draftPhonetic, placeholder: '音标（可选，如 /əˈbændən/）' })
        .width('100%').height(44).fontSize(14).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).borderRadius(12)
        .onChange((value: string) => { this.draftPhonetic = value; })
      TextInput({ text: this.draftMeaning, placeholder: '释义（必填，如 v. 放弃；抛弃）' })
        .width('100%').height(44).fontSize(14).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).borderRadius(12)
        .onChange((value: string) => { this.draftMeaning = value; })
      TextArea({ text: this.draftExample, placeholder: '例句（可选）' })
        .width('100%').height(76).fontSize(14).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).borderRadius(12)
        .onChange((value: string) => { this.draftExample = value; })

      Text('词书标签').fontSize(12).fontColor(Theme.secondaryText)
        .width('100%').margin({ top: 12, bottom: 6 })
      Flex({ wrap: FlexWrap.Wrap }) {
        this.bookChip(USER_BOOK_CUSTOM, '自建')
        ForEach(booksOf(), (book: WordBook) => {
          this.bookChip(book.id, book.name)
        }, (book: WordBook) => book.id)
      }.width('100%')

      Row() {
        Button('保存').layoutWeight(1).height(42).fontSize(14)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveDraft(); })
        Button('取消').layoutWeight(1).height(42).fontSize(14).margin({ left: 10 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.closeEditor(); })
      }.width('100%').margin({ top: 14 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .border({ width: 1, color: Theme.divider })
  }

  @Builder
  importCard() {
    Column() {
      Row() {
        Text('批量粘贴导入').fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text('取消').fontSize(13).fontColor(Theme.secondaryText)
          .onClick(() => { this.closeImport(); })
      }.width('100%').alignItems(VerticalAlign.Center)

      Text('每行一条：word|phonetic|meaning|example（也支持 word|meaning；第五列可写词书标签）')
        .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
      TextArea({ text: this.importText, placeholder: MIN_IMPORT_HINT })
        .width('100%').height(160).fontSize(13).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).borderRadius(12)
        .onChange((value: string) => { this.importText = value; })

      if (this.importResult.length > 0) {
        Text(this.importResult).fontSize(12).fontColor(Theme.accentInk).width('100%').margin({ top: 10 })
      }

      Row() {
        Button('导入').layoutWeight(1).height(42).fontSize(14)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.runImport(); })
        Button('关闭').layoutWeight(1).height(42).fontSize(14).margin({ left: 10 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.closeImport(); })
      }.width('100%').margin({ top: 14 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .border({ width: 1, color: Theme.divider })
  }

  @Builder
  deleteConfirmCard() {
    Column() {
      Text('删除这条单词？').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      Text('删除后不保留恢复入口；已导出过的文本不受影响。').fontSize(12)
        .fontColor(Theme.secondaryText).width('100%').margin({ top: 6 })
      Row() {
        Button('确认删除').layoutWeight(1).height(40).fontSize(13)
          .backgroundColor(Theme.dangerSoft).fontColor(Theme.dangerInk)
          .onClick(() => { this.confirmDelete(); })
        Button('取消').layoutWeight(1).height(40).fontSize(13).margin({ left: 10 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.cancelDelete(); })
      }.width('100%').margin({ top: 12 })
    }
    .width('100%').padding(16).margin({ top: 12 })
    .backgroundColor(Theme.dangerSoft).borderRadius(Theme.cardRadius)
  }

  @Builder
  wordCard(item: UserWord) {
    Column() {
      Row() {
        Column() {
          Row() {
            Text(item.word).fontSize(20).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            if (item.phonetic.length > 0) {
              Text(item.phonetic).fontSize(12).fontColor(Theme.secondaryText).margin({ left: 8 })
            }
          }.alignItems(VerticalAlign.Center)
          Text(item.meaning).fontSize(14).fontColor(Theme.primaryText).margin({ top: 5 })
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(this.bookLabel(item.book)).fontSize(10).fontColor(Theme.accentInk)
          .backgroundColor(Theme.accentSoft).padding({ left: 8, right: 8, top: 4, bottom: 4 })
          .borderRadius(9)
      }.width('100%').alignItems(VerticalAlign.Top)

      if (item.example.length > 0) {
        Text('例：' + item.example).fontSize(12).fontColor(Theme.secondaryText)
          .width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 7 })
      }

      Row() {
        Text('自定义词 · 已收进本地词库').fontSize(10).fontColor(Theme.secondaryText).layoutWeight(1)
        Text('编辑').fontSize(12).fontColor(Theme.accentInk).padding({ left: 10, right: 10, top: 5, bottom: 5 })
          .onClick(() => { this.openEdit(item); })
        Text('删除').fontSize(12).fontColor(Theme.dangerInk).padding({ left: 10, right: 10, top: 5, bottom: 5 })
          .onClick(() => { this.askDelete(item.id); })
      }.width('100%').margin({ top: 8 }).alignItems(VerticalAlign.Center)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  emptyState() {
    Column() {
      Text('＋').fontSize(30).fontColor(Theme.accentInk).width(64).height(64)
        .textAlign(TextAlign.Center).backgroundColor(Theme.accentSoft).borderRadius(32)
      Text('还没有自定义单词').fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .margin({ top: 14 })
      Text('把课本、阅读或听力里遇到的词收进来；支持批量粘贴，也可以整体导出成文本。')
        .fontSize(12).fontColor(Theme.secondaryText).textAlign(TextAlign.Center)
        .margin({ top: 8 }).constraintSize({ maxWidth: 320 })
      Button('添加第一个单词').height(40).fontSize(13).margin({ top: 16 })
        .backgroundColor(Theme.accentInk).fontColor(Color.White)
        .onClick(() => { this.openAdd(); })
      Text('也可以先「批量粘贴导入」十几条。').fontSize(11).fontColor(Theme.secondaryText)
        .margin({ top: 10 })
    }
    .width('100%').alignItems(HorizontalAlign.Center).padding({ top: 44, bottom: 36 })
  }

  @Builder
  exportTipCard() {
    Column() {
      Text('导出 / 复制').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      Text('导出文件写到应用 filesDir，可直接分享/备份；复制会把完整文本放进系统剪贴板。'
        + '两种都是本地操作，不联网。').fontSize(11).fontColor(Theme.secondaryText)
        .width('100%').margin({ top: 6 })
      if (this.exportPath.length > 0) {
        Text('最近导出：' + this.exportPath).fontSize(11).fontColor(Theme.accentInk)
          .width('100%').margin({ top: 8 })
      }
      if (this.words.length > 0) {
        Row() {
          Text('当前筛选 ' + this.words.length.toString() + ' 条，例如：').fontSize(10)
            .fontColor(Theme.secondaryText)
        }.width('100%').margin({ top: 8 })
        ForEach(this.words.slice(0, this.previewCount()), (item: UserWord) => {
          Text('· ' + item.word + (item.meaning.length > 0 ? ' — ' + item.meaning : ''))
            .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 3 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }, (item: UserWord) => 'export-' + item.id)
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 12 })
    .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)
  }

  @Builder
  footer() {
    Text('数据只保存在这台设备：filesDir/studykit_user_words.json · 不联网、不需要账号')
      .fontSize(11).fontColor(Theme.secondaryText).width('100%')
      .textAlign(TextAlign.Center).margin({ top: 20, bottom: 30 })
  }

  build() {
    Column() {
      this.headerBar()
      Scroll() {
        Column() {
          this.searchRow()
          this.actionRow()
          if (this.message.length > 0) {
            this.messageBar()
          }
          if (this.pendingDeleteId.length > 0) {
            this.deleteConfirmCard()
          }
          if (this.editorOpen) {
            this.editorCard()
          }
          if (this.importOpen) {
            this.importCard()
          }
          if (this.loading) {
            Text('正在读取本地词库…').fontSize(13).fontColor(Theme.secondaryText)
              .width('100%').textAlign(TextAlign.Center).margin({ top: 40 })
          } else if (this.words.length === 0) {
            if (this.keyword.length > 0) {
              Column() {
                Text('没有匹配「' + this.keyword + '」的单词').fontSize(15).fontColor(Theme.primaryText)
                Text('换个关键词，或者点上面的 × 清空搜索。').fontSize(12)
                  .fontColor(Theme.secondaryText).margin({ top: 6 })
              }
              .width('100%').padding({ top: 36, bottom: 24 }).alignItems(HorizontalAlign.Center)
            } else {
              this.emptyState()
            }
          } else {
            ForEach(this.words, (item: UserWord) => {
              this.wordCard(item)
            }, (item: UserWord) => item.id)
          }
          this.exportTipCard()
          this.footer()
        }
        .width('100%').constraintSize({ maxWidth: 760 })
        .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
      }
      .layoutWeight(1).width('100%').scrollBar(BarState.Auto)
      .align(Alignment.Top)
    }
    .width('100%').height('100%').backgroundColor(Theme.bg)
    .id('word-manage-' + this.viewVersion.toString())
  }
}
