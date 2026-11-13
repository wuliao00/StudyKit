import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';

const DATA_FILE: string = 'habit_data.json';

/** 一个习惯（对应 Android habits 表的核心字段，Harmony 侧只保留本地会用到的那部分）。 */
export interface HabitItem {
  id: string;
  name: string;
  /** 图标：一个 emoji。 */
  icon: string;
  /** 目标天数（天数型习惯）。默认 66：Lally 2010 的中位数，与 Android Habit.DEFAULT_TARGET_DAYS 同口径。 */
  targetDays: number;
  /** 创建时刻（ms），近 7 天弹性达标的窗口起点。 */
  startDate: number;
  archived: boolean;
  /** 数量型目标（>0 即数量型，如每天 50 词；0 表示天数型）。 */
  targetCount: number;
  /** 数量型单位（个 / 页 / 公里 / ml）。 */
  unit: string;
  /** 默认打卡文案：一键打卡时自动带入备注。 */
  defaultText: string;
  createdAt: number;
}

/** 一次打卡（对应 Android check_ins 表）。date 固定为 yyyy-MM-dd。 */
export interface HabitCheckIn {
  id: string;
  habitId: string;
  date: string;
  note: string;
  /** 本次数量；天数型恒为 1。 */
  amount: number;
  /** 补卡标记：补的日期画灰圈、不与当天打卡混为一谈。 */
  isMakeup: boolean;
  createdAt: number;
}

export interface HabitData {
  habits: HabitItem[];
  checkIns: HabitCheckIn[];
}

/** 日历/周条上的一格。blank 只是月网格的补齐空位，不参与点击。 */
export interface HabitDayCell {
  date: string;
  day: number;
  checked: boolean;
  makeup: boolean;
  today: boolean;
  future: boolean;
  blank: boolean;
}

/** 近 7 天周条上的一格（label 为周一至周日的单字）。 */
export interface HabitWeekCell {
  date: string;
  label: string;
  checked: boolean;
  makeup: boolean;
  today: boolean;
  future: boolean;
  makeupEligible: boolean;
}

export interface HabitSummary {
  totalDays: number;
  totalAmount: number;
  streak: number;
  longestStreak: number;
  weekWindow: number;
  weekChecked: number;
  weekTarget: number;
  weekRate: number;
  weekAchieved: boolean;
  todayAmount: number;
}

export const ICON_CHOICES: string[] = ['📚', '🏃', '💧', '🧘', '✍️', '🌙', '🥗', '🎹'];

/**
 * 习惯 / 打卡的本地存储（独立 JSON 文件 habit_data.json，不碰主应用 Store.ets 的 studykit_data.json）。
 *
 * 口径与 Android 版一致：
 * - 连续天数：今天打了从今天回溯，今天没打但从昨天打了从昨天回溯，断一天就停；
 * - 补卡：只能补「过去 7 天内」（不含今天与未来），补卡标记保留；
 * - 近 7 天弹性达标：窗口按习惯实际年龄收缩，达标线 7 天里 5 天（Lally 2010：漏一天不毁自动性）；
 * - 数量型：同一天多次打卡累加 amount，天数型同一天只记一次（重复打卡只更新备注）。
 */
export class HabitStore {
  static readonly DEFAULT_TARGET_DAYS: number = 66;
  static readonly MAKEUP_WINDOW_DAYS: number = 7;
  static readonly WEEK_WINDOW_DAYS: number = 7;
  static readonly WEEK_TARGET_DAYS: number = 5;

  private static instance: HabitStore = new HabitStore();
  private filePath: string = '';
  private data: HabitData = { habits: [], checkIns: [] };
  private inited: boolean = false;

  static get(): HabitStore {
    return HabitStore.instance;
  }

  static newId(prefix: string): string {
    return prefix + '-' + Date.now().toString() + '-' + Math.floor(Math.random() * 10000).toString();
  }

  // ---------- 日期工具（与 SignIn.ets 同一套写法；纯函数、不读设备时钟） ----------

  static today(): string {
    return HabitStore.iso(new Date());
  }

  static iso(d: Date): string {
    const y: number = d.getFullYear();
    const m: number = d.getMonth() + 1;
    const day: number = d.getDate();
    const ms: string = m < 10 ? '0' + m : m.toString();
    const ds: string = day < 10 ? '0' + day : day.toString();
    return y.toString() + '-' + ms + '-' + ds;
  }

  static parse(iso: string): Date {
    const parts: string[] = iso.split('-');
    return new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
  }

  static addDays(iso: string, delta: number): string {
    const d: Date = HabitStore.parse(iso);
    d.setDate(d.getDate() + delta);
    return HabitStore.iso(d);
  }

  /** 日期差（b - a，单位天）。 */
  static dayDiff(aIso: string, bIso: string): number {
    const a: Date = HabitStore.parse(aIso);
    const b: Date = HabitStore.parse(bIso);
    return Math.round((b.getTime() - a.getTime()) / 86400000);
  }

  /** 该日期能不能补卡：严格早于今天，且不早于今天-7 天。 */
  static canMakeUp(dateIso: string, todayIso: string): boolean {
    if (dateIso >= todayIso) {
      return false;
    }
    return dateIso >= HabitStore.addDays(todayIso, -HabitStore.MAKEUP_WINDOW_DAYS);
  }

  /** 连续打卡天数：今天打了从今天回溯；今天没打但从昨天打了，从昨天回溯。 */
  static streak(dates: string[], todayIso: string): number {
    const set: Set<string> = new Set<string>();
    for (let i = 0; i < dates.length; i++) {
      set.add(dates[i]);
    }
    if (set.size === 0) {
      return 0;
    }
    let cursor: string = set.has(todayIso) ? todayIso : HabitStore.addDays(todayIso, -1);
    if (!set.has(cursor)) {
      return 0;
    }
    let count: number = 0;
    while (set.has(cursor)) {
      count++;
      cursor = HabitStore.addDays(cursor, -1);
    }
    return count;
  }

  /** 最长连续打卡天数（历史最高）。 */
  static longestStreak(dates: string[]): number {
    if (dates.length === 0) {
      return 0;
    }
    const set: Set<string> = new Set<string>();
    for (let i = 0; i < dates.length; i++) {
      set.add(dates[i]);
    }
    const sorted: string[] = Array.from(set);
    sorted.sort();
    let best: number = 1;
    let run: number = 1;
    for (let i = 1; i < sorted.length; i++) {
      if (HabitStore.addDays(sorted[i - 1], 1) === sorted[i]) {
        run++;
      } else {
        run = 1;
      }
      if (run > best) {
        best = run;
      }
    }
    return best;
  }

  /** 窗口实际长度：新习惯不满一周时分母跟日子数走；封顶 7。 */
  static weeklyWindowDays(startDateMs: number, todayIso: string): number {
    const startIso: string = HabitStore.iso(new Date(startDateMs));
    const days: number = HabitStore.dayDiff(startIso, todayIso) + 1;
    if (days < 1) {
      return 1;
    }
    if (days > HabitStore.WEEK_WINDOW_DAYS) {
      return HabitStore.WEEK_WINDOW_DAYS;
    }
    return days;
  }

  /** 达标线：满窗口 5/7；窗口短了等比缩放且永远不高于窗口天数。 */
  static weekTargetDays(windowDays: number): number {
    let days: number = windowDays;
    if (days < 1) {
      days = 1;
    }
    if (days > HabitStore.WEEK_WINDOW_DAYS) {
      days = HabitStore.WEEK_WINDOW_DAYS;
    }
    if (days === HabitStore.WEEK_WINDOW_DAYS) {
      return HabitStore.WEEK_TARGET_DAYS;
    }
    const scaled: number = Math.round(days * HabitStore.WEEK_TARGET_DAYS / HabitStore.WEEK_WINDOW_DAYS);
    if (scaled < 1) {
      return 1;
    }
    if (scaled > days) {
      return days;
    }
    return scaled;
  }

  static weekdayLabel(iso: string): string {
    const labels: string[] = ['日', '一', '二', '三', '四', '五', '六'];
    const d: Date = HabitStore.parse(iso);
    return labels[d.getDay()];
  }

  /** 近 7 天周条（含今天；每格带打卡/补卡/今天/可补卡标记）。 */
  static last7Cells(dates: string[], makeupDates: string[], todayIso: string, makeupAllowed: boolean): HabitWeekCell[] {
    const set: Set<string> = new Set<string>();
    const makeupSet: Set<string> = new Set<string>();
    for (let i = 0; i < dates.length; i++) {
      set.add(dates[i]);
    }
    for (let i = 0; i < makeupDates.length; i++) {
      makeupSet.add(makeupDates[i]);
    }
    const cells: HabitWeekCell[] = [];
    for (let offset = HabitStore.WEEK_WINDOW_DAYS - 1; offset >= 0; offset--) {
      const date: string = HabitStore.addDays(todayIso, -offset);
      const checked: boolean = set.has(date);
      cells.push({
        date: date,
        label: HabitStore.weekdayLabel(date),
        checked: checked,
        makeup: makeupSet.has(date),
        today: date === todayIso,
        future: false,
        makeupEligible: makeupAllowed && !checked && HabitStore.canMakeUp(date, todayIso)
      });
    }
    return cells;
  }

  /**
   * 月度网格：周一起始、固定 42 格（6 行 × 7 列，空位 blank）。
   * 与 Android MonthGridCommon 的口径一致（翻月不跳一行高度）。
   */
  static monthCells(dates: string[], makeupDates: string[], year: number, month: number, todayIso: string): HabitDayCell[] {
    const set: Set<string> = new Set<string>();
    const makeupSet: Set<string> = new Set<string>();
    for (let i = 0; i < dates.length; i++) {
      set.add(dates[i]);
    }
    for (let i = 0; i < makeupDates.length; i++) {
      makeupSet.add(makeupDates[i]);
    }
    const first: Date = new Date(year, month - 1, 1);
    const daysInMonth: number = new Date(year, month, 0).getDate();
    const leading: number = (first.getDay() + 6) % 7;
    const cells: HabitDayCell[] = [];
    for (let i = 0; i < leading; i++) {
      cells.push({ date: '', day: 0, checked: false, makeup: false, today: false, future: false, blank: true });
    }
    for (let day = 1; day <= daysInMonth; day++) {
      const date: string = HabitStore.iso(new Date(year, month - 1, day));
      cells.push({
        date: date,
        day: day,
        checked: set.has(date),
        makeup: makeupSet.has(date),
        today: date === todayIso,
        future: date > todayIso,
        blank: false
      });
    }
    while (cells.length < 42) {
      cells.push({ date: '', day: 0, checked: false, makeup: false, today: false, future: false, blank: true });
    }
    return cells;
  }

  /** 某月打了多少天（月度统计用）。 */
  static monthChecked(year: number, month: number, dates: string[]): number {
    const prefix: string = year.toString() + '-' + (month < 10 ? '0' + month : month.toString());
    let count: number = 0;
    for (let i = 0; i < dates.length; i++) {
      if (dates[i].startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  // ---------- 生命周期与落盘 ----------

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.inited) {
      return;
    }
    this.filePath = context.filesDir + '/' + DATA_FILE;
    this.load();
    this.inited = true;
  }

  private load(): void {
    try {
      if (!fs.accessSync(this.filePath)) {
        this.data = { habits: [], checkIns: [] };
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: HabitData = JSON.parse(text) as HabitData;
      const out: HabitData = { habits: [], checkIns: [] };
      if (raw !== null && raw !== undefined) {
        if (raw.habits !== undefined && raw.habits !== null) {
          out.habits = raw.habits;
        }
        if (raw.checkIns !== undefined && raw.checkIns !== null) {
          out.checkIns = raw.checkIns;
        }
      }
      this.data = out;
    } catch (e) {
      this.data = { habits: [], checkIns: [] };
    }
  }

  private save(): void {
    if (this.filePath.length === 0) {
      return;
    }
    try {
      const file = fs.openSync(this.filePath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, JSON.stringify(this.data));
      } finally {
        fs.closeSync(file);
      }
    } catch (e) {
      // 本地写失败不阻塞页面使用（与 QuestionStore 同策略）
    }
  }

  // ---------- 习惯 CRUD ----------

  /** 全部习惯：未归档在前，归档沉底；同组内新建的在前。 */
  getHabits(includeArchived: boolean): HabitItem[] {
    const result: HabitItem[] = [];
    for (let i = 0; i < this.data.habits.length; i++) {
      const habit: HabitItem = this.data.habits[i];
      if (!includeArchived && habit.archived) {
        continue;
      }
      result.push(habit);
    }
    result.sort((a: HabitItem, b: HabitItem) => {
      if (a.archived !== b.archived) {
        return a.archived ? 1 : -1;
      }
      return b.createdAt - a.createdAt;
    });
    return result;
  }

  activeCount(): number {
    let count: number = 0;
    for (let i = 0; i < this.data.habits.length; i++) {
      if (!this.data.habits[i].archived) {
        count++;
      }
    }
    return count;
  }

  archivedCount(): number {
    return this.data.habits.length - this.activeCount();
  }

  getHabit(id: string): HabitItem | null {
    for (let i = 0; i < this.data.habits.length; i++) {
      if (this.data.habits[i].id === id) {
        return this.data.habits[i];
      }
    }
    return null;
  }

  async addHabit(name: string, icon: string, targetDays: number, targetCount: number,
    unit: string, defaultText: string): Promise<HabitItem> {
    const now: number = Date.now();
    const item: HabitItem = {
      id: HabitStore.newId('h'),
      name: name.trim().length > 0 ? name.trim() : '新习惯',
      icon: icon.length > 0 ? icon : '📚',
      targetDays: targetDays > 0 ? targetDays : HabitStore.DEFAULT_TARGET_DAYS,
      startDate: now,
      archived: false,
      targetCount: targetCount > 0 ? targetCount : 0,
      unit: targetCount > 0 ? (unit.length > 0 ? unit : '个') : '',
      defaultText: defaultText.trim(),
      createdAt: now
    };
    this.data.habits.push(item);
    this.save();
    return item;
  }

  async updateHabit(id: string, name: string, icon: string, targetDays: number, targetCount: number,
    unit: string, defaultText: string): Promise<boolean> {
    const habit: HabitItem | null = this.getHabit(id);
    if (habit === null) {
      return false;
    }
    if (name.trim().length > 0) {
      habit.name = name.trim();
    }
    if (icon.length > 0) {
      habit.icon = icon;
    }
    habit.targetDays = targetDays > 0 ? targetDays : HabitStore.DEFAULT_TARGET_DAYS;
    habit.targetCount = targetCount > 0 ? targetCount : 0;
    habit.unit = habit.targetCount > 0 ? (unit.length > 0 ? unit : '个') : '';
    habit.defaultText = defaultText.trim();
    this.save();
    return true;
  }

  async setArchived(id: string, archived: boolean): Promise<boolean> {
    const habit: HabitItem | null = this.getHabit(id);
    if (habit === null) {
      return false;
    }
    habit.archived = archived;
    this.save();
    return true;
  }

  /** 删除习惯并连带删除它的全部打卡记录（与 Android 的清库口径一致：不留悬空行）。 */
  async deleteHabit(id: string): Promise<boolean> {
    const nextHabits: HabitItem[] = [];
    let found: boolean = false;
    for (let i = 0; i < this.data.habits.length; i++) {
      if (this.data.habits[i].id === id) {
        found = true;
      } else {
        nextHabits.push(this.data.habits[i]);
      }
    }
    if (!found) {
      return false;
    }
    const nextCheckIns: HabitCheckIn[] = [];
    for (let i = 0; i < this.data.checkIns.length; i++) {
      if (this.data.checkIns[i].habitId !== id) {
        nextCheckIns.push(this.data.checkIns[i]);
      }
    }
    this.data.habits = nextHabits;
    this.data.checkIns = nextCheckIns;
    this.save();
    return true;
  }

  /** 某习惯的补卡日期（页面画灰圈用）。 */
  makeupDates(habitId: string): string[] {
    const result: string[] = [];
    for (let i = 0; i < this.data.checkIns.length; i++) {
      const item: HabitCheckIn = this.data.checkIns[i];
      if (item.habitId === habitId && item.isMakeup) {
        result.push(item.date);
      }
    }
    return result;
  }

  /** 页面用的近 7 天周条（实例方法：打卡/补卡状态直接从自己的数据算）。 */
  weekCells(habitId: string, todayIso: string, makeupAllowed: boolean): HabitWeekCell[] {
    return HabitStore.last7Cells(this.checkedDates(habitId), this.makeupDates(habitId), todayIso, makeupAllowed);
  }

  /** 页面用的月网格（实例方法：同上）。 */
  monthCells(habitId: string, year: number, month: number, todayIso: string): HabitDayCell[] {
    return HabitStore.monthCells(this.checkedDates(habitId), this.makeupDates(habitId), year, month, todayIso);
  }

  // ---------- 打卡 ----------

  checkInsOf(habitId: string): HabitCheckIn[] {
    const result: HabitCheckIn[] = [];
    for (let i = 0; i < this.data.checkIns.length; i++) {
      if (this.data.checkIns[i].habitId === habitId) {
        result.push(this.data.checkIns[i]);
      }
    }
    return result;
  }

  /** 打过卡的日期（去重、升序）。连续天数 / 日历 / 达标率都只认这一份。 */
  checkedDates(habitId: string): string[] {
    const set: Set<string> = new Set<string>();
    for (let i = 0; i < this.data.checkIns.length; i++) {
      if (this.data.checkIns[i].habitId === habitId) {
        set.add(this.data.checkIns[i].date);
      }
    }
    const result: string[] = Array.from(set);
    result.sort();
    return result;
  }

  findCheckIn(habitId: string, dateIso: string): HabitCheckIn | null {
    for (let i = 0; i < this.data.checkIns.length; i++) {
      const item: HabitCheckIn = this.data.checkIns[i];
      if (item.habitId === habitId && item.date === dateIso) {
        return item;
      }
    }
    return null;
  }

  /**
   * 在指定日期打卡（今天 / 补卡共用）：
   * - 已有记录：数量型累加本次数量；两类都允许更新备注（空白备注不覆盖旧值）；
   * - 新记录：过去日期一律记 isMakeup=true，且必须落在补卡窗口内；
   * - 今天/未来：未来日期直接拒绝。
   */
  async checkIn(habitId: string, dateIso: string, note: string, amount: number, isMakeup: boolean): Promise<boolean> {
    const habit: HabitItem | null = this.getHabit(habitId);
    if (habit === null) {
      return false;
    }
    const today: string = HabitStore.today();
    if (dateIso > today) {
      return false;
    }
    const existing: HabitCheckIn | null = this.findCheckIn(habitId, dateIso);
    if (existing !== null) {
      if (habit.targetCount > 0 && amount > 0) {
        existing.amount = existing.amount + amount;
      }
      if (note.trim().length > 0) {
        existing.note = note.trim();
      }
      this.save();
      return true;
    }
    if (dateIso < today && !HabitStore.canMakeUp(dateIso, today)) {
      return false;
    }
    const now: number = Date.now();
    const item: HabitCheckIn = {
      id: HabitStore.newId('c'),
      habitId: habitId,
      date: dateIso,
      note: note.trim(),
      amount: habit.targetCount > 0 ? (amount > 0 ? amount : 0) : 1,
      isMakeup: dateIso < today || isMakeup,
      createdAt: now
    };
    this.data.checkIns.push(item);
    this.save();
    return true;
  }

  hasCheckedToday(habitId: string): boolean {
    return this.findCheckIn(habitId, HabitStore.today()) !== null;
  }

  private datesInRange(habitId: string, fromIso: string, toIso: string): string[] {
    const result: string[] = [];
    for (let i = 0; i < this.data.checkIns.length; i++) {
      const item: HabitCheckIn = this.data.checkIns[i];
      if (item.habitId === habitId && item.date >= fromIso && item.date <= toIso) {
        if (result.indexOf(item.date) < 0) {
          result.push(item.date);
        }
      }
    }
    return result;
  }

  /** 某个习惯的汇总：累计、连续、最长、近 7 天弹性达标、今日数量。 */
  summary(habitId: string, todayIso: string): HabitSummary {
    const habit: HabitItem | null = this.getHabit(habitId);
    if (habit === null) {
      return {
        totalDays: 0, totalAmount: 0, streak: 0, longestStreak: 0,
        weekWindow: 1, weekChecked: 0, weekTarget: 1, weekRate: 0, weekAchieved: false, todayAmount: 0
      };
    }
    const dates: string[] = this.checkedDates(habitId);
    let totalAmount: number = 0;
    for (let i = 0; i < this.data.checkIns.length; i++) {
      if (this.data.checkIns[i].habitId === habitId) {
        totalAmount = totalAmount + this.data.checkIns[i].amount;
      }
    }
    const windowDays: number = HabitStore.weeklyWindowDays(habit.startDate, todayIso);
    const from: string = HabitStore.addDays(todayIso, -(windowDays - 1));
    const weekDates: string[] = this.datesInRange(habitId, from, todayIso);
    const target: number = HabitStore.weekTargetDays(windowDays);
    const checked: number = weekDates.length;
    const rate: number = Math.round(checked * 100 / windowDays);
    let todayAmount: number = 0;
    const todayCheckIn: HabitCheckIn | null = this.findCheckIn(habitId, todayIso);
    if (todayCheckIn !== null) {
      todayAmount = todayCheckIn.amount;
    }
    return {
      totalDays: dates.length,
      totalAmount: totalAmount,
      streak: HabitStore.streak(dates, todayIso),
      longestStreak: HabitStore.longestStreak(dates),
      weekWindow: windowDays,
      weekChecked: checked,
      weekTarget: target,
      weekRate: rate,
      weekAchieved: checked >= target,
      todayAmount: todayAmount
    };
  }

  /** 今日数量型进度文案用：0/50 个。 */
  todayAmount(habitId: string): number {
    const item: HabitCheckIn | null = this.findCheckIn(habitId, HabitStore.today());
    return item === null ? 0 : item.amount;
  }

  /** 书摘式的最近打卡备注（卡片展开时回看用）。 */
  recentCheckIns(habitId: string, count: number): HabitCheckIn[] {
    const all: HabitCheckIn[] = this.checkInsOf(habitId);
    all.sort((a: HabitCheckIn, b: HabitCheckIn) => {
      if (a.date === b.date) {
        return b.createdAt - a.createdAt;
      }
      return a.date < b.date ? 1 : -1;
    });
    if (all.length <= count) {
      return all;
    }
    return all.slice(0, count);
  }
}
