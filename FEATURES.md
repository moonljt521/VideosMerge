# 视频剪辑功能清单（状态矩阵）

> **工作流**：Python 脚本验证 → Android/iOS App 移植
> 本文件是「功能级」的真实状态对照表；版本规划见 `ROADMAP.md`。
> **状态**：✅ 已接入 UI 全链路 · 🟡 引擎具备但缺 UI 入口 · ❌ 未实现
> 最近核对：2026-08-27（基于代码逐项盘点）

---

## 一、三大合并模块（首页独立流程）

| # | 功能 | Python 脚本 | Android | iOS | 备注 |
|---|------|:---:|:---:|:---:|------|
| 1 | 网格拼贴合并 | ✅ `grid_merge.py` | ✅ | ✅ | 多视频均匀网格布局 |
| 2 | 画中画合并 | ✅ `collage_merge.py` | ✅ | ✅ | 一主多副布局（人脸裁剪仅 Python 有） |
| 3 | 照片墙合并 | ✅ `photo_wall_merge.py` | ✅ | ✅ | Treemap 布局 + 白边框 + 阴影 |
| 4 | 抖音尾部 Logo 检测截断 | ✅ 内置 | 🔧 接口保留未调用 | 🟡 单片段有按钮，批量版无入口 | freezedetect |

## 二、编辑器 · 基础剪辑

| # | 功能 | 脚本 | Android | iOS | 备注 |
|---|------|:---:|:---:|:---:|------|
| 5 | 时间段裁剪（含分割/区间删除） | ✅ trim_video.py | ✅ | ✅ | 入出点滑块 + 播放头分割 |
| 6 | 旋转 / 翻转 | ✅ rotate_video.py | ✅ | ✅ | transpose/hflip/vflip |
| 7 | 变速（快进/慢动作 0.25x~4x） | ✅ slow_motion.py + speed_up_video.py | ✅ | ✅ | setpts + atempo 链 |
| 8 | 倒放 | ✅ reverse_video.py | ✅ | ✅ | reverse + areverse |
| 9 | 画面空间裁剪（用户自定义区域） | ✅ crop_video.py | ❌ | ❌ | 当前 crop 仅用于画布适配 |
| 10 | 缩放 / 改分辨率 | ✅ scale_video.py | 🟡 | 🟡 | 引擎按 canvas 输出，画布尺寸硬编码无 UI |

## 三、编辑器 · 视觉效果

| # | 功能 | 脚本 | Android | iOS | 备注 |
|---|------|:---:|:---:|:---:|------|
| 11 | 滤镜调色（8 预设 + 手动三参） | ✅ color_filter.py | ✅ | ✅ | iOS 含 CIFilter 实时预览 |
| 12 | 文字水印 | ✅ text_watermark.py | ✅ | ✅ | 平台原生渲染 PNG + overlay |
| 13 | 图片水印 | ✅ image_watermark.py | ✅ | ✅ | 九宫格位置 + 透明度 |
| 14 | 模糊背景填充 | ✅ blur_bg.py | ✅ | ✅ | split + avgblur + overlay |
| 15 | 转场 xfade（约 20 种 + 时长调节） | ✅ transition.py | ✅ | ✅ | 音频 acrossfade 同步 |
| 16 | 去水印（局部模糊/马赛克 + 自动检测） | ✅ mosaic.py | ✅ | ✅ | 区域框选 + 时段生效 + 自动检测 |
| 17 | 画中画多轨（视频/图片叠加） | — | ✅ | ✅ | 位置/大小/透明度/形状蒙版/描边/时间窗 |
| 18 | 关键帧动画 v1 | — | ✅ | ✅ | 仅画中画位置 x/y；缩放/透明度待扩 |
| 19 | 静态贴纸（emoji 库） | — | ✅ | ✅ | 复用画中画叠加；动态特效未做 |
| 20 | 电影黑边 letterbox | ✅ letterbox.py | ❌ | ❌ | |
| 21 | 胶片颗粒/噪点 | ✅ film_grain.py | ❌ | ❌ | |
| 22 | LUT 调色 | ✅ lut_color.py | ❌ | ❌ | 受 FFmpegKit 精简包约束需验证 |
| 23 | 定格 freeze | ✅ freeze_frame.py | ❌ | ❌ | tpad |

## 四、编辑器 · 音频

| # | 功能 | 脚本 | Android | iOS | 备注 |
|---|------|:---:|:---:|:---:|------|
| 24 | 音量调整 | ✅ volume_adjust.py | ✅ | ✅ | 0~3x |
| 25 | 音频淡入淡出 | ✅ audio_fade.py | ✅ | ✅ | afade |
| 26 | 变声（音高调整） | — | ✅ | ✅ | asetrate + atempo 保速变调 |
| 27 | 降噪 | — | ✅ | ✅ | afftdn |
| 28 | 一键静音开关 | — | 🟡 | 🟡 | 音量滑到 0 可等效；Track.muted 为死代码 |
| 29 | BGM 混合 | ✅ mix_bgm.py | 🟡 | 🟡 | AUDIO 轨类型已定义但零使用 |
| 30 | 音频提取（导出音频文件） | ✅ extract_audio.py | ❌ | ❌ | |
| 31 | 音频替换 | ✅ replace_audio.py | ❌ | ❌ | |

## 五、编辑器 · 字幕

| # | 功能 | Android | iOS | 备注 |
|---|------|:---:|:---:|------|
| 32 | 手动字幕（文本 + 起止时间） | ✅ | ✅ | PNG 按时间窗烧录 |
| 33 | 语音转字幕 | ✅ Vosk | ✅ SFSpeech | 仅识别主轨第一个片段；单行不换行 |
| 34 | 字幕样式/花字/文字动画 | ❌ | ❌ | |

## 六、导出与工具

| # | 功能 | 脚本 | Android | iOS | 备注 |
|---|------|:---:|:---:|:---:|------|
| 35 | 导出成片（mp4/H.264 硬编码 + 存相册 + 历史） | — | ✅ | ✅ | 固定 8M 码率 |
| 36 | GIF 动图导出 | ✅ video_to_gif.py | ❌ | ❌ | palettegen/paletteuse |
| 37 | 视频截图 | ✅ screenshot.py | ❌ | ❌ | |
| 38 | 压缩（可选码率/质量） | ✅ compress.py | ❌ | ❌ | |
| 39 | 格式转换 | ✅ convert_format.py | ❌ | ❌ | |
| 40 | 片头片尾 | ✅ intro_outro.py | ❌ | ❌ | |
| 41 | 视频防抖 | ✅ stabilize.py | ❌ | ❌ | vidstab 两步法 |
| 42 | 批量处理 | ✅ batch_process.py | ❌ | ❌ | 跨项目批量套用+导出 |
| 43 | 人声分离 | ❌ | ❌ | ❌ | 需额外模型 |

## 七、通用基建（双端均已有）

草稿自动保存/恢复（导出成功自动清除 ✅）、项目历史记录 + 相册回看、FFmpegRunner 进度解析、画布/素材持久化管理。

> Python 根目录另有全部脚本的独立说明；App 端移植时以各脚本 ffmpeg 参数为参考。
