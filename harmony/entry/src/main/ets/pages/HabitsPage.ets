import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import { Theme } from '../common/Theme';
import {
  HabitItem, HabitCheckIn, HabitDayCell, HabitWeekCell, HabitSummary,
  HabitStore, ICON_CHOICES
} from '../model/HabitStore';

/**
 * 习惯打卡独立页（HarmonyOS 完全移植 B 模块）。
 *
 * 数据存在自己的 filesDir/habit_data.json（HabitStore），不碰 Index/Store.ets。
 * 页面与「学习/签到」主页面同语言：@Entry @Component、router.back()、暖纸色 Theme、760 居中。
 *
 * 功能：习惯新增/编辑/归档/删除（删除二次确认）、每日打卡与近 7 天补卡、连续天数、
 * 近 7 天弹性达标、7 日周条、月历（可翻月）、数量型习惯（同日累加）、空状态给下一步。
 */
@Entry
@Component
struct HabitsPage {
  @State loading: boolean = true;
  @State habits: HabitItem[] = [];
  @State showArchived: boolean = false;
  @State version: number = 0;
  @State todayIso: string = HabitStore.today();

  @State showForm: boolean = false;
  @State editingId: string = '';
  @State formName: string = '';
  @State formIcon: string = '📚';
  @State formTargetDays: string = '66';
  @State formQuantity: boolean = false;
  @State formTargetCount: string = '';
  @State formUnit: string = '个';
  @State formDefaultText: string = '';

  @State checkHabitId: string = '';
  @State checkDate: string = '';
  @State checkNote: string = '';
  @State checkAmount: string = '';

  @State calendarHabitId: string = '';
  @State calYear: number = 0;
  @State calMonth: number = 1;

  private store: HabitStore = HabitStore.get();
  private weekLabels: string[] = ['一', '二', '三', '四', '五', '六', '日'];

  aboutToAppear(): void {
    const context = getContext(this) as common.UIAbilityContext;
    const now: Date = new Date();
    this.calYear = now.getFullYear();
    this.calMonth = now.getMonth() + 1;
    this.store.init(context).then(() => {
      this.reload();
      this.loading = false;
    }).catch(() => {
      this.loading = false;
      this.toast('本地习惯数据读取失败');
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message });
  }

  private reload(): void {
    this.habits = this.store.getHabits(this.showArchived);
    this.version = this.version + 1;
  }

  private switchFilter(archived: boolean): void {
    this.showArchived = archived;
    this.reload();
  }

  private activeCount(): number {
    return this.store.activeCount();
  }

  private archivedCount(): number {
    return this.store.archivedCount();
  }

  private todayCheckedCount(): number {
    let count: number = 0;
    const active: HabitItem[] = this.store.getHabits(false);
    for (let i = 0; i < active.length; i++) {
      if (this.store.hasCheckedToday(active[i].id)) {
        count++;
      }
    }
    return count;
  }

  private isQuantity(habit: HabitItem): boolean {
    return habit.targetCount > 0;
  }

  private summaryOf(habit: HabitItem): HabitSummary {
    return this.store.summary(habit.id, this.todayIso);
  }

  private weekCellsOf(habit: HabitItem): HabitWeekCell[] {
    return this.store.weekCells(habit.id, this.todayIso, true);
  }

  private monthCellsOf(habit: HabitItem): HabitDayCell[] {
    return this.store.monthCells(habit.id, this.calYear, this.calMonth, this.todayIso);
  }

  private cardSubtitle(habit: HabitItem): string {
    const summary: HabitSummary = this.summaryOf(habit);
    let line: string = '';
    if (this.isQuantity(habit)) {
      line = '今日 ' + this.formatAmount(summary.todayAmount) + '/' + this.formatAmount(habit.targetCount)
        + ' ' + habit.unit + ' · 连续 ' + summary.streak.toString() + ' 天';
    } else {
      line = '连续 ' + summary.streak.toString() + ' 天 · 累计 ' + summary.totalDays.toString() + ' 天';
    }
    line = line + ' · 近 ' + summary.weekWindow.toString() + ' 天 '
      + summary.weekChecked.toString() + '/' + summary.weekWindow.toString();
    if (summary.weekAchieved) {
      line = line + ' 达标';
    }
    return line;
  }

  private formatAmount(value: number): string {
    if (Math.abs(value - Math.round(value)) < 0.001) {
      return Math.round(value).toString();
    }
    return value.toFixed(1);
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

  /** 只留数字与一个小数点（数量型输入用）；以点开头时补 0，保证后面 Number() 一定能解析。 */
  private decimalOnly(value: string): string {
    let out: string = '';
    let hasDot: boolean = false;
    for (let i = 0; i < value.length; i++) {
      const ch: string = value.charAt(i);
      if (ch >= '0' && ch <= '9') {
        out = out + ch;
      } else if (ch === '.' && !hasDot) {
        hasDot = true;
        out = out + ch;
      }
    }
    if (out.length > 0 && out.charAt(0) === '.') {
      return '0' + out;
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

  /** 文本 → 数量（空串按 0 处理；调用前已用 decimalOnly 收过口）。 */
  private amountValue(text: string): number {
    if (text.length === 0) {
      return 0;
    }
    const value: number = Number(text);
    if (value !== value) {
      return 0;
    }
    return value;
  }


  private targetProgress(habit: HabitItem): number {
    if (!this.isQuantity(habit) || habit.targetCount <= 0) {
      return 0;
    }
    const done: number = this.store.todayAmount(habit.id);
    const percent: number = Math.round(done * 100 / habit.targetCount);
    if (percent < 0) {
      return 0;
    }
    if (percent > 100) {
      return 100;
    }
    return percent;
  }

  // ---------- 习惯表单 ----------

  private openCreate(): void {
    this.editingId = '';
    this.formName = '';
    this.formIcon = '📚';
    this.formTargetDays = HabitStore.DEFAULT_TARGET_DAYS.toString();
    this.formQuantity = false;
    this.formTargetCount = '';
    this.formUnit = '个';
    this.formDefaultText = '';
    this.showForm = true;
    this.checkHabitId = '';
  }

  private openEdit(habit: HabitItem): void {
    this.editingId = habit.id;
    this.formName = habit.name;
    this.formIcon = habit.icon;
    this.formTargetDays = habit.targetDays.toString();
    this.formQuantity = this.isQuantity(habit);
    this.formTargetCount = this.isQuantity(habit) ? this.formatAmount(habit.targetCount) : '';
    this.formUnit = habit.unit.length > 0 ? habit.unit : '个';
    this.formDefaultText = habit.defaultText;
    this.showForm = true;
    this.checkHabitId = '';
  }

  private saveForm(): void {
    const name: string = this.formName.trim();
    if (name.length === 0) {
      this.toast('先给习惯起个名字');
      return;
    }
    const targetDays: number = this.intValue(this.formTargetDays);
    if (targetDays <= 0 || targetDays > 999) {
      this.toast('目标天数填 1–999 的整数');
      return;
    }
    let targetCount: number = 0;
    let unit: string = '';
    if (this.formQuantity) {
      targetCount = this.amountValue(this.formTargetCount);
      if (targetCount <= 0) {
        this.toast('数量型习惯要填一个大于 0 的目标数量');
        return;
      }
      unit = this.formUnit.trim().length > 0 ? this.formUnit.trim() : '个';
    }
    if (this.editingId.length > 0) {
      this.store.updateHabit(this.editingId, name, this.formIcon, targetDays, targetCount, unit,
        this.formDefaultText).then((ok: boolean) => {
        if (ok) {
          this.showForm = false;
          this.reload();
          this.toast('习惯已更新');
        } else {
          this.toast('没找到要改的习惯');
        }
      });
    } else {
      this.store.addHabit(name, this.formIcon, targetDays, targetCount, unit,
        this.formDefaultText).then(() => {
        this.showForm = false;
        this.reload();
        this.toast('习惯已创建');
      });
    }
  }

  private toggleArchived(habit: HabitItem): void {
    this.store.setArchived(habit.id, !habit.archived).then((ok: boolean) => {
      if (ok) {
        this.reload();
        this.toast(habit.archived ? '已取消归档' : '已归档，可在「已归档」里找回');
      }
    });
  }

  private confirmDelete(habit: HabitItem): void {
    AlertDialog.show({
      title: '删除习惯？',
      message: '「' + habit.name + '」和它的全部打卡记录会一起删除，删除后不可恢复。',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '删除', fontColor: Theme.dangerInk,
        action: () => {
          this.store.deleteHabit(habit.id).then(() => {
            this.reload();
            this.toast('已删除');
          });
        }
      }
    });
  }

  // ---------- 打卡 / 补卡 ----------

  private openCheckIn(habit: HabitItem, date: string): void {
    const existing: HabitCheckIn | null = this.store.findCheckIn(habit.id, date);
    this.checkHabitId = habit.id;
    this.checkDate = date;
    if (existing !== null) {
      this.checkNote = existing.note;
      this.checkAmount = '';
    } else {
      this.checkNote = habit.defaultText;
      this.checkAmount = '';
    }
  }

  private closeCheckIn(): void {
    this.checkHabitId = '';
    this.checkDate = '';
    this.checkNote = '';
    this.checkAmount = '';
  }

  private submitCheckIn(): void {
    const habit: HabitItem | null = this.store.getHabit(this.checkHabitId);
    if (habit === null) {
      this.closeCheckIn();
      return;
    }
    let amount: number = 0;
    if (this.isQuantity(habit)) {
      amount = this.amountValue(this.checkAmount);
      if (amount <= 0) {
        this.toast('请填写本次数量');
        return;
      }
    }
    const date: string = this.checkDate;
    const existing: HabitCheckIn | null = this.store.findCheckIn(habit.id, date);
    const isMakeup: boolean = date < this.todayIso;
    this.store.checkIn(habit.id, date, this.checkNote, amount, isMakeup).then((ok: boolean) => {
      if (!ok) {
        this.toast(date < this.todayIso ? '只能补最近 7 天的空缺' : '打卡没写进去，请重试');
        return;
      }
      this.closeCheckIn();
      this.reload();
      if (existing !== null) {
        this.toast(this.isQuantity(habit) ? '已累加今日进度' : '已更新打卡备注');
      } else {
        this.toast(isMakeup ? '已补卡' : '已打卡');
      }
    });
  }

  /** 点日格：今天 → 打卡/改备注；过去 7 天内 → 补卡；未来 → 只提示。 */
  private tapDay(habit: HabitItem, date: string): void {
    if (date.length === 0) {
      return;
    }
    if (date > this.todayIso) {
      this.toast('这一天还没到，先过好今天');
      return;
    }
    const existing: HabitCheckIn | null = this.store.findCheckIn(habit.id, date);
    if (existing === null && date < this.todayIso && !HabitStore.canMakeUp(date, this.todayIso)) {
      this.toast('补卡只到最近 7 天，更早的空缺补不了');
      return;
    }
    this.openCheckIn(habit, date);
  }

  private toggleCalendar(habit: HabitItem): void {
    if (this.calendarHabitId === habit.id) {
      this.calendarHabitId = '';
      return;
    }
    this.calendarHabitId = habit.id;
    const now: Date = new Date();
    this.calYear = now.getFullYear();
    this.calMonth = now.getMonth() + 1;
  }

  private shiftMonth(habit: HabitItem, delta: number): void {
    let month: number = this.calMonth + delta;
    let year: number = this.calYear;
    if (month < 1) {
      month = 12;
      year = year - 1;
    }
    if (month > 12) {
      month = 1;
      year = year + 1;
    }
    this.calYear = year;
    this.calMonth = month;
  }

  private monthTitle(): string {
    return this.calYear.toString() + ' 年 ' + this.calMonth.toString() + ' 月';
  }

  private monthCheckedCount(habit: HabitItem): number {
    return HabitStore.monthChecked(this.calYear, this.calMonth, this.store.checkedDates(habit.id));
  }

  private startDemoHabit(): void {
    this.store.addHabit('睡前读 5 页', '📚', HabitStore.DEFAULT_TARGET_DAYS, 0, '', '今天读了几页？')
      .then(() => {
        this.reload();
        this.toast('已创建示例习惯，今晚就能打卡');
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
        Text('习惯打卡').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        Text('进行中 ' + this.activeCount().toString() + ' 个 · 今日已打卡 '
          + this.todayCheckedCount().toString() + ' 个 · 归档 ' + this.archivedCount().toString() + ' 个')
          .fontSize(11).fontColor(Theme.secondaryText).margin({ top: 2 })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      Button(this.showForm ? '收起' : '新建习惯').height(36).fontSize(13)
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
      Text('进行中').fontSize(12)
        .fontColor(!this.showArchived ? Color.White : Theme.secondaryText)
        .backgroundColor(!this.showArchived ? Theme.accentInk : Theme.card)
        .padding({ left: 14, right: 14, top: 7, bottom: 7 }).borderRadius(14).margin({ right: 8 })
        .onClick(() => { this.switchFilter(false); })
      Text('已归档 ' + this.archivedCount().toString()).fontSize(12)
        .fontColor(this.showArchived ? Color.White : Theme.secondaryText)
        .backgroundColor(this.showArchived ? Theme.goldInk : Theme.card)
        .padding({ left: 14, right: 14, top: 7, bottom: 7 }).borderRadius(14)
        .onClick(() => { this.switchFilter(true); })
      Blank()
      Text('连续 ' + this.maxStreak().toString() + ' 天').fontSize(11).fontColor(Theme.goldInk)
    }.width('100%').margin({ top: 4, bottom: 4 })
  }

  private maxStreak(): number {
    let best: number = 0;
    const active: HabitItem[] = this.store.getHabits(false);
    for (let i = 0; i < active.length; i++) {
      const summary: HabitSummary = this.store.summary(active[i].id, this.todayIso);
      if (summary.streak > best) {
        best = summary.streak;
      }
    }
    return best;
  }

  @Builder
  formPanel() {
    Column() {
      Text(this.editingId.length > 0 ? '编辑习惯' : '新建习惯')
        .fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText).width('100%')
      Text('小目标更容易坚持：默认 66 天是养成研究中自动性形成的中位数。')
        .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })

      TextInput({ text: this.formName, placeholder: '习惯名称（必填，如 睡前读 5 页）' })
        .width('100%').height(42).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.formName = value; })

      Text('图标').fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 10 })
      Row() {
        ForEach(ICON_CHOICES, (icon: string) => {
          Text(icon).fontSize(18)
            .backgroundColor(this.formIcon === icon ? Theme.accentSoft : Theme.cardWarm)
            .borderRadius(10).padding({ left: 8, right: 8, top: 4, bottom: 4 }).margin({ right: 6, top: 6 })
            .onClick(() => { this.formIcon = icon; })
        }, (icon: string) => icon)
      }.width('100%')

      Row() {
        Column() {
          Text('目标天数').fontSize(11).fontColor(Theme.secondaryText)
          TextInput({ text: this.formTargetDays, placeholder: '66' })
            .width('100%').height(40).margin({ top: 4 })
            .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
            .onChange((value: string) => { this.formTargetDays = this.digitsOnly(value); })
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Column() {
          Text('类型').fontSize(11).fontColor(Theme.secondaryText).margin({ left: 12 })
          Row() {
            Text('天数型').fontSize(11)
              .fontColor(!this.formQuantity ? Color.White : Theme.secondaryText)
              .backgroundColor(!this.formQuantity ? Theme.accentInk : Theme.cardWarm)
              .padding({ left: 8, right: 8, top: 6, bottom: 6 }).borderRadius(10)
              .onClick(() => { this.formQuantity = false; })
            Text('数量型').fontSize(11)
              .fontColor(this.formQuantity ? Color.White : Theme.secondaryText)
              .backgroundColor(this.formQuantity ? Theme.accentInk : Theme.cardWarm)
              .padding({ left: 8, right: 8, top: 6, bottom: 6 }).borderRadius(10).margin({ left: 6 })
              .onClick(() => { this.formQuantity = true; })
          }.margin({ left: 12, top: 8 })
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      }.width('100%').margin({ top: 10 })

      if (this.formQuantity) {
        Row() {
          Column() {
            Text('每日目标数量').fontSize(11).fontColor(Theme.secondaryText)
            TextInput({ text: this.formTargetCount, placeholder: '如 50' })
              .width('100%').height(40).margin({ top: 4 })
              .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
              .onChange((value: string) => { this.formTargetCount = this.decimalOnly(value); })
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Column() {
            Text('单位').fontSize(11).fontColor(Theme.secondaryText).margin({ left: 12 })
            TextInput({ text: this.formUnit, placeholder: '个' })
              .width('100%').height(40).margin({ top: 4, left: 12 })
              .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
              .onChange((value: string) => { this.formUnit = value; })
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        }.width('100%').margin({ top: 8 })
      }

      TextInput({ text: this.formDefaultText, placeholder: '默认打卡文案（选填，一键打卡时带入备注）' })
        .width('100%').height(42).margin({ top: 10 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.formDefaultText = value; })

      Row() {
        Button('取消').layoutWeight(1).height(42)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.showForm = false; })
        Button(this.editingId.length > 0 ? '保存修改' : '创建习惯').layoutWeight(1).height(42)
          .margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.saveForm(); })
      }.width('100%').margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  checkInPanel() {
    Column() {
      Text(this.checkDate < this.todayIso ? '补打卡' : '打卡')
        .fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText).width('100%')
      Text(this.checkPanelSubtitle())
        .fontSize(11).fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })

      if (this.checkPanelIsQuantity()) {
        Text('今日已累计 ' + this.formatAmount(this.checkPanelTodayAmount()) + ' '
          + this.checkPanelUnit() + '，目标 ' + this.checkPanelTarget() + ' ' + this.checkPanelUnit())
          .fontSize(11).fontColor(Theme.accentInk).width('100%').margin({ top: 8 })
        TextInput({ text: this.checkAmount, placeholder: '本次数量（必填）' })
          .width('100%').height(42).margin({ top: 8 })
          .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
          .onChange((value: string) => { this.checkAmount = this.decimalOnly(value); })
      }

      TextInput({ text: this.checkNote, placeholder: '备注（选填）' })
        .width('100%').height(42).margin({ top: 8 })
        .backgroundColor(Theme.cardWarm).fontColor(Theme.primaryText)
        .onChange((value: string) => { this.checkNote = value; })

      Row() {
        Button('取消').layoutWeight(1).height(42)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.secondaryText)
          .onClick(() => { this.closeCheckIn(); })
        Button(this.checkDate < this.todayIso ? '确认补卡' : '确认打卡').layoutWeight(1).height(42)
          .margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => { this.submitCheckIn(); })
      }.width('100%').margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .border({ width: 1, color: Theme.accentSoft })
  }

  private checkPanelHabit(): HabitItem | null {
    return this.store.getHabit(this.checkHabitId);
  }

  private checkPanelSubtitle(): string {
    const habit: HabitItem | null = this.checkPanelHabit();
    if (habit === null) {
      return '';
    }
    const date: Date = HabitStore.parse(this.checkDate);
    const existing: HabitCheckIn | null = this.store.findCheckIn(habit.id, this.checkDate);
    let line: string = habit.name + ' · ' + date.getMonth() + '月' + date.getDate() + '日';
    if (this.checkDate < this.todayIso) {
      line = line + '（补卡限最近 7 天内，会单独标记）';
    }
    if (existing !== null) {
      line = line + ' · 这一天已经打过卡，再提交会' + (habit.targetCount > 0 ? '累加数量' : '更新备注');
    }
    return line;
  }

  private checkPanelIsQuantity(): boolean {
    const habit: HabitItem | null = this.checkPanelHabit();
    return habit !== null && habit.targetCount > 0;
  }

  private checkPanelUnit(): string {
    const habit: HabitItem | null = this.checkPanelHabit();
    return habit === null ? '' : habit.unit;
  }

  private checkPanelTarget(): string {
    const habit: HabitItem | null = this.checkPanelHabit();
    return habit === null ? '' : this.formatAmount(habit.targetCount);
  }

  private checkPanelTodayAmount(): number {
    const habit: HabitItem | null = this.checkPanelHabit();
    return habit === null ? 0 : this.store.todayAmount(habit.id);
  }

  @Builder
  weekStrip(habit: HabitItem) {
    Row() {
      ForEach(this.weekCellsOf(habit), (cell: HabitWeekCell) => {
        Column() {
          Text(cell.label).fontSize(10).fontColor(Theme.secondaryText)
          Column() {
            if (cell.checked) {
              Text('✓').fontSize(12).fontColor(Color.White).fontWeight(FontWeight.Bold)
            } else if (cell.makeupEligible) {
              Text('补').fontSize(10).fontColor(Theme.warningInk)
            }
          }
          .width(26).height(26).borderRadius(13)
          .backgroundColor(this.weekCellBg(cell))
          .border({ width: cell.today ? 2 : 1, color: this.weekCellBorder(cell) })
          .justifyContent(FlexAlign.Center)
          .margin({ top: 4 })
          .onClick(() => { this.tapDay(habit, cell.date); })
          Text(cell.date.substring(8)).fontSize(9).fontColor(Theme.secondaryText).margin({ top: 2 })
        }.layoutWeight(1).alignItems(HorizontalAlign.Center)
      }, (cell: HabitWeekCell) => habit.id + '-' + cell.date + '-' + cell.checked.toString()
        + this.version.toString())
    }.width('100%').margin({ top: 10 })
  }

  private weekCellBg(cell: HabitWeekCell): string {
    if (cell.checked) {
      return cell.makeup ? Theme.goldSoft : Theme.accent;
    }
    return Theme.cardWarm;
  }

  private weekCellBorder(cell: HabitWeekCell): string {
    if (cell.today) {
      return Theme.accentInk;
    }
    if (cell.makeupEligible) {
      return Theme.divider;
    }
    return Theme.cardWarm;
  }

  @Builder
  monthPanel(habit: HabitItem) {
    Column() {
      Row() {
        Text('‹').fontSize(20).fontColor(Theme.accentInk)
          .padding({ left: 10, right: 10 }).onClick(() => { this.shiftMonth(habit, -1); })
        Text(this.monthTitle()).fontSize(13).fontColor(Theme.primaryText).layoutWeight(1)
          .textAlign(TextAlign.Center)
        Text('›').fontSize(20).fontColor(Theme.accentInk)
          .padding({ left: 10, right: 10 }).onClick(() => { this.shiftMonth(habit, 1); })
      }.width('100%').alignItems(VerticalAlign.Center)

      Row() {
        ForEach(this.weekLabels, (label: string) => {
          Text(label).fontSize(10).fontColor(Theme.secondaryText).layoutWeight(1)
            .textAlign(TextAlign.Center)
        }, (label: string) => label)
      }.width('100%').margin({ top: 8 })

      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(this.monthCellsOf(habit), (cell: HabitDayCell) => {
          Column() {
            if (!cell.blank) {
              Text(cell.day.toString()).fontSize(12)
                .fontColor(cell.checked ? Color.White : (cell.future ? Theme.divider : Theme.primaryText))
              if (cell.checked) {
                Text(cell.makeup ? '补' : '✓').fontSize(9)
                  .fontColor(cell.makeup ? Theme.goldInk : Color.White)
              }
            }
          }
          .width('14.28%').height(46)
          .backgroundColor(cell.checked ? (cell.makeup ? Theme.goldSoft : Theme.accent) : Theme.cardWarm)
          .borderRadius(8)
          .border({ width: cell.today ? 2 : 0, color: Theme.accentInk })
          .justifyContent(FlexAlign.Center)
          .onClick(() => { this.tapDay(habit, cell.date); })
        }, (cell: HabitDayCell) => habit.id + '-' + cell.date + '-' + cell.checked.toString()
          + this.calYear.toString() + '-' + this.calMonth.toString() + '-' + this.version.toString())
      }.width('100%').margin({ top: 8 })

      Text('本月已打卡 ' + this.monthCheckedCount(habit).toString() + ' 天 · 补卡只会补近 7 天的空缺')
        .fontSize(10).fontColor(Theme.secondaryText).width('100%').margin({ top: 8 })
    }
    .width('100%').padding(12).margin({ top: 10 })
    .backgroundColor(Theme.cardWarm).borderRadius(12)
  }

  @Builder
  habitCard(habit: HabitItem) {
    Column() {
      Row() {
        Text(habit.icon).fontSize(24)
        Column() {
          Row() {
            Text(habit.name).fontSize(16).fontWeight(FontWeight.Medium)
              .fontColor(habit.archived ? Theme.secondaryText : Theme.primaryText)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (habit.archived) {
              Text('已归档').fontSize(9).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
                .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(6).margin({ left: 6 })
            }
          }.width('100%')
          Text(this.cardSubtitle(habit)).fontSize(11).fontColor(Theme.secondaryText)
            .margin({ top: 3 }).width('100%')
        }.layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
        if (!habit.archived) {
          Button(this.store.hasCheckedToday(habit.id) ? '追加/改备注' : '打卡').height(34).fontSize(12)
            .backgroundColor(Theme.accentInk).fontColor(Color.White)
            .onClick(() => { this.openCheckIn(habit, this.todayIso); })
        }
      }.width('100%').alignItems(VerticalAlign.Center)

      if (this.isQuantity(habit)) {
        Progress({ value: this.targetProgress(habit), total: 100, type: ProgressType.Linear })
          .color(Theme.accent).backgroundColor(Theme.divider)
          .width('100%').height(6).margin({ top: 10 })
        Text('连续 ' + this.summaryOf(habit).streak.toString() + '/' + habit.targetDays.toString()
          + ' 天 · 目标 ' + this.formatAmount(habit.targetCount) + ' ' + habit.unit + '/天')
          .fontSize(10).fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })
      } else {
        Progress({ value: this.streakProgress(habit), total: habit.targetDays,
          type: ProgressType.Linear })
          .color(Theme.accent).backgroundColor(Theme.divider)
          .width('100%').height(6).margin({ top: 10 })
        Text('连续 ' + this.summaryOf(habit).streak.toString() + '/' + habit.targetDays.toString()
          + ' 天 · 累计 ' + this.summaryOf(habit).totalDays.toString() + ' 天')
          .fontSize(10).fontColor(Theme.secondaryText).width('100%').margin({ top: 4 })
      }

      this.weekStrip(habit)

      Flex({ wrap: FlexWrap.Wrap }) {
        Text(this.calendarHabitId === habit.id ? '收起月历' : '月历').fontSize(11).fontColor(Theme.accentInk)
          .backgroundColor(Theme.accentSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(10).margin({ right: 8, top: 6 })
          .onClick(() => { this.toggleCalendar(habit); })
        Text('编辑').fontSize(11).fontColor(Theme.accentInk)
          .backgroundColor(Theme.cardWarm).padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(10).margin({ right: 8, top: 6 })
          .onClick(() => { this.openEdit(habit); })
        Text(habit.archived ? '取消归档' : '归档').fontSize(11).fontColor(Theme.goldInk)
          .backgroundColor(Theme.goldSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(10).margin({ right: 8, top: 6 })
          .onClick(() => { this.toggleArchived(habit); })
        Text('删除').fontSize(11).fontColor(Theme.dangerInk)
          .backgroundColor(Theme.dangerSoft).padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(10).margin({ top: 6 })
          .onClick(() => { this.confirmDelete(habit); })
      }.width('100%')

      if (this.calendarHabitId === habit.id) {
        this.monthPanel(habit)
      }

      Text(this.weekReviewLine(habit)).fontSize(10).fontColor(Theme.secondaryText)
        .width('100%').margin({ top: 10 })
    }
    .width('100%').padding(16).margin({ top: 10 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
    .border({ width: 1, color: Theme.divider })
  }

  private streakProgress(habit: HabitItem): number {
    const streak: number = this.summaryOf(habit).streak;
    if (streak > habit.targetDays) {
      return habit.targetDays;
    }
    return streak;
  }

  private weekReviewLine(habit: HabitItem): string {
    const summary: HabitSummary = this.summaryOf(habit);
    if (this.isQuantity(habit)) {
      return '近 ' + summary.weekWindow.toString() + ' 天 ' + summary.weekChecked.toString() + '/'
        + summary.weekWindow.toString() + ' · 累计 ' + this.formatAmount(summary.totalAmount) + ' '
        + habit.unit + ' · 补卡会单独标记';
    }
    return '近 ' + summary.weekWindow.toString() + ' 天 ' + summary.weekChecked.toString() + '/'
      + summary.weekWindow.toString() + ' 天（' + summary.weekTarget.toString() + ' 天达标）· 最长连续 '
      + summary.longestStreak.toString() + ' 天';
  }

  @Builder
  emptyState() {
    Column() {
      Text(this.showArchived ? '还没有归档的习惯' : '还没有习惯')
        .fontSize(18).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      Text(this.showArchived
        ? '归档不是删除：归档的习惯还能在这里找回来，取消归档就回到进行中。'
        : '先建一个小到不可能失败的习惯，比如「睡前读 5 页」。每天点一下打卡，连续天数会自己长起来。')
        .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
        .textAlign(TextAlign.Start).width('100%')
      if (!this.showArchived) {
        Row() {
          Button('新建第一个习惯').layoutWeight(1).height(42)
            .backgroundColor(Theme.accentInk).fontColor(Color.White)
            .onClick(() => { this.openCreate(); })
          Button('用示例开始').layoutWeight(1).height(42).margin({ left: 10 })
            .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
            .onClick(() => { this.startDemoHabit(); })
        }.width('100%').margin({ top: 14 })
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(20).margin({ top: 12 })
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  build() {
    Column() {
      if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Theme.accent)
          Text('正在读取本地习惯').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.header()
            this.filterChips()

            if (this.showForm) {
              this.formPanel()
            }
            if (this.checkHabitId.length > 0) {
              this.checkInPanel()
            }

            if (this.habits.length === 0) {
              this.emptyState()
            } else {
              ForEach(this.habits, (habit: HabitItem) => {
                this.habitCard(habit)
              }, (habit: HabitItem) => habit.id + '-' + habit.archived.toString() + '-'
                + this.calendarHabitId + '-' + this.version.toString())
            }

            Text('习惯和打卡只保存在应用本地文件 habit_data.json，不上传；补卡限最近 7 天，'
              + '连续天数按真实打卡回溯。').fontSize(11).fontColor(Theme.secondaryText)
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
