package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.HalfLifeKernel
import com.studykit.data.memory.SchedulingKernel

/**
 * 全仓唯一知道"有哪些内核、按哪一份参数跑"的地方（v2.7 计划 A Task 9）。
 *
 * 内核是**每轮会话**的决定，取自 `AppSettings`：所有排期调用点
 * （`StudyViewModel.gradeCard`、评分按钮预览、明日预告、错题重做、书摘复习、复习提醒）都从这里取，
 * 于是"按哪个内核排"与"按哪个内核显示"结构上是同一个答案 —— 按钮上印的间隔
 * 与点下去真排出的间隔不可能分家。
 *
 * **v3.0-A Task 2.6：取内核必须带 [AppSettings]，没有单参的那条路。**
 * v2.9 给这里加过双参重载（`settings.fsrsActiveParams` 非空就用它构造 `FsrsKernel(w)`），
 * 但**单参 `forId(id)` 一直留着**，而全部生产调用点留着的正是那一条 —— 于是"采纳的参数回流调度器"
 * 只在单测层为真，真机上排期与显示都还在跑发布默认参数。修法是把那条重载**删掉**：
 * 要内核就得把手上那份设置一起交出来，忘了就是编译不过。留个警告或留个默认值都会让下一个调用点再忘一次。
 *
 * **不许**用 `words.kernel` 那一列挑内核：它记的是"上一次原生写这行的内核是谁"
 * （落库时的审计戳，spec §2.1），拿它挑会让没评分过的新行按列默认值 `FSRS` 显示、
 * 却按用户设置排期，两处各说一套。
 *
 * 坏值回 FSRS：与 `AppSettings.fromMap` 对 [com.studykit.data.AppSettings.KERNEL_IDS]
 * 之外的值一律取默认同一口径，仓里不留第二套"默认内核"的解释。半衰期那枚实例做成字段，
 * 因为它是纯函数、无状态，每张卡都新建一次只是白扔对象；FSRS 那侧反过来**必须每次新建**，
 * 否则采纳过的参数会被那份缓存吃掉。
 */
object KernelHub {
    private val halfLife = HalfLifeKernel()

    /**
     * 按**那份设置**取活跃内核：id 决定哪个内核，[AppSettings.fsrsActiveParams] 决定 FSRS
     * 用哪一份 21 维向量。
     *
     * 只作用于 FSRS 分支：`fsrsActiveParams` 非 null → 用它构造 `FsrsKernel(w)`；
     * null（从没校准过 / 脏值退回）→ 取发布默认 [com.studykit.data.memory.FsrsModel.DEFAULT_PARAMS]。
     * HALF_LIFE 不受影响：它没有 FSRS 参数维，仍按 id 返回半衰期内核。
     *
     * [settings] 传 null 只有一种正当读法：**还没读到设置**（首帧、或调用方手上确实没有那份快照），
     * 此时退回默认参数。它**不是**"这个调用点不参与校准"的开关——调用点只要拿得到设置就必须传进来，
     * 否则显示与排期就会一边按校准值、一边按默认值（v2.9 就是这么漏掉的）。
     */
    fun forId(id: String?, settings: AppSettings?): SchedulingKernel =
        if (id == "HALF_LIFE") {
            halfLife
        } else {
            settings?.fsrsActiveParams?.let { FsrsKernel(it) } ?: FsrsKernel()
        }
}
