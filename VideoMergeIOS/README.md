# VideoMerger — iOS 视频合并 App

将 Mac 上的 Python 视频合并脚本（`grid_merge.py`、`collage_merge.py`、`photo_wall_merge.py`）移植为原生 iOS 应用，对应 Android 端的 [VideoMergeAndroid](../VideoMergeAndroid)。

## 功能

- 从相册选择多个视频（PhotosUI/PHPicker）
- 三种合并模式：
  - **网格拼贴** — 均匀网格布局（对应 `grid_merge.py`）
  - **画中画** — 一主多副布局（对应 `collage_merge.py`）
  - **照片墙** — 大小错落 + 白边框 + 阴影（对应 `photo_wall_merge.py`）
- 可选画布尺寸（1920×1080 / 1080×1920 / 1080×1080 / 4K）
- 画中画模式可选主窗口位置（左/右/上/下）和占比
- 实时进度显示 + FFmpeg 滚动日志
- 合并完成后预览 + 长按/按钮保存到相册
- 历史记录页（保存在 App Sandbox 中）

## 技术栈

| 组件 | 说明 |
|------|------|
| **Swift** | 100% Swift |
| **SwiftUI** | 声明式 UI |
| **FFmpegKit** | `ffmpeg-kit-ios-full-gpl:6.0`（CocoaPods），含 ffmpeg + ffprobe + x264/aac |
| **PhotosUI** | PHPicker 多选视频 |
| **Photos** | 保存视频到相册 |
| **AVKit / AVFoundation** | 视频预览、缩略图生成 |

## 项目结构

```
VideoMergeIOS/
├── Podfile                        # CocoaPods 依赖配置
├── VideoMerger.xcodeproj/
└── VideoMerger/
    ├── VideoMergerApp.swift       # App 入口 + FFmpegKit 初始化
    ├── Info.plist
    ├── Assets.xcassets/
    ├── Models/
    │   ├── MergeTypes.swift       # 合并类型 & 选项 & 数值辅助
    │   ├── VideoMeta.swift
    │   ├── MergeResult.swift
    │   └── HistoryEntry.swift
    ├── Merger/
    │   ├── FFmpegRunner.swift     # FFmpegKit 异步封装
    │   ├── GridMerger.swift       # 网格合并算法 → ffmpeg 命令
    │   ├── CollageMerger.swift    # 画中画合并算法 → ffmpeg 命令
    │   ├── PhotoWallMerger.swift  # 照片墙合并算法 → ffmpeg 命令
    │   └── MergeEngine.swift      # 合并引擎（协调完整流程）
    ├── Util/
    │   ├── MediaUtils.swift       # 视频复制、FFprobe 元数据、相册保存、logo 检测
    │   └── VideoHistoryStore.swift
    └── UI/
        ├── MergeScreen.swift      # 主界面
        ├── MergeViewModel.swift   # ViewModel + UI 状态
        ├── VideoPlayerView.swift  # 内联 + 全屏播放器
        └── HistoryView.swift      # 历史记录页
```

## 如何编译

### 前提条件

- macOS 13+
- Xcode 15+（已安装 Swift 5.9+）
- CocoaPods（`sudo gem install cocoapods`）
- iOS 15+ 真机或模拟器（FFmpegKit 在模拟器上也能跑）

### 步骤

1. 用终端进入 `VideoMergeIOS/` 目录
2. 安装 FFmpegKit 依赖：
   ```bash
   pod install
   ```
   首次会下载约 100~200MB 的 ffmpeg-kit-ios-full-gpl 二进制。
3. 用 Xcode 打开 **`VideoMerger.xcworkspace`**（注意是 `.xcworkspace` 不是 `.xcodeproj`）
4. 选择真机或模拟器，点击 Run（▶）编译运行
5. 首次运行时 App 会请求相册访问权限

> **注意**：FFmpegKit `full-gpl` 变体包含完整编解码器（x264、aac 等），App 体积约 80~120MB。  
> FFmpegKit 在 2025 年初被官方归档，但 CocoaPods CDN 上的发布版本仍然可用。

## 使用流程

```
打开 App
  ↓
点击「从相册选择视频」→ 系统相册多选（PHPicker）
  ↓
选择合并模式（网格 / 画中画 / 照片墙）
  ↓
可选：调整画布尺寸、主窗口位置等参数
  ↓
点击「开始合并」→ 进度条 + FFmpeg 日志实时显示
  ↓
完成！点击预览全屏播放 / 长按或点按钮保存到相册
```

## 与 Python 脚本 / Android 项目的对应关系

| Python 脚本 | Android Kotlin 类 | iOS Swift 类 | 差异 |
|-------------|-------------------|--------------|------|
| `grid_merge.py` | `GridMerger.kt` | `GridMerger.swift` | 同 Android：移除了 logo 截断检测的 freezedetect 实现（保留接口） |
| `collage_merge.py` | `CollageMerger.kt` | `CollageMerger.swift` | 同 Android：移除了人脸检测，使用正中裁剪 |
| `photo_wall_merge.py` | `PhotoWallMerger.kt` | `PhotoWallMerger.swift` | 同 Android：移除了人脸检测，使用偏上裁剪 |
| `ffmpeg` / `ffprobe` 命令行 | `FFmpegKit` / `FFprobeKit` | `FFmpegKit` / `FFprobeKit` | iOS 同样使用 FFmpegKit |
| 文件系统读写 | `MediaStore` API | `Photos` framework | iOS 通过 `PHPhotoLibrary` 保存到相册 |

### 编码器差异

- **Android** 使用 `h264_mediacodec`（硬件编码）
- **iOS** 使用 `libx264 -preset medium -crf 20`（软件编码，与 Python 脚本一致，跨平台兼容性好）

## 注意事项

1. **App 体积**：FFmpegKit full-gpl 约 80~120MB，是主要体积来源
2. **处理时间**：取决于视频数量和时长，iPhone 上通常比 Mac 慢 2~5 倍
3. **内存**：大视频合并可能需要较多内存，建议在 iPhone XR 及以上设备运行
4. **相册权限**：iOS 必须在 Info.plist 中声明 `NSPhotoLibraryUsageDescription` 和 `NSPhotoLibraryAddUsageDescription`
5. **沙盒**：历史记录文件保存在 App Documents 目录中，卸载 App 会丢失

## 后续可选增强

- [ ] 集成 Vision 框架的人脸检测（替代 OpenCV Haar 级联）
- [ ] 添加 logo 尾部截断检测的实际调用（freezedetect 已实现）
- [ ] 视频预览缩略图显示
- [ ] 支持自定义输出分辨率
- [ ] 支持选择编码预设（fast/medium/slow）
- [ ] 支持横屏 UI
