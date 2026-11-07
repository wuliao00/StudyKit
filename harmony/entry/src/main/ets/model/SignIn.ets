export interface SignInRecord {
  day: string;
  makeup: boolean;
}

export interface SignInDay {
  date: string;
  signed: boolean;
  makeup: boolean;
}

export interface SignInCell {
  date: string;
  day: number;
  signed: boolean;
  makeup: boolean;
  today: boolean;
  blank: boolean;
}

export interface SignInMonth {
  year: number;
  month: number;
  cells: SignInCell[];
}

export interface SignInSummary {
  signedToday: boolean;
  currentStreak: number;
  longestStreak: number;
  totalDays: number;
  weekDays: SignInDay[];
  weekSigned: number;
  signedDays: string[];
  makeupDays: string[];
  last14: SignInDay[];
}

export class SignInStats {
  static readonly MAX_MAKEUP: number = 3;
  static readonly MILESTONES: number[] = [3, 7, 14, 30, 66];

  static today(): string {
    return SignInStats.iso(new Date());
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
    const d: Date = SignInStats.parse(iso);
    d.setDate(d.getDate() + delta);
    return SignInStats.iso(d);
  }

  static emptySummary(): SignInSummary {
    return {
      signedToday: false,
      currentStreak: 0,
      longestStreak: 0,
      totalDays: 0,
      weekDays: [],
      weekSigned: 0,
      signedDays: [],
      makeupDays: [],
      last14: []
    };
  }

  static summary(records: SignInRecord[], todayIso: string): SignInSummary {
    const signedSet: Set<string> = new Set<string>();
    const makeupSet: Set<string> = new Set<string>();
    for (let i = 0; i < records.length; i++) {
      const record: SignInRecord = records[i];
      if (record.day <= todayIso) {
        signedSet.add(record.day);
        if (record.makeup) {
          makeupSet.add(record.day);
        }
      }
    }
    const week: SignInDay[] = SignInStats.weekDays(todayIso, signedSet, makeupSet);
    let weekSigned: number = 0;
    for (let i = 0; i < week.length; i++) {
      if (week[i].signed && week[i].date <= todayIso) {
        weekSigned++;
      }
    }
    const last14: SignInDay[] = [];
    for (let offset = 13; offset >= 0; offset--) {
      const date: string = SignInStats.addDays(todayIso, -offset);
      last14.push({
        date: date,
        signed: signedSet.has(date),
        makeup: makeupSet.has(date)
      });
    }
    return {
      signedToday: signedSet.has(todayIso),
      currentStreak: SignInStats.currentStreak(signedSet, todayIso),
      longestStreak: SignInStats.longestStreak(signedSet),
      totalDays: signedSet.size,
      weekDays: week,
      weekSigned: weekSigned,
      signedDays: Array.from(signedSet).sort(),
      makeupDays: Array.from(makeupSet).sort(),
      last14: last14
    };
  }

  static currentStreak(days: Set<string>, todayIso: string): number {
    let cursor: string = days.has(todayIso) ? todayIso : SignInStats.addDays(todayIso, -1);
    if (!days.has(cursor)) {
      return 0;
    }
    let count: number = 0;
    while (days.has(cursor)) {
      count++;
      cursor = SignInStats.addDays(cursor, -1);
    }
    return count;
  }

  static longestStreak(days: Set<string>): number {
    if (days.size === 0) {
      return 0;
    }
    const arr: string[] = Array.from(days);
    arr.sort();
    let best: number = 1;
    let run: number = 1;
    for (let i = 1; i < arr.length; i++) {
      if (SignInStats.addDays(arr[i - 1], 1) === arr[i]) {
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

  static weekDays(todayIso: string, signed: Set<string>, makeup: Set<string>): SignInDay[] {
    const d: Date = SignInStats.parse(todayIso);
    const mondayOffset: number = (d.getDay() + 6) % 7;
    const monday: string = SignInStats.addDays(todayIso, -mondayOffset);
    const result: SignInDay[] = [];
    for (let i = 0; i < 7; i++) {
      const date: string = SignInStats.addDays(monday, i);
      const eligible: boolean = date <= todayIso;
      result.push({
        date: date,
        signed: eligible && signed.has(date),
        makeup: eligible && makeup.has(date)
      });
    }
    return result;
  }

  static monthGrid(year: number, month: number, signed: Set<string>, makeup: Set<string>, todayIso: string): SignInMonth {
    const first: Date = new Date(year, month - 1, 1);
    const daysInMonth: number = new Date(year, month, 0).getDate();
    const leading: number = (first.getDay() + 6) % 7;
    const cells: SignInCell[] = [];
    for (let i = 0; i < leading; i++) {
      cells.push({ date: '', day: 0, signed: false, makeup: false, today: false, blank: true });
    }
    for (let day = 1; day <= daysInMonth; day++) {
      const date: string = SignInStats.iso(new Date(year, month - 1, day));
      cells.push({
        date: date,
        day: day,
        signed: signed.has(date),
        makeup: makeup.has(date),
        today: date === todayIso,
        blank: false
      });
    }
    while (cells.length < 42) {
      cells.push({ date: '', day: 0, signed: false, makeup: false, today: false, blank: true });
    }
    return { year: year, month: month, cells: cells };
  }

  static monthSigned(month: string, signedDays: string[]): number {
    let count: number = 0;
    for (let i = 0; i < signedDays.length; i++) {
      if (signedDays[i].startsWith(month)) {
        count++;
      }
    }
    return count;
  }

  static makeupUsed(month: string, makeupDays: string[]): number {
    return SignInStats.monthSigned(month, makeupDays);
  }

  static withinWindow(date: string, todayIso: string): boolean {
    if (date >= todayIso) {
      return false;
    }
    return date >= SignInStats.addDays(todayIso, -3);
  }

  static canMakeUp(date: string, todayIso: string, makeupDays: string[]): boolean {
    if (!SignInStats.withinWindow(date, todayIso)) {
      return false;
    }
    const month: string = date.substring(0, 7);
    return SignInStats.makeupUsed(month, makeupDays) < SignInStats.MAX_MAKEUP;
  }

  static canUndo(date: string, todayIso: string, makeupDays: string[]): boolean {
    return SignInStats.withinWindow(date, todayIso) && makeupDays.indexOf(date) >= 0;
  }

  static milestonesReached(longest: number): number[] {
    const result: number[] = [];
    for (let i = 0; i < SignInStats.MILESTONES.length; i++) {
      if (longest >= SignInStats.MILESTONES[i]) {
        result.push(SignInStats.MILESTONES[i]);
      }
    }
    return result;
  }
}
