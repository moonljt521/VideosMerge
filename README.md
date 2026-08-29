<div align="center">

# VideosMerge · 影剪

**跨平台视频工具箱:移动端视频编辑器 · 视频合并 · 抖音无水印解析**

**Cross-platform video toolbox: mobile video editor · video merging · watermark-free Douyin parsing**

[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS-blue)]( "")
[![Android](https://img.shields.io/badge/Android-SDK%2034-green?logo=android)]( "")
[![iOS](https://img.shields.io/badge/iOS-16%2B-lightgrey?logo=ios)]( "")
[![Engine](https://img.shields.io/badge/engine-FFmpegKit%20full--gpl-orange)]( "")

</div>

---

## 简体中文

### 项目简介

VideosMerge(应用名**影剪**)是一套三端同源的视频处理工具:Android 与 iOS 双原生 App 共享同一套功能设计与 FFmpeg 算法参数,根目录的 Python 脚本则是所有能力的"先行验证层"——每项功能先以脚本跑通 ffmpeg 命令,再移植进 App。

### 功能特性

#### 🎬 影剪编辑器(Android / iOS)

对标剪映的移动端剪辑核心:

- **时间轴剪辑** —— 裁剪、分割、变速(0.25x~4x)、旋转/翻转,播放头固定居中的胶片式交互
- **滤镜与调色** —— 实时预览,支持 LUT
- **画中画** —— 多轨叠加、位置关键帧动画、变速与旋转
- **贴纸与水印** —— Emoji 贴纸、图片水印、文字水印、水印自动检测 + 手动框选遮挡
- **字幕** —— 手动字幕轨 + Vosk 离线语音转写(词级时间戳,SRT 烧录)
- **音频** —— 变声(音高 0.5x~2x)、降噪(afftdn)
- **转场** —— 实时预览
- **草稿系统** —— 自动保存/恢复,导出成功自动清除

#### 🧩 视频合并(Android / iOS)

- 三种布局:**网格拼贴** / **画中画**(一主多副)/ **照片墙**(错落布局 + 白边框阴影)
- 自动检测并截断抖音尾部静止 logo 片段(`freezedetect`)
- 画布预设:1920×1080 / 1080×1920 / 1080×1080 / 4K
- 编码:Android 硬编(`h264_mediacodec`),iOS 软编(`libx264`)

#### 💧 抖音去水印(Android / iOS)

粘贴分享文案,自动解析出**无水印**视频,预览后保存到相册:

```
分享文案 ──提取──> v.douyin.com 短链 ──302──> 落地页取视频 ID
        ──> 移动端 feed 接口(带 App UA)──> play_addr 无水印地址
        ──> 多候选容错下载 ──> 预览播放 ──> 保存到相册
```

> 抖音水印是"保存"时服务端实时叠加的角标,原始文件本身无水印;解析的本质是绕过加水印出口直取源文件。详情见 [Android README](VideoMergeAndroid/README.md#抖音去水印解析原理)。

#### 🐍 Python 脚本库

33 个视频处理脚本(裁剪/变速/水印/转场/混音/GIF 等),是 App 端全部能力的原型与参数参考,可独立使用:

```bash
python3 grid_merge.py -d /path/to/videos       # 网格合并
python3 collage_merge.py -d /path/to/videos    # 画中画
python3 photo_wall_merge.py -d /path/to/videos # 照片墙
```

### 项目结构

```
VideosMerge/
├── VideoMergeAndroid/        # Android App(Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/moon/videomerger/
│   │   ├── editor/           #   影剪编辑器(时间轴/引擎/面板)
│   │   ├── home/             #   首页(新建项目/草稿/历史)
│   │   ├── merger/           #   三种合并算法
│   │   ├── douyin/           #   抖音链接解析
│   │   └── ui/               #   合并/去水印界面与播放器
│   └── py/                   #   合并脚本副本
├── VideoMergeIOS/            # iOS App(Swift + SwiftUI,结构与 Android 一一对应)
├── *.py                      # 29 个独立视频处理脚本
├── FEATURES.md               # 功能状态矩阵(双端逐项对照)
└── ROADMAP.md                # 版本化路线图
```

### 快速开始

**Android**(Android Studio Hedgehog+,JDK 17):

```bash
cd VideoMergeAndroid
./gradlew assembleDebug      # 调试包
./gradlew packageApk         # 签名 release 包 → outputs/
```

**iOS**(Xcode 15+,CocoaPods):

```bash
cd VideoMergeIOS
pod install                  # 首次约 100~200MB(ffmpeg-kit full-gpl)
open VideoMerger.xcworkspace # 注意是 .xcworkspace
```

**Python**(需 ffmpeg/ffprobe,部分脚本需 opencv-python):

```bash
python3 trim_video.py --help
```

### 技术要点

- **双端同构**:Android(Kotlin/Compose)与 iOS(Swift/SwiftUI)模块一一对应 —— `Merger/`、`Editor/Engine/`、`Douyin/`、`Util/`,算法参数完全一致
- **FFmpegKit full-gpl**:含 x264/aac 全编解码器;Android 通过 ABI 过滤 + R8 将 APK 控制在 ~17MB
- **解析健壮性**:无水印地址多候选降级(稳定 `video_id` 形式优先)、请求统一携带 App UA、失败文案兜底

## English

### Overview

VideosMerge (app name **影剪**) is a three-tier video toolbox: native Android and iOS apps share one feature set and identical FFmpeg parameters, while the Python scripts in this repo act as the prototyping layer — every capability is first validated as an ffmpeg command line, then ported into the apps.

### Features

**🎬 Video Editor (Android / iOS)** — a CapCut-style mobile editor:

- Timeline editing: trim, split, speed (0.25x–4x), rotate/flip, film-strip UI with fixed center playhead
- Filters & color grading with live preview (LUT support)
- Picture-in-picture: multi-track overlay, position keyframes, speed & rotation
- Stickers (emoji), image & text watermarks, watermark auto-detection + manual box masking
- Subtitles: manual track + offline speech-to-text via Vosk (word-level timestamps, SRT burn-in)
- Audio: pitch shifting (0.5x–2x), noise reduction (afftdn)
- Live-previewed transitions, auto-saved drafts

**🧩 Video Merging (Android / iOS)**

- Three layouts: **grid**, **collage** (main + subs), **photo wall** (scattered cards with borders & shadows)
- Auto truncation of Douyin's trailing frozen-logo segment (`freezedetect`)
- Canvas presets: 1920×1080 / 1080×1920 / 1080×1080 / 4K
- Encoders: hardware `h264_mediacodec` on Android, `libx264` on iOS

**💧 Douyin Watermark-free Parsing (Android / iOS)**

Paste a share text, get the watermark-free video, preview, and save to the photo album:

```
share text → v.douyin.com short link → 302 → video ID from landing URL
           → mobile feed API (with app UA) → play_addr (watermark-free)
           → multi-candidate download → preview → save
```

> Douyin's watermark is composited server-side at download time; the source file itself is clean. Parsing simply fetches the original. See the [Android README](VideoMergeAndroid/README.md#抖音去水印解析原理) for details.

**🐍 Python Script Library** — 33 standalone scripts (trim/speed/watermark/transition/mix/GIF/...) that prototype every app capability and work on their own.

### Project Layout

```
VideosMerge/
├── VideoMergeAndroid/        # Android app (Kotlin + Jetpack Compose)
│   └── .../videomerger/      #   editor/ home/ merger/ douyin/ ui/
├── VideoMergeIOS/            # iOS app (Swift + SwiftUI, 1:1 mirror of Android)
├── *.py                      # 29 standalone video-processing scripts
├── FEATURES.md               # per-feature status matrix (Android vs iOS)
└── ROADMAP.md                # versioned roadmap
```

### Getting Started

**Android** (Android Studio Hedgehog+, JDK 17):

```bash
cd VideoMergeAndroid
./gradlew assembleDebug      # debug build
./gradlew packageApk         # signed release APK → outputs/
```

**iOS** (Xcode 15+, CocoaPods):

```bash
cd VideoMergeIOS
pod install                  # ~100–200MB on first run (ffmpeg-kit full-gpl)
open VideoMerger.xcworkspace # note: .xcworkspace, not .xcodeproj
```

**Python** (requires ffmpeg/ffprobe; some scripts need opencv-python):

```bash
python3 trim_video.py --help
```

### Technical Notes

- **Mirrored codebase**: Android (Kotlin/Compose) and iOS (Swift/SwiftUI) modules map 1:1 — `Merger/`, `Editor/Engine/`, `Douyin/`, `Util/` — with identical algorithm parameters
- **FFmpegKit full-gpl**: full x264/aac codec set; Android APK kept at ~17MB via ABI filtering + R8
- **Parser resilience**: multi-candidate watermark-free URLs (stable `video_id` form first), app UA on every request, graceful failure messages

---

## 文档 Documentation

| 文档 Doc | 内容 Content |
|----------|--------------|
| [VideoMergeAndroid/README.md](VideoMergeAndroid/README.md) | Android 架构、打包与解析原理 / architecture & build |
| [VideoMergeIOS/README.md](VideoMergeIOS/README.md) | iOS 架构与构建 / architecture & build |
| [FEATURES.md](FEATURES.md) | 双端功能状态矩阵 / per-feature status matrix |
| [ROADMAP.md](ROADMAP.md) | 版本路线图 / versioned roadmap |

## 免责声明 Disclaimer

抖音解析功能仅供个人学习与备份已获授权的内容,请尊重创作者权益与平台服务条款,勿用于商业用途。
The Douyin parsing feature is for personal study and backing up authorized content only. Please respect creators' rights and the platform's terms of service; do not use it commercially.
