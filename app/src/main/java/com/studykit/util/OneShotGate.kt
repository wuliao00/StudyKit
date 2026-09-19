package com.studykit.util

/**
 * ViewModel 侧的一次性门（终审 C4）：保存/删除这类「写完就 pop」的动作，连点两次只放行第一次。
 *
 * 用法（成对使用，`finally` 里必须放行）：
 * ```
 * if (!gate.tryEnter()) return
 * viewModelScope.launch {
 *     try {
 *         ...
 *         onSaved()            // 成功路径的最后一步，早退分支不得走到这里
 *     } finally {
 *         gate.leave()
 *     }
 * }
 * ```
 *
 * 门开在 **ViewModel** 而不是页面的 `enabled` 标志上，有两条理由：
 *  - 全应用有六个保存入口（单词 / 题目 / 习惯 / 书籍 / 书摘·书评 / 拍照错题），
 *    各写一套页面态必然漂移（漏一个入口就连点双插库 + 双 pop）；
 *  - 页面的 `enabled` 只挡视觉，挡不住「同一帧内两次点击已经排进事件队列」的情况，
 *    而这里是 `tryEnter()` 与 `launch` 同线程连续执行，第二次进来必然被挡。
 *
 * 校验失败的早退分支同样落在 `try/finally` 里，门不会被永久关死；
 * 状态不保证线程安全，只给主线程上的 ViewModel 方法用。
 */
internal class OneShotGate {

    private var entered = false

    /** 抢这道门：首次 `true`，未放行前的重复调用一律 `false` */
    fun tryEnter(): Boolean {
        if (entered) return false
        entered = true
        return true
    }

    /** 放行：下一次调用又能进来（放 `finally`，异常与早退都不留残锁） */
    fun leave() {
        entered = false
    }

    /** 当前是否被占着：只给单测与调试看，页面不据此渲染 */
    val occupied: Boolean get() = entered
}
