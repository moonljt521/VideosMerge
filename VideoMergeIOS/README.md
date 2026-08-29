<div align="center">

# VideoMerger · 影剪 for iOS

**iOS 端原生实现,与 [Android 端](../VideoMergeAndroid) 功能 1:1 对齐**

**Native iOS implementation, feature-for-feature aligned with the [Android app](../VideoMergeAndroid)**

[![iOS](https://img.shields.io/badge/iOS-16%2B-lightgrey?logo=ios)]( "")
[![Swift](https://img.shields.io/badge/Swift-SwiftUI-orange?logo=swift)]( "")

</div>

---

## 简体中文

### 功能

**🎬 影剪编辑器**

- 时间轴剪辑(裁剪/分割/变速/旋转翻转)、滤镜调色实时预览、LUT
- 画中画(多轨 + 关键帧动画)、贴纸、图片/文字水印、去水印检测与框选遮挡
- 字幕(手动 + Vosk 离线语音转写)、音频变声/降噪、转场实时预览
- 草稿自动保存/恢复,导出成功自动清除

**🧩 视频合并**

- 网格拼贴 / 画中画(一主多副)/ 照片墙(错落 + 边框阴影)
- 抖音尾部静止 logo 自动截断(`freezedetect`)
- 画布预设 1920×1080 / 1080×1920 / 1080×1080 / 4K,`libx264` 软编

**💧 抖音去水印**

- 粘贴分享文案 → 解析无水印视频 → 预览 → 保存相册
- 与 Android 完全同链路:短链 → 视频 ID → 移动端 feed 接口 → 多候选无水印地址下载
- 详见 [`Douyin/DouyinParser.swift`](VideoMerger/Douyin/DouyinParser.swift) 头注释

### 项目结构

```
VideoMergeIOS/
├── Podfile                          # CocoaPods(ffmpeg-kit-ios-full-gpl)
├── VideoMerger.xcworkspace          # 用 Xcode 打开这个
└── VideoMerger/
    ├── VideoMergerApp.swift         # 入口 + FFmpegKit/音频会话初始化
    ├── Home/                        # 首页(新建项目/草稿/历史)※ 位于 UI/HomeView.swift
    ├── Editor/                      # 影剪编辑器
    │   ├── EditorViewModel.swift    #   状态机(+Features 扩展)
    │   ├── Engine/                  #   导出/滤镜/贴纸/画中画蒙版/水印检测/语音识别
    │   ├── Data/DraftStore.swift    #   草稿持久化
    │   └── UI/                      #   编辑器界面/预览/时间轴/面板
    ├── Douyin/                      # 抖音去水印(Parser/ViewModel/View)
    ├── Merger/                      # 三种合并算法 + MergeEngine
    ├── Models/                      # 合并类型/元数据/历史条目
    ├── UI/                          # 合并界面/播放器/历史/选片器
    └── Util/                        # MediaUtils / VideoHistoryStore
```

### 编译

前提:Xcode 15+(macOS 13+)、CocoaPods、iOS 16+ 真机或模拟器。

```bash
cd VideoMergeIOS
pod install                  # 首次下载 ffmpeg-kit full-gpl 约 100~200MB
open VideoMerger.xcworkspace # 注意是 .xcworkspace 不是 .xcodeproj
```

选择真机/模拟器,Run(▶)。首次运行请求相册权限。

### 技术要点

| 组件 | 说明 |
|------|------|
| Swift + SwiftUI | 声明式 UI,`@MainActor` ObservableObject 状态管理 |
| FFmpegKit full-gpl | `ffmpeg-kit-ios-full-gpl`(社区预编译,含 x264/aac) |
| AVFoundation | 预览播放、缩略图;Photos 框架保存相册 |
| PHPicker + PHAssetResource | 多选视频流式写盘,避免大视频撑爆内存 |
| Vosk | 离线中文语音识别(词级时间戳字幕) |

### 注意事项

1. **体积**:ffmpeg-kit full-gpl 约 80~120MB,是主要体积来源
2. **编码**:`libx264 -preset medium -crf 20` 软编(与 Python 脚本一致),处理速度比 Android 硬编慢
3. **相册权限**:`NSPhotoLibraryUsageDescription` / `NSPhotoLibraryAddUsageDescription` 已声明
4. **沙盒**:草稿与历史存在 App Documents,卸载即丢失

## English

### Features

- **🎬 Editor** — CapCut-style timeline (trim/split/speed/rotate/flip), live filter & LUT preview, picture-in-picture with keyframes, stickers, image/text watermarks, watermark detection & box masking, manual subtitles + offline Vosk speech-to-text (word-level, SRT burn-in), audio pitch shifting & noise reduction, live-previewed transitions, auto-saved drafts
- **🧩 Merging** — grid / collage / photo-wall layouts, Douyin trailing-logo auto-truncation (`freezedetect`), canvas presets up to 4K, `libx264` encoding
- **💧 Douyin parsing** — paste a share text, parse the watermark-free video, preview, save to Photos. Identical chain to Android: short link → video ID → mobile feed API → multi-candidate watermark-free URLs (see [`Douyin/DouyinParser.swift`](VideoMerger/Douyin/DouyinParser.swift))

### Build

Requires Xcode 15+ (macOS 13+), CocoaPods, iOS 16+ device or simulator:

```bash
cd VideoMergeIOS
pod install                  # downloads ~100–200MB ffmpeg-kit full-gpl on first run
open VideoMerger.xcworkspace # note: .xcworkspace, not .xcodeproj
```

### Technical Notes

| Component | Notes |
|-----------|-------|
| Swift + SwiftUI | Declarative UI, `@MainActor` ObservableObject state |
| FFmpegKit full-gpl | community prebuilt `ffmpeg-kit-ios-full-gpl` (x264/aac) |
| AVFoundation / Photos | playback, thumbnails, album saving |
| PHPicker + PHAssetResource | streaming multi-select copy to temp files |
| Vosk | offline Chinese speech recognition (word-level timestamps) |

**Notes:** ffmpeg-kit adds ~80–120MB (main size driver); `libx264` software encoding is slower than Android's hardware path; drafts/history live in the app sandbox.

---

> 项目总览 Project overview: [根 README](../README.md) · [FEATURES.md](../FEATURES.md) · [ROADMAP.md](../ROADMAP.md)
