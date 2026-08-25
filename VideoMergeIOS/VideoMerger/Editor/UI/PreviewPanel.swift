//
//  PreviewPanel.swift
//  VideoMerger
//
//  iOS 移植自 Android PreviewPanel.kt
//  分层渲染：单层=当前片段 AVPlayer；转场重叠区=双 AVPlayer 交叉淡化（近似动画）
//

import SwiftUI
import AVFoundation

struct PreviewPanel: View {
    let state: EditorUiState
    let playhead: Double
    let onTogglePlay: () -> Void
    let onSeek: (Double) -> Void

    var body: some View {
        let clips = state.project.sortedMainClips
        if clips.isEmpty {
            ZStack {
                Color.black
                Text("导入视频后在此预览").foregroundColor(.gray).font(.system(size: 14))
            }
        } else {
            PreviewContent(
                state: state, clips: clips, playhead: playhead,
                onTogglePlay: onTogglePlay, onSeek: onSeek)
        }
    }
}

private struct PreviewContent: View {
    let state: EditorUiState
    let clips: [Clip]
    let playhead: Double
    let onTogglePlay: () -> Void
    let onSeek: (Double) -> Void

    var body: some View {
        let zone = findTransitionZone(clips, playhead)
        let primary = zone?.next ?? clips.first {
            playhead >= $0.timelineStart - 0.05 && playhead < $0.timelineEnd - 0.05
        } ?? clips[0]

        ZStack {
            Color.black

            // 视频层（key 语义：同一片段跨模式保留播放器实例）
            if let zone = zone {
                VideoLayerView(
                    clip: zone.prev, role: .bottom, progress: zone.progress(playhead),
                    isPlaying: state.isPlaying, playhead: playhead, clips: clips,
                    drivesPlayhead: false, onSeek: onSeek)
                VideoLayerView(
                    clip: zone.next, role: .top, progress: zone.progress(playhead),
                    transitionEffect: zone.prev.transition,
                    isPlaying: state.isPlaying, playhead: playhead, clips: clips,
                    drivesPlayhead: true, onSeek: onSeek)
            } else {
                VideoLayerView(
                    clip: primary, role: .solo, progress: 1,
                    isPlaying: state.isPlaying, playhead: playhead, clips: clips,
                    drivesPlayhead: true, onSeek: onSeek)
            }

            // 淡入白/黑遮罩
            if let z = zone, z.prev.transition == .fadewhite || z.prev.transition == .fadeblack {
                let p = z.progress(playhead)
                let alpha = 1 - abs(2 * p - 1)
                if alpha > 0.01 {
                    (z.prev.transition == .fadewhite ? Color.white : Color.black)
                        .opacity(alpha)
                        .ignoresSafeArea()
                }
            }

            // 去水印区域标识（红框，按时段显隐）
            WatermarkRegionOverlayView(clip: primary, playhead: playhead)

            // 画中画叠加（静态帧：视频用缩略图、图片用原图；导出为真实动态叠加）
            PipOverlaysView(state: state, playhead: playhead)

            // 字幕预览（当前时间生效的字幕）
            let activeSubs = state.project.subtitles.filter { playhead >= $0.startTime && playhead <= $0.endTime }
            ForEach(activeSubs) { sub in
                Text(sub.text)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 24)
            }

            // 预览保真度提示
            let unpreviewed: [String] = {
                var list: [String] = []
                if primary.reversed { list.append("倒放") }
                if primary.rotation != 0 || primary.hflip || primary.vflip { list.append("旋转/翻转") }
                if primary.textOverlay != nil { list.append("文字水印") }
                if primary.logoCutTime != nil { list.append("截断") }
                if primary.blurBgEnabled { list.append("模糊背景") }
                if !state.project.tracks.filter({ $0.type == .picture }).flatMap(\.clips).isEmpty { list.append("画中画动态画面") }
                return list
            }()
            if !unpreviewed.isEmpty {
                Text("预览不含\(unpreviewed.joined(separator: "/"))，以导出为准")
                    .font(.system(size: 10)).foregroundColor(Color(hex: 0xFFCCCCCC))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill(Color.black.opacity(0.6)))
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, 6)
            }

            // 时间（右上角小字）
            Text("\(timeStr(playhead)) / \(timeStr(state.project.totalDuration))")
                .font(.system(size: 10))
                .foregroundColor(Color.white.opacity(0.8))
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Capsule().fill(Color.black.opacity(0.4)))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .padding(.top, 6).padding(.trailing, 8)

            // 播放按钮（暂停态显示）
            if !state.isPlaying {
                Button(action: onTogglePlay) {
                    Image(systemName: "play.fill")
                        .font(.system(size: 28))
                        .foregroundColor(.white)
                        .frame(width: 60, height: 60)
                        .background(Circle().fill(Color.black.opacity(0.55)))
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { onTogglePlay() }
    }

    private func timeStr(_ s: Double) -> String {
        String(format: "%02d:%02d", Int(s) / 60, Int(s) % 60)
    }
}

// MARK: - 层角色

enum LayerRole {
    case solo, bottom, top
}

// MARK: - 单个视频层（一个 AVPlayer）

private struct VideoLayerView: View {
    let clip: Clip
    let role: LayerRole
    let progress: Double
    var transitionEffect: TransitionEffect? = nil
    let isPlaying: Bool
    let playhead: Double
    let clips: [Clip]
    let drivesPlayhead: Bool
    let onSeek: (Double) -> Void

    @State private var player: AVPlayer? = nil
    @State private var timeObserver: Any? = nil
    @State private var lastSeekedPos: Double = 0

    var body: some View {
        ZStack {
            // AVPlayerLayer 由 PlayerContainerView 承载
            PlayerContainerView(player: player)
                .ignoresSafeArea()
        }
        .modifier(TransitionTransform(effect: transitionEffect, progress: progress))
        .opacity(role == .bottom ? min(1 - progress, 1) : (role == .top ? progress : 1))
        .onAppear {
            if player == nil {
                let item = AVPlayerItem(url: URL(fileURLWithPath: clip.mediaPath))
                let p = AVPlayer(playerItem: item)
                p.rate = Float(clip.speed)
                p.isMuted = false
                // 初始定位到播放头对应的源时间
                let src = sourceTime(clip, playhead)
                p.seek(to: CMTime(seconds: src, preferredTimescale: 600))
                p.volume = role == .bottom ? Float(1 - progress) : (role == .top ? Float(progress) : 1)
                player = p
                lastSeekedPos = playhead
                addBoundaryObserver(p)
                addPeriodicObserver(p)
            }
        }
        .onDisappear {
            if let t = timeObserver { player?.removeTimeObserver(t) }
            player?.pause()
            player = nil
        }
        .onChange(of: isPlaying) { playing in
            guard drivesPlayhead || role != .bottom else { return }
            if playing { player?.play() } else { player?.pause() }
        }
        .onChange(of: progress) { p in
            // 转场音量交叉衰减
            if role == .bottom { player?.volume = Float(min(1 - p, 1)) }
            if role == .top { player?.volume = Float(min(p, 1)) }
        }
        .onChange(of: playhead) { pos in
            // 暂停态：拖动时间轴重新定位
            guard !isPlaying, pos != lastSeekedPos else { return }
            lastSeekedPos = pos
            let src = min(max(sourceTime(clip, pos), clip.trimStart), endOf(clip))
            if let cur = player?.currentTime().seconds, abs(cur - src) > 0.1 {
                player?.seek(to: CMTime(seconds: src, preferredTimescale: 600),
                             toleranceBefore: .zero, toleranceAfter: .zero)
            }
        }
    }

    /// 片段播完 → 跳下一片段或循环
    private func addBoundaryObserver(_ p: AVPlayer) {
        let end = endOf(clip)
        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: p.currentItem, queue: .main
        ) { _ in
            guard drivesPlayhead else { return }
            let idx = clips.firstIndex { $0.id == clip.id } ?? -1
            if idx >= 0, idx < clips.count - 1 {
                onSeek(clips[idx + 1].timelineStart)
            } else {
                p.seek(to: CMTime(seconds: clip.trimStart, preferredTimescale: 600))
                onSeek(0)
            }
        }
    }

    /// 播放推进：播放器位置 → 时间轴位置（~10Hz）
    private func addPeriodicObserver(_ p: AVPlayer) {
        timeObserver = p.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.1, preferredTimescale: 600), queue: .main
        ) { time in
            guard drivesPlayhead, isPlaying else { return }
            let pos = time.seconds
            if clip.trimEnd > 0 && pos >= clip.trimEnd - 0.03 {
                let idx = clips.firstIndex { $0.id == clip.id } ?? -1
                if idx >= 0, idx < clips.count - 1 {
                    onSeek(clips[idx + 1].timelineStart)
                } else {
                    p.seek(to: CMTime(seconds: clip.trimStart, preferredTimescale: 600))
                    onSeek(0)
                }
                return
            }
            let tl = min(max(clip.timelinePosOf(pos), clip.timelineStart), clip.timelineEnd)
            onSeek(tl)
        }
    }
}

/// AVPlayerLayer 容器（UIViewRepresentable）
private struct PlayerContainerView: UIViewRepresentable {
    let player: AVPlayer?

    final class PlayerUIView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> PlayerUIView {
        let v = PlayerUIView()
        v.playerLayer.videoGravity = .resizeAspect
        return v
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        uiView.playerLayer.player = player
    }
}

// MARK: - 转场变换（上层近似动画）

struct TransitionTransform: ViewModifier {
    let effect: TransitionEffect?
    let progress: Double

    func body(content: Content) -> some View {
        let q = CGFloat(min(max(progress, 0), 1))
        GeometryReader { geo in
            let size = geo.size
            content
                .modifier(RawTransform(effect: effect, q: q, size: size))
                .opacity(effect == nil ? 1 : q)
        }
    }
}

private struct RawTransform: ViewModifier {
    let effect: TransitionEffect?
    let q: CGFloat
    let size: CGSize

    func body(content: Content) -> some View {
        switch effect {
        case .slideleft, .smoothleft:
            content.offset(x: size.width * (1 - q))
        case .slideright, .smoothright:
            content.offset(x: -size.width * (1 - q))
        case .slideup:
            content.offset(y: size.height * (1 - q))
        case .slidedown:
            content.offset(y: -size.height * (1 - q))
        case .wipeleft:
            content.clipShape(LeftWipeShape(progress: q))
        case .wiperight:
            content.clipShape(RightWipeShape(progress: q))
        case .wipeup:
            content.clipShape(TopWipeShape(progress: q))
        case .wipedown:
            content.clipShape(BottomWipeShape(progress: q))
        case .circleopen:
            content.clipShape(Circle().size(width: size.width * q, height: size.height * q)
                .offset(x: (size.width - size.width * q) / 2, y: (size.height - size.height * q) / 2))
        default:
            content
        }
    }
}

private struct LeftWipeShape: Shape {   // 从右向左展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: rect.width * (1 - progress), y: 0, width: rect.width * progress, height: rect.height))
    }
}
private struct RightWipeShape: Shape {  // 从左向右展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: 0, y: 0, width: rect.width * progress, height: rect.height))
    }
}
private struct TopWipeShape: Shape {    // 从下向上展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: 0, y: rect.height * (1 - progress), width: rect.width, height: rect.height * progress))
    }
}
private struct BottomWipeShape: Shape { // 从上向下展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: 0, y: 0, width: rect.width, height: rect.height * progress))
    }
}

// MARK: - 去水印区域红框

private struct WatermarkRegionOverlayView: View {
    let clip: Clip
    let playhead: Double

    var body: some View {
        let srcTime = clip.sourceTimeAt(playhead)
        let active = clip.watermarkRegions.filter { $0.activeAt(srcTime) }
        if !active.isEmpty {
            GeometryReader { geo in
                ForEach(Array(active.enumerated()), id: \.offset) { i, r in
                    Text("水印\(i + 1)")
                        .font(.system(size: 9)).foregroundColor(.white)
                        .padding(.horizontal, 3).padding(.vertical, 1)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .background(
                            RoundedRectangle(cornerRadius: 2)
                                .fill(Color.red.opacity(0.13))
                                .overlay(RoundedRectangle(cornerRadius: 2).stroke(Color.red, lineWidth: 1))
                        )
                        .frame(width: geo.size.width * r.w, height: geo.size.height * r.h)
                        .offset(x: geo.size.width * r.x, y: geo.size.height * r.y)
                }
            }
            .allowsHitTesting(false)
        }
    }
}

// MARK: - 辅助

private func endOf(_ clip: Clip) -> Double {
    clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration
}

/// 时间轴位置 → 预览源时间（倒放按正放近似映射）
private func sourceTime(_ clip: Clip, _ timelinePos: Double) -> Double {
    if clip.reversed {
        return clip.trimStart + (timelinePos - clip.timelineStart) * clip.speed
    }
    return min(max(clip.sourceTimeAt(timelinePos), clip.trimStart), endOf(clip))
}

// MARK: - 画中画叠加预览（静态帧）

private struct PipOverlaysView: View {
    let state: EditorUiState
    let playhead: Double

    var body: some View {
        let pipClips = state.project.tracks
            .filter { $0.type == .picture }
            .flatMap { $0.clips }
            .filter { $0.pipEnabled && playhead >= $0.timelineStart - 0.05 && playhead < $0.timelineEnd - 0.05 }
        if pipClips.isEmpty { return AnyView(EmptyView()) }
        return AnyView(
            GeometryReader { geo in
                ForEach(Array(pipClips.enumerated()), id: \.element.id) { _, pip in
                    let srcPath = pip.isImage ? pip.mediaPath : pip.thumbnailPath
                    if let srcPath = srcPath, let img = UIImage(contentsOfFile: srcPath) {
                        let pipW = geo.size.width * pip.pipWidth
                        let pipH = pipW * (img.size.height / max(img.size.width, 1))
                        let (nx, ny) = interpolatePipPosition(pip.pipKeyframes, playhead, pip.pipX, pip.pipY)
                        let shape: AnyShape = {
                            switch pip.pipShape {
                            case .circle: return AnyShape(Circle())
                            case .rounded:
                                return AnyShape(RoundedRectangle(cornerRadius: pipW * pip.pipCornerRadius))
                            default: return AnyShape(Rectangle())
                            }
                        }()
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFill()
                            .frame(width: pipW, height: pipH)
                            .clipShape(shape)
                            .overlay(
                                shape.stroke(Color.white, lineWidth: pip.pipBorder ? geo.size.width * pip.pipBorderWidth : 0)
                            )
                            .opacity(pip.pipOpacity)
                            .offset(x: (geo.size.width - pipW) * nx, y: (geo.size.height - pipH) * ny)
                            .allowsHitTesting(false)
                    }
                }
            }
        )
    }
}
