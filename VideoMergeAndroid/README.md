# VideoMerger — Android 视频合并 App

将你 Mac 上的 Python 视频合并脚本（`grid_merge.py`、`collage_merge.py`、`photo_wall_merge.py`）移植为原生 Android 应用。

## 功能

- 从手机相册选择多个视频
- 三种合并模式：
  - **网格拼贴** — 均匀网格布局（对应 `grid_merge.py`）
  - **画中画** — 一主多副布局（对应 `collage_merge.py`）
  - **照片墙** — 大小错落 + 白边框 + 阴影（对应 `photo_wall_merge.py`）
- 可选画布尺寸（1920×1080 / 1080×1920 / 1080×1080 / 4K）
- 画中画模式可选主窗口位置（左/右/上/下）和占比
- 实时进度显示
- 合并完成后自动保存到相册 `Movies/VideoMerger/`

## 技术栈

| 组件 | 说明 |
|------|------|
| **Kotlin** | 100% Kotlin |
| **Jetpack Compose** | 声明式 UI |
| **FFmpegKit** | Maven 依赖 `dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3`（`full` / LGPL，FFmpeg n6.1.6），含 ffmpeg + ffprobe + mediacodec/aac + libass/subtitles |
| **MediaStore API** | 通过 `MediaStore` 将输出保存到系统相册 |
| **Coroutines** | 异步执行 ffmpeg + 进度回调 |

## 项目结构

```
VideoMerger/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/
        │   ├── strings.xml
        │   └── themes.xml
        └── java/com/moon/videomerger/
            ├── VideoMergerApp.kt          # Application 初始化
            ├── MainActivity.kt            # Compose 入口
            ├── util/
            │   └── MediaUtils.kt          # 视频复制、元数据探测、相册保存
            ├── merger/
            │   ├── MergeTypes.kt          # 合并类型 & 选项 & 扩展函数
            │   ├── FFmpegRunner.kt        # FFmpegKit 异步封装
            │   ├── GridMerger.kt          # 网格合并算法 → ffmpeg 命令
            │   ├── CollageMerger.kt       # 画中画合并算法 → ffmpeg 命令
            │   ├── PhotoWallMerger.kt     # 照片墙合并算法 → ffmpeg 命令
            │   └── MergeEngine.kt         # 合并引擎（协调完整流程）
            └── ui/
                ├── MergeViewModel.kt      # ViewModel + UI 状态
                └── MergeScreen.kt         # Compose 界面
```

## 如何编译

### 前提条件

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

### 步骤

1. 用 Android Studio 打开 `VideoMerger/` 目录
2. 等待 Gradle sync 完成（FFmpegKit 从 Maven Central 拉取，首次 sync 会下载约 30MB）
3. 连接 Android 手机（开启 USB 调试）或启动模拟器
4. 点击 Run（▶）编译安装

> **注意**：当前使用 FFmpegKit `full`（LGPL）变体，视频编码依赖设备 `h264_mediacodec`（LGPL 兜底可用 `openh264`），不引入 GPL 组件。

## 使用流程

```
打开 App
  ↓
点击「从相册选择视频」→ 系统相册多选
  ↓
选择合并模式（网格 / 画中画 / 照片墙）
  ↓
可选：调整画布尺寸、主窗口位置等参数
  ↓
点击「开始合并」→ 进度条显示处理进度
  ↓
完成！视频已保存到相册 Movies/VideoMerger/
```

## 与 Python 脚本的对应关系

| Python 脚本 | Kotlin 类 | 差异 |
|-------------|-----------|------|
| `grid_merge.py` | `GridMerger.kt` | 移除了 logo 截断检测（可按需加回） |
| `collage_merge.py` | `CollageMerger.kt` | 移除了人脸检测（Android 上可集成 ML Kit 替代） |
| `photo_wall_merge.py` | `PhotoWallMerger.kt` | 移除了人脸检测，使用偏上裁剪替代 |
| `ffmpeg` / `ffprobe` 命令行 | `FFmpegKit` / `FFprobeKit` | FFmpegKit 在 Android 上提供了相同的 API |
| 文件系统读写 | `MediaStore` API | Android 10+ 使用 MediaStore 无需存储权限 |

## 后续可选增强

- [ ] 集成 Google ML Kit 人脸检测（替代 OpenCV Haar 级联）
- [ ] 添加 logo 尾部截断检测
- [ ] 视频预览缩略图显示
- [ ] 合并结果内置播放器预览
- [ ] 支持自定义输出分辨率
- [ ] 支持选择编码预设（fast/medium/slow）

## 注意事项

1. **APK 体积**：当前使用 FFmpegKit `full`（LGPL）约 49MB，主要体积来源是 arm64 原生库；如需减小体积可改回 `min` 或按需裁剪 ABI
2. **处理时间**：取决于视频数量和时长，手机上通常比电脑慢 2~5 倍
3. **内存**：大视频合并可能需要较多内存，建议 minSdk 24+ 的设备至少 4GB RAM
4. **Android 10+**：无需 `WRITE_EXTERNAL_STORAGE` 权限，通过 MediaStore 直接写入相册
