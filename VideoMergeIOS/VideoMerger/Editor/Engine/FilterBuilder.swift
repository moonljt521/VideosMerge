//
//  FilterBuilder.swift
//  VideoMerger
//
//  iOS 移植自 Android FilterBuilder.kt（含转场重叠模型 / 去水印滤镜图等全部修正）
//

import Foundation
import UIKit

/// 数字格式化（ffmpeg 需要小数点，避免本地化逗号）
func fmt(_ v: Double, _ digits: Int = 3) -> String {
    String(format: "%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), v)
}

func toAligned16(_ v: Int) -> Int { max(16, (v / 16) * 16) }

struct TextPngResult {
    let file: URL
    let width: Int
    let height: Int
}

final class FilterBuilder {

    // MARK: - 单片段视频滤镜（分段：pre=源帧几何 / post=旋转缩放调色）

    func buildVideoFilters(_ clip: Clip, _ canvasW: Int, _ canvasH: Int, _ fps: Int) -> String {
        let (pre, post) = buildVideoFiltersSegmented(clip, canvasW, canvasH, fps)
        if pre.isEmpty { return post }
        if post.isEmpty { return pre }
        return pre + "," + post
    }

    /// pre = trim/变速/倒放（保持源帧几何）；post = 旋转/翻转/缩放/调色/帧率/格式
    /// ★ 去水印区域按源帧归一化坐标定义，滤镜图插在 pre 与 post 之间
    func buildVideoFiltersSegmented(_ clip: Clip, _ canvasW: Int, _ canvasH: Int, _ fps: Int) -> (String, String) {
        var pre: [String] = []

        // 1. 时间裁剪
        if clip.trimStart > 0 || (clip.trimEnd > 0 && clip.trimEnd < clip.mediaDuration) {
            let end = clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration
            pre.append("trim=start=\(fmt(clip.trimStart)):end=\(fmt(end))")
            pre.append("setpts=PTS-STARTPTS")
        }
        // 2. 变速
        if clip.speed != 1.0 {
            pre.append("setpts=PTS/\(fmt(clip.speed))")
        }
        // 2.5 倒放
        if clip.reversed {
            pre.append("reverse")
        }

        var post: [String] = []
        // 3. 旋转/翻转
        switch clip.rotation {
        case 90: post.append("transpose=1")
        case -90: post.append("transpose=0")
        case 180: post.append("transpose=1,transpose=1")
        default: break
        }
        if clip.hflip { post.append("hflip") }
        if clip.vflip { post.append("vflip") }

        // 4. 缩放画布（模糊背景模式由外层 split 逻辑处理）
        if !clip.blurBgEnabled {
            post.append("scale=\(canvasW):\(canvasH):force_original_aspect_ratio=increase:flags=lanczos")
            post.append("crop=\(canvasW):\(canvasH)")
            post.append("setsar=1")
        }

        // 5. 调色
        appendColorAdjust(clip, &post)

        // 5.5 统一帧率/timebase（xfade/concat 要求）
        post.append("framerate=fps=\(fps)")
        post.append("settb=1/\(fps)")
        post.append("setpts=N")

        if !clip.blurBgEnabled {
            post.append("format=yuv420p")
        }

        return (pre.joined(separator: ","), post.joined(separator: ","))
    }

    /// 模糊背景开关（预留，与 Android 对齐）
    static let supportsBlurBg = false

    private func appendColorAdjust(_ clip: Clip, _ post: inout [String]) {
        let brightness = min(max(clip.filterPreset.brightness + clip.brightness, -1), 1)
        let contrast = min(max(clip.filterPreset.contrast + clip.contrast, -1), 1)
        let saturation = min(max(clip.filterPreset.saturation + clip.saturation, -1), 1)
        let hue = clip.filterPreset.hue
        let gamma = clip.filterPreset.gamma

        guard brightness != 0 || contrast != 0 || saturation != 0 || hue != 0 || gamma != 1 else { return }

        if hue != 0 || saturation != 0 {
            var hs: [String] = []
            if hue != 0 { hs.append("hue=\(fmt(hue))") }
            if saturation != 0 { hs.append("saturation=\(fmt(saturation))") }
            post.append("huesaturation=" + hs.joined(separator: ":"))
        }
        if brightness != 0 || contrast != 0 {
            let k = min(max(1 + contrast, 0), 2)
            let t = brightness + 0.5 * (1 - k)
            post.append("colorchannelmixer=rr=\(fmt(k)):gg=\(fmt(k)):bb=\(fmt(k)):ra=\(fmt(t)):ga=\(fmt(t)):ba=\(fmt(t))")
        }
        if gamma != 1 {
            let expr = "clip(255*pow(val/255,\(fmt(gamma))),0,255)"
            post.append("lutrgb=r='\(expr)':g='\(expr)':b='\(expr)'")
        }
    }

    // MARK: - 音频滤镜

    func buildAudioFilters(_ clip: Clip) -> String? {
        guard clip.hasAudio else { return nil }
        var f: [String] = []
        if clip.trimStart > 0 || (clip.trimEnd > 0 && clip.trimEnd < clip.mediaDuration) {
            let end = clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration
            f.append("atrim=start=\(fmt(clip.trimStart)):end=\(fmt(end))")
            f.append("asetpts=PTS-STARTPTS")
        }
        if clip.speed != 1.0 {
            f.append(atempoChain(clip.speed))
        }
        if clip.reversed {
            f.append("areverse")
        }
        if clip.volume != 1.0 {
            f.append("volume=\(fmt(clip.volume))")
        }
        let dur = clip.timelineDuration
        let maxFade = dur / 2
        if clip.audioFadeIn > 0 {
            f.append("afade=t=in:st=0:d=\(fmt(min(clip.audioFadeIn, maxFade)))")
        }
        if clip.audioFadeOut > 0 {
            let d = min(clip.audioFadeOut, maxFade)
            f.append("afade=t=out:st=\(fmt(max(dur - d, 0))):d=\(fmt(d))")
        }
        return f.isEmpty ? nil : f.joined(separator: ",")
    }

    /// atempo 链（单滤镜只支持 0.5~2）
    private func atempoChain(_ speed: Double) -> String {
        var s = speed
        var parts: [String] = []
        while s > 2.0 { parts.append("atempo=2.0"); s /= 2.0 }
        while s < 0.5 { parts.append("atempo=0.5"); s /= 0.5 }
        parts.append("atempo=\(fmt(s))")
        return parts.joined(separator: ",")
    }

    // MARK: - 去水印滤镜图

    /// 对 inputLabel 的每个水印区域 crop→处理→overlay 回原位（按各自时间段生效）
    func buildWatermarkGraph(input: String, output: String, clip: Clip, frameW: Int, frameH: Int, parts: inout [String]) {
        let regions = clip.watermarkRegions.filter { $0.w >= 0.005 && $0.h >= 0.005 }
        if regions.isEmpty {
            parts.append("\(input)null\(output)")
            return
        }
        let base = "[wm_base]"
        // ★ 标签后不能跟逗号
        parts.append("\(input) format=yuv420p\(base)")

        let n = regions.count
        let splitLabels = (0...n).map { "[wm_s\($0)]" }.joined()
        parts.append("\(base) split=\(n + 1)\(splitLabels)")
        var cur = "[wm_s0]"
        for (k, r) in regions.enumerated() {
            let processed = "[wm_b\(k)]"
            parts.append("[wm_s\(k + 1)]\(regionFilterBody(r, frameW, frameH))\(processed)")
            let out = k == n - 1 ? output : "[wm_o\(k)]"
            parts.append("\(cur)\(processed) overlay=\(evenPx(r.x * Double(frameW), frameW)):\(evenPx(r.y * Double(frameH), frameH)):\(regionEnableExpr(clip, r))\(out)")
            cur = out
        }
    }

    private func regionFilterBody(_ r: WatermarkRegion, _ frameW: Int, _ frameH: Int) -> String {
        let x = evenPx(r.x * Double(frameW), frameW)
        let y = evenPx(r.y * Double(frameH), frameH)
        let w = max(evenPx(r.w * Double(frameW), frameW - x), 8)
        let h = max(evenPx(r.h * Double(frameH), frameH - y), 8)
        switch r.mode {
        case .blur:
            let radius = min(max(r.strength, 1), 40, max(1, w / 2), max(1, h / 2))
            return "crop=\(w):\(h):\(x):\(y),avgblur=sizeX=\(radius):sizeY=\(radius),format=yuv420p"
        case .mosaic:
            let block = min(max(r.strength, 4), 64)
            let sw = max(w / block, 2), sh = max(h / block, 2)
            return "crop=\(w):\(h):\(x):\(y),scale=\(sw):\(sh):flags=neighbor,scale=\(w):\(h):flags=neighbor,format=yuv420p"
        }
    }

    /// 区域生效时间：区域存源视频秒，滤镜 t 为 trim/变速后的流时间，需换算
    private func regionEnableExpr(_ clip: Clip, _ r: WatermarkRegion) -> String {
        let dur = clip.timelineDuration
        let s = min(max(sourceToStreamTime(clip, r.startTime), 0), dur)
        let e = r.endTime <= r.startTime ? dur : min(max(sourceToStreamTime(clip, r.endTime), 0), dur)
        // 表达式含逗号，单引号包裹避免被滤镜链解析器切分
        return "enable='between(t,\(fmt(s, 2)),\(fmt(e, 2)))'"
    }

    private func sourceToStreamTime(_ clip: Clip, _ srcT: Double) -> Double {
        let end = clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration
        return clip.reversed ? (end - srcT) / clip.speed : (srcT - clip.trimStart) / clip.speed
    }

    private func evenPx(_ v: Double, _ maxV: Int) -> Int {
        let i = Int(v.rounded())
        let clamped = min(max(i, 0), max(maxV, 0))
        return clamped % 2 == 0 ? clamped : max(clamped - 1, 0)
    }

    // MARK: - 转场链

    /// 相邻片段有效转场时长（与数据层 effectiveTransitionOverlap 一致）
    private func effectiveTransitionDur(_ clips: [Clip], _ i: Int) -> Double {
        effectiveTransitionOverlap(prev: clips[i], next: clips[i + 1])
    }

    private func hasAnyTransition(_ clips: [Clip]) -> Bool {
        clips.zipWithNext().contains { a, _ in a.transition != .none && a.transitionDuration > 0 }
    }

    /// 导出后实际输出时长（时间轴已按转场=重叠建模，即最后一片段结束点）
    func computeOutputDuration(_ project: EditorProject) -> Double {
        project.mainTrack?.duration ?? 0.0
    }

    /// xfade 链：x0=xfade(v0,v1), x1=xfade(x0,v2)...
    private func buildXfadeChain(_ clips: [Clip], _ videoLabels: [String], _ parts: inout [String]) -> String {
        let n = clips.count
        if n < 2 { return videoLabels[0] }
        let durations = clips.map(\.timelineDuration)
        var cumulativeOut = durations[0]
        var prevLabel = videoLabels[0]

        for i in 0..<n - 1 {
            let effect = clips[i].transition
            let curLabel = videoLabels[i + 1]
            let outLabel = i == n - 2 ? "[vout]" : "[x\(i)]"
            let transDur = effectiveTransitionDur(clips, i)
            let offset = cumulativeOut - transDur
            let key = effect == .none ? "fade" : effect.rawValue
            let d = effect == .none ? 0.01 : transDur
            parts.append("\(prevLabel)\(curLabel)xfade=transition=\(key):duration=\(fmt(d)):offset=\(fmt(offset))\(outLabel)")
            cumulativeOut += durations[i + 1] - d
            prevLabel = outLabel
        }
        return n == 2 ? "[vout]" : prevLabel
    }

    /// acrossfade 链（与 xfade 镜像，保证音画同步缩短）
    private func buildAcrossfadeChain(_ clips: [Clip], _ audioLabels: [String], _ parts: inout [String]) -> String {
        let n = clips.count
        if n < 2 { return audioLabels[0] }
        var prevLabel = audioLabels[0]
        for i in 0..<n - 1 {
            let curLabel = audioLabels[i + 1]
            let outLabel = i == n - 2 ? "[aout]" : "[ax\(i)]"
            let d = effectiveTransitionDur(clips, i)
            parts.append("\(prevLabel)\(curLabel)acrossfade=d=\(fmt(d))\(outLabel)")
            prevLabel = outLabel
        }
        return prevLabel
    }

    // MARK: - 完整导出命令

    func buildExportCommand(_ project: EditorProject, _ outputPath: String) -> String {
        guard let mainTrack = project.mainTrack, !mainTrack.clips.isEmpty else { return "" }
        let clips = mainTrack.clips.sorted { $0.timelineStart < $1.timelineStart }

        let canvasW = toAligned16(project.canvasWidth)
        let canvasH = toAligned16(project.canvasHeight)
        let hasTransition = hasAnyTransition(clips)

        // 文字水印 PNG
        var textPngs: [Int: TextPngResult] = [:]
        for (i, clip) in clips.enumerated() {
            if let text = clip.textOverlay, !text.trimmingCharacters(in: .whitespaces).isEmpty {
                if let png = TextWatermarkRenderer.renderToPng(
                    text: text, fontSize: clip.textSize, colorStr: clip.textColor,
                    opacity: clip.textOpacity, border: clip.textBorder) {
                    textPngs[i] = png
                }
            }
        }

        var parts: [String] = []
        var videoLabels: [String] = []
        var audioLabels: [String] = []

        // 输入编号：0..n-1 主轨；之后文字水印 PNG
        var nextInputIdx = clips.count
        var textPngInputIdx: [Int: Int] = [:]

        for (i, clip) in clips.enumerated() {
            let (pre, post) = buildVideoFiltersSegmented(clip, canvasW, canvasH, project.fps)
            let prePrefix = pre.isEmpty ? "" : pre + ","
            let postSeg = post.isEmpty ? "" : post + ","

            if clip.blurBgEnabled {
                // 模糊背景（预留）：pre → 水印 → post → split 背景/前景
                let baseLabel: String
                if !clip.watermarkRegions.isEmpty {
                    parts.append("[\(i):v]\(prePrefix)format=yuv420p[wm_in\(i)]")
                    buildWatermarkGraph(input: "[wm_in\(i)]", output: "[wm_g\(i)]", clip: clip, frameW: clip.width, frameH: clip.height, parts: &parts)
                    baseLabel = "[wm_g\(i)]"
                } else {
                    baseLabel = "[\(i):v]"
                }
                parts.append("\(baseLabel)\(postSeg)split=2[bg\(i)][fg\(i)]")
                parts.append("[bg\(i)]scale=\(canvasW):\(canvasH):force_original_aspect_ratio=increase,crop=\(canvasW):\(canvasH),setsar=1,avgblur=sizeX=\(clip.blurStrength):sizeY=\(clip.blurStrength),format=yuv420p[bgblur\(i)]")
                parts.append("[fg\(i)]scale=\(canvasW):\(canvasH):force_original_aspect_ratio=decrease,setsar=1,format=yuv420p[fgscaled\(i)]")
                parts.append("[bgblur\(i)][fgscaled\(i)]overlay=(W-w)/2:(H-h)/2[v\(i)]")
            } else {
                if !clip.watermarkRegions.isEmpty {
                    parts.append("[\(i):v]\(prePrefix)format=yuv420p[wm_in\(i)]")
                    buildWatermarkGraph(input: "[wm_in\(i)]", output: "[wm_g\(i)]", clip: clip, frameW: clip.width, frameH: clip.height, parts: &parts)
                    // ★ post 直接接标签，不能带尾逗号
                    parts.append("[wm_g\(i)]\(post)[v\(i)]")
                } else {
                    let vFilters = buildVideoFilters(clip, canvasW, canvasH, project.fps)
                    parts.append("[\(i):v]\(vFilters)[v\(i)]")
                }
            }
            videoLabels.append("[v\(i)]")

            if let png = textPngs[i] {
                textPngInputIdx[i] = nextInputIdx
                nextInputIdx += 1
                _ = png
            }
        }

        // 文字水印 overlay（画布坐标系）
        var finalVideoLabels: [String] = []
        for (i, clip) in clips.enumerated() {
            var label = "[v\(i)]"
            if let png = textPngs[i] {
                let idx = textPngInputIdx[i]!
                let (ox, oy) = TextWatermarkRenderer.calcOverlayPosition(
                    clip.textPosition, png.width, png.height, canvasW, canvasH)
                parts.append("[\(idx):v]format=rgba,scale=\(png.width):\(png.height)[png\(i)]")
                let newLabel = "[vt\(i)]"
                parts.append("\(label)[png\(i)]overlay=\(ox):\(oy)\(newLabel)")
                label = newLabel
            }
            finalVideoLabels.append(label)
        }

        // 音频
        let anyAudio = clips.contains { $0.hasAudio }
        for (i, clip) in clips.enumerated() {
            if clip.hasAudio {
                if let aFilters = buildAudioFilters(clip) {
                    parts.append("[\(i):a]\(aFilters)[a\(i)]")
                } else {
                    parts.append("[\(i):a]aresample=44100[a\(i)]")
                }
            } else {
                parts.append("anullsrc=channel_layout=stereo:sample_rate=44100,atrim=duration=\(fmt(clip.timelineDuration)),asetpts=PTS-STARTPTS,aformat=channel_layouts=stereo[a\(i)]")
            }
            audioLabels.append("[a\(i)]")
        }

        // 视频拼接：转场 or concat
        var finalVideoLabel = hasTransition
            ? buildXfadeChain(clips, finalVideoLabels, &parts)
            : {
                parts.append(finalVideoLabels.joined() + "concat=n=\(clips.count):v=1:a=0[vout]")
                return "[vout]"
            }()

        // 音频拼接（转场时 acrossfade 同步缩短）
        let hasAudio = !audioLabels.isEmpty
        var finalAudioLabel: String? = nil
        if hasAudio {
            if hasTransition {
                finalAudioLabel = buildAcrossfadeChain(clips, audioLabels, &parts)
            } else {
                parts.append(audioLabels.joined() + "concat=n=\(clips.count):v=0:a=1[aout]")
                finalAudioLabel = "[aout]"
            }
        }

        let filterComplex = parts.joined(separator: ";")

        // 命令组装
        var cmd = "-y"
        for clip in clips {
            cmd += " -i \"\(clip.mediaPath)\""
        }
        for i in clips.indices {
            if textPngs[i] != nil {
                cmd += " -i \"\(textPngs[i]!.file.path)\""
            }
        }
        cmd += " -filter_complex \"\(filterComplex)\""
        cmd += " -map \"\(finalVideoLabel)\""
        if let a = finalAudioLabel { cmd += " -map \(a)" }
        // iOS：硬件 VideoToolbox 编码（full-gpl 亦含 libx264 可作回退）
        cmd += " -c:v h264_videotoolbox -b:v 8M"
        if hasAudio { cmd += " -c:a aac -b:a 192k" }
        let totalDur = computeOutputDuration(project)
        if totalDur > 0 { cmd += " -t \(fmt(totalDur, 2))" }
        cmd += " -movflags +faststart"
        cmd += " \"\(outputPath)\""
        return cmd
    }
}

// MARK: - Array zipWithNext 辅助

extension Array {
    func zipWithNext() -> [(Element, Element)] {
        guard count > 1 else { return [] }
        return (0..<count - 1).map { (self[$0], self[$0 + 1]) }
    }
}
