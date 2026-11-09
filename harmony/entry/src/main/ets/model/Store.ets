import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import { SignInRecord, SignInStats } from './SignIn';

const PREF_NAME: string = 'studykit_harmony';
const SIGN_KEY: string = 'sign_ins_v1';
const MASTERED_KEY: string = 'mastered_words_v1';

export class StudyStore {
  private static instance: StudyStore = new StudyStore();
  private pref: preferences.Preferences | null = null;

  static get(): StudyStore {
    return StudyStore.instance;
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.pref === null) {
      this.pref = await preferences.getPreferences(context, PREF_NAME);
    }
  }

  async loadSignIns(): Promise<SignInRecord[]> {
    if (this.pref === null) {
      return [];
    }
    const raw = await this.pref.get(SIGN_KEY, '[]');
    try {
      return JSON.parse(String(raw)) as SignInRecord[];
    } catch (e) {
      return [];
    }
  }

  private async saveSignIns(records: SignInRecord[]): Promise<void> {
    if (this.pref === null) {
      return;
    }
    await this.pref.put(SIGN_KEY, JSON.stringify(records));
    await this.pref.flush();
  }

  async signIn(todayIso: string): Promise<boolean> {
    const records: SignInRecord[] = await this.loadSignIns();
    for (let i = 0; i < records.length; i++) {
      if (records[i].day === todayIso) {
        return false;
      }
    }
    records.push({ day: todayIso, makeup: false });
    await this.saveSignIns(records);
    return true;
  }

  async makeUp(date: string, todayIso: string): Promise<boolean> {
    const records: SignInRecord[] = await this.loadSignIns();
    for (let i = 0; i < records.length; i++) {
      if (records[i].day === date) {
        return false;
      }
    }
    const summary = SignInStats.summary(records, todayIso);
    if (!SignInStats.canMakeUp(date, todayIso, summary.makeupDays)) {
      return false;
    }
    records.push({ day: date, makeup: true });
    await this.saveSignIns(records);
    return true;
  }

  async undoMakeUp(date: string, todayIso: string): Promise<boolean> {
    const records: SignInRecord[] = await this.loadSignIns();
    const summary = SignInStats.summary(records, todayIso);
    if (!SignInStats.canUndo(date, todayIso, summary.makeupDays)) {
      return false;
    }
    const next: SignInRecord[] = [];
    for (let i = 0; i < records.length; i++) {
      if (!(records[i].day === date && records[i].makeup)) {
        next.push(records[i]);
      }
    }
    await this.saveSignIns(next);
    return true;
  }

  async loadMastered(): Promise<string[]> {
    if (this.pref === null) {
      return [];
    }
    const raw = await this.pref.get(MASTERED_KEY, '[]');
    try {
      return JSON.parse(String(raw)) as string[];
    } catch (e) {
      return [];
    }
  }

  async addMastered(word: string): Promise<string[]> {
    const mastered: string[] = await this.loadMastered();
    if (mastered.indexOf(word) < 0) {
      mastered.push(word);
      if (this.pref !== null) {
        await this.pref.put(MASTERED_KEY, JSON.stringify(mastered));
        await this.pref.flush();
      }
    }
    return mastered;
  }

  async resetMastered(): Promise<void> {
    if (this.pref !== null) {
      await this.pref.put(MASTERED_KEY, '[]');
      await this.pref.flush();
    }
  }
}
