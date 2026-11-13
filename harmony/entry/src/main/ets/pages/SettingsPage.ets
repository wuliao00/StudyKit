import router from '@ohos.router';
import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import promptAction from '@ohos.promptAction';
import picker from '@ohos.file.picker';
import { BusinessError } from '@ohos.base';
import { Theme } from '../common/Theme';
import { StudyStore } from '../model/Store';
import { PlanStore } from '../model/PlanStore';
import {
  BackupStore,
  BackupOutcome,
  DEFAULT_DAILY_GOAL,
  MAX_DAILY_GOAL,
  MIN_DAILY_GOAL,
  SettingsStore
} from '../model/BackupStore';

// 设置页：每日目标 / 本地提醒开关 / zip 备份与恢复 / 清空数据（二次确认）。
// 全部本机：读写 filesDir 下的 JSON，备份走系统文件选择器 + @ohos.zlib，不联网、不需要额外权限。

@Entry
@Component
struct SettingsPage {
  @State loading: boolean = true;
  @State dailyGoal: number = DEFAULT_DAILY_GOAL;
  @State reminderEnabled: boolean = false;
  @State busy: boolean = false;
  @State message: string = '';
  @State messageOk: boolean = true;
  @State dataFileLine: string = '还没有学习数据';
  @State plansFileLine: string = '还没有计划数据';
  @State prefsFileLine: string = '默认设置';
  @State planCount: number = 0;
  @State dataCards: number = 0;

  private store: StudyStore = StudyStore.get();
  private plans: PlanStore = PlanStore.get();
  private prefs: SettingsStore = SettingsStore.get();
  private context: common.UIAbilityContext | null = null;

  async aboutToAppear(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    this.context = ctx;
    try {
      await this.store.init(ctx);
      await this.plans.init(ctx);
      await this.prefs.init(ctx);
    } catch (e) {
      this.setMessage('读取本机设置失败，已回到默认值：' + JSON.stringify(e), false);
    }
    this.refresh();
    this.loading = false;
  }

  private toast(text: string): void {
    promptAction.showToast({ message: text });
  }

  private setMessage(text: string, ok: boolean): void {
    this.message = text;
    this.messageOk = ok;
  }

  private refresh(): void {
    this.dailyGoal = this.prefs.dailyGoal;
    this.reminderEnabled = this.prefs.reminderEnabled;
    this.planCount = this.plans.planCount();
    this.dataCards = this.store.cards.length;
    this.dataFileLine = this.fileLine('studykit_data.json', this.dataCards.toString() + ' 张词卡');
    this.plansFileLine = this.fileLine('studykit_plans.json', this.planCount.toString() + ' 个计划');
    this.prefsFileLine = this.reminderEnabled
      ? '每日 ' + this.dailyGoal.toString() + ' 词 · 本地提醒已开'
      : '每日 ' + this.dailyGoal.toString() + ' 词 · 本地提醒已关';
  }

  private fileLine(name: string, content: string): string {
    const ctx = this.context;
    if (ctx === null) {
      return name + '：未初始化';
    }
    try {
      const path: string = ctx.filesDir + '/' + name;
      if (!fs.accessSync(path)) {
        return name + '：' + content + '（尚未写入）';
      }
      const stat = fs.statSync(path);
      return name + '：' + content + ' · ' + BackupStore.mbText(stat.size);
    } catch (e) {
      return name + '：读取失败（不影响其它功能）';
    }
  }

  private contextOrNull(): common.UIAbilityContext | null {
    if (this.context === null) {
      this.setMessage('页面还没准备好，请返回重新进入设置。', false);
      return null;
    }
    return this.context as common.UIAbilityContext;
  }

  private async changeGoal(delta: number): Promise<void> {
    if (this.busy) {
      return;
    }
    const next: number = this.dailyGoal + delta;
    if (next < MIN_DAILY_GOAL || next > MAX_DAILY_GOAL) {
      return;
    }
    this.busy = true;
    try {
      await this.prefs.setDailyGoal(next);
      this.dailyGoal = this.prefs.dailyGoal;
      this.prefsFileLine = '每日 ' + this.dailyGoal.toString() + ' 词 · 已保存';
      this.toast('每日目标：' + this.dailyGoal.toString() + ' 词');
    } catch (e) {
      this.setMessage('保存每日目标失败，请重试。', false);
    } finally {
      this.busy = false;
    }
  }

  private async toggleReminder(enabled: boolean): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    try {
      await this.prefs.setReminderEnabled(enabled);
      this.reminderEnabled = this.prefs.reminderEnabled;
      this.toast(this.reminderEnabled ? '本地提醒已开' : '本地提醒已关');
    } catch (e) {
      this.setMessage('保存提醒开关失败，请重试。', false);
    } finally {
      this.busy = false;
    }
  }

  private async exportBackup(): Promise<void> {
    if (this.busy) {
      return;
    }
    const ctx = this.contextOrNull();
    if (ctx === null) {
      return;
    }
    this.busy = true;
    this.setMessage('正在打包本机数据…', true);
    let outcome: BackupOutcome | null = null;
    try {
      outcome = await BackupStore.createBackup(ctx);
      if (!outcome.ok) {
        this.setMessage(outcome.message, false);
        return;
      }
      const documentPicker = new picker.DocumentViewPicker(ctx);
      const options = new picker.DocumentSaveOptions();
      const baseName: string = outcome.zipPath.substring(outcome.zipPath.lastIndexOf('/') + 1);
      options.newFileNames = [baseName];
      options.fileSuffixChoices = ['.zip'];
      const uris: string[] = await documentPicker.save(options);
      if (uris.length === 0) {
        this.setMessage('已取消保存：备份包还在应用缓存里，没有写到外部目录。', false);
        return;
      }
      const dest = fs.openSync(uris[0], fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.copyFileSync(outcome.zipPath, dest.fd);
      } finally {
        fs.closeSync(dest);
      }
      this.setMessage('备份已保存到所选位置（' + BackupStore.mbText(outcome.bytes) + '）。'
        + '包含：学习数据、学习计划、设置。文件由你保管，应用不会上传。', true);
      this.toast('备份已保存');
    } catch (e) {
      this.setMessage(BackupStore.describeError(e as BusinessError, '导出'), false);
    } finally {
      if (outcome !== null && outcome.zipPath.length > 0) {
        try {
          if (fs.accessSync(outcome.zipPath)) {
            fs.unlinkSync(outcome.zipPath);
          }
        } catch (ignored) {
          // 缓存清理失败不影响导出结果
        }
      }
      this.busy = false;
    }
  }

  private async importBackup(): Promise<void> {
    if (this.busy) {
      return;
    }
    const ctx = this.contextOrNull();
    if (ctx === null) {
      return;
    }
    this.busy = true;
    this.setMessage('正在读取备份包…', true);
    try {
      const documentPicker = new picker.DocumentViewPicker(ctx);
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['.zip'];
      const uris: string[] = await documentPicker.select(options);
      if (uris.length === 0) {
        this.setMessage('已取消导入，本机数据没有改动。', false);
        return;
      }
      const outcome: BackupOutcome = await BackupStore.restoreBackup(ctx, uris[0]);
      if (!outcome.ok) {
        this.setMessage(outcome.message, false);
        return;
      }
      await this.plans.reload();
      this.refresh();
      this.setMessage(outcome.message, true);
      this.toast('导入完成');
    } catch (e) {
      this.setMessage(BackupStore.describeError(e as BusinessError, '读取备份'), false);
    } finally {
      this.busy = false;
    }
  }

  private confirmClear(): void {
    if (this.busy) {
      return;
    }
    AlertDialog.show({
      title: '清空全部本机数据',
      message: '会删除词卡与复习记录、签到、错题、学习计划和设置，且不可恢复。'
        + '建议先导出一份备份再清空。确定清空吗？',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '确认清空',
        fontColor: Theme.dangerInk,
        action: () => {
          this.clearAllData();
        }
      }
    });
  }

  private async clearAllData(): Promise<void> {
    const ctx = this.contextOrNull();
    if (ctx === null) {
      return;
    }
    this.busy = true;
    try {
      await this.store.resetAll();
      await this.plans.clearAll();
      await this.prefs.reset();
      this.dailyGoal = this.prefs.dailyGoal;
      this.reminderEnabled = this.prefs.reminderEnabled;
      this.refresh();
      this.setMessage('已清空本机全部数据。首页/统计页里的旧数字要等应用重启后才完全归零。', true);
      this.toast('已清空');
    } catch (e) {
      this.setMessage('清空失败：可能有数据正在写入，请稍后再试。', false);
    } finally {
      this.busy = false;
    }
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
        Text(this.busy ? '处理中…' : '本机数据').fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%').padding({ top: 4, bottom: 10 })
      Text('设置').fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text('每日目标、提醒、备份与清空；数据只在这台设备上。').fontSize(12)
        .fontColor(Theme.secondaryText).margin({ top: 4 })
    }.alignItems(HorizontalAlign.Start).width('100%').padding({ top: 6, bottom: 14 })
  }

  @Builder
  messageBanner() {
    if (this.message.length > 0) {
      Row() {
        Text(this.message).fontSize(12).lineHeight(19)
          .fontColor(this.messageOk ? Theme.successInk : Theme.dangerInk)
          .layoutWeight(1)
        Text('×').fontSize(18).fontColor(Theme.secondaryText).margin({ left: 8 })
          .onClick(() => {
            this.message = '';
          })
      }
      .width('100%').padding(12).backgroundColor(this.messageOk ? Theme.successSoft : Theme.dangerSoft)
      .borderRadius(12).margin({ bottom: 14 })
    }
  }

  @Builder
  goalCard() {
    Column() {
      Text('每日目标').fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('每天复习多少张词卡算达标。统计页的「今日进度」按这个数算；'
        + '没到目标也会如实显示，不做假的连续打卡。').fontSize(11)
        .fontColor(Theme.secondaryText).margin({ top: 5 }).lineHeight(17)
      Row() {
        Button('−').width(48).height(48).fontSize(24)
          .backgroundColor(Theme.cardWarm).fontColor(this.dailyGoal <= MIN_DAILY_GOAL ? Theme.divider : Theme.accentInk)
          .enabled(!this.busy && this.dailyGoal > MIN_DAILY_GOAL)
          .onClick(() => {
            this.changeGoal(-5);
          })
        Column() {
          Text(this.dailyGoal.toString()).fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.accentInk)
          Text('词 / 天').fontSize(11).fontColor(Theme.secondaryText)
        }.layoutWeight(1).alignItems(HorizontalAlign.Center)
        Button('+').width(48).height(48).fontSize(24)
          .backgroundColor(Theme.cardWarm).fontColor(this.dailyGoal >= MAX_DAILY_GOAL ? Theme.divider : Theme.accentInk)
          .enabled(!this.busy && this.dailyGoal < MAX_DAILY_GOAL)
          .onClick(() => {
            this.changeGoal(5);
          })
      }.width('100%').margin({ top: 12 })
      Text('范围 ' + MIN_DAILY_GOAL.toString() + '–' + MAX_DAILY_GOAL.toString() + ' 词，点一下存一次。')
        .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 8 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  reminderCard() {
    Column() {
      Row() {
        Text('本地提醒').fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Toggle({ type: ToggleType.Switch, isOn: this.reminderEnabled })
          .selectedColor(Theme.accentInk)
          .enabled(!this.busy)
          .onChange((isOn: boolean) => {
            this.toggleReminder(isOn);
          })
      }.width('100%')
      Text('提醒只在本机生效：计划页顶部会显示「今天还没开始」的提示条。'
        + '当前版本不申请系统通知权限，不会在系统通知栏弹消息，也不会上传任何内容。')
        .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 6 }).lineHeight(17)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 14 })
  }

  @Builder
  backupCard() {
    Column() {
      Text('数据与备份').fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('备份是一个 zip：导出后由你在系统文件选择器里决定存哪；导入会覆盖本机同名的数据文件。'
        + '文件只在本机读写，应用不上传。').fontSize(11).fontColor(Theme.secondaryText)
        .margin({ top: 5 }).lineHeight(17)

      Column() {
        Text(this.dataFileLine).fontSize(11).fontColor(Theme.secondaryText)
        Text(this.plansFileLine).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
        Text(this.prefsFileLine).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 3 })
      }.width('100%').alignItems(HorizontalAlign.Start).padding(12).margin({ top: 10 })
      .backgroundColor(Theme.cardWarm).borderRadius(10)

      Row() {
        Button('导出备份').layoutWeight(1).height(46).fontSize(14)
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .enabled(!this.busy)
          .onClick(() => {
            this.exportBackup();
          })
        Button('导入备份').layoutWeight(1).height(46).fontSize(14).margin({ left: 10 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
          .enabled(!this.busy)
          .onClick(() => {
            this.importBackup();
          })
      }.width('100%').margin({ top: 12 })

      Text('导入学习数据后，请彻底关闭应用再打开——否则首页会把导入前的旧数据写回去。')
        .fontSize(10).fontColor(Theme.warningInk).margin({ top: 10 }).lineHeight(16)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 14 })
  }

  @Builder
  dangerCard() {
    Column() {
      Text('清空数据').fontSize(17).fontWeight(FontWeight.Medium).fontColor(Theme.dangerInk)
      Text('删掉本机全部学习数据、计划与设置，不可恢复。清空前建议先导出备份。').fontSize(11)
        .fontColor(Theme.secondaryText).margin({ top: 5 }).lineHeight(17)
      Button('清空全部本机数据').width('100%').height(46).fontSize(14).margin({ top: 12 })
        .backgroundColor(Theme.dangerSoft).fontColor(Theme.dangerInk)
        .enabled(!this.busy)
        .onClick(() => {
          this.confirmClear();
        })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ top: 14 })
  }

  @Builder
  footerNote() {
    Column() {
      Text('口径说明').fontSize(13).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text('· 设置保存在 filesDir/studykit_prefs.json，和学习数据分文件存放；'
        + '导出的 zip 含 studykit_data.json、studykit_plans.json、studykit_prefs.json 与 meta.json。').fontSize(10)
        .fontColor(Theme.secondaryText).lineHeight(16).margin({ top: 6 })
      Text('· 每日目标只影响本页与你打开统计页时看到的进度，不改学习算法。').fontSize(10)
        .fontColor(Theme.secondaryText).lineHeight(16).margin({ top: 3 })
      Text('· 提醒开关是本地设置；系统通知需要在后续版本单独申请权限。').fontSize(10)
        .fontColor(Theme.secondaryText).lineHeight(16).margin({ top: 3 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 14, bottom: 30 })
    .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)
  }

  build() {
    Scroll() {
      Row() {
        Column() {
          this.header()
          this.messageBanner()
          if (this.loading) {
            Column() {
              LoadingProgress().width(40).height(40).color(Theme.accent)
              Text('正在读取本机设置').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
            }.width('100%').height(240).justifyContent(FlexAlign.Center)
          } else {
            this.goalCard()
            this.reminderCard()
            this.backupCard()
            this.dangerCard()
            this.footerNote()
          }
        }.width('100%').constraintSize({ maxWidth: 760 })
      }.width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .width('100%').height('100%').backgroundColor(Theme.bg).scrollBar(BarState.Off)
  }
}
