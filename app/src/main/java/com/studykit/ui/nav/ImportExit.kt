package com.studykit.ui.nav

import androidx.navigation.NavController
import com.studykit.ui.bulkimport.ImportUiState
import com.studykit.util.importer.ImportOutcome

/**
 * 导入结果页与「离开导入流」的两处真机回归修复，收在这里是为了能被纯单测钉住
 * （不构造 ImportViewModel —— 它要 StudyKitApp 容器，Robolectric 下 WorkManager 会崩）。
 */

/**
 * 结果页「成功导入 N」的显示源。
 *
 * Bug A 根因（真机词库导入成功后显示 0 个）：DB 侧写回是对的（`imported_count` 与实际插入数一致，
 * 见 `DictImportWriteBackTest`），错在**显示源**。结果页原先写 `importState.outcome ?: ImportOutcome(0, …)`
 * —— 一旦 `outcome` 被抹掉就伪造出一个 0 的账目；而退出回调在 pop 之前先调了 `importViewModel.reset()`
 * 把这条共享 StateFlow 清空。dict/OCR 那条路 pop 又是空操作（见 [popBackOutOfImportFlow]），结果页还挂在
 * 组合里，读到的正是被清成 null 的 outcome → 渲染成「成功导入 0」。
 *
 * 契约：这里**绝不为 null 伪造 0**。真读不到就交回 null，由调用方显示能离开的占位，而不是谎报军情。
 */
internal fun importResultOutcomeFor(state: ImportUiState): ImportOutcome? = state.outcome

/**
 * 退出导入流时「最终落在哪一屏」的决策（返回栈以 `destination.route` 模板表示，底→顶）。
 *
 * Bug B 根因（词库录入成功后点「好」反复留在原地）：退出原先死盯 `ImportRoutes.PASTE` 作 pop 目标。
 * 但只有「批量粘贴」那条路的返回栈里才有 paste 页——在线词库（words→store→preview→result）与截图取词
 * （words→preview→result）根本不经过 paste。`popBackStack` 找不到目标就返回 false、原地不动，用户于是
 * 困在结果页。
 *
 * 判据是「paste 在不在栈里」这一条，与 [popBackOutOfImportFlow] 用 `popBackStack` 返回值复刻的分支同源：
 *  - 在 ⇒ 粘贴路径：照旧弹过/停在 paste（inclusive 决定停在哪，语义一字不改）；
 *  - 不在 ⇒ 词库 / 截图取词：把结果页与预览页一起弹掉，`ImportRoutes.PREVIEW` 是三入口唯一共有的父路由，
 *    落在它下面那一屏——正是进入导入流之前用户站着的地方。
 */
internal fun importExitLanding(routes: List<String?>, inclusive: Boolean): String? {
    val paste = routes.lastIndexOf(ImportRoutes.PASTE)
    if (paste >= 0) {
        // 粘贴路径：inclusive=true 弹过 paste 落在它下面那一屏，inclusive=false 就停在 paste 上改那几行。
        return if (inclusive) routes.getOrNull(paste - 1) else ImportRoutes.PASTE
    }
    // 没有 paste：连弹结果页与预览页，落在预览页下面那一屏。
    return routes.getOrNull(routes.lastIndexOf(ImportRoutes.PREVIEW) - 1)
}

/**
 * 把 [importExitLanding] 的决策落到真实导航上。只用公开 API：`popBackStack` 命中当且仅当目标在栈里，
 * 于是「先试 PASTE，返回 false 就说明这条栈没有 paste」正是 Bug B 的空操作信号，紧接着兜底离开。
 */
internal fun popBackOutOfImportFlow(navController: NavController, inclusive: Boolean) {
    // 粘贴路径：栈里有 paste，弹它 —— 命中即返回 true，行为与从前一字不差。
    if (navController.popBackStack(ImportRoutes.PASTE, inclusive = inclusive)) return
    // 在线词库 / 截图取词：没有 paste，上面那句是 no-op。连弹结果页与预览页，落在进入导入流之前那一屏。
    navController.popBackStack(ImportRoutes.RESULT, inclusive = true)
    navController.popBackStack(ImportRoutes.PREVIEW, inclusive = true)
}
