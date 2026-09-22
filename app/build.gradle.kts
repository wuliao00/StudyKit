// 插件与依赖版本统一由 gradle/libs.versions.toml 管理
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.studykit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.studykit"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.3.0"
        // ML Kit 的 bundled OCR 给四个 ABI 各带一份 libmlkit_google_ocr_pipeline.so
        // （x86_64 11.6MB + x86 11.6MB + arm64 11.1MB + armeabi 6.8MB = 41MB），
        // 而本仓是**直接发 APK**（GitHub Release / 网盘），不是走应用商店的 per-device split，
        // 四个 ABI 会原样进包。x86 系只有模拟器用得到，砍掉后 APK 从 56.9MB 回到 33.7MB。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
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
}
