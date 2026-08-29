<div align="center">

# VideosMerge · 影剪

**Cross-platform video toolbox: mobile video editor · video merging · watermark-free Douyin parsing**

[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS-blue)]( "")
[![Android](https://img.shields.io/badge/Android-SDK%2034-green?logo=android)]( "")
[![iOS](https://img.shields.io/badge/iOS-16%2B-lightgrey?logo=ios)]( "")
[![Engine](https://img.shields.io/badge/engine-FFmpegKit%20full--gpl-orange)]( "")

**English** · [简体中文](README.zh-CN.md)

</div>

---

## Overview

VideosMerge (app name **影剪**) is a three-tier video toolbox: native Android and iOS apps share one feature set and identical FFmpeg parameters, while the Python scripts in this repo act as the prototyping layer — every capability is first validated as an ffmpeg command line, then ported into the apps.

## Features

### 🎬 Video Editor (Android / iOS)

A CapCut-style mobile editor:

- Timeline editing: trim, split, speed (0.25x–4x), rotate/flip, film-strip UI with fixed center playhead
- Filters & color grading with live preview (LUT support)
- Picture-in-picture: multi-track overlay, position keyframes, speed & rotation
- Stickers (emoji), image & text watermarks, watermark auto-detection + manual box masking
- Subtitles: manual track + offline speech-to-text via Vosk (word-level timestamps, SRT burn-in)
- Audio: pitch shifting (0.5x–2x), noise reduction (afftdn)
- Live-previewed transitions, auto-saved drafts

### 🧩 Video Merging (Android / iOS)

- Three layouts: **grid**, **collage** (main + subs), **photo wall** (scattered cards with borders & shadows)
- Auto truncation of Douyin's trailing frozen-logo segment (`freezedetect`)
- Canvas presets: 1920×1080 / 1080×1920 / 1080×1080 / 4K
- Encoders: hardware `h264_mediacodec` on Android, `libx264` on iOS

### 💧 Douyin Watermark-free Parsing (Android / iOS)

Paste a share text, get the watermark-free video, preview, and save to the photo album:

```
share text → v.douyin.com short link → 302 → video ID from landing URL
           → mobile feed API (with app UA) → play_addr (watermark-free)
           → multi-candidate download → preview → save
```

> Douyin's watermark is composited server-side at download time; the source file itself is clean. Parsing simply fetches the original. See the [Android README](VideoMergeAndroid/README.md) for details.

### 🐍 Python Script Library

33 standalone scripts (trim / speed / watermark / transition / mix / GIF / ...) that prototype every app capability and work on their own.

## Project Layout

```
VideosMerge/
├── VideoMergeAndroid/        # Android app (Kotlin + Jetpack Compose)
│   └── .../videomerger/      #   editor/ home/ merger/ douyin/ ui/
├── VideoMergeIOS/            # iOS app (Swift + SwiftUI, 1:1 mirror of Android)
├── *.py                      # 29 standalone video-processing scripts
├── FEATURES.md               # per-feature status matrix (Android vs iOS)
└── ROADMAP.md                # versioned roadmap
```

## Getting Started

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

## Technical Notes

- **Mirrored codebase**: Android (Kotlin/Compose) and iOS (Swift/SwiftUI) modules map 1:1 — `Merger/`, `Editor/Engine/`, `Douyin/`, `Util/` — with identical algorithm parameters
- **FFmpegKit full-gpl**: full x264/aac codec set; Android APK kept at ~17MB via ABI filtering + R8
- **Parser resilience**: multi-candidate watermark-free URLs (stable `video_id` form first), app UA on every request, graceful failure messages

## Documentation

| Doc | Content |
|-----|---------|
| [VideoMergeAndroid/README.md](VideoMergeAndroid/README.md) | Android architecture, build & parsing internals |
| [VideoMergeIOS/README.md](VideoMergeIOS/README.md) | iOS architecture & build |
| [FEATURES.md](FEATURES.md) | per-feature status matrix (Android vs iOS) |
| [ROADMAP.md](ROADMAP.md) | versioned roadmap |

## Disclaimer

The Douyin parsing feature is for personal study and backing up authorized content only. Please respect creators' rights and the platform's terms of service; do not use it commercially.
