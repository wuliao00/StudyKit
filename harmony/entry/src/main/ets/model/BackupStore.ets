import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';
import zlib from '@ohos.zlib';
import { BusinessError } from '@ohos.base';

// 设置 + 备份：两块都只碰 filesDir 里自己的 JSON 与 cacheDir 里的临时 zip。
// 不修改 Store.ets（studykit_data.json 的写者），不申请额外权限：
// - 设置写 filesDir/studykit_prefs.json；
// - 导出 = 把本机数据文件压成 zip（cacheDir），由设置页用系统文件选择器另存；
// - 导入 = 文件选择器选 zip，解压到 cacheDir、校验后覆盖本机 JSON。

const PREFS_FILE: string = 'studykit_prefs.json';
const DATA_FILE: string = 'studykit_data.json';
const PLANS_FILE: string = 'studykit_plans.json';
const META_FILE: string = 'meta.json';
const BACKUP_APP: string = 'StudyKit-Harmony';
const BACKUP_VERSION: number = 1;

export const DEFAULT_DAILY_GOAL: number = 30;
export const MIN_DAILY_GOAL: number = 5;
export const MAX_DAILY_GOAL: number = 200;

export interface AppPrefs {
  dailyGoal: number;
  reminderEnabled: boolean;
  updatedAt: number;
}

interface PrefsFile {
  version: number;
  prefs: AppPrefs;
}

export interface BackupOutcome {
  ok: boolean;
  message: string;
  /* 导出成功时：zip 在 cacheDir 的路径 */
  zipPath: string;
  /* 导出成功时：zip 字节数 */
  bytes: number;
  /* 导入成功时：是否覆盖了学习数据（需要重启才生效） */
  dataImported: boolean;
  /* 导入成功时：是否覆盖了计划 */
  plansImported: boolean;
  /* 导入成功时：是否覆盖了设置 */
  prefsImported: boolean;
}

interface BackupMeta {
  app: string;
  version: number;
  exportedAt: string;
  files: string[];
}

interface CardProbe {
  word: string;
}

interface DataProbe {
  cards?: CardProbe[];
}

interface PlanProbe {
  id?: string;
}

interface PlansProbe {
  plans?: PlanProbe[];
}

interface PrefsProbe {
  dailyGoal?: number;
}

function pad2(value: number): string {
  return value < 10 ? '0' + value.toString() : value.toString();
}

function nowStamp(): string {
  const d = new Date();
  return d.getFullYear().toString() + pad2(d.getMonth() + 1) + pad2(d.getDate())
    + '-' + pad2(d.getHours()) + pad2(d.getMinutes()) + pad2(d.getSeconds());
}

function emptyOutcome(): BackupOutcome {
  return {
    ok: false,
    message: '',
    zipPath: '',
    bytes: 0,
    dataImported: false,
    plansImported: false,
    prefsImported: false
  };
}

function defaults(): AppPrefs {
  return {
    dailyGoal: DEFAULT_DAILY_GOAL,
    reminderEnabled: false,
    updatedAt: 0
  };
}

export class SettingsStore {
  private static instance: SettingsStore = new SettingsStore();
  private filePath: string = '';
  private prefs: AppPrefs = defaults();

  static get(): SettingsStore {
    return SettingsStore.instance;
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.filePath.length > 0) {
      return;
    }
    this.filePath = context.filesDir + '/' + PREFS_FILE;
    await this.reload();
  }

  async reload(): Promise<void> {
    if (this.filePath.length === 0) {
      return;
    }
    try {
      if (!fs.accessSync(this.filePath)) {
        this.prefs = defaults();
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: PrefsFile = JSON.parse(text) as PrefsFile;
      this.prefs = SettingsStore.normalize(raw);
    } catch (e) {
      this.prefs = defaults();
    }
  }

  private static normalize(raw: PrefsFile): AppPrefs {
    const out: AppPrefs = defaults();
    if (raw === null || raw === undefined || raw.prefs === null || raw.prefs === undefined) {
      return out;
    }
    const p: AppPrefs = raw.prefs;
    if (p.dailyGoal !== undefined && p.dailyGoal !== null) {
      out.dailyGoal = SettingsStore.clampGoal(p.dailyGoal);
    }
    if (p.reminderEnabled !== undefined && p.reminderEnabled !== null) {
      out.reminderEnabled = p.reminderEnabled;
    }
    if (p.updatedAt !== undefined && p.updatedAt !== null) {
      out.updatedAt = p.updatedAt;
    }
    return out;
  }

  static clampGoal(value: number): number {
    const rounded: number = Math.round(value);
    if (rounded < MIN_DAILY_GOAL) {
      return MIN_DAILY_GOAL;
    }
    if (rounded > MAX_DAILY_GOAL) {
      return MAX_DAILY_GOAL;
    }
    return rounded;
  }

  get current(): AppPrefs {
    return this.prefs;
  }

  get dailyGoal(): number {
    return this.prefs.dailyGoal;
  }

  get reminderEnabled(): boolean {
    return this.prefs.reminderEnabled;
  }

  private async save(): Promise<void> {
    if (this.filePath.length === 0) {
      return;
    }
    this.prefs.updatedAt = Date.now();
    const body: PrefsFile = { version: 1, prefs: this.prefs };
    const file = fs.openSync(this.filePath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(file.fd, JSON.stringify(body));
    } finally {
      fs.closeSync(file);
    }
  }

  async setDailyGoal(value: number): Promise<void> {
    this.prefs.dailyGoal = SettingsStore.clampGoal(value);
    await this.save();
  }

  async setReminderEnabled(enabled: boolean): Promise<void> {
    this.prefs.reminderEnabled = enabled;
    await this.save();
  }

  async reset(): Promise<void> {
    this.prefs = defaults();
    await this.save();
  }
}

export class BackupStore {
  /** 把本机数据文件压成一个 zip（放 cacheDir），返回 zip 路径。设置页再交给文件选择器另存。 */
  static async createBackup(context: common.UIAbilityContext): Promise<BackupOutcome> {
    const outcome: BackupOutcome = emptyOutcome();
    const stamp: string = nowStamp();
    const stageDir: string = context.cacheDir + '/backup-stage-' + stamp;
    const zipPath: string = context.cacheDir + '/StudyKit-backup-' + stamp + '.zip';
    try {
      BackupStore.ensureDir(stageDir);
      const included: string[] = [];
      const sources: string[] = [DATA_FILE, PLANS_FILE, PREFS_FILE];
      const staged: string[] = [];
      for (let i = 0; i < sources.length; i++) {
        const src: string = context.filesDir + '/' + sources[i];
        if (fs.accessSync(src)) {
          const dst: string = stageDir + '/' + sources[i];
          fs.copyFileSync(src, dst);
          included.push(sources[i]);
          staged.push(dst);
        }
      }
      const meta: BackupMeta = {
        app: BACKUP_APP,
        version: BACKUP_VERSION,
        exportedAt: stamp,
        files: included
      };
      const metaPath: string = stageDir + '/' + META_FILE;
      BackupStore.writeText(metaPath, JSON.stringify(meta));
      staged.push(metaPath);
      if (staged.length === 0) {
        outcome.message = '本机暂无可备份的数据：先学习几张卡或建一个计划再试。';
        return outcome;
      }
      const options: zlib.Options = {
        level: zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION
      };
      await zlib.compressFiles(staged, zipPath, options);
      const stat = fs.statSync(zipPath);
      outcome.ok = true;
      outcome.zipPath = zipPath;
      outcome.bytes = stat.size;
      outcome.message = '已生成本机备份（' + BackupStore.mbText(stat.size) + '），接着在系统文件选择器里选保存位置。';
      return outcome;
    } catch (e) {
      const be: BusinessError = e as BusinessError;
      outcome.message = BackupStore.describeError(be, '导出');
      return outcome;
    } finally {
      BackupStore.removeTree(stageDir);
    }
  }

  /**
   * 从文件选择器返回的 uri 恢复备份：
   * 解压到 cacheDir → 找三份 JSON → 校验 → 覆盖 filesDir。学习数据要重启应用才生效。
   */
  static async restoreBackup(context: common.UIAbilityContext, uri: string): Promise<BackupOutcome> {
    const outcome: BackupOutcome = emptyOutcome();
    const stamp: string = nowStamp();
    const workDir: string = context.cacheDir + '/backup-restore-' + stamp;
    const tmpZip: string = context.cacheDir + '/restore-' + stamp + '.zip';
    try {
      if (uri.length === 0) {
        outcome.message = '没有选中备份文件。';
        return outcome;
      }
      BackupStore.ensureDir(workDir);
      const src = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      try {
        fs.copyFileSync(src.fd, tmpZip);
      } finally {
        fs.closeSync(src);
      }
      await zlib.decompressFile(tmpZip, workDir);
      const dataPath: string = BackupStore.findFile(workDir, DATA_FILE);
      const plansPath: string = BackupStore.findFile(workDir, PLANS_FILE);
      const prefsPath: string = BackupStore.findFile(workDir, PREFS_FILE);
      if (dataPath.length === 0 && plansPath.length === 0 && prefsPath.length === 0) {
        outcome.message = '这个 zip 里没有 StudyKit 的备份文件（需要 studykit_data.json / studykit_plans.json / studykit_prefs.json）。';
        return outcome;
      }
      let dataCards: number = 0;
      let planCount: number = 0;
      if (dataPath.length > 0) {
        const probe: DataProbe = BackupStore.readJson(dataPath) as DataProbe;
        if (probe === null || probe === undefined) {
          outcome.message = '备份里的学习数据格式不对，可能不是本应用导出的文件。';
          return outcome;
        }
        if (probe.cards === undefined || probe.cards === null) {
          outcome.message = '备份里的学习数据缺少 cards 字段，已中止导入（本机数据未改动）。';
          return outcome;
        }
        dataCards = probe.cards.length;
      }
      if (plansPath.length > 0) {
        const probe: PlansProbe = BackupStore.readJson(plansPath) as PlansProbe;
        if (probe === null || probe === undefined || probe.plans === undefined || probe.plans === null) {
          outcome.message = '备份里的学习计划格式不对，已中止导入（本机数据未改动）。';
          return outcome;
        }
        planCount = probe.plans.length;
      }
      if (prefsPath.length > 0) {
        const probe: PrefsProbe = BackupStore.readJson(prefsPath) as PrefsProbe;
        if (probe === null || probe === undefined) {
          outcome.message = '备份里的设置格式不对，已中止导入（本机数据未改动）。';
          return outcome;
        }
      }
      if (dataPath.length > 0) {
        fs.copyFileSync(dataPath, context.filesDir + '/' + DATA_FILE);
        outcome.dataImported = true;
      }
      if (plansPath.length > 0) {
        fs.copyFileSync(plansPath, context.filesDir + '/' + PLANS_FILE);
        outcome.plansImported = true;
      }
      if (prefsPath.length > 0) {
        fs.copyFileSync(prefsPath, context.filesDir + '/' + PREFS_FILE);
        outcome.prefsImported = true;
      }
      if (outcome.prefsImported) {
        await SettingsStore.get().reload();
      }
      const parts: string[] = [];
      if (outcome.dataImported) {
        parts.push('学习数据（' + dataCards.toString() + ' 张词卡）');
      }
      if (outcome.plansImported) {
        parts.push('学习计划（' + planCount.toString() + ' 个）');
      }
      if (outcome.prefsImported) {
        parts.push('设置');
      }
      outcome.ok = true;
      outcome.message = '导入完成：' + parts.join('、') + '。'
        + (outcome.dataImported ? '学习数据要彻底关闭应用再打开才生效（否则可能被旧数据覆盖）。' : '');
      return outcome;
    } catch (e) {
      const be: BusinessError = e as BusinessError;
      outcome.message = BackupStore.describeError(be, '导入');
      return outcome;
    } finally {
      BackupStore.removeTree(workDir);
      if (fs.accessSync(tmpZip)) {
        fs.unlinkSync(tmpZip);
      }
    }
  }

  static describeError(be: BusinessError, action: string): string {
    let code: number = -1;
    if (be !== null && be !== undefined && be.code !== undefined && be.code !== null) {
      code = be.code;
    }
    if (code === 900003) {
      return action + '失败：这个文件不是有效的 zip 压缩包（可能已损坏或不是 StudyKit 备份）。';
    }
    if (code === 900001 || code === 900002 || code === 13900002) {
      return action + '失败：文件不存在或已被移走，请重新选择。';
    }
    if (code === 13900012) {
      return action + '失败：没有权限访问这个文件，请重新选择（必要时换个目录）。';
    }
    if (code === 13900025) {
      return action + '失败：设备存储空间不足，清理一些空间后再试。';
    }
    if (code === 401) {
      return action + '失败：文件选择器参数异常，请重试。';
    }
    if (code === 13900020) {
      return action + '失败：文件路径无效，请重试。';
    }
    return action + '失败（错误码 ' + code.toString() + '）。请重试；如果反复失败，把这条错误码记下来反馈。';
  }

  static mbText(bytes: number): string {
    if (bytes < 1024) {
      return bytes.toString() + ' B';
    }
    if (bytes < 1024 * 1024) {
      return (bytes / 1024).toFixed(1) + ' KB';
    }
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  /** 数据文件是否已存在（设置页展示"本机数据"用） */
  static hasData(context: common.UIAbilityContext): boolean {
    return fs.accessSync(context.filesDir + '/' + DATA_FILE);
  }

  static plansFileExists(context: common.UIAbilityContext): boolean {
    return fs.accessSync(context.filesDir + '/' + PLANS_FILE);
  }

  private static ensureDir(path: string): void {
    if (!fs.accessSync(path)) {
      fs.mkdirSync(path, true);
    }
  }

  private static writeText(path: string, text: string): void {
    const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(file.fd, text);
    } finally {
      fs.closeSync(file);
    }
  }

  private static readJson(path: string): object | null {
    try {
      const text: string = fs.readTextSync(path);
      const parsed: object = JSON.parse(text) as object;
      return parsed;
    } catch (e) {
      return null;
    }
  }

  private static removeTree(path: string): void {
    try {
      if (!fs.accessSync(path)) {
        return;
      }
      const stat = fs.statSync(path);
      if (!stat.isDirectory()) {
        fs.unlinkSync(path);
        return;
      }
      const names: string[] = fs.listFileSync(path);
      for (let i = 0; i < names.length; i++) {
        BackupStore.removeTree(path + '/' + names[i]);
      }
      fs.rmdirSync(path);
    } catch (e) {
      // 清理失败不影响主流程
    }
  }

  private static findFile(root: string, name: string): string {
    try {
      const stat = fs.statSync(root);
      if (!stat.isDirectory()) {
        return '';
      }
      const names: string[] = fs.listFileSync(root);
      for (let i = 0; i < names.length; i++) {
        const child: string = root + '/' + names[i];
        if (names[i] === name) {
          return child;
        }
        const found: string = BackupStore.findFile(child, name);
        if (found.length > 0) {
          return found;
        }
      }
    } catch (e) {
      return '';
    }
    return '';
  }
}
