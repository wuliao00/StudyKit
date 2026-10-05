package com.studykit.optimize

import com.studykit.data.memory.FsrsModel

/**
 * 端侧优化器的**训练接缝**（v2.9 计划 Task 11）。
 *
 * ## 为什么要有这一层
 *
 * 真正能训 21 参的东西是 Rust JNI（[FsrsNative]），而它的 `.so` 只有 arm 两份：CI 唯一的执行器
 * `testDebugUnitTest` 跑在 x86 JVM 上，一个都加载不了（[FsrsNativeGuardTest] 把这件事钉成了
 * "在 host 上只许降级、不许炸"）。可 Task 11 的风险**不在算式在编排** —— 谁先谁后、什么时候不许写库、
 * 写几份、异常落在哪。所以把原生调用收进这一个接口，编排层只认接口：
 *  - 生产：[NativeFsrsTrainer]（唯一碰 `external fun` 的地方）；
 *  - 单测：随便写一个假实现，就能把 [CalibrateViewModel] 的护栏逐条钉住（见
 *    [com.studykit.optimize.CalibrateGuardrailTest]）。
 *
 * ## null 是本接口的**正常返回值**，不是异常
 *
 * 两个方法都回可空：**回 null 一律读作"这一件事没做成"**（原生库没装上 / JNI 返回空数组 = Rust 侧
 * panic 或解析越界 / 该侧根本没有数据）。调用方据此保留旧参数并落成 `Failed` 状态，
 * 绝不允许把 null 当"loss = 0"往下算。这条约定与 `lib.rs` 的失败信号（空数组）逐字对齐。
 *
 * 方法都是**同步阻塞**的：训练在设备上要几百毫秒到几秒，由调用方决定放到哪个线程去跑
 * （[CalibrateViewModel] 走 `Dispatchers.Default`，[CalibrateWorker] 走 WorkManager 自己的协程）。
 */
interface FsrsTrainer {

    /**
     * 在 [init] 的基础上用 train 侧数据做 AdamW 训练。
     *
     * @param init 起点参数 = **当前活跃参数**（没校准过则是 [FsrsModel.DEFAULT_PARAMS]）。
     *   必须是 21 项：`lib.rs` 只在长度对得上时才用传入值，否则静默回落到它自带的默认，
     *   于是"从在位参数继续训"会变成"每次都从出厂值重训"。
     * @param iters 迭代轮数；<=0 时按 [DEFAULT_ITERS]（与 Rust 端 `TrainCfg::default()` 同值）。
     * @return 训练报告；**原生不可用或 JNI 失败时回 null**。
     */
    fun train(packed: Packed, init: DoubleArray, iters: Int): TrainReport?

    /**
     * 用 [params] 评估 **val 侧**（[Packed.valReviews]）——训练在 train、打分在 val，
     * 这条分界由 [LogPacker.pack] 的整卡时序切分保证，接缝这里不重新选数据。
     *
     * @return 校准指标；**原生不可用 / JNI 失败 / val 侧为空时回 null**。
     */
    fun evaluate(packed: Packed, params: DoubleArray): EvalMetrics?

    companion object {
        /**
         * 默认迭代轮数。取 Rust 端 `TrainCfg::default().iters`（`fsrsjni/src/core.rs`）同一个值，
         * 免得两处各写一份字面量然后哪天漂移；也正好是 `lib.rs` 里 `ci[0].max(1)` 能吃到的那个参数位。
         */
        const val DEFAULT_ITERS = 150
    }
}

/**
 * [FsrsTrainer] 的生产实现：委托 [FsrsNative]。
 *
 * 三道降级闸，任何一道不过都回 null，**绝不抛**：
 *  1. [FsrsNative.isAvailable] = false（缺 `.so` / ABI 不符）——此时调 `external fun` 会
 *     `UnsatisfiedLinkError`，所以这一条必须写在调用之前；
 *  2. 对应侧一条 review 都没有（空日志、或全被 [LogPacker] 清洗掉）——不喂空数组去试探 Rust；
 *  3. JNI 抛任何 Throwable（含 Rust panic 翻译成的 `VirtualMachineError`）或返回短数组
 *     ——[MetricsParser] 据此回 null / NaN 指标。
 *
 * ⚠ `nativeTrain` 的第 4 个参数 `cfg` 在 Rust 侧是 `vec![0i32; 2]` + `get_int_array_region(&cfg, 0, …)`：
 * **传进去的数组长度必须 ≥2**，短了 `get_int_array_region` 失败，`?` 直接回空数组=训练失败，
 * 症状看着像"Rust 训不动"，其实是 Kotlin 侧少给了一个占位（`lib.rs` 第 91-96 行）。
 * 当前 Rust 只读 `ci[0]`（iters），第二项恒为占位 0。
 */
class NativeFsrsTrainer : FsrsTrainer {

    override fun train(packed: Packed, init: DoubleArray, iters: Int): TrainReport? {
        if (!FsrsNative.isAvailable) return null
        if (packed.trainReviews.isEmpty() || packed.trainBounds.isEmpty()) return null
        val out = call {
            FsrsNative.nativeTrain(
                packed.trainReviews,
                packed.trainBounds,
                init.asFloatArray(),
                intArrayOf(if (iters <= 0) FsrsTrainer.DEFAULT_ITERS else iters, 0),
            )
        } ?: return null
        return MetricsParser.parseTrain(out)
    }

    override fun evaluate(packed: Packed, params: DoubleArray): EvalMetrics? {
        if (!FsrsNative.isAvailable) return null
        if (packed.valReviews.isEmpty() || packed.valBounds.isEmpty()) return null
        val out = call {
            FsrsNative.nativeEvaluate(packed.valReviews, packed.valBounds, params.asFloatArray())
        } ?: return null
        // 空数组 = Rust 失败；parseEvaluate 对短数组只回 NaN 指标，那还会把"没做成"说成"loss 是 NaN"，
        // 所以这一侧在进解析器之前先按接缝约定回 null。
        if (out.size < MetricsParser.EVAL_OUT_LEN) return null
        return MetricsParser.parseEvaluate(out)
    }

    /** 原生调用的唯一入口：把任何 Throwable 都折成 null（调用方只认"没做成"）。 */
    private inline fun call(block: () -> FloatArray): FloatArray? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    private fun DoubleArray.asFloatArray(): FloatArray = FloatArray(size) { this[it].toFloat() }
}
