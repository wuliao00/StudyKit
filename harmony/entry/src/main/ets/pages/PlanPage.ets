import router from '@ohos.router';
import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';
import { Theme } from '../common/Theme';
import { StudyStore } from '../model/Store';
import { PlanStore, PlanTemplate, PlanTemplates, StudyPlan, plansToday } from '../model/PlanStore';
import { SettingsStore } from '../model/BackupStore';

// 学习计划页：6 个内置模板 → 改字段 → 实时预览 → 保存/删除（文件落 filesDir/studykit_plans.json）。
// 不碰 Index/Store：学习数据只读，本页只写自己的计划文件。

const MODE_LIST: number = 0;
const MODE_EDITOR: number = 1;

@Entry
@Component
struct PlanPage {
  @State loading: boolean = true;
  @State mode: number = MODE_LIST;
  @State plans: StudyPlan[] = [];
  @State reminderEnabled: boolean = false;
  @State todayReviewed: number = 0;
  @State dailyGoal: number = 30;
  @State formError: string = '';

  // 编辑器字段（一个计划一行；不用对象 @State，避免 ArkTS 深观测问题）
  @State editId: string = '';
  @State templateId: string = '';
  @State titleInput: string = '';
  @State goalInput: string = '';
  @State daysValue: number = 7;
  @State minutesValue: number = 25;
  @State stepsInput: string = '';
  @State noteInput: string = '';

  private store: StudyStore = StudyStore.get();
  private planStore: PlanStore = PlanStore.get();
  private prefs: SettingsStore = SettingsStore.get();

  async aboutToAppear(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    try {
      await this.store.init(ctx);
      await this.planStore.init(ctx);
      await this.prefs.init(ctx);
    } catch (e) {
      promptAction.showToast({ message: '读取本机计划失败，已按空列表显示' });
    }
    this.refresh();
    this.loading = false;
  }

  private refresh(): void {
    const all: StudyPlan[] = this.planStore.allPlans();
    const copy: StudyPlan[] = [];
    for (let i = 0; i < all.length; i++) {
      copy.push(all[i]);
    }
    this.plans = copy;
    this.reminderEnabled = this.prefs.reminderEnabled;
    this.dailyGoal = this.prefs.dailyGoal;
    const today: string = plansToday();
    this.todayReviewed = this.store.todayReviewCount(today);
  }

  private toast(text: string): void {
    promptAction.showToast({ message: text });
  }

  // ---------- 模板 → 编辑器 ----------

  private startFromTemplate(template: PlanTemplate): void {
    const draft: StudyPlan = PlanStore.templatePlan(template);
    this.editId = '';
    this.templateId = template.id;
    this.titleInput = draft.title;
    this.goalInput = draft.goal;
    this.daysValue = draft.days;
    this.minutesValue = draft.dailyMinutes;
    this.stepsInput = PlanStore.stepsToText(draft.steps);
    this.noteInput = '';
    this.formError = '';
    this.mode = MODE_EDITOR;
  }

  private editPlan(plan: StudyPlan): void {
    this.editId = plan.id;
    this.templateId = plan.templateId;
    this.titleInput = plan.title;
    this.goalInput = plan.goal;
    this.daysValue = plan.days;
    this.minutesValue = plan.dailyMinutes;
    this.stepsInput = PlanStore.stepsToText(plan.steps);
    this.noteInput = plan.note;
    this.formError = '';
    this.mode = MODE_EDITOR;
  }

  private backToList(): void {
    this.mode = MODE_LIST;
    this.formError = '';
    this.refresh();
  }

  private stepsPreview(): string[] {
    return PlanStore.textToSteps(this.stepsInput);
  }

  private previewTitle(): string {
    const trimmed: string = this.titleInput.trim();
    return trimmed.length > 0 ? trimmed : '（还没写名称）';
  }

  private previewGoal(): string {
    const trimmed: string = this.goalInput.trim();
    return trimmed.length > 0 ? trimmed : '（还没写目标）';
  }

  private templateLabel(): string {
    const template: PlanTemplate | null = PlanTemplates.find(this.templateId);
    return template === null ? '自定义计划' : template.method;
  }

  private changeDays(delta: number): void {
    const next: number = this.daysValue + delta;
    if (next < 1 || next > 365) {
      return;
    }
    this.daysValue = next;
  }

  private changeMinutes(delta: number): void {
    const next: number = this.minutesValue + delta;
    if (next < 5 || next > 240) {
      return;
    }
    this.minutesValue = next;
  }

  private async save(): Promise<void> {
    const title: string = this.titleInput.trim();
    const steps: string[] = this.stepsPreview();
    if (title.length === 0) {
      this.formError = '先给计划起个名字（例如「CET4 考前一周」）。';
      return;
    }
    if (steps.length === 0) {
      this.formError = '至少写一步今天能做的动作；一行一步。';
      return;
    }
    this.formError = '';
    const now: number = Date.now();
    const existing: StudyPlan | null = this.editId.length > 0 ? this.planStore.findPlan(this.editId) : null;
    const plan: StudyPlan = {
      id: existing === null ? PlanStore.newId() : existing.id,
      templateId: this.templateId,
      title: title,
      goal: this.goalInput.trim(),
      startDate: existing === null ? plansToday() : existing.startDate,
      days: this.daysValue,
      dailyMinutes: this.minutesValue,
      steps: steps,
      note: this.noteInput.trim(),
      createdAt: existing === null ? now : existing.createdAt,
      updatedAt: now
    };
    try {
      await this.planStore.savePlan(plan);
      this.toast(existing === null ? '计划已保存到本机' : '计划已更新');
      this.mode = MODE_LIST;
      this.refresh();
    } catch (e) {
      this.formError = '保存失败：写入本机 JSON 时出错了，请重试。';
    }
  }

  private confirmDelete(plan: StudyPlan): void {
    AlertDialog.show({
      title: '删除计划',
      message: '删除「' + plan.title + '」？只删这份学习计划，不动学习数据。',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '删除',
        fontColor: Theme.dangerInk,
        action: () => {
          this.deletePlan(plan.id);
        }
      }
    });
  }

  private async deletePlan(id: string): Promise<void> {
    try {
      const removed: boolean = await this.planStore.deletePlan(id);
      this.refresh();
      this.toast(removed ? '计划已删除' : '计划已经不存在');
    } catch (e) {
      this.toast('删除失败，请重试');
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
        Text('计划只存本机').fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%').padding({ top: 4, bottom: 10 })
      Text(this.mode === MODE_EDITOR ? (this.editId.length > 0 ? '编辑计划' : '新建计划') : '学习计划')
        .fontSize(34).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
      Text(this.mode === MODE_EDITOR
        ? '改字段，下面那张卡就是存下来的样子；保存后写进本机 JSON。'
        : '选一个模板改造成自己的计划，或继续上次的计划；数据只在本机。')
        .fontSize(12).fontColor(Theme.secondaryText).margin({ top: 4 }).lineHeight(18)
    }.alignItems(HorizontalAlign.Start).width('100%').padding({ top: 6, bottom: 14 })
  }

  @Builder
  reminderBanner() {
    if (this.reminderEnabled) {
      Row() {
        Text('🔔').fontSize(13)
        Text(this.todayReviewed <= 0
          ? '提醒已开：今天还没开始。挑一个模板，先做 10 分钟也算开工。'
          : '提醒已开：今天已复习 ' + this.todayReviewed.toString() + ' 词（每日目标 '
            + this.dailyGoal.toString() + '），继续保持。')
          .fontSize(11).fontColor(Theme.goldInk).layoutWeight(1).margin({ left: 6 }).lineHeight(16)
      }.width('100%').padding(12).backgroundColor(Theme.goldSoft).borderRadius(12)
      .margin({ bottom: 14 })
    } else {
      Row() {
        Text('提醒关着：设置页打开「本地提醒」后，这里会显示今日开工提示。').fontSize(10)
          .fontColor(Theme.secondaryText).layoutWeight(1)
      }.width('100%').padding(10).backgroundColor(Theme.cardWarm).borderRadius(12)
      .margin({ bottom: 14 })
    }
  }

  @Builder
  templateCard(template: PlanTemplate) {
    Column() {
      Row() {
        Text(template.title).fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Text(template.method).fontSize(10).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
          .padding({ left: 7, right: 7, top: 3, bottom: 3 }).borderRadius(9).margin({ left: 8 })
        Blank()
        Text(template.days.toString() + ' 天 · ' + template.dailyMinutes.toString() + ' 分/天')
          .fontSize(10).fontColor(Theme.secondaryText)
      }.width('100%')
      Text(template.tagline).fontSize(11).fontColor(Theme.secondaryText).margin({ top: 6 }).lineHeight(17)
      Row() {
        Text('目标：' + template.goal).fontSize(10).fontColor(Theme.secondaryText)
          .layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        Button('用这个模板').height(34).fontSize(12).margin({ left: 8 })
          .backgroundColor(Theme.accentSoft).fontColor(Theme.accentInk)
          .onClick(() => {
            this.startFromTemplate(template);
          })
      }.width('100%').margin({ top: 10 }).alignItems(VerticalAlign.Center)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(14)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ bottom: 10 })
  }

  @Builder
  savedPlanCard(plan: StudyPlan) {
    Column() {
      Row() {
        Text(plan.title).fontSize(16).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(plan.days.toString() + ' 天 · ' + plan.dailyMinutes.toString() + ' 分/天')
          .fontSize(10).fontColor(Theme.secondaryText)
      }.width('100%')
      if (plan.goal.length > 0) {
        Text('目标：' + plan.goal).fontSize(11).fontColor(Theme.secondaryText)
          .margin({ top: 5 }).lineHeight(17).maxLines(2)
      }
      Text(this.planStepLine(plan)).fontSize(10).fontColor(Theme.secondaryText)
        .margin({ top: 5 }).lineHeight(16).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      Row() {
        Text('开始于 ' + plan.startDate + ' · 共 ' + plan.steps.length.toString() + ' 步')
          .fontSize(10).fontColor(Theme.secondaryText).layoutWeight(1)
        Button('编辑').height(32).fontSize(12)
          .backgroundColor(Theme.cardWarm).fontColor(Theme.accentInk)
          .onClick(() => {
            this.editPlan(plan);
          })
        Button('删除').height(32).fontSize(12).margin({ left: 8 })
          .backgroundColor(Theme.dangerSoft).fontColor(Theme.dangerInk)
          .onClick(() => {
            this.confirmDelete(plan);
          })
      }.width('100%').margin({ top: 10 }).alignItems(VerticalAlign.Center)
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(14)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius).margin({ bottom: 10 })
  }

  private planStepLine(plan: StudyPlan): string {
    if (plan.steps.length === 0) {
      return '还没有步骤';
    }
    let text: string = '步骤：';
    for (let i = 0; i < plan.steps.length && i < 3; i++) {
      if (i > 0) {
        text += ' → ';
      }
      text += plan.steps[i];
    }
    if (plan.steps.length > 3) {
      text += ' …';
    }
    return text;
  }

  @Builder
  previewCard() {
    Column() {
      Row() {
        Text('实时预览').fontSize(12).fontColor(Theme.goldInk).backgroundColor(Theme.goldSoft)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8)
        Blank()
        Text('保存后长这样').fontSize(10).fontColor(Theme.secondaryText)
      }.width('100%')
      Text(this.previewTitle()).fontSize(20).fontWeight(FontWeight.Bold).fontColor(Theme.primaryText)
        .margin({ top: 10 }).maxLines(2)
      Text(this.templateLabel() + ' · ' + this.daysValue.toString() + ' 天 · 每天 '
        + this.minutesValue.toString() + ' 分钟 · 从 ' + plansToday() + ' 开始')
        .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 5 })
      Text('目标：' + this.previewGoal()).fontSize(12).fontColor(Theme.primaryText)
        .margin({ top: 8 }).lineHeight(18)
      if (this.stepsPreview().length > 0) {
        Column() {
          ForEach(this.stepsPreview(), (step: string, index: number) => {
            Row() {
              Text((index + 1).toString() + '.').fontSize(10).fontColor(Theme.accentInk)
                .width(18)
              Text(step).fontSize(11).fontColor(Theme.primaryText).layoutWeight(1).lineHeight(17)
            }.width('100%').alignItems(VerticalAlign.Top).margin({ top: 4 })
          }, (step: string, index: number) => index.toString() + step)
        }.width('100%').margin({ top: 8 })
      }
      if (this.noteInput.trim().length > 0) {
        Text('备注：' + this.noteInput.trim()).fontSize(10).fontColor(Theme.secondaryText)
          .margin({ top: 8 }).lineHeight(16)
      }
      Text('共 ' + this.stepsPreview().length.toString() + ' 步 · 只存在这台设备上').fontSize(10)
        .fontColor(Theme.secondaryText).margin({ top: 10 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)
    .border({ width: 1, color: Theme.divider })
  }

  @Builder
  editorForm() {
    Column() {
      Text('计划名称').fontSize(12).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
      TextInput({ placeholder: '例如：CET4 考前一周', text: this.titleInput })
        .height(44).fontSize(14).margin({ top: 6 })
        .backgroundColor(Theme.card).borderRadius(10)
        .onChange((value: string) => {
          this.titleInput = value;
        })

      Text('目标（做到什么算完成）').fontSize(12).fontWeight(FontWeight.Medium)
        .fontColor(Theme.primaryText).margin({ top: 14 })
      TextArea({ placeholder: '例如：同类题正确率稳定过 80%', text: this.goalInput })
        .height(66).fontSize(13).margin({ top: 6 })
        .backgroundColor(Theme.card).borderRadius(10)
        .onChange((value: string) => {
          this.goalInput = value;
        })

      Row() {
        Column() {
          Text('周期天数').fontSize(12).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Row() {
            Button('−').width(38).height(38).fontSize(20)
              .backgroundColor(Theme.card).fontColor(Theme.accentInk)
              .onClick(() => {
                this.changeDays(-1);
              })
            Text(this.daysValue.toString() + ' 天').fontSize(14).fontWeight(FontWeight.Medium)
              .fontColor(Theme.primaryText).width(64).textAlign(TextAlign.Center)
            Button('+').width(38).height(38).fontSize(20)
              .backgroundColor(Theme.card).fontColor(Theme.accentInk)
              .onClick(() => {
                this.changeDays(1);
              })
          }.margin({ top: 6 })
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Column() {
          Text('每天分钟').fontSize(12).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
          Row() {
            Button('−').width(38).height(38).fontSize(20)
              .backgroundColor(Theme.card).fontColor(Theme.accentInk)
              .onClick(() => {
                this.changeMinutes(-5);
              })
            Text(this.minutesValue.toString() + ' 分').fontSize(14).fontWeight(FontWeight.Medium)
              .fontColor(Theme.primaryText).width(64).textAlign(TextAlign.Center)
            Button('+').width(38).height(38).fontSize(20)
              .backgroundColor(Theme.card).fontColor(Theme.accentInk)
              .onClick(() => {
                this.changeMinutes(5);
              })
          }.margin({ top: 6 })
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      }.width('100%').margin({ top: 14 })

      Text('步骤（一行一步，今天就能做的动作）').fontSize(12).fontWeight(FontWeight.Medium)
        .fontColor(Theme.primaryText).margin({ top: 14 })
      TextArea({ placeholder: '一行一步，例如：\n选一个概念\n用大白话讲一遍', text: this.stepsInput })
        .height(140).fontSize(12).margin({ top: 6 })
        .backgroundColor(Theme.card).borderRadius(10)
        .onChange((value: string) => {
          this.stepsInput = value;
        })

      Text('备注（可选）').fontSize(12).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        .margin({ top: 14 })
      TextArea({ placeholder: '例如：周末只做套卷', text: this.noteInput })
        .height(56).fontSize(12).margin({ top: 6 })
        .backgroundColor(Theme.card).borderRadius(10)
        .onChange((value: string) => {
          this.noteInput = value;
        })

      if (this.formError.length > 0) {
        Text(this.formError).fontSize(11).fontColor(Theme.dangerInk)
          .margin({ top: 10 }).lineHeight(16)
      }

      Row() {
        Button('取消').layoutWeight(1).height(46).fontSize(14)
          .backgroundColor(Theme.card).fontColor(Theme.secondaryText)
          .onClick(() => {
            this.backToList();
          })
        Button(this.editId.length > 0 ? '保存修改' : '保存计划').layoutWeight(1).height(46)
          .fontSize(14).margin({ left: 10 })
          .backgroundColor(Theme.accentInk).fontColor(Color.White)
          .onClick(() => {
            this.save();
          })
      }.width('100%').margin({ top: 16 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(16)
    .backgroundColor(Theme.card).borderRadius(Theme.cardRadius)
  }

  @Builder
  listView() {
    Column() {
      this.reminderBanner()
      Text('模板：挑一个改造成自己的').fontSize(15).fontWeight(FontWeight.Medium)
        .fontColor(Theme.primaryText).width('100%').margin({ bottom: 10 })
      ForEach(PlanTemplates.all(), (template: PlanTemplate) => {
        this.templateCard(template)
      }, (template: PlanTemplate) => template.id)

      Row() {
        Text('我的计划').fontSize(15).fontWeight(FontWeight.Medium).fontColor(Theme.primaryText)
        Blank()
        Text(this.plans.length.toString() + ' 个').fontSize(11).fontColor(Theme.secondaryText)
      }.width('100%').margin({ top: 10, bottom: 10 })
      if (this.plans.length === 0) {
        Column() {
          Text('还没有保存的计划').fontSize(13).fontColor(Theme.primaryText)
          Text('选上面任意模板，改一改再保存，就会出现在这里。').fontSize(11)
            .fontColor(Theme.secondaryText).margin({ top: 4 })
        }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
        .backgroundColor(Theme.cardWarm).borderRadius(Theme.cardRadius)
      } else {
        ForEach(this.plans, (plan: StudyPlan) => {
          this.savedPlanCard(plan)
        }, (plan: StudyPlan) => plan.id + plan.updatedAt.toString())
      }
      Text('口径：计划只是本机的学习清单，不会改动排期算法；删除计划不影响词卡与复习记录。')
        .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 10, bottom: 30 }).lineHeight(16)
    }.width('100%')
  }

  @Builder
  editorView() {
    Column() {
      this.editorForm()
      Column() {
        this.previewCard()
      }.width('100%').margin({ top: 14 })
      Text('保存位置：应用 filesDir/studykit_plans.json；换设备/清数据前先到设置页导出备份。')
        .fontSize(10).fontColor(Theme.secondaryText).margin({ top: 12, bottom: 30 }).lineHeight(16)
    }.width('100%')
  }

  build() {
    Scroll() {
      Row() {
        Column() {
          this.header()
          if (this.loading) {
            Column() {
              LoadingProgress().width(40).height(40).color(Theme.accent)
              Text('正在读取本机计划').fontSize(12).fontColor(Theme.secondaryText).margin({ top: 8 })
            }.width('100%').height(240).justifyContent(FlexAlign.Center)
          } else if (this.mode === MODE_EDITOR) {
            this.editorView()
          } else {
            this.listView()
          }
        }.width('100%').constraintSize({ maxWidth: 760 })
      }.width('100%').justifyContent(FlexAlign.Center)
      .padding({ left: Theme.pagePadding, right: Theme.pagePadding })
    }
    .width('100%').height('100%').backgroundColor(Theme.bg).scrollBar(BarState.Off)
  }
}
