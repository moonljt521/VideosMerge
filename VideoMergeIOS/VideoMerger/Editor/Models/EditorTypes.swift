//
//  EditorTypes.swift
//  VideoMerger
//
//  iOS 移植自 Android EditorTypes.kt
//  编辑器数据模型：轨道/片段/项目 + 时间轴布局（转场=重叠）+ 联动平移
//

import Foundation
import SwiftUI

// MARK: - 轨道类型

enum TrackType: String, Codable {
    case main
    case picture
    case text
    case audio
}

// MARK: - 滤镜预设

enum FilterPreset: String, Codable, CaseIterable {
    case none, vintage, warm, cool, vivid, bw, bright, dark

    var displayName: String {
        switch self {
        case .none: return "原图"
        case .vintage: return "复古"
        case .warm: return "暖色"
        case .cool: return "冷色"
        case .vivid: return "鲜艳"
        case .bw: return "黑白"
        case .bright: return "明亮"
        case .dark: return "暗调"
        }
    }

    var brightness: Double { switch self { case .none: 0; case .vintage: 0.08; case .warm: 0.05; case .cool: -0.03; case .vivid: 0.02; case .bw: 0.0; case .bright: 0.15; case .dark: -0.1 } }
    var contrast: Double { switch self { case .none: 0; case .vintage: -0.15; case .warm: 0.05; case .cool: 0.1; case .vivid: 0.2; case .bw: 0.1; case .bright: -0.05; case .dark: 0.25 } }
    var saturation: Double { switch self { case .none: 0; case .vintage: -0.3; case .warm: 0.2; case .cool: -0.1; case .vivid: 0.5; case .bw: -1.0; case .bright: 0.1; case .dark: -0.15 } }
    var hue: Double { switch self { case .none: 0; case .vintage: 15; case .warm: 10; case .cool: -15; case .vivid: 0; case .bw: 0; case .bright: 0; case .dark: 0 } }
    var gamma: Double { switch self { case .none: 1; case .vintage: 1.1; case .warm: 1.0; case .cool: 1.0; case .vivid: 1.0; case .bw: 1.0; case .bright: 0.9; case .dark: 1.2 } }
}

// MARK: - 旋转角度

enum RotationMode: Int, CaseIterable {
    case none = 0
    case cw90 = 90
    case ccw90 = -90
    case r180 = 180

    var displayName: String {
        switch self {
        case .none: return "不旋转"
        case .cw90: return "顺时针90°"
        case .ccw90: return "逆时针90°"
        case .r180: return "180°"
        }
    }
}

// MARK: - 转场效果

enum TransitionEffect: String, Codable, CaseIterable {
    case none, fade, wipeleft, wiperight, wipeup, wipedown
    case slideleft, slideright, slideup, slidedown
    case circleopen, circleclose, dissolve, pixelize
    case fadewhite, fadeblack, zoomin, hblur, radial
    case smoothleft, smoothright

    var displayName: String {
        switch self {
        case .none: return "无转场"
        case .fade: return "淡入淡出"
        case .wipeleft: return "向左擦除"
        case .wiperight: return "向右擦除"
        case .wipeup: return "向上擦除"
        case .wipedown: return "向下擦除"
        case .slideleft: return "向左滑动"
        case .slideright: return "向右滑动"
        case .slideup: return "向上滑动"
        case .slidedown: return "向下滑动"
        case .circleopen: return "圆形展开"
        case .circleclose: return "圆形关闭"
        case .dissolve: return "溶解"
        case .pixelize: return "像素化"
        case .fadewhite: return "淡入白色"
        case .fadeblack: return "淡入黑色"
        case .zoomin: return "放大"
        case .hblur: return "水平模糊"
        case .radial: return "径向"
        case .smoothleft: return "平滑左滑"
        case .smoothright: return "平滑右滑"
        }
    }
}

// MARK: - 画中画形状（预留）

enum PipShape: String, Codable {
    case rect, rounded, circle
}

// MARK: - 去水印区域

enum WatermarkMode: String, Codable {
    case blur
    case mosaic

    var displayName: String { self == .blur ? "模糊" : "马赛克" }
}

struct WatermarkRegion: Codable, Equatable {
    var x: Double
    var y: Double
    var w: Double
    var h: Double
    var mode: WatermarkMode = .blur
    var strength: Int = 14
    /// 生效开始（源视频秒）；endTime <= startTime 表示整个片段生效
    var startTime: Double = 0.0
    var endTime: Double = -1.0

    func activeAt(_ sourceTime: Double) -> Bool {
        endTime <= startTime || (sourceTime >= startTime && sourceTime <= endTime)
    }

    /// 与另一区域大面积重叠（IoU > 阈值）
    func overlaps(_ other: WatermarkRegion, iouThreshold: Double = 0.3) -> Bool {
        let ix = min(x + w, other.x + other.w) - max(x, other.x)
        let iy = min(y + h, other.y + other.h) - max(y, other.y)
        guard ix > 0, iy > 0 else { return false }
        let inter = ix * iy
        let union = w * h + other.w * other.h - inter
        return inter / union > iouThreshold
    }
}

// MARK: - 转场重叠（时间轴布局与导出 xfade 的唯一来源）

/// NONE 转场的等效重叠时长（导出 xfade 链用 0.01s 淡变衔接，时间轴必须同值）
let TRANSITION_NONE_OVERLAP = 0.01

/// 小于此重叠时长的转场不做双轨预览
let TRANSITION_PREVIEW_MIN_OVERLAP = 0.05

/// 相邻片段间的有效转场重叠时长（秒）。
/// next.timelineStart = prev.timelineEnd - 本函数返回值
func effectiveTransitionOverlap(prev: Clip, next: Clip) -> Double {
    if prev.transition == .none { return TRANSITION_NONE_OVERLAP }
    let maxD = min(prev.timelineDuration, next.timelineDuration) * 0.8
    return min(max(prev.transitionDuration, 0.01), max(maxD, 0.01))
}

/// 按列表顺序重排主轨片段（纯函数）：转场处下一片段提前重叠时长开始
func relayoutMainTrackClips(_ clips: [Clip]) -> [Clip] {
    var nextStart = 0.0
    return clips.enumerated().map { i, clip in
        let placed = clip.withTimelineStart(nextStart)
        nextStart = i < clips.count - 1
            ? placed.timelineEnd - effectiveTransitionOverlap(prev: placed, next: clips[i + 1])
            : placed.timelineEnd
        return placed
    }
}

// MARK: - 转场重叠区（预览用）

struct TransitionZone {
    let prev: Clip
    let next: Clip

    var start: Double { next.timelineStart }
    var end: Double { prev.timelineEnd }
    var duration: Double { max(end - start, 1e-6) }

    func progress(_ timelinePos: Double) -> Double {
        min(max((timelinePos - start) / duration, 0), 1)
    }
}

/// 找到播放头所在的转场重叠区；不在任何转场内返回 nil
func findTransitionZone(_ clips: [Clip], _ timelinePos: Double) -> TransitionZone? {
    for i in 0..<max(clips.count - 1, 0) {
        let a = clips[i], b = clips[i + 1]
        if a.transition == .none { continue }
        if effectiveTransitionOverlap(prev: a, next: b) < TRANSITION_PREVIEW_MIN_OVERLAP { continue }
        if timelinePos >= b.timelineStart && timelinePos < a.timelineEnd {
            return TransitionZone(prev: a, next: b)
        }
    }
    return nil
}

// MARK: - 主轨变化后叠加元素联动平移

/// 主轨时长变化后，锚点之后的字幕/画中画整体平移 delta（剪映式联动）
func shiftOverlaysAfter(project: EditorProject, anchor: Double, delta: Double) -> EditorProject {
    if delta == 0 { return project }
    let eps = 0.05

    let newSubtitles = project.subtitles.map { sub -> Subtitle in
        if sub.startTime >= anchor - eps {
            var s = sub
            s.startTime = max(sub.startTime + delta, 0)
            s.endTime = max(sub.endTime + delta, 0.05)
            return s
        }
        return sub
    }

    let newTracks = project.tracks.map { track -> Track in
        if track.type == .main { return track }
        var t = track
        t.clips = track.clips.map { c in
            c.timelineStart >= anchor - eps ? c.withTimelineStart(max(c.timelineStart + delta, 0)) : c
        }
        return t
    }

    var p = project
    p.subtitles = newSubtitles
    p.tracks = newTracks
    return p
}

// MARK: - 片段

struct Clip: Codable, Equatable, Identifiable {
    var id = UUID().uuidString
    var mediaPath: String
    var mediaName: String
    var mediaDuration: Double
    var width: Int
    var height: Int
    var hasAudio: Bool

    // 时间裁剪
    var trimStart: Double = 0.0
    var trimEnd: Double = 0.0     // 0 表示到结尾

    // 时间轴位置
    var timelineStart: Double = 0.0

    // 变速 / 倒放
    var speed: Double = 1.0
    var reversed: Bool = false

    // 音频
    var volume: Double = 1.0
    var audioFadeIn: Double = 0.0
    var audioFadeOut: Double = 0.0

    // 旋转/翻转
    var rotation: Int = 0
    var hflip: Bool = false
    var vflip: Bool = false

    // 滤镜
    var filterPreset: FilterPreset = .none
    var brightness: Double = 0.0
    var contrast: Double = 0.0
    var saturation: Double = 0.0

    // 文字水印
    var textOverlay: String? = nil
    var textSize: Int = 28
    var textColor: String = "white"
    var textPosition: String = "bottom-right"
    var textOpacity: Double = 1.0
    var textBorder: Bool = true

    // 图片水印（预留）
    var imageWatermarkPath: String? = nil
    var imageWatermarkScale: Double = 0.2
    var imageWatermarkOpacity: Double = 1.0
    var imageWatermarkPosition: String = "bottom-right"

    // 转场（该片段到下一片段的转场）
    var transition: TransitionEffect = .none
    var transitionDuration: Double = 0.8

    // 模糊背景
    var blurBgEnabled: Bool = false
    var blurStrength: Int = 12

    // 截断（抖音尾部 logo）
    var logoCutTime: Double? = nil

    // 去水印区域（预留）
    var watermarkRegions: [WatermarkRegion] = []

    // 画中画（预留）
    var pipEnabled: Bool = false
    var isImage: Bool = false
    var pipX: Double = 0.0
    var pipY: Double = 0.0
    var pipWidth: Double = 0.3
    var pipOpacity: Double = 1.0
    var pipShape: PipShape = .rect
    var pipCornerRadius: Double = 0.15
    var pipBorder: Bool = false
    var pipBorderWidth: Double = 0.02

    // 缩略图路径
    var thumbnailPath: String? = nil

    /// 有效时长（裁剪后）
    var effectiveDuration: Double { (trimEnd > 0 ? trimEnd : mediaDuration) - trimStart }

    /// 时间轴上的有效时长（变速后）
    var timelineDuration: Double { effectiveDuration / speed }

    /// 时间轴结束位置
    var timelineEnd: Double { timelineStart + timelineDuration }

    /// 时间轴位置 → 源视频时间（倒放自动处理）
    func sourceTimeAt(_ timelinePos: Double) -> Double {
        let offset = timelinePos - timelineStart
        let end = trimEnd > 0 ? trimEnd : mediaDuration
        return reversed ? end - offset * speed : trimStart + offset * speed
    }

    /// 源视频时间 → 时间轴位置
    func timelinePosOf(_ sourceTime: Double) -> Double {
        let end = trimEnd > 0 ? trimEnd : mediaDuration
        return reversed
            ? timelineStart + (end - sourceTime) / speed
            : timelineStart + (sourceTime - trimStart) / speed
    }

    /// 不可变修改 timelineStart（Swift struct 值语义天然隔离撤销快照）
    func withTimelineStart(_ start: Double) -> Clip {
        var c = self
        c.timelineStart = start
        return c
    }
}

// MARK: - 字幕

struct Subtitle: Codable, Equatable, Identifiable {
    var id = UUID().uuidString
    var text: String
    var startTime: Double
    var endTime: Double
}

// MARK: - 轨道

struct Track: Codable, Equatable {
    var id = UUID().uuidString
    var type: TrackType
    var clips: [Clip] = []
    var muted: Bool = false
    var hidden: Bool = false

    var duration: Double { clips.map(\.timelineEnd).max() ?? 0.0 }
}

// MARK: - 编辑项目
/// 注意：canvasWidth/Height 必须 16 对齐（编码器宏块对齐）

struct EditorProject: Codable, Equatable {
    var id = UUID().uuidString
    var name: String = "未命名项目"
    var canvasWidth: Int = 1088
    var canvasHeight: Int = 1920
    var fps: Int = 30
    var tracks: [Track] = []
    var subtitles: [Subtitle] = []
    var createdAt: Double = Date().timeIntervalSince1970 * 1000
    var updatedAt: Double = Date().timeIntervalSince1970 * 1000

    var totalDuration: Double { tracks.map(\.duration).max() ?? 0.0 }

    var mainTrack: Track? {
        get { tracks.first { $0.type == .main } }
        set {
            if let nv = newValue {
                if let idx = tracks.firstIndex(where: { $0.type == .main }) {
                    tracks[idx] = nv
                } else {
                    tracks.append(nv)
                }
            } else {
                tracks.removeAll { $0.type == .main }
            }
        }
    }

    var sortedMainClips: [Clip] {
        (mainTrack?.clips ?? []).sorted { $0.timelineStart < $1.timelineStart }
    }
}


// MARK: - Android 风格十六进制颜色

extension Color {
    init(hex: UInt32) {
        let a = Double((hex >> 24) & 0xFF) / 255
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: hex > 0xFFFFFF ? a : 1)
    }
}
