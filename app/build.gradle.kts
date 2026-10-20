// 插件与依赖版本统一由 gradle/libs.versions.toml 管理
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Room schema 快照的输出目录。
 *
 * 光把 `exportSchema` 打开不够：room 2.6.1 拿不到 schemaLocation 时**不导出任何快照**，
 * 只打一条 warning（`ProcessorErrors` 原文：“Schema export directory was not provided to
 * the annotation processor so Room cannot export the schema …”），也不会退回某个默认目录。
 * 快照取证无从谈起 —— 指到 `$projectDir/schemas` 才谈得上把每一版的 JSON 提交进仓。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
        versionCode = 21
        versionName = "3.1.0"
        // instrumented test 的执行器。本机没有设备也没有模拟器镜像，CI 里只有
        // `:app:assembleDebugAndroidTest` 这条「只编译」的闸 —— 这些用例**从未在设备上执行过**，
        // 真跑是 `:app:connectedDebugAndroidTest`（需要接到一台设备/一个模拟器）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 端侧 OCR 走 PP-OCRv6（ONNX Runtime Android），每个 ABI 各带一份 libonnxruntime.so
        // （arm64-v8a 约 32MB + armeabi-v7a 约 22.7MB，stored 不压缩）。本仓是**直接发 APK**
        // （GitHub Release / 网盘），不是走应用商店的 per-device split，故只留两个 arm ABI：
        // x86 系只有模拟器用得到，砍掉能省一大截而不影响任何真机用户。
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
        // AGP 8 默认不生成 BuildConfig；AppDatabase 依 BuildConfig.DEMO_SEED 决定是否播种演示数据
        buildConfig = true
    }

    defaultConfig {
        // 演示数据播种开关，**默认关**。
        //
        // 原先的闸门是 `BuildConfig.DEBUG`，但**我们分发的正是 assembleDebug 产物** ——
        // 对真实使用者来说那个条件恒为 true，等于没有闸门：全新安装会被灌进
        // 20 个演示单词、3 个演示习惯（背单词/跑步/阅读）连打卡记录、题目、书、错题。
        // 一个学习应用的首启应该是干净的空库，让用户看到自己的空白，而不是别人的假数据。
        //
        // 本地预览想看有内容的界面，显式开：
        //     ./gradlew assembleDebug -PdemoSeed=true
        // 注意这里必须算出 "true"/"false" 两个字面量：`findProperty(...).toString()`
        // 在没有该属性时给出的是字符串 "null"，生成到 BuildConfig 里是个编译不过的表达式。
        buildConfigField(
            "boolean",
            "DEMO_SEED",
            "${project.findProperty("demoSeed") == "true"}",
        )
        // v2.9 校准链路的「灌合成数据」入口闸门。**不能图省事用 BuildConfig.DEBUG**——理由与
        // 上面 DEMO_SEED 完全同一条：分发出去的就是 debug 包，那条恒为 true。review_log 是
        // append-only 且「清空学习数据」不删它，假记录一旦灌进用户机器就永久留着，还能凑满
        // FsrsGate.MIN_TERMS=1000 让校准有资格「采用新参数」——等于用合成曲线排他的真复习。
        // 本地验链路：./gradlew assembleDebug -PseedSynth=true
        buildConfigField(
            "boolean",
            "SEED_SYNTH_LOG",
            "${project.findProperty("seedSynth") == "true"}",
        )
    }

    testOptions {
        unitTests {
            // Robolectric 要读合并后的 manifest（Compose 测试规则需要它提供的 LaunchActivity），
            // 还要能取到资源；缺任一条，`createComposeRule()` 在 JVM 上直接起不来。
            isIncludeAndroidResources = true
        }
    }
    // Room schema 快照目录。MigrationTestHelper 逐字校验迁移产物时要从**它能读到的 assets**取
    // `com.studykit.data.AppDatabase/<v>.json`。
    // A-T7 实跑证伪：AGP 不把 test 源集 assets 并进变体 merge，Robolectric 只见
    // mergeDebugAssets ⇒ MigrationTestHelper 读不到快照。改挂 debug 变体：debug 包多发
    // 两份 JSON（~80KB）换 CI 里真实有效的迁移校验网；test 源集那行是死管，删。
    sourceSets {
        getByName("debug").assets.srcDirs("$projectDir/schemas")
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

    // 截图取词 / 拍照录题：PP-OCRv6（det+rec+dict）离线打进 assets，全离线、不需要 GMS（spec §5.4）。
    // v2.8 起 ML Kit 已下线，onnxruntime 是唯一端侧 OCR 引擎的运行时。
    implementation(libs.onnxruntime.android)

    implementation(libs.androidx.core.ktx)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
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
    // 真模型测试：Robolectric 跑在宿主 JVM 上，`onnxruntime-android` 的 .so 是 Android ABI
    // 原生库，JVM 加载不了；这一坐标只给测试 classpath 提供桌面版原生库（版本与 Android 侧对齐）。
    testImplementation(libs.onnxruntime)

    // ── instrumented tests（app/src/androidTest）────────────────────────────
    // 这个源集是 v3.0-C 第 8 条欠账的补课：设备基准（50/200/500 三档）与一条真实 Activity 冒烟。
    // ⛔ 它们**从未在任何设备或模拟器上执行过**（本机既无设备镜像也无 AVD；CI 只跑
    //    `:app:assembleDebugAndroidTest`，只证明编得过）。第一份真实读数要由用户在真机上跑
    //    `.\gradlew.bat :app:connectedDebugAndroidTest` 取得。
    // 依赖尽量复用版本目录里的既有坐标；只有 runner 是新坐标（AndroidJUnitRunner 的家），
    // 版本与 androidx.test:core 对齐，见 gradle/libs.versions.toml。
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Compose 的 createAndroidComposeRule / createComposeRule 需要 BOM 对齐的 ui-test-junit4
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// 单元测试的 classpath 上，`onnxruntime-android`（AAR）与桌面版 `onnxruntime`（JAR）提供同一批
// `ai.onnxruntime.*` 类；JVM 只认先出现的那一份，而 AAR 里的 .so 是 Android ABI，宿主 JVM 加载不了。
// 真模型测试必须用桌面 JAR，所以**只从测试的两条 classpath** 里排掉 AAR；生产编译与 instrumented
// 测试仍走 `onnxruntime-android`，Android 侧依赖和版本一行没动。
configurations.configureEach {
    if (name == "debugUnitTestCompileClasspath" || name == "debugUnitTestRuntimeClasspath") {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

// 测试失败时把完整堆栈打到 CI 控制台。默认只给一个异常类名，等于每排查一次就要重跑一轮构建
// —— 而本仓唯一的执行器是 CI，一轮七八分钟，看不见的失败就是白跑。
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
    }
    // 环境修法，不是放宽断言：本机 TMP/TEMP 指向 `C:\Windows\Temp`（Gradle 已经报过
    // `Failed to open directory: C:\Windows\Temp`），而 ONNX Runtime 的桌面 JAR 会把
    // 原生库解压到 `java.io.tmpdir`。指到构建目录内，避免写系统临时目录、也避免解压失败。
    val testTmpDir = layout.buildDirectory.dir("tmp/unit-test").get().asFile
    doFirst { testTmpDir.mkdirs() }
    systemProperty("java.io.tmpdir", testTmpDir.absolutePath)
}
