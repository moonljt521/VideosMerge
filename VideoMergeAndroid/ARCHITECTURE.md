# VideoEditor Android 架构设计

> 从「合并工具」到「剪映式编辑器」的架构演进

## 新旧架构对比

| 维度 | 旧版（VideoMerger） | 新版（VideoEditor） |
|------|------|------|
| 核心流程 | 选视频→选模式→合并→保存 | 选视频→时间轴编辑→实时预览→导出 |
| UI 布局 | 单页表单 | 三段式：预览+时间轴+工具栏 |
| 操作粒度 | 整个合并任务 | 单个片段（分割/调色/变速/加文字...） |
| 预览方式 | 合并后播放 | 编辑时实时预览 |
| 数据模型 | MergeType + MergeOptions | Project → Track → Clip → Effect |

## 新目录结构

```
com.moon.videomerger/
├── MainActivity.kt              # 导航入口
├── VideoMergerApp.kt            # Application
│
├── editor/                      # ── 编辑器核心 ──
│   ├── data/
│   │   ├── EditorTypes.kt       # 数据模型（Project/Track/Clip/Effect）
│   │   └── EditorState.kt       # UI 状态
│   ├── engine/
│   │   ├── FilterBuilder.kt     # Clip → ffmpeg filter_complex
│   │   ├── ExportEngine.kt      # 时间轴 → ffmpeg 命令 → 导出
│   │   └── FFmpegRunner.kt      # FFmpegKit 封装（复用现有）
│   ├── ui/
│   │   ├── EditorScreen.kt      # 三段式主界面
│   │   ├── EditorViewModel.kt   # 状态管理
│   │   ├── PreviewPanel.kt      # ExoPlayer 预览区
│   │   ├── TimelinePanel.kt     # 多轨时间轴
│   │   ├── ToolbarPanel.kt      # 底部工具栏
│   │   └── panels/              # 工具子面板
│   │       ├── TrimPanel.kt     # 裁剪
│   │       ├── SpeedPanel.kt    # 变速
│   │       ├── FilterPanel.kt   # 滤镜
│   │       ├── TextPanel.kt     # 文字
│   │       ├── AudioPanel.kt    # 音频
│   │       └── ExportPanel.kt   # 导出
│   └── tools/
│       └── ClipProcessor.kt     # 片段级处理（缩略图/分割/变速...）
│
├── home/
│   ├── HomeScreen.kt            # 首页（新建项目/历史）
│   └── HomeViewModel.kt
│
├── merger/                      # ── 保留现有合并功能 ──
│   └── ...（现有文件不改动）
│
└── util/
    ├── MediaUtils.kt            # （现有，复用）
    └── VideoHistoryStore.kt     # （现有，复用）
```

## 数据模型

```
EditorProject
├── id: String
├── name: String
├── canvasWidth: Int (默认 1080)
├── canvasHeight: Int (默认 1920)
├── fps: Int (默认 30)
├── tracks: List<Track>
│
└── Track
    ├── id: String
    ├── type: TrackType (MAIN/PICTURE/TEXT/AUDIO)
    ├── clips: List<Clip>
    │
    └── Clip
        ├── id: String
        ├── mediaPath: String       # 源文件路径
        ├── trimStart: Double        # 入点（秒）
        ├── trimEnd: Double          # 出点（秒）
        ├── timelineStart: Double   # 时间轴上的位置
        ├── speed: Double           # 变速倍率
        ├── volume: Double          # 音量
        ├── effects: List<Effect>
        │
        └── Effect
            ├── type: EffectType
            ├── params: Map<String, Any>
```

## 三段式 UI 布局

```
┌─────────────────────┐
│ ← 项目名    导出 ↗  │  顶部栏（48dp）
├─────────────────────┤
│                     │
│   预览区            │  约屏幕 45%
│   ExoPlayer         │  实时渲染
│                     │
├─────────────────────┤
│ ┃▌播放头             │
│ ┃ ▓▓▓▓│▓▓▓▓▓▓▓    │  时间轴 约屏幕 25%
│ ┃ ▓▓▓▓│▓▓▓▓▓▓▓    │  多轨+缩略图
├─────────────────────┤
│ 剪辑 变速 滤镜 文字 │  工具栏 约屏幕 30%
│ 转场 音频 导出  ...  │  可滚动
└─────────────────────┘
```

## 交互流程

1. **首页** → 新建项目 → 选择视频
2. **编辑器** → 视频自动放入主轨
3. **选中片段** → 工具栏显示可用操作
4. **点击工具** → 底部切换为工具面板（滑块/选项）
5. **实时预览** → 调整参数时预览区更新
6. **导出** → 构建 ffmpeg 命令 → 进度条 → 保存到相册
```

## FFmpegKit 版本与依赖

FFmpegKit 官方已 2025-01 归档、2025-04 从 Maven Central 下架。本项目已切换到社区维护分支
[ffmpegkit-maintained/ffmpeg](https://github.com/ffmpegkit-maintained/ffmpeg)（同包名
`com.arthenica.ffmpegkit`、同 API，仅 groupId 变化）。依赖在 `app/build.gradle.kts`：

```gradle
implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3") // full = LGPL，FFmpeg n6.1.6
implementation("com.arthenica:smart-exception-java:0.2.1")       // ★ 分支 POM 漏声明，需显式补
```

### 为什么是 full（LGPL）而不是 full-gpl（GPL）

| 变体 | 许可 | 说明 |
|------|------|------|
| `ffmpeg-kit-full` | LGPL | ✅ 现用。可闭源；含 libass/subtitles + 全套 LGPL 编解码 |
| `ffmpeg-kit-min-gpl` / `full-gpl` | GPL | 加 x264/x265/vidstab，但会强制 App 整包 GPL 开源，商用别碰 |

> 实测：`full`（LGPL）里也 **没有 `drawtext`、`boxblur`、`libx264`**。文字水印继续用 Canvas PNG + overlay；模糊背景继续用 `avgblur`；编码继续用硬件 `h264_mediacodec`。只有明确需要 x264 软件编码或 vidstab 防抖时，才考虑 `-gpl`。

### 版本选择（重要）

维护分支有三条 LTS 线，务必固定版本：

| 版本 | FFmpeg | 状态 |
|------|--------|------|
| `6.0.3` | n6.1.6 | ✅ 现用，最接近原 n6.0，转场行为稳定 |
| `7.1.6` | n7.1.5 | 备选 |
| `8.1.7` | n8.1.2 | ⚠️ xfade 转场真机导出 native 崩溃，已回退 |

### h264_mediacodec 注意事项

- 画布尺寸必须 **16 对齐**（macroblock 对齐），否则编码失败
- 不支持 `-crf` / `-preset`，用 `-b:v` 指定比特率
- 要求 `yuv420p` 像素格式
- LGPL 兜底可用 `openh264`（full 内含）；仍不建议切到 GPL 的 `libx264`

### 已踩过的坑

1. **启动 NoClassDefFoundError**：分支 POM 漏声明 `smart-exception` 传递依赖，`FFmpegKitConfig` 运行时要 `com.arthenica.smartexception.java.Exceptions` → 显式加 `com.arthenica:smart-exception-java:0.2.1`。
2. **8.1.7 转场崩溃**：n8.1.2 的 xfade 在真机导出时 native SIGSEGV → 回退 6.0.3。
