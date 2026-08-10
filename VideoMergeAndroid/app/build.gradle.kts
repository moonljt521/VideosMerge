import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
            isMinifyEnabled = false
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

    // FFmpegKit — 已从 Maven Central 下架（项目归档），改用本地 .aar
    implementation(fileTree("libs") {
        include("*.aar", "*.jar")
    })

    // Media3 ExoPlayer — 视频播放
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
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
