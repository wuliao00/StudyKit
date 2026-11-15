import UIAbility from '@ohos.app.ability.UIAbility';
import AbilityConstant from '@ohos.app.ability.AbilityConstant';
import Want from '@ohos.app.ability.Want';
import window from '@ohos.window';
import hilog from '@ohos.hilog';
import { PpOcrEngine } from '../model/PpOcrEngine';

const TAG = 'StudyKitHarmony';

export default class EntryAbility extends UIAbility {
  // 默认仍从首页启动；仅当 aa start 显式传入 page=pages/PpOcrSelfTestPage 时直达自检页，
  // 便于 DSH 独立取证（不影响正常用户路径）。
  private startPage: string = 'pages/Index';

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    this.applyStartPage(want);
    hilog.info(0x0000, TAG, 'StudyKit HarmonyOS ability created, startPage=%{public}s', this.startPage);
  }

  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    this.applyStartPage(want);
  }

  private applyStartPage(want: Want): void {
    try {
      const params: Record<string, Object> | undefined = want.parameters;
      if (params === undefined || params === null) {
        return;
      }
      const page: Object = params['page'];
      if (typeof page === 'string' && page === 'pages/PpOcrSelfTestPage') {
        this.startPage = page;
      }
      const ppocrFlag: Object = params['ppocr'];
      if (typeof ppocrFlag === 'string' && ppocrFlag === 'off') {
        PpOcrEngine.forceDisable('aa start 参数 ppocr=off');
        hilog.info(0x0000, TAG, 'PP-OCR force disabled for failure-path test');
      }
    } catch (e) {
      hilog.warn(0x0000, TAG, 'parse start page failed');
    }
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent(this.startPage, (err) => {
      if (err.code) {
        hilog.error(0x0000, TAG, 'Failed to load content: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(0x0000, TAG, 'content loaded: %{public}s', this.startPage);
    });
  }
}
