# 视频剪辑功能清单

> **工作流**：Python 脚本验证 → Android/iOS App 移植  
> **优先级**：P0 = 必须 / P1 = 高 / P2 = 中 / P3 = 低  
> **状态**：✅ 已实现 · 🔧 脚本已完成 App 待移植 · ❌ 未实现

---

## 一、已实现功能（现有基线）

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 1 | 网格拼贴合并 | ✅ `grid_merge.py` | ✅ `GridMerger.kt` | ✅ `GridMerger.swift` | — | 多视频均匀网格布局 |
| 2 | 画中画合并 | ✅ `collage_merge.py` | ✅ `CollageMerger.kt` | ✅ `CollageMerger.swift` | — | 一主多副布局 + 人脸裁剪(Python) |
| 3 | 照片墙合并 | ✅ `photo_wall_merge.py` | ✅ `PhotoWallMerger.kt` | ✅ `PhotoWallMerger.swift` | — | Treemap 布局 + 白边框 + 阴影 |
| 4 | 视频快进/加速 | ✅ `speed_up_video.py` | ❌ | ❌ | — | setpts + atempo 链式 |
| 5 | 抖音尾部 Logo 检测截断 | ✅ (内置于三个合并脚本) | 🔧 接口保留未调用 | 🔧 接口保留未调用 | — | freezedetect 滤镜 |
| 6 | 人脸检测智能裁剪 | ✅ (内置于 collage/wall) | ❌ 使用正中裁剪 | ❌ 使用正中裁剪 | — | OpenCV Haar 级联 |

---

## 二、基础剪辑（单视频操作）

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 7 | **视频时间段裁剪** | ❌ | ❌ | ❌ | **P0** | 截取 2s~8s 片段，最高频需求 |
| 8 | **画面空间裁剪** | ❌ | ❌ | ❌ | **P0** | 裁掉画面多余区域，自定义裁剪框 |
| 9 | **视频旋转/翻转** | ❌ | ❌ | ❌ | **P0** | 90°/180°/270° 旋转 + 水平/垂直镜像 |
| 10 | **视频缩放/改分辨率** | ❌ | ❌ | ❌ | **P1** | 720p→1080p 或缩小省空间 |
| 11 | **视频倒放** | ❌ | ❌ | ❌ | **P1** | 反向播放，reverse 滤镜 |
| 12 | **慢动作** | ❌ | ❌ | ❌ | **P1** | 0.25x~0.5x 减速，补充已有的快进 |
| 13 | **画面定格** | ❌ | ❌ | ❌ | **P2** | 某一帧暂停持续 N 秒后继续 |
| 14 | **格式转换** | ❌ | ❌ | ❌ | **P2** | HEVC→H.264 / MP4→MOV 等 |

---

## 三、视觉特效

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 15 | **滤镜调色** | ❌ | ❌ | ❌ | **P0** | 亮度/对比度/饱和度 + 预设风格(复古/暖色/冷色) |
| 16 | **文字水印** | ❌ | ❌ | ❌ | **P0** | 添加标题、时间戳，drawtext 滤镜 |
| 17 | **图片水印** | ❌ | ❌ | ❌ | **P1** | 叠加 logo / 图片 overlay |
| 18 | **模糊背景填充** | ❌ | ❌ | ❌ | **P1** | 竖屏→横屏时背景模糊填充 |
| 19 | **转场效果** | ❌ | ❌ | ❌ | **P1** | 多段视频间淡入淡出/滑动转场 |
| 20 | **电影黑边** | ❌ | ❌ | ❌ | **P2** | 加 2.35:1 电影黑边 / 圆角画面 |
| 21 | **胶片颗粒/噪点** | ❌ | ❌ | ❌ | **P2** | noise 滤镜，复古质感 |
| 22 | **局部马赛克** | ❌ | ❌ | ❌ | **P2** | 人脸/指定区域打码 |
| 23 | **LUT 调色** | ❌ | ❌ | ❌ | **P3** | 加载 .cube LUT 文件专业调色 |

---

## 四、音频处理

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 24 | **音频提取** | ❌ | ❌ | ❌ | **P1** | 视频提取 MP3/AAC 音频 |
| 25 | **音频替换** | ❌ | ❌ | ❌ | **P1** | 替换视频原音轨为新音频文件 |
| 26 | **背景音乐混合** | ❌ | ❌ | ❌ | **P1** | 叠加 BGM，可调混合比例 |
| 27 | **音量调整/静音** | ❌ | ❌ | ❌ | **P1** | 放大/降低/静音 |
| 28 | **音频淡入淡出** | ❌ | ❌ | ❌ | **P2** | 开头淡入 + 结尾淡出 |
| 29 | **视频拼接** | ❌ | ❌ | ❌ | **P1** | 多视频首尾拼接（区别于画中画合并） |
| 30 | **人声分离/降噪** | ❌ | ❌ | ❌ | **P3** | 分离人声/背景音，需要额外模型 |

---

## 五、导出与工具

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 31 | **GIF 动图导出** | ❌ | ❌ | ❌ | **P1** | 视频片段→GIF，可调尺寸/帧率/循环 |
| 32 | **视频截图** | ❌ | ❌ | ❌ | **P1** | 批量提取关键帧/指定时间点截图 |
| 33 | **视频压缩** | ❌ | ❌ | ❌ | **P2** | 降码率/分辨率，大幅减小体积 |
| 34 | **片头片尾** | ❌ | ❌ | ❌ | **P2** | 自动添加图片/视频片头片尾 |
| 35 | **视频防抖** | ❌ | ❌ | ❌ | **P3** | vidstabdetect + vidstabtransform |
| 36 | **批量处理** | ❌ | ❌ | ❌ | **P3** | 对目录所有视频批量执行操作 |

---

## 六、实施路线图

### 阶段一：P0 核心功能（脚本先行）

目标：覆盖最高频的单视频剪辑需求。

| 序号 | 功能 | 脚本文件名 | 核心 ffmpeg 技术 | App 移植难度 |
|:---:|------|------|------|:---:|
| 7 | 视频时间段裁剪 | `trim_video.py` | `ss` + `t` / `trim` 滤镜 | ⭐ |
| 8 | 画面空间裁剪 | `crop_video.py` | `crop=w:h:x:y` | ⭐ |
| 9 | 视频旋转/翻转 | `rotate_video.py` | `transpose` / `hflip` / `vflip` | ⭐ |
| 15 | 滤镜调色 | `color_filter.py` | `eq` / `hue` / `curves` | ⭐⭐ |
| 16 | 文字水印 | `text_watermark.py` | `drawtext` | ⭐⭐ |

### 阶段二：P1 高优先级功能

| 序号 | 功能 | 脚本文件名 | 核心 ffmpeg 技术 | App 移植难度 |
|:---:|------|------|------|:---:|
| 4→App | 快进移植到 App | — | setpts + atempo（已有脚本） | ⭐⭐ |
| 10 | 视频缩放 | `scale_video.py` | `scale` | ⭐ |
| 11 | 视频倒放 | `reverse_video.py` | `reverse` | ⭐⭐ |
| 12 | 慢动作 | `slow_motion.py` | `setpts=PTS*speed` | ⭐ |
| 17 | 图片水印 | `image_watermark.py` | `overlay` | ⭐⭐ |
| 18 | 模糊背景 | `blur_bg.py` | `boxblur` + `overlay` | ⭐⭐⭐ |
| 19 | 转场效果 | `transition.py` | `xfade` | ⭐⭐⭐ |
| 24 | 音频提取 | `extract_audio.py` | `-vn -c:a copy` | ⭐ |
| 25 | 音频替换 | `replace_audio.py` | `-i audio -map 0:v -map 1:a` | ⭐ |
| 26 | 背景音乐混合 | `mix_bgm.py` | `amix` / `volume` | ⭐⭐ |
| 27 | 音量调整/静音 | `volume_adjust.py` | `volume` 滤镜 | ⭐ |
| 29 | 视频拼接 | `concat_video.py` | `concat` demuxer / filter | ⭐⭐ |
| 31 | GIF 导出 | `video_to_gif.py` | `fps` + `palettegen` + `paletteuse` | ⭐⭐ |
| 32 | 视频截图 | `screenshot.py` | `-frames:v 1` / `fps` | ⭐ |

### 阶段三：P2/P3 增强

| 序号 | 功能 | 脚本文件名 | 核心 ffmpeg 技术 | App 移植难度 |
|:---:|------|------|------|:---:|
| 13 | 画面定格 | `freeze_frame.py` | `tpad` + `select` | ⭐⭐ |
| 14 | 格式转换 | `convert_format.py` | `-c:v` / `-c:a` | ⭐ |
| 20 | 电影黑边 | `letterbox.py` | `pad` / `crop` | ⭐ |
| 21 | 胶片颗粒 | `film_grain.py` | `noise` 滤镜 | ⭐⭐ |
| 22 | 局部马赛克 | `mosaic.py` | `crop` + `boxblur` + `overlay` | ⭐⭐⭐ |
| 23 | LUT 调色 | `lut_color.py` | `lut3d` 滤镜 | ⭐⭐ |
| 28 | 音频淡入淡出 | `audio_fade.py` | `afade` 滤镜 | ⭐ |
| 33 | 视频压缩 | `compress.py` | `-crf` / `-b:v` 调参 | ⭐ |
| 34 | 片头片尾 | `intro_outro.py` | `concat` + `overlay` | ⭐⭐⭐ |
| 35 | 视频防抖 | `stabilize.py` | `vidstabdetect` + `vidstabtransform` | ⭐⭐⭐⭐ |
| 36 | 批量处理 | `batch_process.py` | 调度层 | ⭐⭐ |

---

## 七、App 架构规划

现有 App 以「多视频合并」为核心。新增单视频剪辑功能后，建议按如下架构演进：

```
VideoMerger App
├── 合并模块（现有）
│   ├── GridMerger      — 网格拼贴
│   ├── CollageMerger   — 画中画
│   └── PhotoWallMerger — 照片墙
│
├── 剪辑模块（新增）
│   ├── TrimTool         — 时间段裁剪
│   ├── CropTool         — 画面空间裁剪
│   ├── RotateTool       — 旋转/翻转
│   ├── ScaleTool        — 缩放/改分辨率
│   ├── ReverseTool      — 倒放
│   ├── SpeedTool        — 调速（快进+慢动作）
│   ├── FilterTool       — 滤镜调色
│   ├── TextWatermarkTool— 文字水印
│   ├── ImageOverlayTool — 图片水印
│   ├── BlurBgTool       — 模糊背景
│   ├── TransitionTool   — 转场效果
│   └── ...
│
├── 音频模块（新增）
│   ├── AudioExtractTool — 音频提取
│   ├── AudioReplaceTool — 音频替换
│   ├── MixBgmTool       — 背景音乐混合
│   ├── VolumeTool       — 音量调整
│   └── ...
│
└── 导出模块（新增）
    ├── GifExportTool   — GIF 导出
    ├── ScreenshotTool  — 视频截图
    ├── CompressTool    — 压缩
    └── ...
```

### 共享基础设施

| 组件 | 说明 | 现有基础 |
|------|------|------|
| `FFmpegRunner` | ffmpeg 异步执行 + 进度回调 | ✅ 已有 |
| `MediaUtils` | 视频元数据探测 / 文件管理 | ✅ 已有 |
| `VideoPlayer` | 内联 + 全屏播放器 | ✅ 已有 |
| `VideoHistoryStore` | 本地历史记录 | ✅ 已有 |
| `FilterBuilder` | ffmpeg 滤镜链构建器（新增） | ❌ 待建 |
| `PreviewProvider` | 实时预览缩略图（新增） | ❌ 待建 |

---

## 八、脚本开发规范

每个 Python 脚本遵循以下规范，确保后续可平滑移植到 App：

1. **统一入口**：`python3 xxx.py -i input.mp4 -o output.mp4`
2. **参数化**：所有可调参数通过 argparse 暴露
3. **元数据探测**：用 ffprobe 获取时长/分辨率/音频
4. **ffmpeg 构建**：filter_complex 逻辑清晰、可读
5. **进度输出**：stderr 解析 ffmpeg progress（App 端同样解析）
6. **错误处理**：check=True + try/except 友好提示
7. **文件命名**：`output_xxx.mp4`，避免覆盖原文件
