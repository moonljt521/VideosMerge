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

## FFmpegKit 版本限制与替代方案

当前使用的是本地 `app/libs/ffmpeg-kit.aar`：**FFmpegKit `min` 版本**（LGPL 许可，FFmpeg n6.0），不包含 GPL 组件。以下是限制和替代方案：

| 组件 | min 版状态 | 替代方案 | 实现文件 |
|------|:---:|------|------|
| `libx264` 编码器 | ❌ 缺失 | `h264_mediacodec`（硬件编码） | `FilterBuilder.kt` |
| `eq` 滤镜（调色） | ✅ 可用 | 直接使用（亮度/对比度/饱和度/伽马） | `FilterBuilder.kt` |
| `hue` 滤镜（色调） | 使用 `huesaturation` | `huesaturation`（色调旋转） | `FilterBuilder.kt` |
| `pad` 滤镜（补黑边） | ❌ 缺失 | `scale` cover 模式 + `crop` | `FilterBuilder.kt` |
| `fps` 滤镜 | ✅ 可用 | 统一输入帧率（xfade/concat 要求一致） | `FilterBuilder.kt` |
| `boxblur` 滤镜 | ❌ 缺失 | `avgblur`（均值模糊） | `FilterBuilder.kt` |
| `drawtext` 滤镜 | ❌ 缺失 | Android Canvas 生成 PNG + `overlay` | `TextWatermarkRenderer.kt` |

### h264_mediacodec 注意事项

- 画布尺寸必须 **16 对齐**（macroblock 对齐），否则编码失败
- 不支持 `-crf` / `-preset`，用 `-b:v` 指定比特率
- 要求 `yuv420p` 像素格式
- 部分低端设备/模拟器可能不支持，建议后续替换为 `full-gpl` 版本

### 升级到 full-gpl 版本（推荐）

如需 `libx264`、`pad`、`drawtext`、`boxblur` 等更完整的编码器和滤镜支持，可替换 aar 为 `full-gpl` 版本：

1. 下载 `ffmpeg-kit-full-gpl-*.aar`（从 [FFmpegKit releases](https://github.com/arthenica/ffmpeg-kit/releases) 或 Maven 镜像）
2. 替换 `app/libs/ffmpeg-kit.aar`
3. 修改 `FilterBuilder.kt`：
   - `h264_mediacodec` → `libx264`
   - `scale cover + crop` → `scale + pad`
   - `TextWatermarkRenderer` → `drawtext`（可选，PNG 方案也可保留）
