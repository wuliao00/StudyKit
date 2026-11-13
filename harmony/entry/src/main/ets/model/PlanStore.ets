import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';

// 学习计划模板 + 本机计划存储。
// 数据只落在应用沙箱 filesDir/studykit_plans.json，不上传、不依赖账号与网络。
// 文件独立于 studykit_data.json（Store.ets 的写范围），互不踩。

export interface PlanTemplate {
  id: string;
  title: string;
  method: string;
  tagline: string;
  goal: string;
  days: number;
  dailyMinutes: number;
  steps: string[];
}

export interface StudyPlan {
  id: string;
  templateId: string;
  title: string;
  goal: string;
  startDate: string;
  days: number;
  dailyMinutes: number;
  steps: string[];
  note: string;
  createdAt: number;
  updatedAt: number;
}

interface PlansFile {
  version: number;
  plans: StudyPlan[];
}

const PLANS_FILE: string = 'studykit_plans.json';
const PLANS_VERSION: number = 1;

const TEMPLATES: PlanTemplate[] = [
  {
    id: 'feynman',
    title: '费曼学习法',
    method: '讲给自己听',
    tagline: '每天挑一个概念，用大白话讲明白，讲不通的地方就是没学懂的地方。',
    goal: '把一个单元/一章的核心概念讲到"外行也能听懂"',
    days: 7,
    dailyMinutes: 25,
    steps: [
      '选一个今天要弄懂的概念，写下它',
      '用大白话写一段解释（不许抄书）',
      '找出卡住的地方，回去查书或例题',
      '把解释压到 3 句话，再讲一遍',
      '睡前用 1 分钟自测：还能讲出来吗'
    ]
  },
  {
    id: 'drill',
    title: '习题带练',
    method: '刻意练习',
    tagline: '先做 5 题找错，再只练错的那一类，最后混着做一遍。',
    goal: '同类题正确率从"看运气"变成稳定过 80%',
    days: 14,
    dailyMinutes: 40,
    steps: [
      '限时做 5 道同类题，不查答案',
      '对答案，把错因写成一句话',
      '只针对错因做 2 道变式题',
      '隔一天回做同一批错题',
      '一周一次混科小测，记录正确率'
    ]
  },
  {
    id: 'project',
    title: '项目式学习',
    method: '做点真东西',
    tagline: '给自己一个小项目，学的东西必须有地方用，做完就算学会。',
    goal: '产出一个能给别人看的小作品/小报告',
    days: 21,
    dailyMinutes: 45,
    steps: [
      '定项目：做出来给谁看、看什么',
      '拆成 3 个可验收的小里程碑',
      '每天完成一个小任务并留记录',
      '卡住时先查资料，再问人',
      '最后一天做展示和复盘'
    ]
  },
  {
    id: 'exam-week',
    title: '备考周计划',
    method: '考前一周',
    tagline: '按考试日期倒排：前 4 天补短板，中间 2 天套卷，最后 1 天只看错题。',
    goal: '考前一周不慌乱，每科都有明确清单',
    days: 7,
    dailyMinutes: 60,
    steps: [
      '列出各科最可能失分的 3 个点',
      '第 1-4 天：每天补一个短板点',
      '第 5-6 天：完整做一套卷并计时',
      '整理套卷错题，只留高频错因',
      '最后一天：只看错题清单，早睡'
    ]
  },
  {
    id: 'reading',
    title: '阅读计划',
    method: '每天一点',
    tagline: '固定时间读固定页数，读完写 3 行摘要，周末整理一次笔记。',
    goal: '30 天读完一本书并留下可回看的笔记',
    days: 30,
    dailyMinutes: 20,
    steps: [
      '把书按页数切成 30 份',
      '每天固定时段读一份',
      '读完写 3 行摘要（不抄原文）',
      '周末把 7 天摘要合成一段',
      '最后一天写一页总复盘'
    ]
  },
  {
    id: 'habit',
    title: '习惯养成计划',
    method: '微习惯',
    tagline: '每天只做一个最小动作，先连上 7 天，再加量，不靠意志力硬撑。',
    goal: '连续 30 天不掉链子，形成固定触发点',
    days: 30,
    dailyMinutes: 10,
    steps: [
      '把目标缩到 2 分钟内能完成的动作',
      '绑定一个已有习惯（如早饭后）',
      '每天完成后在应用里留一条记录',
      '第 7 天复盘：哪一步最想放弃',
      '第 30 天再决定要不要加量'
    ]
  }
];

export class PlanTemplates {
  static all(): PlanTemplate[] {
    return TEMPLATES;
  }

  static find(id: string): PlanTemplate | null {
    for (let i = 0; i < TEMPLATES.length; i++) {
      if (TEMPLATES[i].id === id) {
        return TEMPLATES[i];
      }
    }
    return null;
  }
}

function pad2(value: number): string {
  return value < 10 ? '0' + value.toString() : value.toString();
}

export function plansToday(): string {
  const d = new Date();
  return d.getFullYear().toString() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

export class PlanStore {
  private static instance: PlanStore = new PlanStore();
  private filePath: string = '';
  private data: PlansFile = { version: PLANS_VERSION, plans: [] };
  private loaded: boolean = false;

  static get(): PlanStore {
    return PlanStore.instance;
  }

  static templatePlan(template: PlanTemplate): StudyPlan {
    const now: number = Date.now();
    return {
      id: PlanStore.newId(),
      templateId: template.id,
      title: template.title,
      goal: template.goal,
      startDate: plansToday(),
      days: template.days,
      dailyMinutes: template.dailyMinutes,
      steps: template.steps.slice(0, template.steps.length),
      note: '',
      createdAt: now,
      updatedAt: now
    };
  }

  static newId(): string {
    return 'plan-' + Date.now().toString() + '-' + Math.floor(Math.random() * 1000).toString();
  }

  async init(context: common.UIAbilityContext): Promise<void> {
    if (this.filePath.length > 0) {
      return;
    }
    this.filePath = context.filesDir + '/' + PLANS_FILE;
    await this.reload();
  }

  async reload(): Promise<void> {
    this.loaded = true;
    if (this.filePath.length === 0) {
      return;
    }
    try {
      if (!fs.accessSync(this.filePath)) {
        this.data = { version: PLANS_VERSION, plans: [] };
        return;
      }
      const text: string = fs.readTextSync(this.filePath);
      const raw: PlansFile = JSON.parse(text) as PlansFile;
      this.data = PlanStore.normalize(raw);
    } catch (e) {
      this.data = { version: PLANS_VERSION, plans: [] };
    }
  }

  private static normalize(raw: PlansFile): PlansFile {
    const out: PlansFile = { version: PLANS_VERSION, plans: [] };
    if (raw === null || raw === undefined) {
      return out;
    }
    if (raw.plans === undefined || raw.plans === null) {
      return out;
    }
    for (let i = 0; i < raw.plans.length; i++) {
      const p: StudyPlan = raw.plans[i];
      if (p === null || p === undefined) {
        continue;
      }
      const steps: string[] = (p.steps === undefined || p.steps === null) ? [] : p.steps;
      const fixed: StudyPlan = {
        id: (p.id === undefined || p.id === null || p.id.length === 0) ? PlanStore.newId() : p.id,
        templateId: (p.templateId === undefined || p.templateId === null) ? '' : p.templateId,
        title: (p.title === undefined || p.title === null || p.title.length === 0) ? '未命名计划' : p.title,
        goal: (p.goal === undefined || p.goal === null) ? '' : p.goal,
        startDate: (p.startDate === undefined || p.startDate === null) ? plansToday() : p.startDate,
        days: (p.days === undefined || p.days === null || p.days <= 0) ? 7 : p.days,
        dailyMinutes: (p.dailyMinutes === undefined || p.dailyMinutes === null || p.dailyMinutes <= 0)
          ? 20 : p.dailyMinutes,
        steps: steps,
        note: (p.note === undefined || p.note === null) ? '' : p.note,
        createdAt: (p.createdAt === undefined || p.createdAt === null) ? Date.now() : p.createdAt,
        updatedAt: (p.updatedAt === undefined || p.updatedAt === null) ? Date.now() : p.updatedAt
      };
      out.plans.push(fixed);
    }
    return out;
  }

  private async save(): Promise<void> {
    if (this.filePath.length === 0) {
      return;
    }
    const file = fs.openSync(this.filePath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(file.fd, JSON.stringify(this.data));
    } finally {
      fs.closeSync(file);
    }
  }

  get isLoaded(): boolean {
    return this.loaded;
  }

  allPlans(): StudyPlan[] {
    return this.data.plans;
  }

  planCount(): number {
    return this.data.plans.length;
  }

  findPlan(id: string): StudyPlan | null {
    for (let i = 0; i < this.data.plans.length; i++) {
      if (this.data.plans[i].id === id) {
        return this.data.plans[i];
      }
    }
    return null;
  }

  async savePlan(plan: StudyPlan): Promise<void> {
    plan.updatedAt = Date.now();
    let hit: boolean = false;
    for (let i = 0; i < this.data.plans.length; i++) {
      if (this.data.plans[i].id === plan.id) {
        this.data.plans[i] = plan;
        hit = true;
        break;
      }
    }
    if (!hit) {
      this.data.plans.push(plan);
    }
    await this.save();
  }

  async deletePlan(id: string): Promise<boolean> {
    const next: StudyPlan[] = [];
    let removed: boolean = false;
    for (let i = 0; i < this.data.plans.length; i++) {
      if (this.data.plans[i].id === id) {
        removed = true;
      } else {
        next.push(this.data.plans[i]);
      }
    }
    if (removed) {
      this.data.plans = next;
      await this.save();
    }
    return removed;
  }

  async clearAll(): Promise<void> {
    this.data = { version: PLANS_VERSION, plans: [] };
    await this.save();
  }

  static stepsToText(steps: string[]): string {
    let text: string = '';
    for (let i = 0; i < steps.length; i++) {
      if (i > 0) {
        text += '\n';
      }
      text += steps[i];
    }
    return text;
  }

  static textToSteps(text: string): string[] {
    const result: string[] = [];
    const lines: string[] = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line: string = lines[i].trim();
      if (line.length > 0) {
        result.push(line);
      }
    }
    return result;
  }
}
