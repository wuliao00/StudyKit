package com.studykit.util

import android.widget.Toast
import androidx.lifecycle.AndroidViewModel

/**
 * ViewModel 往界面上说一句话的唯一实现。
 *
 * 为什么收到这里：本仓曾有**五份逐字相同**的 `private fun toast` / `rejectWithToast`
 * （`BookViewModel` / `MistakeViewModel` / `StudyViewModel` / `HabitViewModel` /
 * `ContractsViewModel`），两个名字各管一半调用点。同形 helper 的代价不是难看，是
 * **改不动**：想给所有 toast 统一加时长/加埋点/换成 Snackbar，得先记全有五处、
 * 再确认没有第六处漏了 —— 而下一处会在没人注意时再加回来。
 *
 * 两个名字都留着，但**只有一个实现体**：
 * - [toast] 是通用出口（成功提示、失败提示都走它）；
 * - [rejectWithToast] 专门给"用户按了一下、而这一下**没有生效**"的场合。
 *   分开不是为了好看 —— "拒绝要说话"是本仓的一条纪律（`HabitViewModel` 的补卡开关
 *   曾经关掉后照样能点、点完静默不记账，界面上零痕迹，见 CHANGELOG 2.4.2）。
 *   留一个可 grep 的名字，才能回答"到底有没有哪条拒绝路径在装死"。
 *   新增拒绝分支时优先用这一个；纯告知才用 [toast]。
 */
fun AndroidViewModel.toast(message: String) {
    Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
}

/** 见 [toast] 的说明：这一条专用于"那一下被挡下了"，实现就是 [toast]。 */
fun AndroidViewModel.rejectWithToast(message: String) = toast(message)
