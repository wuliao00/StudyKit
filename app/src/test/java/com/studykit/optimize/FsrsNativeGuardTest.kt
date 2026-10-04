package com.studykit.optimize

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v2.9/R2：[FsrsNative] 的"装载失败只降级、不冒泡"这条护栏。
 *
 * 只读 [FsrsNative.isAvailable]，**绝不调** `nativeTrain` / `nativeEvaluate`：单测跑在 x86 JVM 上，
 * `libfsrsjni.so` 只有 arm64-v8a / armeabi-v7a 两份，真去调会 `UnsatisfiedLinkError`，
 * 而那正是 [FsrsNative] 的 init 已经吞掉、并由 T13 在真机上验证的分支。
 *
 * 这条测试钉的是：类在"没有原生库"的环境里被摸到也不炸，且状态如实报 false——
 * T11 的 CalibrateViewModel 就是靠这个布尔值决定走 Rust 还是回落纯 Kotlin。
 */
class FsrsNativeGuardTest {

    @Test fun `host jvm has no fsrsjni so so binding reports unavailable without throwing`() {
        // 访问 object 会触发 init{ System.loadLibrary("fsrsjni") }；失败必须被吞成 loaded=false
        val available = FsrsNative.isAvailable
        assertFalse(
            "JVM 单测环境不该有 libfsrsjni.so（只有 arm 两份）。若这里变 true，" +
                "说明 host 构建把 x86 库塞进了 classpath，T13 之外的路径就要重新审一遍。",
            available,
        )
    }
}
