import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.moon.videomerger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moon.videomerger"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 只保留 arm64-v8a，减少 APK 体积约 46MB
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // ── 签名配置（debug + release 共用） ──
    signingConfigs {
        create("config") {
            keyAlias = "videomerge"
            keyPassword = "videomerge123"
            storeFile = file("keystore/videomerge.jks")
            storePassword = "videomerge123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("config")
        }
        release {
            signingConfig = signingConfigs.getByName("config")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        // 允许单元测试调用 android.util.Log 等框架方法返回默认值（FilterBuilder 导出命令测试需要）
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // FFmpegKit — 迁移到维护分支 ffmpegkit-maintained（同包名 com.arthenica.ffmpegkit，同 API，仅 groupId 变化）
    // full = LGPL（可闭源），含 libass/subtitles + 全套 LGPL 编解码；替代原 min 本地 aar
    // ★ 8.1.7(n8.1.2) 的 xfade 转场在真机导出时 native 崩溃，回退到 6.0.3(n6.1.6) LTS —— 最接近原 n6.0，转场行为不变
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3")
    // ★ 维护分支的 POM 漏声明了 smart-exception 传递依赖，而 FFmpegKitConfig 运行时会用到
    //   com.arthenica.smartexception.java.Exceptions；该库仍在 Maven Central，需显式补上，
    //   否则启动即 NoClassDefFoundError 闪退。
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Media3 ExoPlayer — 视频播放
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Media3 Effect — 预览实时滤镜调色（RgbFilter：亮度/对比度/饱和度）
    implementation("androidx.media3:media3-effect:1.4.1")

    // Coil — 图片加载（时间轴缩略图）
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Vosk — 离线语音识别（语音转字幕）
    implementation("com.alphacephei:vosk-android:0.3.47")

    // kotlinx-serialization —— 项目草稿持久化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // 单元测试 —— 时间轴布局 / 转场重叠等纯数学逻辑
    testImplementation("junit:junit:4.13.2")
}

// ── 打包 Task：生成带签名的 release APK，输出到 outputs/ 目录 ──
// 用法: ./gradlew packageApk
tasks.register("packageApk") {
    dependsOn("assembleRelease")
    doLast {
        val inputApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (!inputApk.exists()) {
            throw GradleException("APK 文件不存在: ${inputApk.absolutePath}")
        }
        val outputDir = File(rootProject.projectDir, "outputs").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val outputApk = File(outputDir, "VideoMerge_$timestamp.apk")
        inputApk.copyTo(outputApk, overwrite = true)
        println("========================================")
        println("APK 已生成: ${outputApk.absolutePath}")
        println("大小: ${"%.2f".format(outputApk.length() / 1024.0 / 1024.0)} MB")
        println("========================================")
    }
}
