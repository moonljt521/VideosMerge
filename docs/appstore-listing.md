# ShadowCut · App Store 上架材料清单（海外全球区，暂不含中国大陆）

> Bundle ID: `com.moon.VideoMerger` · 最低系统 iOS 16.0 · 主语言建议：简体中文（UI 为中文，另加英文本地化）
> 隐私政策 URL: https://moonljt521.github.io/VideosMerge/
> ⚠️ 商店元数据与 App 内界面均不得出现任何第三方平台商标（如"抖音/Douyin/TikTok"字样）——App 内文案已中性化，商店文案按下述内容填写。

---

## 1. App Store Connect 创建参数

| 项 | 值 |
|---|---|
| App 名称 | ShadowCut |
| 主语言 | 简体中文 |
| Bundle ID | com.moon.VideoMerger |
| SKU | shadowcut001（随意，唯一即可） |
| 可用范围 | 全部地区，**取消勾选中国大陆**（免 ICP 备案） |
| 价格 | 免费（内含广告，后续再加 IAP） |

## 2. 英文文案（en-US 本地化）

**Subtitle**（30 字符内）：
```
Merge, Edit & Convert Videos
```

**Keywords**（100 字符内，逗号分隔，勿加空格）：
```
video editor,merge video,trim video,gif maker,compress video,collage,photo wall,slow motion,reverse
```

**Promotional Text**（170 字符内，可随时改）：
```
All-in-one video toolbox: parse & save videos from share links, merge clips in creative layouts, and edit like a pro — 100% on device.
```

**Description**：
```
ShadowCut is a fast, privacy-friendly video toolbox that works entirely on your device. No account, no upload — your videos never leave your phone.

◆ NO-WATERMARK VIDEO PARSING
Paste a share link and get the clean, watermark-free video in seconds. Preview it, save it to your photo library, or share it anywhere.

◆ CREATIVE VIDEO MERGING
Combine multiple clips into one video with three layouts: even grid, main-plus-subs collage, and scattered photo wall. Canvas presets from 1080p to 4K, with hardware-accelerated encoding.

◆ PRO-LEVEL EDITOR
· Timeline editing: trim, split, speed (0.25x–4x), rotate & flip, reverse
· Filters, color adjustments and 20+ transitions with live preview
· Picture-in-picture multi-track overlay with keyframes
· Text & image watermarks, plus smart watermark detection
· Subtitles: manual track or offline speech-to-text (word-level timing)
· Audio tools: volume, fade, pitch shifting, noise reduction

◆ MORE TOOLS
· Video to GIF with three quality presets
· Auto-trim frozen tail logos from downloaded clips
· Drafts & export history, all stored locally

Download ShadowCut and turn scattered clips into finished videos in minutes.
```

**What's New（v1.0）**：
```
Initial release: no-watermark video parsing, 3 merge layouts, full editor, GIF export.
```

## 3. 中文文案（zh-Hans 本地化，主语言）

**副标题**：`视频合并剪辑工具箱`
**关键词**：`视频合并,剪辑,去水印,拼图视频,压缩,GIF,变速,倒放,字幕,画中画`
**宣传文本**：`一站式视频工具箱：粘贴链接保存无水印视频、多种布局合并、专业剪辑——全程在本机完成，不上传不泄露。`
**描述**：按英文版意译，突出「本机处理、隐私安全、免费工具箱」三个卖点；不出现任何第三方平台名称。

## 4. 截图清单（每张配一句 caption）

尺寸：6.9" (1290×2796) 必交；6.5" (1284×2778) 建议同套裁剪。建议 5 张：

| # | 画面 | Caption（EN / ZH） |
|---|---|---|
| 1 | 首页 | Your video toolbox / 你的视频工具箱 |
| 2 | 短视频去水印结果页（预览+保存/分享按钮） | Paste a link, save the clean video / 粘贴链接，保存无水印视频 |
| 3 | 编辑器时间轴 | Pro editing on your phone / 手机上的专业剪辑 |
| 4 | 合并三种布局效果 | Grid, collage & photo wall / 宫格、主次、照片墙 |
| 5 | GIF 导出结果 | One tap to GIF / 一键转 GIF |

截图注意：状态栏用模拟器干净状态（满电满信号）、界面为中性化后的最新版本、不出现任何第三方平台 logo/名称。

## 5. App 隐私标签（如实勾选，别漏也别多勾）

**收集的数据（与身份不关联 Data Not Linked to You）**：
- 标识符：设备 ID（Firebase/AdMob）
- 使用数据：产品交互、广告数据（Analytics/AdMob）
- 诊断：崩溃数据、性能数据（Crashlytics）

**用于跟踪（Data Used to Track You）**：
- 广告数据（AdMob 个性化广告，经 ATT 授权后）——勾选 "Third-Party Advertising" + "Tracking"

**与身份关联的数据**：无（无账号体系，无服务器存储）

## 6. 年龄分级

问卷全按实际答（无暴力/赌博/医疗/限制级内容），预期 **4+**。
- Unrestricted Web Access：**No**（无内嵌浏览器）
- 分享/下载功能不触发额外分级

## 7. 出口合规

只用 HTTPS 标准加密 → 上传构建后合规问题选 "standard encryption algorithms only" = **Yes**（豁免），无需法国额外申报（App 不采集法国用户身份用于加密目的）。BCC 如有问按标准豁免处理。

## 8. App Review 备注（Review Notes 模板）

```
ShadowCut is a local video editing toolbox. All media processing happens on-device.

The "paste a link" feature lets users save videos they already have access to via share links they copied from other apps, using the device's standard networking. We do not host, stream, or redistribute any third-party content, and the app does not use any third-party trademarks in its UI or metadata.

Demo account is not required — all features work offline except link parsing and ads.
```

## 9. 提审前 checklist

- [ ] Xcode 真机验证：广告出街、Firebase 事件、全功能回归
- [ ] AdMob 后台创建 GDPR 消息（Privacy & messaging）
- [ ] LGPL/GPL 决策（三选一，见对话记录；首发可接受现状）
- [ ] 移除/确认无 Debug 日志噪音（FFmpegKit log level 可降为 ERROR）
- [ ] 版本号 1.0.0、构建号 1；Icon 1024 无圆角无 alpha
