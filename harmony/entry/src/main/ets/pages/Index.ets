import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import { Theme } from '../common/Theme';
import { SAMPLE_WORDS, WordItem } from '../model/Words';
import { StudyStore } from '../model/Store';
import { SignInCell, SignInDay, SignInMonth, SignInStats, SignInSummary } from '../model/SignIn';

@Entry
@Component
struct Index {
  @State currentTab: number = 0;
  @State summary: SignInSummary = SignInStats.emptySummary();
  @State mastered: string[] = [];
  @State masteredCount: number = 0;
  @State wordIndex: number = 0;
  @State wordRevealed: boolean = false;
  @State monthOffset: number = 0;
  @State viewVersion: number = 0;
  @State loading: boolean = true;

  private store: StudyStore = StudyStore.get();

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.store.init(context).then(() => {
      this.reload();
    });
  }

  async reload(): Promise<void> {
    const records = await this.store.loadSignIns();
    this.summary = SignInStats.summary(records, SignInStats.today());
    this.mastered = await this.store.loadMastered();
    this.masteredCount = this.mastered.length;
    this.loading = false;
    this.viewVersion = this.viewVersion + 1;
  }

  async signInToday(): Promise<void> {
    const added = await this.store.signIn(SignInStats.today());
    if (added) {
      promptAction.showToast({ message: '签到成功，今天也在一起进步' });
    }
    await this.reload();
  }

  async makeUp(date: string): Promise<void> {
    const added = await this.store.makeUp(date, SignInStats.today());
    if (added) {
      promptAction.showToast({ message: '补签成功' });
    } else {
      promptAction.showToast({ message: '本月补签次数已用完，或日期不在可补范围' });
    }
    await this.reload();
  }

  async undoMakeUp(date: string): Promise<void> {
    const removed = await this.store.undoMakeUp(date, SignInStats.today());
    if (removed) {
      promptAction.showToast({ message: '已撤销补签' });
    }
    await this.reload();
  }

  async markMastered(word: string): Promise<void> {
    this.mastered = await this.store.addMastered(word);
    this.wordIndex++;
    this.wordRevealed = false;
    await this.reload();
  }

  private currentMonthGrid(): SignInMonth {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    const signed = new Set<string>();
    const makeup = new Set<string>();
    for (let i = 0; i < this.summary.signedDays.length; i++) {
      signed.add(this.summary.signedDays[i]);
    }
    for (let i = 0; i < this.summary.makeupDays.length; i++) {
      makeup.add(this.summary.makeupDays[i]);
    }
    return SignInStats.monthGrid(target.getFullYear(), target.getMonth() + 1, signed, makeup, SignInStats.today());
  }

  private monthLabel(): string {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    return target.getFullYear().toString() + ' 年 ' + (target.getMonth() + 1).toString() + ' 月';
  }

  private monthSignedCount(): number {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    const month = target.getFullYear().toString() + '-' + (target.getMonth() + 1 < 10 ? '0' : '') + (target.getMonth() + 1).toString();
    return SignInStats.monthSigned(month, this.summary.signedDays);
  }

  private monthDays(): number {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    if (this.monthOffset === 0) {
      return now.getDate();
    }
    return new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate();
  }

  private monthPercent(): number {
    const days = this.monthDays();
    return days <= 0 ? 0 : Math.floor(this.monthSignedCount() * 100 / days);
  }

  private weekDotColor(day: SignInDay): string {
    if (day.makeup) {
      return Theme.gold;
    }
    if (day.signed) {
      return Theme.accent;
    }
    if (day.date === SignInStats.today()) {
      return Theme.accentSoft;
    }
    return Theme.divider;
  }

  private currentWord(): WordItem {
    const pending: WordItem[] = [];
    for (let i = 0; i < SAMPLE_WORDS.length; i++) {
      if (this.mastered.indexOf(SAMPLE_WORDS[i].word) < 0) {
        pending.push(SAMPLE_WORDS[i]);
      }
    }
    if (pending.length === 0) {
      return { word: '', phonetic: '', meaning: '' };
    }
    const index = this.wordIndex % pending.length;
    return pending[index];
  }

  private pendingWordCount(): number {
    return SAMPLE_WORDS.length - this.mastered.length;
  }

  @Builder
  homePage() {
    Scroll() {
      Column() {
        Row() {
          Column() {
            Text('学习').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            Text('HARMONY 原生版').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 2 })
          }.alignItems(HorizontalAlign.Start)
          Blank()
          Text(this.summary.signedToday ? '今日已签到' : '今日未签到')
            .fontSize(12).fontColor(this.summary.signedToday ? Theme.successInk : Theme.secondaryText)
            .backgroundColor(this.summary.signedToday ? Theme.successSoft : Theme.card)
            .padding({ left: 10, right: 10, top: 6, bottom: 6 })
            .borderRadius(14)
        }.width('100%').padding({ top: 6, bottom: 14 })

        Column() {
          Text('今日待办').fontSize(13).fontColor(Theme.secondaryText)
          Text('0 / 2').fontSize(52).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
          Text('先把本地词库背起来，断网也能学').fontSize(13).fontColor(Theme.secondaryText)
          Button('去背单词')
            .width('100%').height(52).margin({ top: 16 })
            .backgroundColor(Theme.accentInk).fontColor(Color.White)
            .onClick(() => { this.currentTab = 2; })
        }
        .width('100%').alignItems(HorizontalAlign.Start)
        .padding(20).borderRadius(Theme.cardRadius)
        .linearGradient({ angle: 135, colors: [[Theme.accentSoft, 0.0], [Theme.card, 0.72]] })

        Row() {
          ForEach(this.homeStats(), (item: string, index: number) => {
            this.metric(item.split('|')[0], item.split('|')[1])
            if (index < 2) {
              Divider().vertical(true).height(34).color(Theme.divider)
            }
          }, (item: string, index: number) => index.toString() + '-' + item + '-' + this.viewVersion.toString())
        }
        .width('100%').padding({ top: 16, bottom: 16 }).margin({ top: 12 })
        .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

        this.signInCard()
        this.entryCard('题库练习', '按学科刷题 · 答错自动入错题本', Theme.successSoft, Theme.successInk)
        this.entryCard('拍照识别', 'OCR 端侧识别正在做 HarmonyOS 适配', Theme.warningSoft, Theme.warningInk)
        Text('v1.0.0-harmony · 本地数据，不上传账号')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 18, bottom: 28 })
      }
      .width('100%').padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .backgroundColor(Theme.bg).width('100%').height('100%')
    .id('home-' + this.viewVersion.toString())
  }

  @Builder
  metric(label: string, value: string) {
    Column() {
      Text(label).fontSize(12).fontColor(Theme.secondaryText)
      Text(value).fontSize(24).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText).margin({ top: 4 })
    }.layoutWeight(1)
  }

  private homeStats(): string[] {
    return [
      '今日待复习|0',
      '已掌握|' + this.masteredCount.toString(),
      '签到天数|' + this.summary.totalDays.toString()
    ];
  }

  @Builder
  signInCard() {
    Row() {
      Text(this.summary.signedToday ? '✓' : '○')
        .fontSize(24).fontColor(this.summary.signedToday ? Theme.goldInk : Theme.accentInk)
        .width(44).height(44).textAlign(TextAlign.Center)
        .backgroundColor(this.summary.signedToday ? Theme.goldSoft : Theme.accentSoft)
        .borderRadius(22)
      Column() {
        Row() {
          Text('每日签到').fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          if (this.summary.longestStreak >= 3) {
            Text(this.milestonesBadge()).fontSize(11).fontColor(Theme.goldInk)
              .backgroundColor(Theme.goldSoft).padding({ left: 8, right: 8, top: 3, bottom: 3 })
              .borderRadius(10).margin({ left: 6 })
          }
        }.alignItems(VerticalAlign.Center)
        Text(this.summary.signedToday ? '已连续签到 ' + this.summary.currentStreak.toString() + ' 天' : '每天来一下，积少成多')
          .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
        Row() {
          Text('本周 ' + this.summary.weekSigned.toString() + '/7').fontSize(11).fontColor(Theme.secondaryText)
          Row() {
            ForEach(this.summary.weekDays, (day: SignInDay) => {
              Circle().width(6).height(6).fill(this.weekDotColor(day)).margin({ left: 3, right: 3 })
            }, (day: SignInDay) => day.date)
          }.margin({ left: 8 })
        }.margin({ top: 7 }).alignItems(VerticalAlign.Center)
      }.layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 12 })
      Button(this.summary.signedToday ? '已签到' : '签到')
        .height(36).fontSize(13)
        .backgroundColor(this.summary.signedToday ? Theme.successSoft : Theme.accent)
        .fontColor(this.summary.signedToday ? Theme.successInk : Color.White)
        .enabled(!this.summary.signedToday)
        .onClick(() => { this.signInToday(); })
    }
    .width('100%').padding(16).margin({ top: 14 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .onClick(() => { this.currentTab = 1; })
  }

  @Builder
  entryCard(title: string, caption: string, soft: string, ink: string) {
    Row() {
      Text(title.substring(0, 1)).fontSize(18).fontColor(ink).width(42).height(42).textAlign(TextAlign.Center)
        .backgroundColor(soft).borderRadius(21)
      Column() {
        Text(title).fontSize(16).fontColor(Theme.primaryText).fontWeight(FontWeight.Medium)
        Text(caption).fontSize(12).fontColor(Theme.secondaryText).margin({ top: 3 })
      }.alignItems(HorizontalAlign.Start).margin({ left: 12 }).layoutWeight(1)
      Text('›').fontSize(24).fontColor(Theme.secondaryText)
    }
    .width('100%').padding(16).margin({ top: 12 }).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  private milestonesBadge(): string {
    const reached = SignInStats.milestonesReached(this.summary.longestStreak);
    if (reached.length === 0) {
      return '';
    }
    return reached[reached.length - 1].toString() + ' 天';
  }

  @Builder
  signInPage() {
    Scroll() {
      Column() {
        Row() {
          Column() {
            Text('签到').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
            Text('连续、补签、里程碑都在本机').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 2 })
          }.alignItems(HorizontalAlign.Start)
          Blank()
          Text('累计 ' + this.summary.totalDays.toString() + ' 天').fontSize(12).fontColor(Theme.goldInk)
        }.width('100%').padding({ top: 6, bottom: 12 })

        Row() {
          ForEach(this.statItems(), (item: string, index: number) => {
            this.statBox(item.split('|')[0], item.split('|')[1])
          }, (item: string, index: number) => index.toString() + '-' + item + '-' + this.viewVersion.toString())
        }.width('100%').padding(10).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

        Row() {
          Text('里程碑').fontSize(11).fontColor(Theme.secondaryText)
          ForEach(SignInStats.MILESTONES, (milestone: number) => {
            Text(milestone.toString() + '天').fontSize(11)
              .fontColor(this.summary.longestStreak >= milestone ? Theme.goldInk : Theme.secondaryText)
              .backgroundColor(this.summary.longestStreak >= milestone ? Theme.goldSoft : Theme.divider)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 }).borderRadius(10).margin({ left: 5 })
          }, (milestone: number) => milestone.toString())
        }.width('100%').alignItems(VerticalAlign.Center).margin({ top: 12 })

        Row() {
          Text('‹').fontSize(28).fontColor(this.monthOffset < 0 ? Theme.accentInk : Theme.divider)
            .width(44).height(44).textAlign(TextAlign.Center)
            .onClick(() => { if (this.monthOffset < 0) { this.monthOffset++; } })
          Text(this.monthLabel()).fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText).layoutWeight(1).textAlign(TextAlign.Center)
          Text('›').fontSize(28).fontColor(this.monthOffset < 0 ? Theme.accentInk : Theme.divider)
            .width(44).height(44).textAlign(TextAlign.Center)
            .onClick(() => { if (this.monthOffset < 0) { this.monthOffset--; } })
        }.width('100%').margin({ top: 12 })

        Text('本月签到 ' + this.monthSignedCount().toString() + ' / ' + this.monthDays().toString() + ' 天（' + this.monthPercent().toString() + '%）')
          .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 4 })

        Row() {
          ForEach(['一', '二', '三', '四', '五', '六', '日'], (label: string) => {
            Text(label).fontSize(11).fontColor(Theme.secondaryText).layoutWeight(1).textAlign(TextAlign.Center)
          }, (label: string) => label)
        }.width('100%').margin({ top: 10, bottom: 6 })

        Grid() {
          ForEach(this.currentMonthGrid().cells, (cell: SignInCell, index: number) => {
            GridItem() {
              this.dayCell(cell)
            }
          }, (cell: SignInCell, index: number) => index.toString() + '-' + this.viewVersion.toString())
        }
        .columnsTemplate('1fr 1fr 1fr 1fr 1fr 1fr 1fr')
        .rowsGap(6).columnsGap(6).height(300).width('100%')

        Text('本月补签 ' + SignInStats.makeupUsed(this.currentMonthKey(), this.summary.makeupDays).toString() + ' / 3 次')
          .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
        Text('补签只允许最近 3 天；每个月最多 3 次，点已补签格子可以撤销。')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 4, bottom: 24 })
      }.width('100%').padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }.backgroundColor(Theme.bg).width('100%').height('100%')
    .id('signin-' + this.viewVersion.toString())
  }

  private currentMonthKey(): string {
    const now = new Date();
    const target = new Date(now.getFullYear(), now.getMonth() + this.monthOffset, 1);
    const month = target.getMonth() + 1;
    return target.getFullYear().toString() + '-' + (month < 10 ? '0' : '') + month.toString();
  }

  private statItems(): string[] {
    return [
      '连续|' + this.summary.currentStreak.toString(),
      '最长|' + this.summary.longestStreak.toString(),
      '本月|' + this.monthSignedCount().toString() + '/' + this.monthDays().toString()
    ];
  }

  @Builder
  statBox(label: string, value: string) {
    Column() {
      Text(value).fontSize(22).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(label).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
    }.layoutWeight(1)
  }

  @Builder
  dayCell(cell: SignInCell) {
    if (cell.blank) {
      Text('').height(38)
    } else {
      Column() {
        Text(cell.day.toString()).fontSize(13)
          .fontColor(cell.makeup ? Theme.goldInk : (cell.signed ? Theme.accentInk : (cell.today ? Theme.accentInk : Theme.primaryText)))
        if (cell.signed) {
          Circle().width(4).height(4).fill(cell.makeup ? Theme.gold : Theme.accent).margin({ top: 3 })
        }
      }
      .width('100%').height(38)
      .justifyContent(FlexAlign.Center)
      .backgroundColor(cell.makeup ? Theme.goldSoft : (cell.signed ? Theme.accentSoft : Color.Transparent))
      .borderRadius(19)
      .border({ width: cell.today ? 1.5 : (this.isMakeUpEligible(cell) ? 1 : 0), color: Theme.accent })
      .onClick(() => { this.onDayTap(cell); })
    }
  }

  private isMakeUpEligible(cell: SignInCell): boolean {
    if (cell.blank || cell.signed || !cell.date) {
      return false;
    }
    if (this.summary.makeupDays.indexOf(cell.date) >= 0) {
      return false;
    }
    return SignInStats.canMakeUp(cell.date, SignInStats.today(), this.summary.makeupDays);
  }

  private onDayTap(cell: SignInCell): void {
    if (cell.blank || !cell.date) {
      return;
    }
    if (cell.makeup && SignInStats.canUndo(cell.date, SignInStats.today(), this.summary.makeupDays)) {
      AlertDialog.show({
        title: '撤销补签',
        message: '撤销 ' + cell.date + ' 的补签？连续签到和本月统计会相应减少。',
        primaryButton: { value: '取消', action: () => {} },
        secondaryButton: {
          value: '撤销', fontColor: Theme.warningInk,
          action: () => { this.undoMakeUp(cell.date); }
        }
      });
      return;
    }
    if (this.isMakeUpEligible(cell)) {
      AlertDialog.show({
        title: '补签',
        message: '补签 ' + cell.date + '？补签会计入连续签到和本月统计，并在日历里标出。',
        primaryButton: { value: '取消', action: () => {} },
        secondaryButton: {
          value: '补签', fontColor: Theme.accentInk,
          action: () => { this.makeUp(cell.date); }
        }
      });
    }
  }

  @Builder
  wordsPage() {
    Scroll() {
      Column() {
        Text('单词').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText).width('100%').padding({ top: 6 })
        Text('内置 ' + SAMPLE_WORDS.length.toString() + ' 个示例词 · 已掌握 ' + this.mastered.length.toString())
          .fontSize(12).fontColor(Theme.secondaryText).width('100%').margin({ top: 4, bottom: 16 })

        if (this.pendingWordCount() === 0) {
          Column() {
            Text('这一轮词都记住了').fontSize(22).fontColor(Theme.primaryText)
            Text('可以重置示例词，再练一轮。').fontSize(13).fontColor(Theme.secondaryText).margin({ top: 8 })
            Button('重置示例词').margin({ top: 18 }).backgroundColor(Theme.accentInk)
              .onClick(() => { this.store.resetMastered().then(() => { this.wordIndex = 0; this.reload(); }); })
          }.width('100%').padding(30).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
        } else {
          Column() {
            Text('还剩 ' + this.pendingWordCount().toString() + ' 个').fontSize(12).fontColor(Theme.secondaryText)
            Text(this.currentWord().word).fontSize(38).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText).margin({ top: 24 })
            Text(this.currentWord().phonetic).fontSize(15).fontColor(Theme.secondaryText).margin({ top: 6 })
            if (this.wordRevealed) {
              Text(this.currentWord().meaning).fontSize(18).fontColor(Theme.accentInk).margin({ top: 20 })
            } else {
              Text('点击卡片查看释义').fontSize(14).fontColor(Theme.secondaryText).margin({ top: 24 })
            }
          }
          .width('100%').height(330).justifyContent(FlexAlign.Center)
          .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
          .onClick(() => { this.wordRevealed = true; })

          Row() {
            Button('再学一次')
              .layoutWeight(1).height(50).backgroundColor(Theme.warningSoft).fontColor(Theme.warningInk)
              .onClick(() => { this.wordIndex++; this.wordRevealed = false; })
            Button('记住了')
              .layoutWeight(1).height(50).margin({ left: 12 }).backgroundColor(Theme.accentInk)
              .onClick(() => { this.markMastered(this.currentWord().word); })
          }.margin({ top: 18 })
        }
        Text('HarmonyOS 版暂用 24 个示例词做流程验证，词库导入与 FSRS 排期在后续版本接入。')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 20, bottom: 28 })
      }.width('100%').padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }.backgroundColor(Theme.bg).width('100%').height('100%')
  }

  @Builder
  profilePage() {
    Scroll() {
      Column() {
        Text('我的').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText).width('100%').padding({ top: 6 })
        Column() {
          Text('StudyKit · HarmonyOS').fontSize(20).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Text('原生 ArkTS / ArkUI 版 · v1.0.0').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 6 })
          Text('所有数据保存在本机 Preferences，不上传、不同步、没有账号。').fontSize(13).fontColor(Theme.secondaryText).margin({ top: 14 })
        }.width('100%').padding(20).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

        Column() {
          Text('这一版已实现').fontSize(16).fontColor(Theme.primaryText).width('100%')
          Text('· 首页签到卡与本周进度').fontSize(13).fontColor(Theme.secondaryText).width('100%').margin({ top: 10 })
          Text('· 整月签到日历 / 补签 / 撤销 / 月度统计').fontSize(13).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
          Text('· 里程碑徽章与本地持久化').fontSize(13).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
          Text('· 24 词示例复习流程').fontSize(13).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
          Text('· 暖纸感原生布局与平板上自适应').fontSize(13).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
        }.width('100%').padding(20).margin({ top: 12 }).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

        Column() {
          Text('后续接入').fontSize(16).fontColor(Theme.primaryText).width('100%')
          Text('词库导入 / FSRS 排期 / 题库与错题 / OCR 拍照识别 / 数据导出。')
            .fontSize(13).fontColor(Theme.secondaryText).width('100%').margin({ top: 10 })
        }.width('100%').padding(20).margin({ top: 12 }).backgroundColor(Theme.card).borderRadius(Theme.cardRadius)

        Text('Android v3.21 的功能会按模块逐步迁到 ArkTS；当前是原生 MVP，不是 Android 端的等价镜像。')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 18, bottom: 28 })
      }.width('100%').padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }.backgroundColor(Theme.bg).width('100%').height('100%')
  }

  build() {
    Tabs({ barPosition: BarPosition.End, index: this.currentTab }) {
      TabContent() { this.homePage() }.tabBar('学习')
      TabContent() { this.signInPage() }.tabBar('签到')
      TabContent() { this.wordsPage() }.tabBar('单词')
      TabContent() { this.profilePage() }.tabBar('我的')
    }
    .barMode(BarMode.Fixed)
    .barBackgroundColor(Theme.card)
    .barHeight(62)
    .onChange((index: number) => {
      this.currentTab = index;
      this.reload();
    })
    .width('100%').height('100%').backgroundColor(Theme.bg)
  }
}
