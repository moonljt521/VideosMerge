<div align="center">

# VideoMerger · 影剪 for iOS

**iOS 端原生实现,与 [Android 端](../VideoMergeAndroid) 功能 1:1 对齐**

[![iOS](https://img.shields.io/badge/iOS-16%2B-lightgrey?logo=ios)]( "")
[![Swift](https://img.shields.io/badge/Swift-SwiftUI-orange?logo=swift)]( "")

[English](README.md) · **简体中文**

</div>

---

## 功能

### 🎬 影剪编辑器

- 时间轴剪辑(裁剪/分割/变速 0.25x~4x/旋转翻转)
- 滤镜调色实时预览,支持 LUT
- 画中画(多轨 + 位置关键帧动画)、贴纸、图片/文字水印
- 水印自动检测 + 手动框选遮挡
- 字幕(手动轨道 + Vosk 离线语音转写,词级时间戳,SRT 烧录)
- 音频变声(0.5x~2x)与降噪(afftdn)
- 转场实时预览,草稿自动保存/恢复(导出成功自动清除)

### 🧩 视频合并

- 三种布局:**网格拼贴** / **画中画**(一主多副)/ **照片墙**(错落 + 边框阴影)
- 抖音尾部静止 logo 自动截断(`freezedetect`)
- 画布预设 1920×1080 / 1080×1920 / 1080×1080 / 4K,`libx264` 软编

### 💧 抖音去水印

粘贴分享文案 → 解析无水印视频 → 预览 → 保存相册。

与 Android 完全同链路:短链 → 视频 ID → 移动端 feed 接口 → 多候选无水印地址下载。详见 [`Douyin/DouyinParser.swift`](VideoMerger/Douyin/DouyinParser.swift) 头注释。

## 项目结构

```
VideoMergeIOS/
├── Podfile                          # CocoaPods(ffmpeg-kit-ios-full-gpl)
├── VideoMerger.xcworkspace          # 用 Xcode 打开这个
└── VideoMerger/
    ├── VideoMergerApp.swift         # 入口 + FFmpegKit/音频会话初始化
    ├── Editor/                      # 影剪编辑器
    │   ├── EditorViewModel.swift    #   状态机(+Features 扩展)
    │   ├── Engine/                  #   导出/滤镜/贴纸/画中画蒙版/水印检测/语音识别
    │   ├── Data/DraftStore.swift    #   草稿持久化
    │   └── UI/                      #   编辑器界面/预览/时间轴/面板
    ├── Douyin/                      # 抖音去水印(Parser/ViewModel/View)
    ├── Merger/                      # 三种合并算法 + MergeEngine
    ├── Models/                      # 合并类型/元数据/历史条目
    ├── UI/                          # 合并界面/播放器/历史/选片器(含首页 HomeView)
    └── Util/                        # MediaUtils / VideoHistoryStore
```

## 编译

前提:Xcode 15+(macOS 13+)、CocoaPods、iOS 16+ 真机或模拟器。

```bash
cd VideoMergeIOS
pod install                  # 首次下载 ffmpeg-kit full-gpl 约 100~200MB
open VideoMerger.xcworkspace # 注意是 .xcworkspace 不是 .xcodeproj
```

选择真机/模拟器,Run(▶)。首次运行请求相册权限。

## 技术要点

| 组件 | 说明 |
|------|------|
| Swift + SwiftUI | 声明式 UI,`@MainActor` ObservableObject 状态管理 |
| FFmpegKit full-gpl | `ffmpeg-kit-ios-full-gpl`(社区预编译,含 x264/aac) |
| AVFoundation / Photos | 预览播放、缩略图,Photos 框架保存相册 |
| PHPicker + PHAssetResource | 多选视频流式写盘,避免大视频撑爆内存 |
| Vosk | 离线中文语音识别(词级时间戳) |

## 注意事项

1. **体积**:ffmpeg-kit full-gpl 约 80~120MB,是主要体积来源
2. **编码**:`libx264 -preset medium -crf 20` 软编(与 Python 脚本一致),比 Android 硬编慢
3. **相册权限**:`NSPhotoLibraryUsageDescription` / `NSPhotoLibraryAddUsageDescription` 已在 Info.plist 声明
4. **沙盒**:草稿与历史存在 App Documents,卸载即丢失

## 常见问题

### `No such module 'ffmpegkit'`

**原因:打开的是 `VideoMerger.xcodeproj`,而不是 `VideoMerger.xcworkspace`。**

CocoaPods 把 ffmpegkit 的 xcframework 放在 `Pods` 子工程里。只有 `.xcworkspace` 同时包含
`VideoMerger.xcodeproj` 和 `Pods/Pods.xcodeproj`,构建时才会先把 Pods 编出来;
直接开 `.xcodeproj` 时 Pods 从未被构建,Swift 自然找不到 `ffmpegkit` 模块。

解决:关闭 Xcode → `open VideoMerger.xcworkspace` → 再 Run。

若仍报错,按顺序排查:

```bash
# 1. 确认 Pods 已完整安装(xcframework 存在)
ls Pods/ffmpeg-kit-ios-full-gpl/Frameworks/ffmpegkit.xcframework

# 2. 重新安装并校验
pod deintegrate && rm -rf Pods Podfile.lock && pod install

# 3. 清缓存(改过 Podfile / 换过 Xcode 后常见)
rm -rf ~/Library/Developer/Xcode/DerivedData/VideoMerger-*
```

安装后 `Pods/Pods.xcodeproj` 必须存在,且 `VideoMerger.xcworkspace/contents.xcworkspacedata`
应同时引用 `VideoMerger.xcodeproj` 与 `Pods/Pods.xcodeproj`。

### 命令行构建

```bash
xcodebuild -workspace VideoMerger.xcworkspace -scheme VideoMerger \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
```

---

> 项目总览: [根 README](../README.md) · [FEATURES.md](../FEATURES.md) · [ROADMAP.md](../ROADMAP.md)
