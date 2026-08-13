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
| 7 | **视频时间段裁剪** | ✅ `trim_video.py` | ✅ `FilterBuilder.kt` | ❌ | **P0** | trim 滤镜 + 边界检查 |
| 8 | **画面空间裁剪** | ✅ `crop_video.py` | ✅ `FilterBuilder.kt` | ❌ | **P0** | scale cover + crop（min 版无 pad） |
| 9 | **视频旋转/翻转** | ✅ `rotate_video.py` | ✅ `FilterBuilder.kt` + 剪辑面板 UI | ❌ | **P0** | transpose/hflip/vflip |
| 10 | **视频缩放/改分辨率** | ✅ `scale_video.py` | ✅ `FilterBuilder.kt` | ❌ | **P1** | scale + 16 对齐（mediacodec 要求） |
| 11 | **视频倒放** | ✅ `reverse_video.py` | ✅ `FilterBuilder.kt` + 变速面板开关 | ❌ | **P1** | reverse/areverse 滤镜 |
| 12 | **慢动作** | ✅ `slow_motion.py` | ✅ `FilterBuilder.kt` | ❌ | **P1** | setpts + atempo 链式 |
| 13 | **画面定格** | ✅ `freeze_frame.py` | ❌ | ❌ | **P2** | tpad 滤镜 |
| 14 | **格式转换** | ✅ `convert_format.py` | ❌ | ❌ | **P2** | H.264/HEVC/VP9 |

---

## 三、视觉特效

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 15 | **滤镜调色** | ✅ `color_filter.py` | ✅ `FilterBuilder.kt` | ❌ | **P0** | eq + huesaturation + 7 种预设 |
| 16 | **文字水印** | ✅ `text_watermark.py` | ✅ `TextWatermarkRenderer.kt` | ❌ | **P0** | Android Canvas 生成 PNG + overlay（替代 drawtext） |
| 17 | **图片水印** | ✅ `image_watermark.py` | 🔧 待移植 | ❌ | **P1** | overlay + 9 宫格 + 透明度 |
| 18 | **模糊背景填充** | ✅ `blur_bg.py` | ✅ `FilterBuilder.kt` | ❌ | **P1** | split + avgblur + overlay（替代 boxblur） |
| 19 | **转场效果** | ✅ `transition.py` | ✅ `FilterBuilder.kt` | ❌ | **P1** | xfade 链式，21 种转场效果 |
| 20 | **电影黑边** | ✅ `letterbox.py` | ❌ | ❌ | **P2** | min 版无 pad，需 scale+color 源实现 |
| 21 | **胶片颗粒/噪点** | ✅ `film_grain.py` | ❌ | ❌ | **P2** | noise 滤镜 + 复古调色 |
| 22 | **局部马赛克** | ✅ `mosaic.py` | ❌ | ❌ | **P2** | crop + scale + overlay |
| 23 | **LUT 调色** | ✅ `lut_color.py` | ❌ | ❌ | **P3** | lut3d 滤镜 |

---

## 四、音频处理

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 24 | **音频提取** | ✅ `extract_audio.py` | ❌ | ❌ | **P1** | MP3/AAC/WAV 格式输出 |
| 25 | **音频替换** | ✅ `replace_audio.py` | ❌ | ❌ | **P1** | 外部音频替换视频原音轨 |
| 26 | **背景音乐混合** | ✅ `mix_bgm.py` | ❌ | ❌ | **P1** | BGM 循环+混合+淡入淡出 |
| 27 | **音量调整/静音** | ✅ `volume_adjust.py` | ✅ `FilterBuilder.kt` | ❌ | **P1** | volume 滤镜 |
| 28 | **音频淡入淡出** | ✅ `audio_fade.py` | ✅ `FilterBuilder.kt` + 音频面板 | ❌ | **P2** | afade 滤镜 |
| 29 | **视频拼接** | ✅ `concat_video.py` | ✅ `FilterBuilder.kt` | ❌ | **P1** | concat 滤镜（同编码重编码） |
| 30 | **人声分离/降噪** | ❌ | ❌ | ❌ | **P3** | 分离人声/背景音，需要额外模型 |

---

## 五、导出与工具

| # | 功能 | Python 脚本 | Android | iOS | 优先级 | 备注 |
|---|------|:---:|:---:|:---:|:---:|------|
| 31 | **GIF 动图导出** | ✅ `video_to_gif.py` | ❌ | ❌ | **P1** | 调色板优化，3 级质量 |
| 32 | **视频截图** | ✅ `screenshot.py` | ❌ | ❌ | **P1** | 单帧/多时间点/等间隔截图 |
| 33 | **视频压缩** | ✅ `compress.py` | ❌ | ❌ | **P2** | crf+分辨率缩放+音频比特率 |
| 34 | **片头片尾** | ✅ `intro_outro.py` | ❌ | ❌ | **P2** | 图片/视频片头片尾 + concat 拼接 |
| 35 | **视频防抖** | ✅ `stabilize.py` | ❌ | ❌ | **P3** | vidstab 两步法 + deshake 回退 |
| 36 | **批量处理** | ✅ `batch_process.py` | ❌ | ❌ | **P3** | 6 种操作批量处理目录视频 |

---

## 六、实施路线图

### 阶段一：P0 核心功能（脚本先行） ✅ 全部完成

目标：覆盖最高频的单视频剪辑需求。

| 序号 | 功能 | 脚本文件名 | 核心 ffmpeg 技术 | 状态 | App 移植难度 |
|:---:|------|------|------|:---:|:---:|
| 7 | 视频时间段裁剪 | `trim_video.py` | `ss` + `t` / `trim` 滤镜 | ✅ 已验证 | ⭐ |
| 8 | 画面空间裁剪 | `crop_video.py` | `crop=w:h:x:y` | ✅ 已验证 | ⭐ |
| 9 | 视频旋转/翻转 | `rotate_video.py` | `transpose` / `hflip` / `vflip` | ✅ 已验证 | ⭐ |
| 15 | 滤镜调色 | `color_filter.py` | `eq` / `hue` | ✅ 已验证 | ⭐⭐ |
| 16 | 文字水印 | `text_watermark.py` | Pillow PNG + `overlay` | ✅ 已验证 | ⭐⭐ |

### 阶段二：P1 高优先级功能 ✅ 脚本全部完成

| 序号 | 功能 | 脚本文件名 | 核心 ffmpeg 技术 | 状态 | App 移植难度 |
|:---:|------|------|------|:---:|:---:|
| 4→App | 快进移植到 App | — | setpts + atempo（已有脚本） | ❌ 待移植 | ⭐⭐ |
| 10 | 视频缩放 | `scale_video.py` | `scale` | ✅ 已验证 | ⭐ |
| 11 | 视频倒放 | `reverse_video.py` | `reverse` | ✅ 已验证 | ⭐⭐ |
| 12 | 慢动作 | `slow_motion.py` | `setpts=PTS*speed` | ✅ 已验证 | ⭐ |
| 17 | 图片水印 | `image_watermark.py` | `overlay` | ✅ 已验证 | ⭐⭐ |
| 18 | 模糊背景 | `blur_bg.py` | `boxblur` + `overlay` | ✅ 已验证 | ⭐⭐⭐ |
| 19 | 转场效果 | `transition.py` | `xfade` | ✅ 已验证 | ⭐⭐⭐ |
| 24 | 音频提取 | `extract_audio.py` | `-vn -c:a copy` | ✅ 已验证 | ⭐ |
| 25 | 音频替换 | `replace_audio.py` | `-i audio -map 0:v -map 1:a` | ✅ 已验证 | ⭐ |
| 26 | 背景音乐混合 | `mix_bgm.py` | `amix` / `volume` | ✅ 已验证 | ⭐⭐ |
| 27 | 音量调整/静音 | `volume_adjust.py` | `volume` 滤镜 | ✅ 已验证 | ⭐ |
| 29 | 视频拼接 | `concat_video.py` | `concat` demuxer / filter | ✅ 已验证 | ⭐⭐ |
| 31 | GIF 导出 | `video_to_gif.py` | `fps` + `palettegen` + `paletteuse` | ✅ 已验证 | ⭐⭐ |
| 32 | 视频截图 | `screenshot.py` | `-frames:v 1` / `fps` | ✅ 已验证 | ⭐ |

### 阶段三：P2/P3 增强 ✅ 全部完成

| 序号 | 功能 | 脚本文件名 | 核心 ffmpeg 技术 | 状态 | App 移植难度 |
|:---:|------|------|------|:---:|:---:|
| 13 | 画面定格 | `freeze_frame.py` | `tpad` stop_mode=clone | ✅ 已验证 | ⭐⭐ |
| 14 | 格式转换 | `convert_format.py` | `-c:v` / `-c:a` | ✅ 已验证 | ⭐ |
| 20 | 电影黑边 | `letterbox.py` | `pad` + 圆角蒙版 | ✅ 已验证 | ⭐ |
| 21 | 胶片颗粒 | `film_grain.py` | `noise` 滤镜 | ✅ 已验证 | ⭐⭐ |
| 22 | 局部马赛克 | `mosaic.py` | `crop` + `scale` + `overlay` | ✅ 已验证 | ⭐⭐⭐ |
| 23 | LUT 调色 | `lut_color.py` | `lut3d` 滤镜 | ✅ 已验证 | ⭐⭐ |
| 28 | 音频淡入淡出 | `audio_fade.py` | `afade` 滤镜 | ✅ 已验证 | ⭐ |
| 33 | 视频压缩 | `compress.py` | `-crf` / `scale` / `-b:a` | ✅ 已验证 | ⭐ |
| 34 | 片头片尾 | `intro_outro.py` | `concat` + `loop` + `overlay` | ✅ 已验证 | ⭐⭐⭐ |
| 35 | 视频防抖 | `stabilize.py` | `vidstab` / `deshake` 回退 | ✅ 已验证 | ⭐⭐⭐⭐ |
| 36 | 批量处理 | `batch_process.py` | 调度层（6 种操作） | ✅ 已验证 | ⭐⭐ |

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
| `FilterBuilder` | ffmpeg 滤镜链构建器（min 版适配） | ✅ 已建 |
| `TextWatermarkRenderer` | Android Canvas 生成文字 PNG | ✅ 已建 |
| `ExportEngine` | 导出引擎 + 相册保存 | ✅ 已建 |
| `PreviewProvider` | 实时预览缩略图 | ❌ 待建（当前 ExoPlayer 原始预览） |

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
