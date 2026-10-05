package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.HalfLifeKernel
import com.studykit.data.memory.SchedulingKernel

/**
 * 全仓唯一知道"有哪些内核、怎么按 id 取"的地方（v2.7 计划 A Task 9）。
 *
 * 内核是**每轮会话**的决定，取自 `AppSettings.schedulingKernel`：所有排期调用点
 * （`StudyViewModel.gradeCard`、评分按钮预览、明日预告、复习提醒）都从这里取，
 * 于是"按哪个内核排"与"按哪个内核显示"结构上是同一个答案 —— 按钮上印的间隔
 * 与点下去真排出的间隔不可能分家。
 *
 * **不许**用 `words.kernel` 那一列挑内核：它记的是"上一次原生写这行的内核是谁"
 * （落库时的审计戳，spec §2.1），拿它挑会让没评分过的新行按列默认值 `FSRS` 显示、
 * 却按用户设置排期，两处各说一套。
 *
 * 坏值回 FSRS：与 `AppSettings.fromMap` 对 [com.studykit.data.AppSettings.KERNEL_IDS]
 * 之外的值一律取默认同一口径，仓里不留第二套"默认内核"的解释。两枚实例做成字段，
 * 因为它们是纯函数、无状态，每张卡都新建一次只是白扔对象。
 */
object KernelHub {
    private val fsrs = FsrsKernel()
    private val halfLife = HalfLifeKernel()

    /** 按设置里的 id 取内核；`null`（没读到设置）与垃圾值一样回默认 FSRS */
    fun forId(id: String?): SchedulingKernel = if (id == "HALF_LIFE") halfLife else fsrs

    /**
     * v2.9 Task 5：取内核时把**活跃 FSRS 参数**也带进来。纯函数、参数来自 [AppSettings]，
     * 便于 `ParamInjectionTest` 直接从设置层驱动验证"训练参数真的进入调度器"。
     *
     * 只作用于 FSRS 分支：`settings.fsrsActiveParams` 非 null → 用它构造 `FsrsKernel(w)`；
     * null（没校准过 / 脏值退回）→ 复用默认 [fsrs]（= DEFAULT_PARAMS），与旧签名 `forId(id)` 逐比特一致。
     * HALF_LIFE 不受影响：它没有 FSRS 参数维，仍按 id 返回半衰期内核。`settings` 传 null 时同 null 参数。
     */
    fun forId(id: String?, settings: AppSettings?): SchedulingKernel =
        if (id == "HALF_LIFE") {
            halfLife
        } else {
            settings?.fsrsActiveParams?.let { FsrsKernel(it) } ?: fsrs
        }
}
