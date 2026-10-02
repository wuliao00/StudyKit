package com.studykit.data.memory

/**
 * 超纠正侧信道（app.docx 模块2 P1 + Butler 2011 回弹证据，spec §2.3）。
 * 判定与排期内核无关：任何内核排完正常间隔之后，额外问一句"要不要当日再见一次"。
 * 只有 SURE×未忆起 触发；其余组合返回 null = 不干预。
 * 10 分钟这个数与 FsrsKernel.TEN_MINUTES_IN_DAYS / MemoryModel 的忘记地板同源，不另造。
 */
object Hypercorrection {
    fun retestDelayMinutes(conf: Confidence?, recalled: Boolean): Long? =
        if (conf == Confidence.SURE && !recalled) RETEST_DELAY_MINUTES else null

    /** 与内核"当日再见"地板同值；单独命名是为了让两处"10"在改一漏一时立刻变红 */
    const val RETEST_DELAY_MINUTES = 10L
}
