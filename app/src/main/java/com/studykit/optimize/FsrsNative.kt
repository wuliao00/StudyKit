package com.studykit.optimize

/**
 * v2.9/R2：Rust 优化器 `fsrsjni`（21 参 FSRS v6）的 Kotlin JNI 绑定。
 *
 * 对应 `fsrsjni/src/lib.rs` 里 `#[no_mangle]` 导出的
 * `Java_com_studykit_optimize_FsrsNative_nativeTrain` / `..._nativeEvaluate`——
 * **类名与包名是 JNI 符号名的一部分**，改名要同步改 Rust 侧并重出 `.so`。
 *
 * ⚠ **只在设备上可用**：`.so` 由 `cargo ndk -t arm64-v8a -t armeabi-v7a -o app/src/main/jniLibs`
 * 产出、随 APK 打进 `lib/<abi>/libfsrsjni.so`。JVM 单测（CI 唯一执行器 `testDebugUnitTest`）
 * 跑在 x86 上加载不了 arm 库，所以**单测里绝不调用这两个 external 函数**；
 * Rust≡Kotlin 的公式口径由 [com.studykit.data.memory.FsrsModelParityTest]（Rust 生成的夹具）钉死，
 * 真机上的 JNI 链路（装载、打包、训练跑通）在 T13 验证。
 *
 * 装载策略：`init` 里 `System.loadLibrary` 失败（缺库 / 缺 ABI / 校验不过）只把
 * [isAvailable] 置 false，**不抛**——调用方（T11 的 CalibrateViewModel）据此回落纯 Kotlin 路径，
 * 绝不让一个可选的原生加速把调度器一起带崩。
 *
 * 打包格式（与 `lib.rs` 顶部注释一字对应）：
 * - `reviews`：每条 review 4 个 float = `[rating, elapsedDays, scheduledDays, state]`
 *   （state：0=New 1=Learn 2=Review 3=Relearn）；由 T7 的 `LogPacker` 产出
 * - `bounds`：前缀和 `[0, n1, n1+n2, …]`，长度 = 卡数 + 1；items 按「首评时间」升序
 * - `nativeTrain` 返回 `FloatArray[21+5]` = params[21] + `[lossFirst, lossLast, nItems, nTerms, ms]`，
 *   `nativeEvaluate` 返回 `FloatArray[2+30]` = `[logloss, nTerms]` + 10 桶 ×（pred 均值, obs 均值, 计数）；
 *   **返回空数组即失败**（Rust 侧 panic / 解析越界都走这条路），调用方须保留旧参数。
 */
object FsrsNative {

    @Volatile
    private var loaded = false

    /** 原生库是否真的装上了。false 时**不要**调下面两个 external 函数（会 `UnsatisfiedLinkError`）。 */
    val isAvailable: Boolean get() = loaded

    init {
        loaded = try {
            System.loadLibrary("fsrsjni")
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / SecurityException 都只降级，不冒泡
            false
        }
    }

    /**
     * AdamW 训练 21 参 v6。`bounds` 是 [reviews] 的按卡前缀和；`init` 须为 21 项（长度不符则
     * Rust 侧回落默认参数）；`cfg = [iters, lrBits]`（当前只读 iters，其余走 Rust 端 TrainCfg 默认）。
     */
    external fun nativeTrain(
        reviews: FloatArray,
        bounds: IntArray,
        init: FloatArray,
        cfg: IntArray,
    ): FloatArray

    /** 用给定参数评估：logloss + 10 个校准分桶。空数组 = 失败。 */
    external fun nativeEvaluate(
        reviews: FloatArray,
        bounds: IntArray,
        params: FloatArray,
    ): FloatArray
}
