import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import {
  MistakeItem, QuestionStore, QUESTION_ALL, QUESTION_SUBJECTS
} from '../model/QuestionStore';

@Entry
@Component
struct MistakesPage {
  @State loading: boolean = true;
  @State mistakes: MistakeItem[] = [];
  @State subject: string = QUESTION_ALL;
  @State statusFilter: number = 0;
  @State expandedId: string = '';
  @State editingId: string = '';
  @State noteDraft: string = '';
  @State showAdd: boolean = false;
  @State addSubject: string = '数学';
  @State addContent: string = '';
  @State addAnswer: string = '';
  @State addNote: string = '';
  @State version: number = 0;

  private store: QuestionStore = QuestionStore.get();

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.store.init(context).then(() => {
      this.reload();
      this.loading = false;
    }).catch(() => {
      this.loading = false;
      this.toast('本地错题数据读取失败');
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private reload(): void {
    this.mistakes = this.store.getMistakes().slice(0);
    this.version = this.version + 1;
  }

  private filterSubjects(): string[] {
    const result: string[] = [];
    const fromData: string[] = this.store.mistakeSubjects();
    for (let i = 0; i < fromData.length; i++) {
      result.push(fromData[i]);
    }
    for (let i = 0; i < QUESTION_SUBJECTS.length; i++) {
      if (result.indexOf(QUESTION_SUBJECTS[i]) < 0) {
        result.push(QUESTION_SUBJECTS[i]);
      }
    }
    return result;
  }

  private filteredMistakes(): MistakeItem[] {
    const result: MistakeItem[] = [];
    for (let i = 0; i < this.mistakes.length; i++) {
      const item: MistakeItem = this.mistakes[i];
      if (this.subject !== QUESTION_ALL && item.subject !== this.subject) {
        continue;
      }
      if (this.statusFilter === 1 && item.mastered) {
        continue;
      }
      if (this.statusFilter === 2 && !item.mastered) {
        continue;
      }
      result.push(item);
    }
    return result;
  }

  private pendingCount(): number {
    let count: number = 0;
    for (let i = 0; i < this.mistakes.length; i++) {
      if (!this.mistakes[i].mastered) {
        count++;
      }
    }
    return count;
  }

  private masteredCount(): number {
    return this.mistakes.length - this.pendingCount();
  }

  private toggleExpand(id: string): void {
    this.expandedId = this.expandedId === id ? '' : id;
    this.editingId = '';
    this.noteDraft = '';
  }

  private startEditNote(item: MistakeItem): void {
    this.editingId = item.id;
    this.noteDraft = item.note;
  }

  private saveNote(item: MistakeItem): void {
    this.store.updateMistakeNote(item.id, this.noteDraft).then(() => {
      this.toast('笔记已保存');
      this.editingId = '';
      this.reload();
    });
  }

  private markMastered(item: MistakeItem): void {
    this.store.markMastered(item.id, !item.mastered).then(() => {
      this.toast(item.mastered ? '已标记为待复习' : '已标记掌握');
      this.reload();
    });
  }

  private reviewOne(item: MistakeItem): void {
    this.store.reviewMistake(item.id).then(() => {
      this.toast('已记录复习一次');
      this.reload();
    });
  }

  private deleteOne(item: MistakeItem): void {
    AlertDialog.show({
      title: '删除错题',
      message: '删除后不可恢复，确定删除这道错题吗？',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '删除', fontColor: Theme.dangerInk,
        action: () => {
          this.store.deleteMistake(item.id).then(() => {
            this.toast('已删除');
            this.reload();
          });
        }
      }
    });
  }

  private exportText(): void {
    const path: string = this.store.saveMistakesExport();
    if (path.length === 0) {
      this.toast('导出失败，请重试');
      return;
    }
    AlertDialog.show({
      title: '导出成功',
      message: '错题文本已保存到应用本地：\n' + path + '\n\n内容为纯文本，可复制或后续分享。',
      primaryButton: { value: '知道了', action: () => {} }
    });
  }

  private saveNewMistake(): void {
    if (this.addContent.trim().length === 0) {
      this.toast('请先填写错题内容');
      return;
    }
    this.store.addManualMistake(this.addSubject, this.addContent.trim(), this.addAnswer.trim(),
      this.addNote.trim()).then(() => {
        this.toast('已加入错题本');
        this.addContent = '';
        this.addAnswer = '';
        this.addNote = '';
        this.showAdd = false;
        this.reload();
      });
  }

  private isSubjectFilterActive(subject: string): boolean {
    return this.subject === subject;
  }

  @Builder
  header() {
    Row() {
      Button('←').width(40).height(40).fontSize(20)
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onClick(() => { router.back(); })
      Column() {
        Text('错题本').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('共 ' + this.mistakes.length.toString() + ' 道 · 待复习 ' + this.pendingCount().toString()
          + ' · 已掌握 ' + this.masteredCount().toString()).fontSize(11)
          .fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      Button('导出文本').height(34).fontSize(12)
        .backgroundColor(Theme.goldSoft).fontColor(Theme.goldInk)
        .onClick(() => { this.exportText(); })
    }.width('100%').alignItems(VerticalAlign.Center).padding({ top: 8, bottom: 10 })
  }

  @Builder
  chips() {
    Scroll() {
      Row() {
        Text('全部').fontSize(12)
          .fontColor(this.isSubjectFilterActive(QUESTION_ALL) ? Color.White : Theme.secondaryText)
          .backgroundColor(this.isSubjectFilterActive(QUESTION_ALL) ? Theme.accentInk : Theme.card)
          .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
          .onClick(() => { this.subject = QUESTION_ALL; })
        ForEach(this.filterSubjects(), (subject: string) => {
          Text(subject).fontSize(12)
            .fontColor(this.isSubjectFilterActive(subject) ? Color.White : Theme.secondaryText)
            .backgroundColor(this.isSubjectFilterActive(subject) ? Theme.accentInk : Theme.card)
            .padding({ left: 12, right: 12, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
            .onClick(() => { this.subject = subject; })
        }, (subject: string) => subject)
      }
    }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
  }

  @Builder
  statusChips() {
    Row() {
      Text('全部状态').fontSize(12)
        .fontColor(this.statusFilter === 0 ? Color.White : Theme.secondaryText)
        .backgroundColor(this.statusFilter === 0 ? Theme.goldInk : Theme.card)
        .padding({ left: 10, right: 10, top: 6, bottom: 6 }).borderRadius(12).margin({ right: 8 })
        .onClick(() => { this.statusFilter = 0; })
      Text('待复习').fontSize(12)
        .fontColor(this.statusFilter === 1 ? Color.White : Theme.secondaryText)
        .backgroundColor(this.statusFilter === 1 ? Theme.warningInk : Theme.card)
        .padding({ left: 10, right: 10, top: 6, bottom: 6 }).borderRadius(12).margin({ right: 8 })
        .onClick(() => { this.statusFilter = 1; })
      Text('已掌握').fontSize(12)
        .fontColor(this.statusFilter === 2 ? Color.White : Theme.secondaryText)
        .backgroundColor(this.statusFilter === 2 ? Theme.successInk : Theme.card)
        .padding({ left: 10, right: 10, top: 6, bottom: 6 }).borderRadius(12)
        .onClick(() => { this.statusFilter = 2; })
    }.width('100%').margin({ top: 8 })
  }

  @Builder
  mistakeCard(item: MistakeItem) {
    Column() {
      Row() {
        Text(item.subject).fontSize(10).fontColor(Theme.accentInk)
          .backgroundColor(Theme.accentSoft).padding({ left: 7, right: 7, top: 3, bottom: 3 }).borderRadius(8)
        Text(item.mastered ? '已掌握' : '待复习').fontSize(10)
          .fontColor(item.mastered ? Theme.successInk : Theme.warningInk)
          .backgroundColor(item.mastered ? Theme.successSoft : Theme.warningSoft)
          .padding({ left: 7, right: 7, top: 3, bottom: 3 }).borderRadius(8).margin({ left: 6 })
        Blank()
        Text('错 ' + item.wrongCount.toString() + ' 次').fontSize(10).fontColor(Theme.secondaryText)
      }.width('100%')

      Text(item.content).fontSize(15).fontColor(Theme.primaryText).margin({ top: 10 })
        .width('100%')
        .maxLines(this.expandedId === item.id ? 20 : 2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })

      Row() {
        Text('最近复习：' + QuestionStore.formatTime(item.reviewAt)).fontSize(10)
          .fontColor(Theme.secondaryText)
        Blank()
        Text(this.expandedId === item.id ? '收起' : '详情').fontSize(11).fontColor(Theme.accentInk)
      }.width('100%').margin({ top: 8 })

      if (this.expandedId === item.id) {
        Column() {
          Divider().color(Theme.divider).margin({ top: 4, bottom: 12 })
          Text('答案').fontSize(11).fontColor(Theme.secondaryText)
          Text(item.answer.length > 0 ? item.answer : '（未填写）').fontSize(14)
            .fontColor(Theme.successInk).width('100%').margin({ top: 4 })

          if (this.editingId === item.id) {
            TextArea({ text: this.noteDraft, placeholder: '写点笔记，比如错因、知识点…' })
              .width('100%').height(96).margin({ top: 10 })
              .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
              .onChange((value: string) => { this.noteDraft = value; })
            Row() {
              Button('取消').layoutWeight(1).height(40)
                .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
                .onClick(() => { this.editingId = ''; })
              Button('保存笔记').layoutWeight(1).height(40).margin({ left: 10 })
                .backgroundColor(Theme.accentInk).fontColor(Color.White)
                .onClick(() => { this.saveNote(item); })
            }.width('100%').margin({ top: 8 })
          } else {
            Text('笔记').fontSize(11).fontColor(Theme.secondaryText).margin({ top: 10 })
            Text(item.note.length > 0 ? item.note : '（还没有笔记）').fontSize(13)
              .fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })
            Row() {
              Button('编辑笔记').layoutWeight(1).height(40)
                .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
                .onClick(() => { this.startEditNote(item); })
              Button(item.mastered ? '标记待复习' : '标记掌握').layoutWeight(1).height(40).margin({ left: 10 })
                .backgroundColor(Theme.successSoft).fontColor(Theme.successInk)
                .onClick(() => { this.markMastered(item); })
            }.width('100%').margin({ top: 10 })
            Row() {
              Button('复习一次').layoutWeight(1).height(40)
                .backgroundColor(Theme.accentSoft).fontColor(Theme.accentInk)
                .onClick(() => { this.reviewOne(item); })
              Button('删除').layoutWeight(1).height(40).margin({ left: 10 })
                .backgroundColor(Theme.dangerSoft).fontColor(Theme.dangerInk)
                .onClick(() => { this.deleteOne(item); })
            }.width('100%').margin({ top: 8 })
          }
        }.width('100%').alignItems(HorizontalAlign.Start)
      }
    }
    .width('100%').padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .border({ width: this.expandedId === item.id ? 1 : 0, color: Theme.divider })
    .onClick(() => { this.toggleExpand(item.id); })
  }

  @Builder
  addForm() {
    Column() {
      Text('手动添加错题').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .width('100%')
      Text('手写内容也可以，答案和笔记选填。').fontSize(11).fontColor(Theme.secondaryText)
        .width('100%').margin({ top: 4 })
      Row() {
        ForEach(QUESTION_SUBJECTS, (subject: string) => {
          Text(subject).fontSize(11)
            .fontColor(this.addSubject === subject ? Color.White : Theme.secondaryText)
            .backgroundColor(this.addSubject === subject ? Theme.accentInk : Theme.cardWarm)
            .padding({ left: 10, right: 10, top: 5, bottom: 5 }).borderRadius(10).margin({ right: 8, top: 8 })
            .onClick(() => { this.addSubject = subject; })
        }, (subject: string) => subject)
      }.width('100%')
      TextArea({ text: this.addContent, placeholder: '错题内容 / 题干' })
        .width('100%').height(80).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.addContent = value; })
      TextArea({ text: this.addAnswer, placeholder: '正确答案（选填）' })
        .width('100%').height(60).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.addAnswer = value; })
      TextArea({ text: this.addNote, placeholder: '笔记 / 错因（选填）' })
        .width('100%').height(60).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.addNote = value; })
      Row() {
        Button('取消').layoutWeight(1).height(42)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.showAdd = false; })
        Button('保存到错题本').layoutWeight(1).height(42).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveNewMistake(); })
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
          Text('正在读取本地错题').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.header()
            this.chips()
            this.statusChips()

            Row() {
              Button(this.showAdd ? '收起手动添加' : '手动添加错题').layoutWeight(1).height(42)
                .backgroundColor(Theme.card).fontColor(Theme.accentInk)
                .onClick(() => { this.showAdd = !this.showAdd; })
            }.width('100%').margin({ top: 12 })

            if (this.showAdd) {
              this.addForm()
            }

            if (this.filteredMistakes().length === 0) {
              Column() {
                Text('这个筛选下还没有错题').fontSize(17).fontWeight(FontWeight.Medium)
                  .fontColor(Theme.primaryText)
                Text('练习页答错的题会自动收进来，也可以手动添加或从“拍照/粘贴导入”页导入。')
                  .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
              }.width('100%').padding(24).margin({ top: 12 })
              .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
            } else {
              ForEach(this.filteredMistakes(), (item: MistakeItem) => {
                this.mistakeCard(item)
              }, (item: MistakeItem) => item.id + '-' + item.mastered.toString() + '-'
                + item.reviewAt.toString() + '-' + this.version.toString())
            }

            Text('错题数据保存在应用本地文件，不上传；导出为纯文本保存到 filesDir。').fontSize(11)
              .fontColor(Theme.secondaryText).margin({ top: 16, bottom: 28 })
          }.width('100%').constraintSize({ maxWidth: 760 })
        }.width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%')
    .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    .backgroundColor(Theme.bg)
  }
}
