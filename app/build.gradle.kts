// 插件与依赖版本统一由 gradle/libs.versions.toml 管理
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * debug 签名必须**跨构建稳定**。
 *
 * 病根：AGP 在没配 signingConfig 时，用构建机上现生成的 `~/.android/debug.keystore`。
 * GitHub Actions 的 runner 每次都是新机器，于是每次 CI 出的 debug 包**签名都不同**，
 * `adb install -r` 必报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —— 而这个仓是直接发 APK 的，
 * 用户换包只能"卸载再装"，卸载 = 本地学习数据全没。2026-09-24 真机走查时它就咬了我一口
 * （想覆盖安装"修完之后"的包，装不上）。
 *
 * 解法：CI 从仓库 secret 里取出固定 keystore，路径通过 `STUDYKIT_DEBUG_KEYSTORE` 传进来。
 * 本地没有这个变量时退回 AGP 默认行为（自己机器上装没问题，只是与 CI 包互不兼容）。
 *
 * 为什么还要 `REQUIRE_DEBUG_KEYSTORE` 那一层硬失败：静默退回随机 key，症状要等到
 * 下一次覆盖安装才暴露，而且看起来像"设备/签名玄学"。宁可构建当场红。
 * 只在 debug 构建那条路上要求（release job 不签名，见 .github/workflows/ci.yml 的注释）。
 */
val sharedDebugKeystorePath: String? = System.getenv("STUDYKIT_DEBUG_KEYSTORE")
val sharedDebugKeystoreFile: File? = sharedDebugKeystorePath?.let { path ->
    val f = file(path)
    if (!f.isFile) {
        error("STUDYKIT_DEBUG_KEYSTORE 指向的文件不存在：$path（secret 解码步骤没跑成？）")
    }
    f
}
if (System.getenv("REQUIRE_DEBUG_KEYSTORE")?.toBoolean() == true && sharedDebugKeystoreFile == null) {
    error(
        "这一条 CI 路径必须带固定 debug keystore（STUDYKIT_DEBUG_KEYSTORE），" +
            "否则产物签名每次都不一样，用户换包就得卸载重装、连学习数据一起丢。",
    )
}

android {
    namespace = "com.studykit"
    compileSdk = 35

    signingConfigs {
        if (sharedDebugKeystoreFile != null) {
            create("sharedDebug") {
                storeFile = sharedDebugKeystoreFile
                // 与 AGP 默认 debug keystore 同一套口令：这把钥匙只用于 debug 包，
                // 不进应用商店，也不是"应用身份"（release 至今不签名，见 CHANGELOG 的发版决定）。
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.studykit"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.4.3"
        // ML Kit 的 bundled OCR 给四个 ABI 各带一份 libmlkit_google_ocr_pipeline.so
        // （x86_64 11.6MB + x86 11.6MB + arm64 11.1MB + armeabi 6.8MB = 41MB），
        // 而本仓是**直接发 APK**（GitHub Release / 网盘），不是走应用商店的 per-device split，
        // 四个 ABI 会原样进包。x86 系只有模拟器用得到，砍掉后 APK 从 56.9MB 回到 33.7MB。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // 只有 CI 提供了固定 keystore 时才覆盖；本地留空 = 用 AGP 默认 debug keystore
            if (sharedDebugKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
        release {
            // 开启混淆与资源收缩；混淆后的 release 包需在本地真机完整回归验证（详见 CHANGELOG）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // AGP 8 默认不生成 BuildConfig；AppDatabase 依 BuildConfig.DEBUG 决定是否播种演示数据
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric 要读合并后的 manifest（Compose 测试规则需要它提供的 LaunchActivity），
            // 还要能取到资源；缺任一条，`createComposeRule()` 在 JVM 上直接起不来。
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 图片加载
    implementation(libs.coil.compose)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // 安装期写入 ART baseline profile（见 src/main/baseline-prof.txt）
    implementation(libs.androidx.profileinstaller)

    // 截图取词：bundled 中文模型进 APK，全离线、不需要 GMS（spec §5.4）
    implementation(libs.mlkit.text.recognition.chinese)

    implementation(libs.androidx.core.ktx)

    // 单元测试
    testImplementation(libs.junit)
    // 仅测试期：android.jar 里的 org.json 是 `Stub!` 占位，JVM 单测一调就抛
    // （既有 `Question.parseOptions` 因此一直没被测过）。运行时仍用系统实现，APK 零影响。
    testImplementation(libs.json)

    // Compose 渲染守卫：`CheckInSheet` 那类"内容根本没进语义树"的缺陷，纯 JVM 逻辑测试抓不到。
    // 走 Robolectric 而不是 instrumented test —— 后者要模拟器，本仓 CI 唯一的执行器是
    // `testDebugUnitTest`（见 .github/workflows/ci.yml），引模拟器会把构建时长和变红风险一起抬上去。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Compose 的 createComposeRule 经 instrumentation 起宿主 activity，需要 espresso。
    // 通常由 ui-test-junit4 传递带进来，这里显式声明，免得哪天 BOM 升级后静默消失。
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // `ui-test-manifest` 提供那个宿主 `androidx.activity.ComponentActivity`，**必须是
    // debugImplementation**：manifest 只合进 debug 那一份，而 Robolectric 读的正是它。
    // 写成 testImplementation 的症状极有迷惑性 —— 每条测试统一抛
    // `Unable to resolve activity for Intent { cmp=com.studykit/...ComponentActivity }`，
    // 看着像断言失败，其实一个测试都没跑起来（见 robolectric/robolectric#4736）。
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// 测试失败时把完整堆栈打到 CI 控制台。默认只给一个异常类名，等于每排查一次就要重跑一轮构建
// —— 而本仓唯一的执行器是 CI，一轮七八分钟，看不见的失败就是白跑。
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
    }
}
