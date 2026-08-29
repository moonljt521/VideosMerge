<div align="center">

# VideoMerger · 影剪 for iOS

**Native iOS implementation, feature-for-feature aligned with the [Android app](../VideoMergeAndroid)**

[![iOS](https://img.shields.io/badge/iOS-16%2B-lightgrey?logo=ios)]( "")
[![Swift](https://img.shields.io/badge/Swift-SwiftUI-orange?logo=swift)]( "")

**English** · [简体中文](README.zh-CN.md)

</div>

---

## Features

### 🎬 Editor

A CapCut-style mobile editor:

- Timeline editing: trim, split, speed (0.25x–4x), rotate/flip
- Filters & color grading with live preview, LUT support
- Picture-in-picture (multi-track + position keyframes), stickers, image & text watermarks
- Watermark auto-detection + manual box masking
- Subtitles: manual track + offline speech-to-text via Vosk (word-level timestamps, SRT burn-in)
- Audio pitch shifting (0.5x–2x) & noise reduction (afftdn)
- Live-previewed transitions, auto-saved drafts (cleared after successful export)

### 🧩 Video Merging

- Three layouts: **grid** / **collage** (main + subs) / **photo wall** (scattered cards with borders & shadows)
- Auto truncation of Douyin's trailing frozen-logo segment (`freezedetect`)
- Canvas presets 1920×1080 / 1080×1920 / 1080×1080 / 4K, `libx264` software encoding

### 💧 Douyin Watermark-free Parsing

Paste a share text → parse the watermark-free video → preview → save to Photos.

Identical chain to Android: short link → video ID → mobile feed API → multi-candidate watermark-free URLs. See the header comment in [`Douyin/DouyinParser.swift`](VideoMerger/Douyin/DouyinParser.swift) for details.

## Project Layout

```
VideoMergeIOS/
├── Podfile                          # CocoaPods (ffmpeg-kit-ios-full-gpl)
├── VideoMerger.xcworkspace          # open this in Xcode
└── VideoMerger/
    ├── VideoMergerApp.swift         # entry + FFmpegKit/audio-session init
    ├── Editor/                      # the editor
    │   ├── EditorViewModel.swift    #   state machine (+Features extensions)
    │   ├── Engine/                  #   export / filters / stickers / PiP mask /
    │   │                            #   watermark detection / speech recognition
    │   ├── Data/DraftStore.swift    #   draft persistence
    │   └── UI/                      #   editor screen / preview / timeline / panels
    ├── Douyin/                      # Douyin parsing (Parser / ViewModel / View)
    ├── Merger/                      # three merge algorithms + MergeEngine
    ├── Models/                      # merge types / metadata / history entry
    ├── UI/                          # merge screen / players / history / picker (incl. HomeView)
    └── Util/                        # MediaUtils / VideoHistoryStore
```

## Build

Requires Xcode 15+ (macOS 13+), CocoaPods, iOS 16+ device or simulator:

```bash
cd VideoMergeIOS
pod install                  # downloads ~100–200MB ffmpeg-kit full-gpl on first run
open VideoMerger.xcworkspace # note: .xcworkspace, not .xcodeproj
```

Pick a device or simulator and hit Run (▶). The app requests photo-library access on first launch.

## Technical Notes

| Component | Notes |
|-----------|-------|
| Swift + SwiftUI | Declarative UI, `@MainActor` ObservableObject state |
| FFmpegKit full-gpl | community prebuilt `ffmpeg-kit-ios-full-gpl` (x264/aac) |
| AVFoundation / Photos | playback, thumbnails, album saving |
| PHPicker + PHAssetResource | streaming multi-select copy to temp files |
| Vosk | offline Chinese speech recognition (word-level timestamps) |

**Notes:**

1. **Size**: ffmpeg-kit full-gpl adds ~80–120MB — the main size driver
2. **Encoding**: `libx264 -preset medium -crf 20` software encoding (same as the Python scripts), slower than Android's hardware path
3. **Permissions**: `NSPhotoLibraryUsageDescription` / `NSPhotoLibraryAddUsageDescription` declared in Info.plist
4. **Sandbox**: drafts and history live in the app's Documents directory and are lost on uninstall

---

> Project overview: [root README](../README.md) · [FEATURES.md](../FEATURES.md) · [ROADMAP.md](../ROADMAP.md)
